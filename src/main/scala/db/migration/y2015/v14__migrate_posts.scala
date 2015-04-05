package db.migration.y2015

import com.debiki.core._
import com.debiki.core.Prelude._
import db.migration.MigrationHelper
import org.flywaydb.core.api.migration.jdbc.JdbcMigration
import java.{sql => js, util => ju}


class v14__migrate_posts extends JdbcMigration {

  var systemTransaction: SystemTransaction = null
  var siteTransaction: SiteTransaction = null


  def migrate(connection: js.Connection) {
    MigrationHelper.systemDbDao.setTheOneAndOnlyConnection(connection)
    systemTransaction = MigrationHelper.systemDbDao
    val allSites = systemTransaction.loadSites()
    for (site <- allSites) {
      siteTransaction = systemTransaction.siteTransaction(site.id)
      val allPageMetas = siteTransaction.loadAllPageMetas()
      for (pageMeta <- allPageMetas) {
        migratePage(pageMeta)
      }
    }
  }

  
  def migratePage(pageMeta: PageMeta) {
    val partsOld: PageParts = siteTransaction.loadPagePartsOld(pageMeta.pageId) getOrDie "DwE0Pk3W2"
    for (oldPost <- partsOld.getAllPosts) {
      val newPost = upgradePost(pageMeta.pageId, oldPost)
      siteTransaction.insertPost(newPost)
    }
  }


  def upgradePost(pageId: PageId, oldPost: Post): Post2 = {
    val collapsedStatus =
      if (oldPost.isTreeCollapsed) Some(CollapsedStatus.TreeCollapsed)
      else if (oldPost.isPostCollapsed) Some(CollapsedStatus.PostCollapsed)
      else None

    val closedStatus =
      if (oldPost.isTreeClosed) Some(ClosedStatus.TreeClosed)
      else None

    val deletedStatus =
      if (oldPost.isTreeDeleted) Some(DeletedStatus.TreeDeleted)
      else if (oldPost.isPostDeleted) Some(DeletedStatus.PostDeleted)
      else None

    val longAgo = new ju.Date(0)

    Post2(
      siteId = siteTransaction.siteId,
      pageId = pageId,
      id = oldPost.id,
      parentId = oldPost.parentId,
      multireplyPostIds = oldPost.multireplyPostIds,
      createdAt = oldPost.creationDati,
      createdById = oldPost.userId.toInt,
      lastEditedAt = oldPost.lastEditAppliedAt,
      lastEditedById = oldPost.lastEditorId.map(_.toInt),
      lastApprovedEditAt = oldPost.lastEditAppliedAt,   // for simplicity
      lastApprovedEditById = Some(SystemUser.User.id2), //
      numDistinctEditors = oldPost.numDistinctEditors,
      approvedSource = oldPost.approvedText,
      approvedHtmlSanitized = oldPost.approvedHtmlSanitized,
      approvedAt = oldPost.lastApprovalDati,
      approvedById = oldPost.lastManuallyApprovedById.map(_.toInt) orElse
                      oldPost.lastApprovalDati.map(_ => SystemUser.User.id2),
      approvedVersion = if (oldPost.lastApprovalDati.isDefined) Some(Post2.FirstVersion) else None,
      currentSourcePatch = None, // ignore unapproved edits
      currentVersion = Post2.FirstVersion,
      collapsedStatus = collapsedStatus,
      collapsedAt = collapsedStatus.map(_ => longAgo),
      collapsedById = collapsedStatus.map(_ => SystemUser.User.id2),
      closedStatus = closedStatus,
      closedAt = closedStatus.map(_ => longAgo),
      closedById = closedStatus.map(_ => SystemUser.User.id2),
      hiddenAt = None,
      hiddenById = None,
      deletedStatus = deletedStatus,
      deletedAt = deletedStatus.map(_ => longAgo),
      deletedById = deletedStatus.map(_ => SystemUser.User.id2),
      // Throw away all this info; it's not of much interest, because
      // no one but I has really been using Debiki thus far.
      pinnedPosition = None,
      numPendingFlags = 0,
      numHandledFlags = 0,
      numPendingEditSuggestions = 0,
      numLikeVotes = 0,
      numWrongVotes = 0,
      numCollapseVotes = 0,
      numTimesRead = 0)
  }

}