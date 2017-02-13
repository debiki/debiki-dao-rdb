/**
 * Copyright (C) 2014 Kaj Magnus Lindberg (born 1979)
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
import collection.mutable.ArrayBuffer
import com.debiki.core._
import java.{sql => js}
import scala.collection.mutable
import Rdb._
import RdbUtil._


/** Saves and loads info on how many times each post has been read and by whom.
  */
trait PostsReadStatsSiteDaoMixin extends SiteTransaction { // RENAME to ReadStats...
  self: RdbSiteTransaction =>


  def updatePostsReadStats(pageId: PageId, postNrsRead: Set[PostNr],
        readById: UserId, readFromIp: String) {

    // There's an ignore-duplicate-inserts rule in the database (DW1_PSTSRD_IGNORE_DUPL_INS).
    // However, if two transactions insert the same PK data at the same time that rule
    // will have no effect, see:
    //  http://postgresql.1045698.n5.nabble.com/Duplicated-entries-are-not-ignored-even-if-a-quot-do-instead-nothing-quot-rule-is-added-td5116004.html
    //   """if a concurrent transaction tries to create the same record, one of the transactions
    //   is going to find that it already exists on transaction commit. An INSERT-rule is not
    //   going to protect you against that."""
    // So let's insert each row in its own transaction and ignore any PK error.
    // (One single transaction for all rows won't work, because the whole transaction would
    // fail on any single unique key error.)
    // (If we'd like to avoid roundtrips for separate commits, we could enable autocommit?
    // Or insert via a stored procedure? Well, performance hardly matters.)
    for (postNr <- postNrsRead) {
      transactionCheckQuota { implicit connection =>
        val sql = s"""
          insert into post_read_stats3(
            SITE_ID, PAGE_ID, post_nr, IP, USER_ID, READ_AT)
          values (?, ?, ?, ?, ?, now_utc())"""
        val values = List[AnyRef](siteId, pageId, postNr.asAnyRef,
          readFromIp, readById.asAnyRef)
        try {
          db.update(sql, values)
        }
        catch {
          case ex: js.SQLException if isUniqueConstrViolation(ex) =>
            // Ignore, simply means the user has already read the post.
            // And see the long comment above.
        }
      }
    }
  }


  def loadPostsReadStats(pageId: PageId): PostsReadStats =
    loadPostsReadStats(pageId, postNr = None)


  def loadPostsReadStats(pageId: PageId, postNr: Option[PostNr]): PostsReadStats = {
    var sql = s"""
      select post_nr, IP, USER_ID from post_read_stats3
      where SITE_ID = ? and PAGE_ID = ?"""
    val values = ArrayBuffer[AnyRef](siteId, pageId)
    postNr foreach { id =>
      sql += " and post_nr = ?"
      values.append(id.asAnyRef)
    }
    val ipsByPostNr = mutable.HashMap[PostNr, ArrayBuffer[String]]()
    val roleIdsByPostNr = mutable.HashMap[PostNr, ArrayBuffer[RoleId]]()

    runQuery(sql, values.toList, rs => {
      while (rs.next) {
        val postNr = rs.getInt("post_nr")
        val ip = rs.getString("IP")
        val anyUserId = getOptionalIntNoneNot0(rs, "USER_ID")
        anyUserId match {
          case Some(id) if User.isRoleId(id) =>
            val buffer = roleIdsByPostNr.getOrElseUpdate(postNr, new ArrayBuffer[RoleId])
            buffer += id
          case _ =>
            val buffer = ipsByPostNr.getOrElseUpdate(postNr, new ArrayBuffer[String])
            buffer += ip
        }
      }
    })

    val immutableIpsMap = ipsByPostNr.map({ case (postNr, ips) =>
      (postNr, ips.toSet)
    }).toMap
    val immutableRolesMap = roleIdsByPostNr.map({ case (postNr, roleIds) =>
      (postNr, roleIds.toSet)
    }).toMap

    PostsReadStats(immutableIpsMap, immutableRolesMap)
  }


  def movePostsReadStats(oldPageId: PageId, newPageId: PageId,
        newPostNrsByOldNrs: Map[PostNr, PostNr]) {
    require(oldPageId != newPageId, "EsE7YJK830")
    if (newPostNrsByOldNrs.isEmpty)
      return

    val oldPostNrs = newPostNrsByOldNrs.keys.toList
    val values = ArrayBuffer[AnyRef](newPageId)
    val whens: String = newPostNrsByOldNrs.toSeq.map(_ match {
      case (oldNr: PostNr, newNr: PostNr) =>
        values.append(oldNr.asAnyRef)
        values.append(newNr.asAnyRef)
        s"when ? then ?"
    }).mkString(" ")
    values.append(siteId)
    values.append(oldPageId)
    val statement = s"""
      update post_read_stats3 set
        page_id = ?,
        post_nr =
          case post_nr
          $whens
          else post_nr
          end
      where site_id = ? and page_id = ? and post_nr in (${makeInListFor(oldPostNrs)})
      """
    values.appendAll(oldPostNrs.map(_.asAnyRef))
    runUpdate(statement, values.toList)
  }


  def loadUserStats(userId: UserId): Option[UserStats] = {
    val query = """
      select * from user_stats3
      where site_id = ? and user_id = ?
      """
    runQueryFindOneOrNone(query, List(siteId, userId.asAnyRef), rs => {
      UserStats(
        userId = userId,
        lastSeenAt = getWhen(rs, "last_seen_at"),
        lastPostedAt = getOptWhen(rs, "last_posted_at"),
        lastEmailedAt = getOptWhen(rs, "last_emailed_at"),
        emailBounceSum = rs.getFloat("email_bounce_sum"),
        firstSeenAtOr0 = getWhen(rs, "first_seen_at"),
        firstNewTopicAt = getOptWhen(rs, "first_new_topic_at"),
        firstDiscourseReplyAt = getOptWhen(rs, "first_discourse_reply_at"),
        firstChatMessageAt = getOptWhen(rs, "first_chat_message_at"),
        topicsNewSince = getWhen(rs, "topics_new_since"),
        notfsNewSinceId = rs.getInt("notfs_new_since_id"),
        numDaysVisited = rs.getInt("num_days_visited"),
        numSecondsReading = rs.getInt("num_seconds_reading"),
        numDiscourseRepliesRead = rs.getInt("num_discourse_replies_read"),
        numDiscourseRepliesPosted = rs.getInt("num_discourse_replies_posted"),
        numDiscourseTopicsEntered = rs.getInt("num_discourse_topics_entered"),
        numDiscourseTopicsRepliedIn = rs.getInt("num_discourse_topics_replied_in"),
        numDiscourseTopicsCreated = rs.getInt("num_discourse_topics_created"),
        numChatMessagesRead = rs.getInt("num_chat_messages_read"),
        numChatMessagesPosted = rs.getInt("num_chat_messages_posted"),
        numChatTopicsEntered = rs.getInt("num_chat_topics_entered"),
        numChatTopicsRepliedIn = rs.getInt("num_chat_topics_replied_in"),
        numChatTopicsCreated = rs.getInt("num_chat_topics_created"),
        numLikesGiven = rs.getInt("num_likes_given"),
        numLikesReceived = rs.getInt("num_likes_received"),
        numSolutionsProvided = rs.getInt("num_solutions_provided"))
    })
  }


  def upsertUserStats(userStats: UserStats) {
    // Dupl code, also in Scala [7FKTU02], perhaps add param `addToOldStats: Boolean`?
    val statement = s"""
      insert into user_stats3 (
        site_id,
        user_id,
        last_seen_at,
        last_posted_at,
        last_emailed_at,
        email_bounce_sum,
        first_seen_at,
        first_new_topic_at,
        first_discourse_reply_at,
        first_chat_message_at,
        topics_new_since,
        notfs_new_since_id,
        num_days_visited,
        num_seconds_reading,
        num_discourse_replies_read,
        num_discourse_replies_posted,
        num_discourse_topics_entered,
        num_discourse_topics_replied_in,
        num_discourse_topics_created,
        num_chat_messages_read,
        num_chat_messages_posted,
        num_chat_topics_entered,
        num_chat_topics_replied_in,
        num_chat_topics_created,
        num_likes_given,
        num_likes_received,
        num_solutions_provided)
      values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      on conflict (site_id, user_id) do update set
        last_seen_at =
            greatest(user_stats3.last_seen_at, excluded.last_seen_at),
        last_posted_at =
            greatest(user_stats3.last_posted_at, excluded.last_posted_at),
        last_emailed_at =
            greatest(user_stats3.last_emailed_at, excluded.last_emailed_at),
        email_bounce_sum =
            excluded.email_bounce_sum,
        first_seen_at =
            least(user_stats3.first_seen_at, excluded.first_seen_at),
        first_new_topic_at =
            least(user_stats3.first_new_topic_at, excluded.first_new_topic_at),
        first_discourse_reply_at =
            least(user_stats3.first_discourse_reply_at, excluded.first_discourse_reply_at),
        first_chat_message_at =
            least(user_stats3.first_chat_message_at, excluded.first_chat_message_at),
        topics_new_since =
            greatest(user_stats3.topics_new_since, excluded.topics_new_since),
        notfs_new_since_id =
            greatest(user_stats3.notfs_new_since_id, excluded.notfs_new_since_id),
        num_days_visited = excluded.num_days_visited,
        num_seconds_reading = excluded.num_seconds_reading,
        num_discourse_replies_read = excluded.num_discourse_replies_read,
        num_discourse_replies_posted = excluded.num_discourse_replies_posted,
        num_discourse_topics_entered = excluded.num_discourse_topics_entered,
        num_discourse_topics_replied_in = excluded.num_discourse_topics_replied_in,
        num_discourse_topics_created = excluded.num_discourse_topics_created,
        num_chat_messages_read = excluded.num_chat_messages_read,
        num_chat_messages_posted = excluded.num_chat_messages_posted,
        num_chat_topics_entered = excluded.num_chat_topics_entered,
        num_chat_topics_replied_in = excluded.num_chat_topics_replied_in,
        num_chat_topics_created = excluded.num_chat_topics_created,
        num_likes_given = excluded.num_likes_given,
        num_likes_received = excluded.num_likes_received,
        num_solutions_provided = excluded.num_solutions_provided
      """

    val values = List(
      siteId,
      userStats.userId.asAnyRef,
      userStats.lastSeenAt.asTimestamp,
      userStats.lastPostedAt.orNullTimestamp,
      userStats.lastEmailedAt.orNullTimestamp,
      userStats.emailBounceSum.asAnyRef,
      userStats.firstSeenAtNot0.asTimestamp,
      userStats.firstNewTopicAt.orNullTimestamp,
      userStats.firstDiscourseReplyAt.orNullTimestamp,
      userStats.firstChatMessageAt.orNullTimestamp,
      userStats.topicsNewSince.asTimestamp,
      userStats.notfsNewSinceId.asAnyRef,
      userStats.numDaysVisited.asAnyRef,
      userStats.numSecondsReading.asAnyRef,
      userStats.numDiscourseRepliesRead.asAnyRef,
      userStats.numDiscourseRepliesPosted.asAnyRef,
      userStats.numDiscourseTopicsEntered.asAnyRef,
      userStats.numDiscourseTopicsRepliedIn.asAnyRef,
      userStats.numDiscourseTopicsCreated.asAnyRef,
      userStats.numChatMessagesRead.asAnyRef,
      userStats.numChatMessagesPosted.asAnyRef,
      userStats.numChatTopicsEntered.asAnyRef,
      userStats.numChatTopicsRepliedIn.asAnyRef,
      userStats.numChatTopicsCreated.asAnyRef,
      userStats.numLikesGiven.asAnyRef,
      userStats.numLikesReceived.asAnyRef,
      userStats.numSolutionsProvided.asAnyRef)

    runUpdateExactlyOneRow(statement, values)
  }



  def loadUserVisitStats(memberId: UserId): immutable.Seq[UserVisitStats] = {
    require(memberId >= LowestNonGuestId, "EdE84SZMI8")
    val query = """
      select * from user_visit_stats3
      where site_id = ? and user_id = ?
      order by visit_date desc
      """
    runQueryFindMany(query, List(siteId, memberId.asAnyRef), rs => {
      UserVisitStats(
        userId = memberId,
        visitDate = getWhen(rs, "visit_date").toDays,
        numSecondsReading = rs.getInt("num_seconds_reading"),
        numDiscourseRepliesRead = rs.getInt("num_discourse_replies_read"),
        numDiscourseTopicsEntered = rs.getInt("num_discourse_topics_entered"),
        numChatMessagesRead = rs.getInt("num_chat_messages_read"),
        numChatTopicsEntered = rs.getInt("num_chat_topics_entered"))
    })
  }


  def upsertUserVisitStats(visitStats: UserVisitStats) {
    val statement = s"""
      insert into user_visit_stats3 (
        site_id,
        user_id,
        visit_date,
        num_seconds_reading,
        num_discourse_replies_read,
        num_discourse_topics_entered,
        num_chat_messages_read,
        num_chat_topics_entered)
      values (?, ?, ?, ?, ?, ?, ?, ?)
      on conflict (site_id, user_id, visit_date) do update set
        num_seconds_reading = excluded.num_seconds_reading,
        num_discourse_replies_read = excluded.num_discourse_replies_read,
        num_discourse_topics_entered = excluded.num_discourse_topics_entered,
        num_chat_messages_read = excluded.num_chat_messages_read,
        num_chat_topics_entered = excluded.num_chat_topics_entered
      """

    val values = List(
      siteId,
      visitStats.userId.asAnyRef,
      visitStats.visitDate.toJavaDate.asTimestamp,
      visitStats.numSecondsReading.asAnyRef,
      visitStats.numDiscourseRepliesRead.asAnyRef,
      visitStats.numDiscourseTopicsEntered.asAnyRef,
      visitStats.numChatMessagesRead.asAnyRef,
      visitStats.numChatTopicsEntered.asAnyRef)

    runUpdateExactlyOneRow(statement, values)
  }

}
