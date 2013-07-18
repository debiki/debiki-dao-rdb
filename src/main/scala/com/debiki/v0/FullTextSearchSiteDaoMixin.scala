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
import FullTextSearchSiteDaoMixin._
import Prelude._



object FullTextSearchSiteDaoMixin {


  // This'll do for now. (Make configurable, later)
  private val DefaultDataDir = "target/elasticsearch-data"

  private val node = {
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

  private val client = node.client()


  // TODO Call on server shutdown
  private def stopSearchDb() {
    node.close()
    client.close()
  }


  private def elasticSearchIdFor(post: Post) = post.page.id + "." + post.id

}



trait FullTextSearchSiteDaoMixin {
  self: RelDbTenantDbDao =>

  protected val indexName = s"site-$siteId"

  // Tips:
  //  blog.trifork.com/2012/09/13/elasticsearch-beyond-big-data-running-elasticsearch-embedded/
  // Not needed?:
  //  https://github.com/dadoonet/spring-elasticsearch
  // because I'll generate indexes asynchronously?


  def fullTextSearch(phrase: String, anyRootPageId: Option[String]): FullTextSearchResult = {
    val queryBuilder = es.index.query.QueryBuilders.queryString(phrase).field("lastApprovedText")
    val searchRequestBuilder = client.prepareSearch(indexName).setQuery(queryBuilder)
    val response: es.action.search.SearchResponse = searchRequestBuilder.execute().actionGet()
    val hits = for (hit: es.search.SearchHit <- response.getHits.getHits) yield {
      val jsonString = hit.getSourceAsString
      val post = Post.fromJsonString(jsonString)
      FullTextSearchHit(post)
    }
    FullTextSearchResult(hits)
  }


  protected def createIndex(siteId: String) {
    val request: es.action.admin.indices.create.CreateIndexRequest =
      es.client.Requests.createIndexRequest(siteId)
      // .settings(yourSettings)
      // .mapping(yourMapping);

    val response: es.action.admin.indices.create.CreateIndexResponse =
      client.admin().indices().create(request).actionGet()

    //client.prepareIndex(s"site-$siteId", "type")
    //client.prepareIndex("esa", "activityStream", id).setSource(json).execute().actionGet()
  }


  protected def fullTextSearchIndexPost(post: Post) {
    val indexRequest: es.action.index.IndexRequest = es.client.Requests.indexRequest(indexName)
        .`type`("post")
        .id(elasticSearchIdFor(post))
        .source(post.toJsonString)
    val indexResponse: es.action.index.IndexResponse = client.index(indexRequest).actionGet()
    System.out.println("Index response version:\n" + indexResponse.getVersion)
  }

}



