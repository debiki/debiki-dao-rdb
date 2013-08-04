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
import org.elasticsearch.{search => es}
import org.elasticsearch.{action => ea}
import org.elasticsearch.action.{search => eas}
import org.elasticsearch.common.{text => ect}
import org.elasticsearch.index.{query => eiq}
import org.elasticsearch.search.{highlight => esh}
import scala.concurrent.{Future, Promise}
import FullTextSearchIndexer._
import FullTextSearchSiteDaoMixin._
import Prelude._



object FullTextSearchSiteDaoMixin {

  private val LastApprovedTextField = "lastApprovedText"
  private val PageIdField = "pageId"
  private val UserIdField = "userId"
  private val HighlightPreMark = "_EsHit1_Kh09BfZwQ4_"
  private val HighlightPostMark = "_EsTih1_Kh09BfZwQ4_"
  private val HighlightPreTag = "<mark>"
  private val HighlightPostTag = "</mark>"

}



trait FullTextSearchSiteDaoMixin {
  self: RelDbTenantDbDao =>

  private def client = self.fullTextSearchIndexer.client

  private val indexName = FullTextSearchIndexer.indexName(siteId)

  // Tips:
  //  blog.trifork.com/2012/09/13/elasticsearch-beyond-big-data-running-elasticsearch-embedded/
  // Not needed?:
  //  https://github.com/dadoonet/spring-elasticsearch
  // because I'll generate indexes asynchronously?


  def fullTextSearch(phrase: String, anyRootPageId: Option[String])
        : Future[FullTextSearchResult] = {

    // Filter by site id and perhaps section id.
    val siteIdFilter = eiq.FilterBuilders.termFilter(JsonKeys.SiteId, siteId)
    val filterToUse = anyRootPageId match {
      case None => siteIdFilter
      case Some(sectionId) =>
        val sectionIdFilter = eiq.FilterBuilders.termFilter(JsonKeys.SectionPageIds, sectionId)
        eiq.FilterBuilders.andFilter().add(siteIdFilter).add(sectionIdFilter)
    }

    // Full-text-search the most recently approved text, for each post.
    // (Don't search the current text, because it might not have been approved and isn't
    // shown, by default. Also, it might be stored in compact diff format, and is
    // not indexed, and thus really not searchable anyway.)
    val queryBuilder = eiq.QueryBuilders.queryString(phrase).field(LastApprovedTextField)

    val filteredQueryBuilder: eiq.FilteredQueryBuilder =
      eiq.QueryBuilders.filteredQuery(queryBuilder,  filterToUse)

    val searchRequestBuilder =
      client.prepareSearch(indexName)
        .setRouting(siteId)
        .setQuery(filteredQueryBuilder)
        .addHighlightedField(LastApprovedTextField)
        .setHighlighterPreTags(HighlightPreMark)
        .setHighlighterPostTags(HighlightPostMark)

    val futureJavaResponse: ea.ListenableActionFuture[eas.SearchResponse] =
      searchRequestBuilder.execute()

    val resultPromise = Promise[FullTextSearchResult]

    futureJavaResponse.addListener(new ea.ActionListener[eas.SearchResponse] {
      def onResponse(response: eas.SearchResponse) {
        resultPromise.success(buildSearchResults(response))
      }
      def onFailure(t: Throwable) {
        resultPromise.failure(t)
      }
    })

    resultPromise.future
  }


  private def buildSearchResults(response: eas.SearchResponse): FullTextSearchResult = {
    var pageIds = Set[PageId]()
    var authorIds = Set[String]()

    val jsonAndElasticSearchHits = for (hit: es.SearchHit <- response.getHits.getHits) yield {
      val jsonString = hit.getSourceAsString
      val json = play.api.libs.json.Json.parse(jsonString)
      val pageId = (json \ PageIdField).as[PageId]
      val authorId = (json \ UserIdField).as[String]
      pageIds += pageId
      authorIds += authorId
      (json, hit)
    }

    val pageMetaByPageId = loadPageMetas(pageIds.toList)
    // ... Could also load author names ...

    val hits = for ((json, hit) <- jsonAndElasticSearchHits) yield {
      val highlightField: esh.HighlightField = hit.getHighlightFields.get(LastApprovedTextField)
      val htmlTextAndMarks: List[String] = highlightField.getFragments.toList.map(_.toString)
      val textAndMarks = htmlTextAndMarks.map(org.jsoup.Jsoup.parse(_).text())
      val textAndHtmlMarks = textAndMarks.map(
          _.replaceAllLiterally(HighlightPreMark, HighlightPreTag)
            .replaceAllLiterally(HighlightPostMark, HighlightPostTag))
      val post = Post.fromJson(json)
      FullTextSearchHit(post, hit.getScore, safeHighlightsHtml = textAndHtmlMarks)
    }

    FullTextSearchResult(hits, pageMetaByPageId)
  }



  /*
  protected def createIndex(siteId: String) {
    val request: es.action.admin.indices.create.CreateIndexRequest =
      es.client.Requests.createIndexRequest(siteId)
      // .settings(yourSettings)
      // .mapping(yourMapping);

    val response: es.action.admin.indices.create.CreateIndexResponse =
      client.admin().indices().create(request).actionGet()

    //client.prepareIndex(s"site-$siteId", "type")
    //client.prepareIndex("esa", "activityStream", id).setSource(json).execute().actionGet()
  }*/

}
