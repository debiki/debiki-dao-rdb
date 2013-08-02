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

import java.{util => ju}
import org.{elasticsearch => es}
import play.{api => p}
import play.api.libs.json._
import FullTextSearchIndexer._
import Prelude._


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
    s"sites_v0"
    // but if the site has its own index:  s"site_${siteId}_v0"  ?


  val ElasticSearchPostTypeName = "post"


  def elasticSearchIdFor(siteId: String, post: Post) =
    s"$siteId:${post.page.id}:${post.id}"

}


private[v0]
class FullTextSearchIndexer(private val relDbDaoFactory: RelDbDaoFactory) {

  private def systemDao = relDbDaoFactory.systemDbDao

  // This'll do for now. (Make configurable, later)
  private val DefaultDataDir = "target/elasticsearch-data"


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


  def shutdown() {
    client.close()
    node.close()
  }


  /** Currently indexes each post directly.
    */
  def indexNewPostsSoon(page: PageNoPath, posts: Seq[Post], siteId: String) {
    posts foreach { fullTextSearchIndexPost(page, _, siteId) }
  }


  private def fullTextSearchIndexPost(page: PageNoPath, post: Post, siteId: String) {
    val json = makeSearchEngineJsonFor(post, page, siteId)
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


  private def makeSearchEngineJsonFor(post: Post, page: PageNoPath, siteId: String): JsValue = {
    var sectionPageIds = page.ancestorIdsParentFirst
    if (page.meta.pageRole.childRole.isDefined) {
      // Since this page can have child pages, consider it a section (e.g. a blog,
      // forum, subforum or a wiki).
      sectionPageIds ::= page.id
    }

    var json = post.toJson
    json += "sectionPageIds" -> Json.toJson(sectionPageIds)
    json += "siteId" -> JsString(siteId)
    json
  }

}

