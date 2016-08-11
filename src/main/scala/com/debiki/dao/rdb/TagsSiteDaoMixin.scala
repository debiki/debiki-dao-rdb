/**
 * Copyright (c) 2016 Kaj Magnus Lindberg
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

import collection.mutable.ArrayBuffer
import com.debiki.core._
import com.debiki.core.Prelude._
import Rdb._
import RdbUtil._


/** Adds and removes post tags. Page tags = tags added to the page body post (post nr 1).
  */
trait TagsSiteDaoMixin extends SiteTransaction {
  self: RdbSiteDao =>


  def loadAllTagsAsSet(): Set[TagLabel] = {
    val query = """
      select tag from post_tags3 where site_id = ?
      """
    runQueryFindManyAsSet(query, List(siteId), rs => rs.getString("tag"))
  }


  def loadTagsByPostId(postIds: Iterable[UniquePostId]): Map[UniquePostId, Set[TagLabel]] = {
    val query = s"""
      select tag, post_id from post_tags3
      where site_id = ? and post_id in (${ makeInListFor(postIds) })
      order by post_id
      """
    val tags = ArrayBuffer[String]()
    var currentPostId = NoPostId
    var tagsByPostId = Map[UniquePostId, Set[TagLabel]]().withDefaultValue(Set.empty)
    runQueryAndForEachRow(query, siteId :: postIds.toList.map(_.asAnyRef), rs => {
      val postId: UniquePostId = rs.getInt("post_id")
      val tag: TagLabel = rs.getString("tag")
      if (currentPostId == NoPostId || currentPostId == postId) {
        currentPostId = postId
        tags += tag
      }
      else {
        tagsByPostId = tagsByPostId.updated(currentPostId, tags.toSet)
        tags.clear()
        currentPostId = postId
      }
    })
    if (currentPostId != NoPostId) {
      tagsByPostId = tagsByPostId.updated(currentPostId, tags.toSet)
    }
    tagsByPostId
  }


  def removeTagsFromPost(tags: Set[Tag], postId: UniquePostId) {
    if (tags.isEmpty)
      return
    val statement = s"""
      delete from post_tags3 where site_id = ? and post_id = ? and tag in (${makeInListFor(tags)})
      """
    val values = siteId :: postId.asAnyRef :: tags.toList
    runUpdate(statement, values)
  }


  def addTagsToPost(tags: Set[TagLabel], postId: UniquePostId) {
    if (tags.isEmpty)
      return
    val rows = ("(?, ?, ?), " * tags.size) dropRight 2 // drops last ", "
    val values: List[AnyRef] = tags.toList flatMap (List(siteId, postId.asAnyRef, _))
    val statement = s"""
      insert into post_tags3 (site_id, post_id, tag) values $rows
      """
    runUpdate(statement, values)
  }


  def renameTag(from: String, to: String) {
    die("EsE5KPU02SK3", "Unimplemented")
    // update post_tags3 ...
  }

}
