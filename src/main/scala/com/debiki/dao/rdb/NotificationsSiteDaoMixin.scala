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
import com.debiki.core.DbDao._
import com.debiki.core.Prelude._
import java.{sql => js, util => ju}
import scala.collection.mutable
import NotificationsSiteDaoMixin._
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


  private def createNotf(notf: Notification)(implicit connection: js.Connection) {
    val sql = """
      insert into DW1_NOTIFICATIONS(
        SITE_ID, CREATED_AT, NOTF_TYPE,
        UNIQUE_POST_ID, PAGE_ID, post_nr, ACTION_TYPE, ACTION_SUB_ID,
        BY_USER_ID, TO_USER_ID)
      values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      """

    val values = mutable.ArrayBuffer[AnyRef](siteId, d2ts(notf.createdAt),
      notf.tyype.toInt.asAnyRef)

    notf match {
      case postNotf: Notification.NewPost =>
        values += postNotf.uniquePostId.asAnyRef
        values += postNotf.pageId
        values += postNotf.postNr.asAnyRef
        values += NullInt // no related post action
        values += NullInt //
        values += postNotf.byUserId.asAnyRef
        values += postNotf.toUserId.asAnyRef
    }

    db.update(sql, values.toList)
  }


  private def deleteNotf(notfToDelete: NotificationToDelete)(implicit connection: js.Connection) {
    val (sql, values: List[AnyRef]) = notfToDelete match {
      case mentionToDelete: NotificationToDelete.MentionToDelete =>
        val sql = """
          delete from DW1_NOTIFICATIONS
          where SITE_ID = ?
            and NOTF_TYPE = 'M'
            and PAGE_ID = ?
            and post_nr = ?
            and TO_USER_ID = ?"""
        val values = List(siteId, mentionToDelete.pageId, mentionToDelete.postNr.asAnyRef,
          mentionToDelete.toUserId.asAnyRef)
        (sql, values)
      case postToDelete: NotificationToDelete.NewPostToDelete =>
        val sql = """
          delete from DW1_NOTIFICATIONS
          where SITE_ID = ?
            and NOTF_TYPE in ('M', 'R', 'N') -- for now
            and PAGE_ID = ?
            and post_nr = ?"""
        val values = List(siteId, postToDelete.pageId, postToDelete.postNr.asAnyRef)
        (sql, values)
    }

    db.update(sql, values)
  }


  def loadNotificationsForRole(roleId: RoleId): Seq[Notification] = {
    val numToLoad = 50 // for now
    val notfsToMail = asSystem.loadNotfsImpl(   // COULD specify consumers
        numToLoad, Some(siteId), userIdOpt = Some(roleId))
    // All loaded notifications are to `roleId` only.
    notfsToMail(siteId)
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

    // Note! This won't work if the notification is related to a post action, not a post,
    // because I'm not including action_type and action_sub_id in the where clause below.
    // I added a comment about this in NotificationGenerator.generateForVote.

    val baseSql =
      "update DW1_NOTIFICATIONS set EMAIL_ID = ?, EMAIL_STATUS = ? where "

    val (whereClause, values): (String, List[AnyRef]) = notification match {
      case newPost: Notification.NewPost =>
        ("SITE_ID = ? and NOTF_TYPE = ? and PAGE_ID = ? and post_nr = ? and TO_USER_ID = ?",
          List(siteId, notification.tyype.toInt.asAnyRef, newPost.pageId,
            newPost.postNr.asAnyRef, newPost.toUserId.asAnyRef))
    }

    val emailStatus =
      if (email.isDefined) NotfEmailStatus.Created
      else NotfEmailStatus.Skipped

    db.update(
      baseSql + whereClause,
      email.map(_.id).orNullVarchar :: emailStatusToFlag(emailStatus) :: values)(connection)
  }

}


object NotificationsSiteDaoMixin {

  def emailStatusToFlag(status: NotfEmailStatus) = status match {
    case NotfEmailStatus.Created => "C"
    case NotfEmailStatus.Skipped => "S"
    case NotfEmailStatus.Undecided => "U"
  }

  def flagToEmailStatus(flag: String) = flag match {
    case "C" => NotfEmailStatus.Created
    case "S" => NotfEmailStatus.Skipped
    case "U" => NotfEmailStatus.Undecided
    case _ => die("DwEKEF05W3", s"Bad email status: `$flag'")
  }

}

