/**
 * Copyright (c) 2018 Kaj Magnus Lindberg
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

import collection.immutable
import com.debiki.core._
import com.debiki.core.Prelude._
import java.{sql => js}
import Rdb._
import scala.collection.mutable.ArrayBuffer


/** Loads and saves Draft:s.
  */
trait DraftsSiteDaoMixin extends SiteTransaction {
  self: RdbSiteTransaction =>


  override def nextDraftNr(userId: UserId): DraftNr = {
    val query = """
      -- can use pk index
      select max(draft_nr) max_nr from drafts3 where site_id = ? and user_id = ?
      """
    runQueryFindExactlyOne(query, List(siteId.asAnyRef, userId.asAnyRef), rs => {
      val maxNr = rs.getInt("max_nr") // null becomes 0, fine
      maxNr + 1
    })
  }


  override def upsertDraft(draft: Draft) {
    // Probably the same person won't be editing the same draft, in two places at once,
    // humans cannot do such things. So upsert, and overwriting = fine, as intended.

    val insertStatement = s"""
      insert into drafts3 (
        site_id,
        by_user_id,
        draft_nr,
        created_at,
        last_edited_at,
        -- auto_post_at,
        -- auto_post_publ_status,
        deleted_at,
        new_topic_category_id,
        new_topic_type,
        message_to_user_id,
        edit_post_id,
        reply_to_page_id,
        reply_to_post_nr,
        reply_type,
        -- reply_whisper_to_user_id,
        title,
        text)
      values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      on conflict (site_id, by_user_id, draft_nr)
      do update set
        -- Use the new version, probably it's more recent.
        -- (Checking last_edited_at = not worth the trouble?)
        created_at = least(drafts3.created_at, excluded.created_at),
        last_edited_at = excluded.last_edited_at,
        -- auto_post_at = excluded.auto_post_at,  — later
        -- auto_post_publ_status =  — later
        deleted_at = excluded.deleted_at,
        new_topic_category_id = excluded.new_topic_category_id,
        new_topic_type = excluded.new_topic_type,
        message_to_user_id = excluded.message_to_user_id,
        edit_post_id = excluded.edit_post_id,
        reply_to_page_id = excluded.reply_to_page_id,
        reply_to_post_nr = excluded.reply_to_post_nr,
        reply_type = excluded.reply_type,
        -- reply_whisper_to_user_id = excluded.reply_whisper_to_user_id,  — later
        title = excluded.title,
        text = excluded.text
      """

    val locator = draft.forWhat

    unimplementedIf(draft.autoPostAt.isDefined, "TyE25920ARKAAT")

    runUpdateSingleRow(insertStatement, List(
      siteId.asAnyRef,
      draft.byUserId.asAnyRef,
      draft.draftNr.asAnyRef,
      draft.createdAt.asTimestamp,
      draft.lastEditedAt.orNullTimestamp,
      // auto post at
      // auto post publ status
      draft.deletedAt.orNullTimestamp,
      locator.newTopicCategoryId.orNullInt,
      draft.newTopicType.map(_.toInt).orNullInt,
      locator.messageToUserId.orNullInt,
      locator.editPostId.orNullInt,
      locator.replyToPageId.orNullVarchar,
      locator.replyToPostNr.orNullInt,
      locator.replyType.map(_.toInt).orNullInt,
      draft.title.orNullVarchar,
      draft.text))
  }


  override def deleteDraft(userId: UserId, draftNr: DraftNr): Boolean = {
    val deleteStatement = s"""
      delete from drafts3 where site_id = ? and by_user_id = ? and draft_nr = ?
      """
    runUpdateSingleRow(deleteStatement, List(siteId.asAnyRef, userId.asAnyRef, draftNr.asAnyRef))
  }


  override def loadDraftByNr(userId: UserId, draftNr: DraftNr): Option[Draft] = {
    val query = s"""
      select * from drafts3 where site_id = ? and by_user_id = ? and draft_nr = ?
      """
    runQueryFindOneOrNone(query, List(siteId.asAnyRef, userId.asAnyRef, draftNr.asAnyRef), readDraft)
  }


  override def loadDraftByLocator(userId: UserId, draftLocator: DraftLocator): Option[Draft] = {
    val values = ArrayBuffer[AnyRef](siteId.asAnyRef, userId.asAnyRef)
    val locatorClauses =
      if (draftLocator.newTopicCategoryId.isDefined) {
        values.append(draftLocator.newTopicCategoryId.get.asAnyRef)
        "new_topic_category_id = ?"
      }
      else if (draftLocator.messageToUserId.isDefined) {
        values.append(draftLocator.messageToUserId.get.asAnyRef)
        "message_to_user_id = ?"
      }
      else if (draftLocator.editPostId.isDefined) {
        values.append(draftLocator.editPostId.get.asAnyRef)
        "edit_post_id = ?"
      }
      else if (draftLocator.replyToPostNr.isDefined) {
        values.append(draftLocator.replyToPageId.get.asAnyRef)
        values.append(draftLocator.replyToPostNr.get.asAnyRef)
        values.append(draftLocator.replyType.get.asAnyRef)
        o"""reply_to_page_id = ? and reply_to_post_nr = ? and reply_type = ?
            and reply_whisper_to_user_id is null"""
      }
      else {
        die("TyE4AKRRJ73")
      }

    val query = s"""
      select * from drafts3
      where site_id = ?
        and by_user_id = ?
        and $locatorClauses"""

    runQueryFindOneOrNone(query, values.toList, readDraft)
  }


  override def listDraftsRecentlyEditedFirst(userId: UserId): immutable.Seq[Draft] = {
    val query = s"""
      select * from drafts3 where site_id = ? and by_user_id = ?
      -- Can use index drafts_byuser_editedat_i?
      order by coalesce(last_edited_at, created_at) desc
      """
    runQueryFindMany(query, List(siteId.asAnyRef, userId.asAnyRef), readDraft)
  }


  private def readDraft(rs: js.ResultSet): Draft = {
    val draftLocator = DraftLocator(
      newTopicCategoryId = getOptInt(rs, "new_topic_category_id"),
      messageToUserId = getOptInt(rs, "message_to_user_id"),
      editPostId = getOptInt(rs, "edit_post_id"),
      replyToPageId = getOptString(rs, "reply_to_page_id"),
      replyToPostNr = getOptInt(rs, "reply_to_post_nr"),
      replyType = getOptInt(rs, "reply_type").flatMap(PostType.fromInt))

    Draft(
      byUserId = getIntNot0(rs, "by_user_id"),
      draftNr = getIntNot0(rs, "draft_nr"),
      forWhat = draftLocator,
      createdAt = getWhen(rs, "created_at"),
      lastEditedAt = getOptWhen(rs, "last_edited_at"),
      autoPostAt = getOptWhen(rs, "auto_post_at"),
      deletedAt = getOptWhen(rs, "deleted_at"),
      newTopicType = getOptInt(rs, "new_topic_type").flatMap(PageRole.fromInt),
      title = getOptString(rs, "title"),
      text = getString(rs, "text"))
  }

}
