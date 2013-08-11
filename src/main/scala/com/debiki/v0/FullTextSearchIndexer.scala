/**
 * Copyright (C) 2013 Kaj Magnus Lindberg (born 1979)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.debiki.v0

import akka.actor._
import akka.pattern.{ask, gracefulStop}
import akka.util.Timeout
import java.{util => ju}
import org.{elasticsearch => es}
import play.{api => p}
import play.api.libs.json._
import scala.util.control.NonFatal
import scala.concurrent.Await
import scala.concurrent.duration._
import FullTextSearchIndexer._
import Prelude._



private[v0]
class FullTextSearchIndexer(private val relDbDaoFactory: RelDbDaoFactory) {

  private implicit val actorSystem = relDbDaoFactory.actorSystem
  private implicit val executionContext = actorSystem.dispatcher

  // This'll do for now. (Make configurable, later)
  private val DefaultDataDir = "target/elasticsearch-data"

  private val IndexPendingPostsDelay = 90 seconds
  private val IndexPendingPostsInterval = 30 seconds
  private val ShutdownTimeout = 10 seconds

  val node = {
    val settingsBuilder = es.common.settings.ImmutableSettings.settingsBuilder()
    settingsBuilder.put("path.data", DefaultDataDir)
    settingsBuilder.put("node.name", "DebikiElasticSearchNode")
    settingsBuilder.put("http.enabled", true)
    settingsBuilder.put("http.port", 9200)
    val nodeBuilder = es.node.NodeBuilder.nodeBuilder()
    val node = nodeBuilder.settings(settingsBuilder.build()).data(true).local(false)
      .clusterName("DebikiElasticSearchCluster").build().start()
    node
  }


  private val indexingActorRef = relDbDaoFactory.actorSystem.actorOf(
    Props(new IndexingActor(client, relDbDaoFactory)), name = "IndexingActor")


  startupAsynchronously()


  /** It's thread safe, see:
    * <http://elasticsearch-users.115913.n3.nabble.com/
    *     Client-per-request-question-tp3390949p3391086.html>
    *
    * The client might throw: org.elasticsearch.action.search.SearchPhaseExecutionException
    * if the ElasticSearch database has not yet started up properly.
    * If you wait for:
    *   newClient.admin().cluster().prepareHealth().setWaitForGreenStatus().execute().actionGet()
    * then the newClient apparently works fine — but waiting for that (once at
    * server startup) takes 30 seconds, on my computer, today 2013-07-20.
    */
  def client = node.client


  /** Starts the search engine, asynchronously:
    * - Creates an index, if absent.
    * - Schedules periodic indexing of posts that need to be indexed.
    */
  def startupAsynchronously() {
    createIndexAndMappinigsIfAbsent()
    actorSystem.scheduler.schedule(
      IndexPendingPostsDelay, IndexPendingPostsInterval, indexingActorRef, IndexPendingPosts)
  }


  def shutdown() {
    p.Logger.info("Stopping IndexingActor...")
    gracefulStop(indexingActorRef, ShutdownTimeout) onComplete { result =>
      if (result.isFailure) {
        p.Logger.warn("Error stopping IndexingActor", result.failed.get)
      }
      p.Logger.info("Shutting down ElasticSearch client...")
      client.close()
      node.close()
    }
  }


  /** Currently indexes each post directly.
    */
  def indexNewPostsSoon(page: PageNoPath, posts: Seq[Post], siteId: String) {
    indexingActorRef ! PostsToIndex(siteId, page, posts)
  }


  /** The index can be deleted like so:  curl -XDELETE localhost:9200/sites_v0
    * But don't do that in any production environment of course.
    */
  private def createIndexAndMappinigsIfAbsent() {
    import es.action.admin.indices.create.CreateIndexResponse
    import es.action.ActionListener

    val createIndexRequest = es.client.Requests.createIndexRequest(IndexV0Name)
      .settings(IndexV0Settings)
      .mapping(IndexV0PostMappingName, IndexV0PostMappingDefinition)

    client.admin().indices().create(createIndexRequest, new ActionListener[CreateIndexResponse] {
      def onResponse(response: CreateIndexResponse) {
        p.Logger.info("Created ElasticSearch index and mapping.")
      }
      def onFailure(t: Throwable): Unit = t match {
        case _: es.indices.IndexAlreadyExistsException =>
          p.Logger.info("ElasticSearch index has already been created, fine.")
        case NonFatal(error) =>
          p.Logger.warn("Error trying to create ElasticSearch index [DwE84dKf0]", error)
      }
    })
  }


  /** Waits for the ElasticSearch cluster to start. (Since I've specified 2 shards, it enters
    * yellow status only, not green status, since there's one ElasticSearch node only (not 2).)
    */
   def debugWaitUntilSearchEngineStarted() {
    client.admin().cluster().prepareHealth().setWaitForYellowStatus().execute().actionGet()
  }


  /** Waits until all pending index requests have been completed.
    * Intended for test suite code only.
    */
  def debugRefreshIndexes() {
    Await.result(
      ask(indexingActorRef, ReplyWhenDoneIndexing)(Timeout(999 seconds)),
      atMost = 999 seconds)

    val refreshRequest = es.client.Requests.refreshRequest(IndexV0Name)
    client.admin.indices.refresh(refreshRequest).actionGet()
  }

}



private[v0] case object ReplyWhenDoneIndexing
private[v0] case object IndexPendingPosts
private[v0] case class PostsToIndex(siteId: String, page: PageNoPath, posts: Seq[Post])



/** An Akka actor that indexes newly created or edited posts, and also periodically
  * scans the database (checks DW1_POSTS.INDEXED_VERSION) for posts that have not
  * yet been indexed (for example because the server crashed, or because the
  * ElasticSearch index has been changed and everything needs to be reindexed).
  */
private[v0] class IndexingActor(
  private val client: es.client.Client,
  private val relDbDaoFactory: RelDbDaoFactory) extends Actor {

  private def systemDao = relDbDaoFactory.systemDbDao


  def receive = {
    case IndexPendingPosts =>
      p.Logger.info("Got a message: IndexPendingPosts")
      // val postsToIndex = systemDao.findPostsNotYetIndexed(limit = 10)
      // indexPosts(postsToIndex)
    case postsToIndex: PostsToIndex =>
      indexPosts(postsToIndex)
    case ReplyWhenDoneIndexing =>
      sender ! "Done indexing."
  }


  private def indexPosts(postsToIndex: PostsToIndex) {
    postsToIndex.posts foreach { post =>
      indexPost(post, postsToIndex.page, postsToIndex.siteId)
    }
  }


  private def indexPost(post: Post, page: PageNoPath, siteId: String) {
    val json = makeJsonToIndex(post, page, siteId)
    val idString = elasticSearchIdFor(siteId, post)
    val indexRequest: es.action.index.IndexRequest =
      es.client.Requests.indexRequest(indexName(siteId))
        .`type`(ElasticSearchPostTypeName)
        .id(idString)
        //.opType(es.action.index.IndexRequest.OpType.CREATE)
        //.version(...)
        .source(json.toString)
        .routing(siteId) // this is faster than extracting `siteId` from JSON source: needn't parse.

    client.index(indexRequest, new es.action.ActionListener[es.action.index.IndexResponse] {
      def onResponse(response: es.action.index.IndexResponse) {
        p.Logger.debug("Indexed: " + idString)
      }
      def onFailure(throwable: Throwable) {
        p.Logger.warn(i"Error when indexing: $idString", throwable)
      }
    })
  }


  private def makeJsonToIndex(post: Post, page: PageNoPath, siteId: String): JsValue = {
    var sectionPageIds = page.ancestorIdsParentFirst
    if (page.meta.pageRole.childRole.isDefined) {
      // Since this page can have child pages, consider it a section (e.g. a blog,
      // forum, subforum or a wiki).
      sectionPageIds ::= page.id
    }

    var json = post.toJson
    json += JsonKeys.SectionPageIds -> Json.toJson(sectionPageIds)
    json += JsonKeys.SiteId -> JsString(siteId)
    json
  }

}



private[v0]
object FullTextSearchIndexer {


  /** For now, use one index for allsites. In the future, if one single site
    * grows unfathomably popular, it can be migrated to its own index. So do include
    * the site name in the index (but don't use it, right now).
    *
    * Include a version number in the index name. See:
    *   http://www.elasticsearch.org/blog/changing-mapping-with-zero-downtime/
    * — if changing the mappings, one can reindex all documents to a new index with a
    * new version number, and then change an alias, or change the Scala source code,
    * to use the new version of the index.
    */
  def indexName(siteId: String) =
    IndexV0Name
  // but if the site has its own index:  s"site_${siteId}_v0"  ?

  val IndexV0Name = s"sites_v0"


  val ElasticSearchPostTypeName = "post"


  def elasticSearchIdFor(siteId: String, post: Post) =
    s"$siteId:${post.page.id}:${post.id}"


  object JsonKeys {
    val SiteId = "siteId"
    val SectionPageIds = "sectionPageIds"
  }


  /** Settings for the currently one and only ElasticSearch index.
    *
    * Over allocate shards ("user based data flow", and in Debiki's case, each user is a website).
    * We'll use routing to direct all traffic for a certain site to a single shard always.
    */
  val IndexV0Settings = i"""{
    |  "number_of_shards": 100,
    |  "number_of_replicas": 1
    |}"""


  val IndexV0PostMappingName = "post"


  val IndexV0PostMappingDefinition = i"""{
    |"post": {
    |  "properties": {
    |    "siteId":                       {"type": "string", "index": "not_analyzed"},
    |    "pageId":                       {"type": "string", "index": "not_analyzed"},
    |    "postId":                       {"type": "integer", "index": "no"},
    |    "parentPostId":                 {"type": "integer"},
    |    "sectionPageIds":               {"type": "string", "index": "not_analyzed"},
    |    "createdAt":                    {"type": "date"},
    |    "currentText":                  {"type": "string", "index": "no"},
    |    "currentMarkup":                {"type": "string", "index": "no"},
    |    "anyDirectApproval":            {"type": "string", "index": "no"},
    |    "where":                        {"type": "string", "index": "no"},
    |    "loginId":                      {"type": "string", "index": "not_analyzed"},
    |    "userId":                       {"type": "string", "index": "not_analyzed"},
    |    "newIp":                        {"type": "ip",     "index": "not_analyzed"},
    |    "lastActedUponAt":              {"type": "date"},
    |    "lastReviewDati":               {"type": "date"},
    |    "lastAuthoritativeReviewDati":  {"type": "date"},
    |    "lastApprovalDati":             {"type": "date"},
    |    "lastApprovedText":             {"type": "string", "analyzer": "snowball"},
    |    "lastPermanentApprovalDati":    {"type": "date"},
    |    "lastManualApprovalDati":       {"type": "date"},
    |    "lastEditAppliedAt":            {"type": "date"},
    |    "lastEditRevertedAt":           {"type": "date"},
    |    "lastEditorId":                 {"type": "string", "index": "no"},
    |    "postCollapsedAt":              {"type": "date"},
    |    "treeCollapsedAt":              {"type": "date"},
    |    "postDeletedAt":                {"type": "date"},
    |    "treeDeletedAt":                {"type": "date"},
    |    "numEditSuggestionsPending":    {"type": "integer"},
    |    "numEditsAppliedUnreviewed":    {"type": "integer"},
    |    "numEditsAppldPrelApproved":    {"type": "integer"},
    |    "numEditsToReview":             {"type": "integer"},
    |    "numDistinctEditors":           {"type": "integer"},
    |    "numCollapsesToReview":         {"type": "integer"},
    |    "numUncollapsesToReview":       {"type": "integer"},
    |    "numDeletesToReview":           {"type": "integer"},
    |    "numUndeletesToReview":         {"type": "integer"},
    |    "numFlagsPending":              {"type": "integer"},
    |    "numFlagsHandled":              {"type": "integer"},
    |    "numCollapsePostVotesPro":      {"type": "integer", "index": "no"},
    |    "numCollapsePostVotesCon":      {"type": "integer", "index": "no"},
    |    "numUncollapsePostVotesPro":    {"type": "integer", "index": "no"},
    |    "numUncollapsePostVotesCon":    {"type": "integer", "index": "no"},
    |    "numCollapseTreeVotesPro":      {"type": "integer", "index": "no"},
    |    "numCollapseTreeVotesCon":      {"type": "integer", "index": "no"},
    |    "numUncollapseTreeVotesPro":    {"type": "integer", "index": "no"},
    |    "numUncollapseTreeVotesCon":    {"type": "integer", "index": "no"},
    |    "numDeletePostVotesPro":        {"type": "integer", "index": "no"},
    |    "numDeletePostVotesCon":        {"type": "integer", "index": "no"},
    |    "numUndeletePostVotesPro":      {"type": "integer", "index": "no"},
    |    "numUndeletePostVotesCon":      {"type": "integer", "index": "no"},
    |    "numDeleteTreeVotesPro":        {"type": "integer", "index": "no"},
    |    "numDeleteTreeVotesCon":        {"type": "integer", "index": "no"},
    |    "numUndeleteTreeVotesPro":      {"type": "integer", "index": "no"},
    |    "numUndeleteTreeVotesCon":      {"type": "integer", "index": "no"}
    |  }
    |}}"""

}


