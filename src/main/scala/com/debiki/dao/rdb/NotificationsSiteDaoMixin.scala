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

import com.debiki.core._
import com.debiki.core.Prelude._
import java.{sql => js, util => ju}
import scala.collection.mutable
import Rdb._


/** Saves and deletes notifications.
  */
trait NotificationsSiteDaoMixin extends SiteTransaction {
  self: RdbSiteDao =>


  def saveDeleteNotifications(notifications: Notifications) {
    // Perhaps we'd better allow notifications to be created and deleted, so that any
    // site-over-quota notifications get sent.
    transactionAllowOverQuota { implicit connection =>
      notifications.toCreate foreach { createNotf(_)(connection) }
      notifications.toDelete foreach { deleteNotf(_)(connection) }
    }
  }


  override def nextNotificationId(): NotificationId = {
    val query = """
      select max(notf_id) max_id from dw1_notifications where site_id = ?
      """
    runQueryFindExactlyOne(query, List(siteId), rs => {
      val maxId = rs.getInt("max_id") // null becomes 0, fine
      maxId + 1
    })
  }


  private def createNotf(notf: Notification)(implicit connection: js.Connection) {
    val sql = """
      insert into DW1_NOTIFICATIONS(
        SITE_ID, notf_id, CREATED_AT, NOTF_TYPE,
        UNIQUE_POST_ID, PAGE_ID, ACTION_TYPE, ACTION_SUB_ID,
        BY_USER_ID, TO_USER_ID)
      values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      """


    val values = mutable.ArrayBuffer[AnyRef](siteId, notf.id.asAnyRef, d2ts(notf.createdAt),
      notf.tyype.toInt.asAnyRef)

    notf match {
      case postNotf: Notification.NewPost =>
        values += postNotf.uniquePostId.asAnyRef
        values += NullVarchar // page id not saved because might change if post moved
        values += NullInt // no related post action
        values += NullInt //
        values += postNotf.byUserId.asAnyRef
        values += postNotf.toUserId.asAnyRef
    }

    db.update(sql, values.toList)
  }


  private def deleteNotf(notfToDelete: NotificationToDelete)(implicit connection: js.Connection) {
    import NotificationType._
    val (sql, values: List[AnyRef]) = notfToDelete match {
      case mentionToDelete: NotificationToDelete.MentionToDelete =>
        val sql = s"""
          delete from DW1_NOTIFICATIONS
          where SITE_ID = ?
            and NOTF_TYPE = ${Mention.toInt}
            and unique_post_id = ?
            and TO_USER_ID = ?"""
        val values = List(siteId, mentionToDelete.uniquePostId.asAnyRef,
          mentionToDelete.toUserId.asAnyRef)
        (sql, values)
      case postToDelete: NotificationToDelete.NewPostToDelete =>
        val sql = s"""
          delete from DW1_NOTIFICATIONS
          where SITE_ID = ?
            and NOTF_TYPE in (
              ${Mention.toInt}, ${DirectReply.toInt}, ${NewPost.toInt})
            and unique_post_id = ?"""
        val values = List(siteId, postToDelete.uniquePostId.asAnyRef)
        (sql, values)
    }

    db.update(sql, values)
  }


  def loadNotificationsForRole(roleId: RoleId, limit: Int, unseenFirst: Boolean,
        upToWhen: Option[ju.Date]): Seq[Notification] = {
    val notfsBySiteId = asSystem.loadNotfsImpl(   // COULD specify consumers
        limit = limit, unseenFirst = unseenFirst, Some(siteId), userIdOpt = Some(roleId),
        upToWhen = upToWhen)
    // All loaded notifications are to `roleId` only.
    notfsBySiteId(siteId)
  }


  def updateNotificationSkipEmail(notifications: Seq[Notification]) {
    transactionAllowOverQuota { implicit connection =>
      updateNotificationConnectToEmail(notifications, email = None)
    }
  }


  def updateNotificationConnectToEmail(notifications: Seq[Notification], email: Option[Email])
        (implicit connection: js.Connection) {
    notifications foreach {
      connectNotificationToEmail(_, email)(connection)
    }
  }


  def connectNotificationToEmail(notification: Notification, email: Option[Email])
        (connection: js.Connection) {
    val statement = i"""
      update dw1_notifications set email_id = ?, email_status = ?
      where site_id = ? and notf_id = ?
      """

    val emailStatus =
      if (email.isDefined) NotfEmailStatus.Created
      else NotfEmailStatus.Skipped

    val values = List(
      email.map(_.id).orNullVarchar,
      emailStatus.toInt.asAnyRef,
      siteId,
      notification.id.asAnyRef)

    db.update(statement, values)(connection)
  }


  def markNotfAsSeenSkipEmail(userId: UserId, notfId: NotificationId) {
    // Include user id in case someone specified the notf id of another user's notification.
    import NotfEmailStatus._
    val statement = i"""
      update dw1_notifications set
        seen_at =
          case
            when seen_at is null then now_utc()
            else seen_at
          end,
        email_status =
          case
            when email_status = ${Undecided.toInt} then ${Skipped.toInt}
            else email_status
          end
      where site_id = ? and to_user_id = ? and notf_id = ?
      """
    runUpdate(statement, List(siteId, userId.asAnyRef, notfId.asAnyRef))
  }

}

