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

package com.debiki.dao.rdb

import akka.actor._
import akka.pattern.{ask, gracefulStop}
import akka.util.Timeout
import com.debiki.core._
import java.{util => ju}
import org.{elasticsearch => es}
import org.elasticsearch.action.ActionListener
import play.{api => p}
import play.api.libs.json._
import scala.util.control.NonFatal
import scala.concurrent.Await
import scala.concurrent.duration._
import FullTextSearchIndexer._
import Prelude._


private[rdb]
class FullTextSearchIndexer(private val relDbDaoFactory: RdbDaoFactory) {

  private implicit val actorSystem = relDbDaoFactory.actorSystem
  private implicit val executionContext = actorSystem.dispatcher

  private val DefaultDataPath = "target/elasticsearch-data"

  private val dataPath =
    relDbDaoFactory.fullTextSearchDbDataPath getOrElse DefaultDataPath

  private def isTest = relDbDaoFactory.isTest

  private val IndexPendingPostsDelay    = (if (isTest) 3 else 30) seconds
  private val IndexPendingPostsInterval = (if (isTest) 1 else 10) seconds
  private val ShutdownTimeout           = (if (isTest) 1 else 10) seconds


  val node = {
    p.Logger.info("Starting ElasticSearch node...")
    val settingsBuilder = es.common.settings.ImmutableSettings.settingsBuilder()
    settingsBuilder.put("path.data", dataPath)
    settingsBuilder.put("node.name", "DebikiElasticSearchNode")
    settingsBuilder.put("http.enabled", true)
    settingsBuilder.put("http.port", 9200)
    val nodeBuilder = es.node.NodeBuilder.nodeBuilder()
      .settings(settingsBuilder.build())
      .data(true)
      .local(false)
      .clusterName("DebikiElasticSearchCluster")
    try {
      val node = nodeBuilder.build()
      node.start()
    }
    catch {
      case ex: java.nio.channels.OverlappingFileLockException =>
        p.Logger.error(o"""Error starting ElasticSearch: a previous ElasticSearch process
          has not been terminated, and has locked ElasticSearch's files. [DwE52Kf0]""")
        throw ex
    }
  }


  private val indexingActorRef = relDbDaoFactory.actorSystem.actorOf(
    Props(new IndexingActor(client, relDbDaoFactory)), name = "IndexingActor")


  startup()


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


  /** Starts the search engine:
    * - Creates an index, if absent.
    * - Schedules periodic indexing of posts that need to be indexed.
    */
  def startup() {
    createIndexAndMappinigsIfAbsent()
    actorSystem.scheduler.schedule(initialDelay = IndexPendingPostsDelay,
      interval = IndexPendingPostsInterval, indexingActorRef, IndexPendingPosts)
  }


  def shutdown() {
    // When developing: It's rather important to shut down ElasticSearch,
    // otherwise ElasticSearch will continue running in the background,
    // preventing a new development server from starting up properly. So don't
    // wait for the IndexingActor to stop, before stopping ElasticSearch.
    // This means that some things might not be indexed — the server will
    // index them in the background, the next time it starts.
    p.Logger.info("Closing the ElasticSearch node...")
    client.close()
    node.close()
    p.Logger.info("Stopping IndexingActor...")
    gracefulStop(indexingActorRef, ShutdownTimeout) onComplete { result =>
      if (result.isFailure) {
        p.Logger.warn("Error stopping IndexingActor [DwE78fZ03]", result.failed.get)
      }
    }
  }


  /** Currently indexes each post directly.
    */
  /*
  def indexNewPostsSoon(page: PageNoPath, posts: Seq[Post2], siteId: String) {
    indexingActorRef ! PostsToIndex(siteId, page, posts.toVector)
  }
  */


  /** The index can be deleted like so:  curl -XDELETE localhost:9200/sites_v0
    * But don't do that in any production environment of course.
    */
  def createIndexAndMappinigsIfAbsent() {
    import es.action.admin.indices.create.CreateIndexResponse

    val createIndexRequest = es.client.Requests.createIndexRequest(IndexName)
      .settings(IndexSettings)
      .mapping(PostMappingName, PostMappingDefinition)

    try {
      val response: CreateIndexResponse =
        client.admin().indices().create(createIndexRequest).actionGet()
      if (response.isAcknowledged)
        p.Logger.info("Created ElasticSearch index and mapping.")
      else
        p.Logger.warn("ElasticSearch index creation request not acknowledged? What does that mean?")
    }
    catch {
      case _: es.indices.IndexAlreadyExistsException =>
        p.Logger.info("ElasticSearch index has already been created, fine.")
      case NonFatal(error) =>
        p.Logger.warn("Error trying to create ElasticSearch index [DwE84dKf0]", error)
        throw error
    }
  }


  def debugDeleteIndexAndMappings() {
    val deleteRequest = es.client.Requests.deleteIndexRequest(IndexName)
    try {
      val response = client.admin.indices.delete(deleteRequest).actionGet()
      if (response.isAcknowledged)
        p.Logger.info("Deleted the ElasticSearch index.")
      else
        p.Logger.warn("ElasticSearch index deletion request not acknowledged? What does that mean?")
    }
    catch {
      case _: org.elasticsearch.indices.IndexMissingException => // ignore
      case NonFatal(ex) => p.Logger.warn("Error deleting ElasticSearch index [DwE5Hf39]:", ex)
    }
  }


  /** Waits for the ElasticSearch cluster to start. (Since I've specified 2 shards, it enters
    * yellow status only, not green status, since there's one ElasticSearch node only (not 2).)
    */
   def debugWaitUntilSearchEngineStarted() {
    import es.action.admin.cluster.{health => h}
    val response: h.ClusterHealthResponse =
      client.admin.cluster.prepareHealth().setWaitForYellowStatus().execute().actionGet()
    val green = response.getStatus == h.ClusterHealthStatus.GREEN
    val yellow = response.getStatus == h.ClusterHealthStatus.YELLOW
    if (!green && !yellow) {
      runErr("DwE74b0f3", s"Bad ElasticSearch status: ${response.getStatus}")
    }
  }


  /** Waits until all pending index requests have been completed.
    * Intended for test suite code only.
    */
  def debugRefreshIndexes() {
    Await.result(
      ask(indexingActorRef, ReplyWhenDoneIndexing)(Timeout(999 seconds)),
      atMost = 999 seconds)

    val refreshRequest = es.client.Requests.refreshRequest(IndexName)
    client.admin.indices.refresh(refreshRequest).actionGet()
  }

}



private[rdb] case object ReplyWhenDoneIndexing
private[rdb] case object IndexPendingPosts
private[rdb] case class PostsToIndex(siteId: String) // , page: PageNoPath, posts: Vector[Post])



/** An Akka actor that indexes newly created or edited posts, and also periodically
  * scans the database (checks DW1_POSTS.INDEXED_VERSION) for posts that have not
  * yet been indexed (for example because the server crashed, or because the
  * ElasticSearch index has been changed and everything needs to be reindexed).
  */
private[rdb] class IndexingActor(
  private val client: es.client.Client,
  private val relDbDaoFactory: RdbDaoFactory) extends Actor {

  // For now, don't index all pending posts. That'd result in my Amazon EC2 instance
  // grinding to a halt, when the hypervisor steals all time becaues it uses too much CPU?
  private val MaxPostsToIndexAtOnce = 50


  def receive = {
    case IndexPendingPosts => loadAndIndexPendingPosts()
    case postsToIndex: PostsToIndex => indexPosts(postsToIndex)
    case ReplyWhenDoneIndexing => sender ! "Done indexing."
  }


  private def loadAndIndexPendingPosts() {
    unimplemented("loadAndIndexPendingPosts [EsE4GPU9]") /*
    val chunksOfPostsToIndex: Seq[PostsToIndex] =
      systemDao.findPostsNotYetIndexedNoTransaction(
        currentIndexVersion = FullTextSearchIndexer.IndexVersion, limit = MaxPostsToIndexAtOnce)

    chunksOfPostsToIndex foreach { postsToIndex =>
      indexPosts(postsToIndex)
    }
    */
  }

  private def indexPosts(postsToIndex: PostsToIndex) {
    unimplemented("indexing posts [DwE2UYJ7]") /*
    postsToIndex.posts foreach { post =>
      indexPost(post, postsToIndex.page, postsToIndex.siteId)
    }
    */
  }


  /*
  private def indexPost(post: Post2, page: PageNoPath, siteId: String) {
    val json = makeJsonToIndex(post, page, siteId)
    val idString = elasticSearchIdFor(siteId, post)
    val indexRequest: es.action.index.IndexRequest =
      es.client.Requests.indexRequest(indexName(siteId))
        .`type`(PostMappingName)
        .id(idString)
        //.opType(es.action.index.IndexRequest.OpType.CREATE)
        //.version(...)
        .source(json.toString)
        .routing(siteId) // this is faster than extracting `siteId` from JSON source: needn't parse.

    // BUG: Race condition: What if the post is changed just after it was indexed,
    // but before `rememberIsIndexed` below is called!
    // Then it'll seem as if the post has been properly indexed, although it has not!
    // Fix this by clarifying which version of the post was actually indexed. Something
    // like optimistic concurrency? — But don't fix that right now, it's a minor issue?

    client.index(indexRequest, new es.action.ActionListener[es.action.index.IndexResponse] {
      def onResponse(response: es.action.index.IndexResponse) {
        p.Logger.debug("Indexed: " + idString)
        // BUG: Race condition if the post is changed *here*. (See comment above.)
        // And, please note: this isn't the actor's thread. This is some thread
        // "controlled" by ElasticSearch.
        val dao = relDbDaoFactory.newSiteDbDao(siteId)
        dao.rememberPostsAreIndexed(IndexVersion, PagePostId(page.id, post.id))
      }
      def onFailure(throwable: Throwable) {
        p.Logger.warn(i"Error when indexing: $idString", throwable)
      }
    })
  }
    */


  /*
  private def makeJsonToIndex(post: Post2, page: PageNoPath, siteId: String): JsValue = {
    var sectionPageIds = page.ancestorIdsParentFirst
    if (page.meta.pageRole.isSection) {
      sectionPageIds ::= page.id
    }

    var json = post.toJson
    json += JsonKeys.SectionPageIds -> Json.toJson(sectionPageIds)
    json += JsonKeys.SiteId -> JsString(siteId)
    json
  }
    */

}



private[rdb]
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
    IndexName
  // but if the site has its own index:  s"site_${siteId}_v0"  ?

  val IndexVersion = 1
  val IndexName = s"sites_v$IndexVersion"


  val PostMappingName = "post"


  def elasticSearchIdFor(siteId: String, post: Post): String =
    unimplemented("indexing Post2 [DwE7KEP23]")/*
    elasticSearchIdFor(siteId, pageId = post.page.id, postId = post.id)
    */

  def elasticSearchIdFor(siteId: String, pageId: PageId, postNr: PostNr): String =
    s"$siteId:$pageId:$postNr"


  object JsonKeys {
    val SiteId = "siteId"
    val SectionPageIds = "sectionPageIds"
  }


  /** Settings for the currently one and only ElasticSearch index.
    *
    * Over allocate shards ("user based data flow", and in Debiki's case, each user is a website).
    * We'll use routing to direct all traffic for a certain site to a single shard always.
    *
    * About ElasticSearch's Snowball analyzer: "[it] uses the standard tokenizer,
    * with standard filter, lowercase filter, stop filter, and snowball filter",
    * see: http://www.elasticsearch.org/guide/reference/index-modules/analysis/snowball-analyzer/
    * Use such an analyzer, but add the html_strip char filter.
    */
  val IndexSettings = i"""
    |number_of_shards: 5
    |number_of_replicas: 1
    |analysis:
    |  analyzer:
    |    HtmlSnowball:
    |      type: custom
    |      tokenizer: standard
    |      filter: [standard, lowercase, stop, snowball]
    |      char_filter: [HtmlStrip]
    |  char_filter :
    |    HtmlStrip :
    |      type : html_strip
    |#     escaped_tags : [xxx, yyy]  -- what's this?
    |#     read_ahead : 1024        -- what's this?
    |"""


  // COULD avoid indexing all fields, don't need them all.
  val PostMappingDefinition = i"""{
    |"post": {
    |  "properties": {
    |    "siteId":                       {"type": "string", "index": "not_analyzed"},
    |    "pageId":                       {"type": "string", "index": "not_analyzed"},
    |    "postId":                       {"type": "integer", "index": "no"},
    |    "parentPostId":                 {"type": "integer"},
    |    "sectionPageIds":               {"type": "string", "index": "not_analyzed"},
    |    "createdAt":                    {"type": "date"},
    |    "currentText":                  {"type": "string", "index": "no"},
    |    "anyDirectApproval":            {"type": "string", "index": "no"},
    |    "where":                        {"type": "string", "index": "no"},
    |    "loginId":                      {"type": "string", "index": "not_analyzed"},
    |    "userId":                       {"type": "string", "index": "not_analyzed"},
    |    "newIp":                        {"type": "ip",     "index": "not_analyzed"},
    |    "lastActedUponAt":              {"type": "date",   "index": "no"},
    |    "lastReviewDati":               {"type": "date",   "index": "no"},
    |    "lastAuthoritativeReviewDati":  {"type": "date",   "index": "no"},
    |    "lastApprovalDati":             {"type": "date",   "index": "no"},
    |    "lastApprovedText":             {"type": "string", "analyzer": "HtmlSnowball"},
    |    "lastPermanentApprovalDati":    {"type": "date",   "index": "no"},
    |    "lastManualApprovalDati":       {"type": "date",   "index": "no"},
    |    "lastEditAppliedAt":            {"type": "date",   "index": "no"},
    |    "lastEditRevertedAt":           {"type": "date",   "index": "no"},
    |    "lastEditorId":                 {"type": "string", "index": "no"},
    |    "postCollapsedAt":              {"type": "date",   "index": "no"},
    |    "treeCollapsedAt":              {"type": "date",   "index": "no"},
    |    "postDeletedAt":                {"type": "date",   "index": "no"},
    |    "postDeletedById":              {"type": "string", "index": "no"},
    |    "treeDeletedAt":                {"type": "date",   "index": "no"},
    |    "treeDeletedById":              {"type": "string", "index": "no"},
    |    "postHiddenAt":                 {"type": "date",   "index": "no"},
    |    "postHiddenById":               {"type": "string", "index": "no"},
    |    "numEditSuggestionsPending":    {"type": "integer", "index": "no"},
    |    "numEditsAppliedUnreviewed":    {"type": "integer", "index": "no"},
    |    "numEditsAppldPrelApproved":    {"type": "integer", "index": "no"},
    |    "numEditsToReview":             {"type": "integer", "index": "no"},
    |    "numDistinctEditors":           {"type": "integer", "index": "no"},
    |    "numCollapsesToReview":         {"type": "integer", "index": "no"},
    |    "numUncollapsesToReview":       {"type": "integer", "index": "no"},
    |    "numDeletesToReview":           {"type": "integer", "index": "no"},
    |    "numUndeletesToReview":         {"type": "integer", "index": "no"},
    |    "numFlagsPending":              {"type": "integer"},
    |    "numFlagsHandled":              {"type": "integer", "index": "no"},
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


