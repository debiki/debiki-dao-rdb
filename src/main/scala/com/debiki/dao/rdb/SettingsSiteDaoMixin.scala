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
import java.sql.ResultSet
import scala.collection.mutable
import Rdb._
import RdbUtil._


/** Creates, updates, deletes and loads settings for e.g. the whole website, a section
  * of the site (e.g. a blog or a forum), a category, single pages.
  */
trait SettingsSiteDaoMixin extends SiteTransaction {
  self: RdbSiteDao =>


  override def loadSiteSettings(): Option[EditedSettings] = {
    val query = s"""
      select *
      from settings3
      where site_id = ? and category_id is null and page_id is null
      """
    runQueryFindOneOrNone(query, List(siteId), readSettingsFromResultSet)
  }


  override def upsertSiteSettings(settings: SettingsToSave) {
    // Later: use Postgres' built-in upsert (when have upgraded to Postgres 9.5)
    if (loadSiteSettings().isDefined) {
      updateSiteSettings(settings)
    }
    else {
      insertSiteSettings(settings)
    }
  }


  private def insertSiteSettings(editedSettings2: SettingsToSave) {
    val statement = s"""
      insert into settings3 (
        site_id,
        category_id,
        page_id,
        user_must_be_auth,
        user_must_be_approved,
        allow_guest_login,
        num_first_posts_to_review,
        num_first_posts_to_approve,
        num_first_posts_to_allow,
        head_styles_html,
        head_scripts_html,
        end_of_body_html,
        header_html,
        footer_html,
        show_forum_categories,
        horizontal_comments,
        social_links_html,
        logo_url_or_html,
        org_domain,
        org_full_name,
        org_short_name,
        contrib_agreement,
        content_license,
        google_analytics_id,
        experimental,
        html_tag_css_classes)
      values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      """
    val values = List(
      siteId,
      NullInt,
      NullVarchar,
      editedSettings2.userMustBeAuthenticated.getOrElse(None).orNullBoolean,
      editedSettings2.userMustBeApproved.getOrElse(None).orNullBoolean,
      editedSettings2.allowGuestLogin.getOrElse(None).orNullBoolean,
      editedSettings2.numFirstPostsToReview.getOrElse(None).orNullInt,
      editedSettings2.numFirstPostsToApprove.getOrElse(None).orNullInt,
      editedSettings2.numFirstPostsToAllow.getOrElse(None).orNullInt,
      editedSettings2.headStylesHtml.getOrElse(None).orNullVarchar,
      editedSettings2.headScriptsHtml.getOrElse(None).orNullVarchar,
      editedSettings2.endOfBodyHtml.getOrElse(None).orNullVarchar,
      editedSettings2.headerHtml.getOrElse(None).orNullVarchar,
      editedSettings2.footerHtml.getOrElse(None).orNullVarchar,
      editedSettings2.showForumCategories.getOrElse(None).orNullBoolean,
      editedSettings2.horizontalComments.getOrElse(None).orNullBoolean,
      editedSettings2.socialLinksHtml.getOrElse(None).orNullVarchar,
      editedSettings2.logoUrlOrHtml.getOrElse(None).orNullVarchar,
      editedSettings2.orgDomain.getOrElse(None).orNullVarchar,
      editedSettings2.orgFullName.getOrElse(None).orNullVarchar,
      editedSettings2.orgShortName.getOrElse(None).orNullVarchar,
      editedSettings2.contribAgreement.getOrElse(None).map(_.toInt).orNullInt,
      editedSettings2.contentLicense.getOrElse(None).map(_.toInt).orNullInt,
      editedSettings2.googleUniversalAnalyticsTrackingId.getOrElse(None).orNullVarchar,
      editedSettings2.showComplicatedStuff.getOrElse(None).orNullBoolean,
      editedSettings2.htmlTagCssClasses.getOrElse(None).orNullVarchar)

    runUpdate(statement, values)
  }


  private def updateSiteSettings(editedSettings2: SettingsToSave) {
    val statement = mutable.StringBuilder.newBuilder.append("update settings3 set ")
    val values = mutable.ArrayBuffer[AnyRef]()
    var somethingToDo = false
    var spaceOrComma = ""

    def maybeSet(column: String, anyValue: Option[AnyRef]) {
      anyValue foreach { value =>
        somethingToDo = true
        statement.append(s"$spaceOrComma$column = ?")
        spaceOrComma = ", "
        values += value
      }
    }

    val s = editedSettings2
    maybeSet("user_must_be_auth", s.userMustBeAuthenticated.map(_.orNullBoolean))
    maybeSet("user_must_be_approved", s.userMustBeApproved.map(_.orNullBoolean))
    maybeSet("allow_guest_login", s.allowGuestLogin.map(_.orNullBoolean))
    maybeSet("num_first_posts_to_review", s.numFirstPostsToReview.map(_.orNullInt))
    maybeSet("num_first_posts_to_approve", s.numFirstPostsToApprove.map(_.orNullInt))
    maybeSet("num_first_posts_to_allow", s.numFirstPostsToAllow.map(_.orNullInt))
    maybeSet("head_styles_html", s.headStylesHtml.map(_.orNullVarchar))
    maybeSet("head_scripts_html", s.headScriptsHtml.map(_.orNullVarchar))
    maybeSet("end_of_body_html", s.endOfBodyHtml.map(_.orNullVarchar))
    maybeSet("header_html", s.headerHtml.map(_.orNullVarchar))
    maybeSet("footer_html", s.footerHtml.map(_.orNullVarchar))
    maybeSet("show_forum_categories", s.showForumCategories.map(_.orNullBoolean))
    maybeSet("horizontal_comments", s.horizontalComments.map(_.orNullBoolean))
    maybeSet("social_links_html", s.socialLinksHtml.map(_.orNullVarchar))
    maybeSet("logo_url_or_html", s.logoUrlOrHtml.map(_.orNullVarchar))
    maybeSet("org_domain", s.orgDomain.map(_.orNullVarchar))
    maybeSet("org_full_name", s.orgFullName.map(_.orNullVarchar))
    maybeSet("org_short_name", s.orgShortName.map(_.orNullVarchar))
    maybeSet("contrib_agreement", s.contribAgreement.map(_.map(_.toInt).orNullInt))
    maybeSet("content_license", s.contentLicense.map(_.map(_.toInt).orNullInt))
    maybeSet("google_analytics_id", s.googleUniversalAnalyticsTrackingId.map(_.orNullVarchar))
    maybeSet("experimental", s.showComplicatedStuff.map(_.orNullBoolean))
    maybeSet("html_tag_css_classes", s.htmlTagCssClasses.map(_.orNullVarchar))

    statement.append(" where site_id = ? and category_id is null and page_id is null")
    values.append(siteId)

    if (somethingToDo) {
      runUpdateExactlyOneRow(statement.toString(), values.toList)
    }
  }


  private def readSettingsFromResultSet(rs: ResultSet): EditedSettings = {
    EditedSettings(
      userMustBeAuthenticated = getOptionalBoolean(rs, "user_must_be_auth"),
      userMustBeApproved = getOptionalBoolean(rs, "user_must_be_approved"),
      allowGuestLogin = getOptionalBoolean(rs, "allow_guest_login"),
      numFirstPostsToReview = getOptionalInt(rs, "num_first_posts_to_review"),
      numFirstPostsToApprove = getOptionalInt(rs, "num_first_posts_to_approve"),
      numFirstPostsToAllow = getOptionalInt(rs, "num_first_posts_to_allow"),
      headStylesHtml = Option(rs.getString("head_styles_html")),
      headScriptsHtml = Option(rs.getString("head_scripts_html")),
      endOfBodyHtml = Option(rs.getString("end_of_body_html")),
      headerHtml = Option(rs.getString("header_html")),
      footerHtml = Option(rs.getString("footer_html")),
      showForumCategories = getOptionalBoolean(rs, "show_forum_categories"),
      horizontalComments = getOptionalBoolean(rs, "horizontal_comments"),
      socialLinksHtml = Option(rs.getString("social_links_html")),
      logoUrlOrHtml = Option(rs.getString("logo_url_or_html")),
      orgDomain = Option(rs.getString("org_domain")),
      orgFullName = Option(rs.getString("org_full_name")),
      orgShortName = Option(rs.getString("org_short_name")),
      contribAgreement = ContribAgreement.fromInt(rs.getInt("contrib_agreement")), // 0 -> None, ok
      contentLicense = ContentLicense.fromInt(rs.getInt("content_license")), // 0 -> None, ok
      googleUniversalAnalyticsTrackingId = Option(rs.getString("google_analytics_id")),
      showComplicatedStuff = getOptionalBoolean(rs, "experimental"),
      htmlTagCssClasses = Option(rs.getString("html_tag_css_classes")))
  }

}
