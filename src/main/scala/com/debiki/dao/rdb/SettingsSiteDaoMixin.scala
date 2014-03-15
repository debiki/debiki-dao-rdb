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
import com.debiki.core.Prelude._
import java.{sql => js, util => ju}
import scala.collection.mutable
import scala.collection.mutable.StringBuilder
import Rdb._
import RdbUtil._


/** Creates, updates, deletes and loads settings for e.g. the whole webite, a section
  * of the site (e.g. a blog or a forum), single pages, and roles (users/groups).
  */
trait SettingsSiteDaoMixin extends SiteDbDao {
  self: RdbSiteDao =>


  def savePageSetting(section: Section, setting: SettingNameValue[_]) {
    db.transaction { connection =>
      val settingName = setting._1
      deletePageSettingImpl(section, settingName)(connection)
      insertPageSettingImpl(section, setting)(connection)
    }
  }


  def loadPageSettings(pageIds: Seq[PageId]): PageSettings = {
    db.withConnection { connection =>
      loadPageSettingsImpl(pageIds)(connection)
    }
  }


  /** Returns the number of settings deleted.
    */
  private def deletePageSettingImpl(section: Section, settingName: String)(
        implicit connection: js.Connection): Int = {
    val (sql, values) = section match {
      case Section.WholeSite =>
        val sql = """
          delete from DW1_SETTINGS
          where TENANT_ID = ? and NAME = ? and TYPE = 'WholeSite'
          """
        (sql, List(siteId, settingName))
      case Section.PageTree(rootPageId) =>
        val sql = """
          delete from DW1_SETTINGS
          where TENANT_ID = ? and NAME = ? and TYPE = 'PageTree' and PAGE_ID = ?
          """
        (sql, List(siteId, settingName, rootPageId))
      case Section.SinglePage(pageId) =>
        val sql = """
          delete from DW1_SETTINGS
          where TENANT_ID = ? and NAME = ? and TYPE = 'SinglePage' and PAGE_ID = ?
          """
        (sql, List(siteId, settingName, pageId))
    }
    db.update(sql, values)
  }


  private def insertPageSettingImpl(section: Section, setting: SettingNameValue[_])(
        implicit connection: js.Connection) {
    val settingName = setting._1
    val settingValue = setting._2

    val sql = """
      insert into DW1_SETTINGS(
        TENANT_ID, TYPE, PAGE_ID, NAME, TEXT_VALUE, LONG_VALUE, DOUBLE_VALUE)
      values (?, ?, ?, ?, ?, ?, ?)
      """

    val typeValue = section match {
      case Section.WholeSite => "WholeSite"
      case _: Section.PageTree => "PageTree"
      case _: Section.SinglePage => "SinglePage"
    }

    val (textValue, longValue, doubleValue) = settingValue match {
      case x: String => (x, NullInt, NullDouble)
      case x: Int => (NullVarchar, x.asAnyRef, NullDouble)
      case x: Long => (NullVarchar, x.asAnyRef, NullDouble)
      case x: Float => (NullVarchar, NullInt, x.asAnyRef)
      case x: Double => (NullVarchar, NullInt, x.asAnyRef)
      case x: Boolean => assErr("DwE7GJ340", "Use 'T' and 'F' instead")
      case x => assErr("DwE77Xkf5", s"Unsupported value: `$x', type: ${classNameOf(x)}")
    }

    val pageIdOrNull = section match {
      case Section.WholeSite => NullVarchar
      case Section.PageTree(rootId) => rootId
      case Section.SinglePage(id) => id
    }

    val values = List[AnyRef](
      siteId, typeValue, pageIdOrNull, settingName, textValue, longValue, doubleValue)

    db.update(sql, values)
  }


  private def loadPageSettingsImpl(pageIds: Seq[PageId])(
        implicit connection: js.Connection): PageSettings = {

    val anyPageId = pageIds.headOption
    val ancestorIds = pageIds // include the page itself

    val sql = StringBuilder.newBuilder.append("select * from (")
    val values = mutable.ArrayBuffer[AnyRef]()

    // Load page specific settings.
    if (anyPageId.isDefined) {
      sql.append("""
        select NAME, TYPE, PAGE_ID, TEXT_VALUE, LONG_VALUE, DOUBLE_VALUE, -1 rownum
        from DW1_SETTINGS
        where TENANT_ID = ?
          and PAGE_ID = ?
          and TYPE = 'SinglePage'""")
      values.append(siteId, anyPageId.get)
    }

    // Load e.g. forum, subforum or blog specific settings.
    if (ancestorIds.nonEmpty) {
      sql.append(s"""
        union select *, row_number() OVER () rownum from (
          select NAME, TYPE, PAGE_ID, TEXT_VALUE, LONG_VALUE, DOUBLE_VALUE
          from DW1_SETTINGS
          where TENANT_ID = ?
            and PAGE_ID in (${ makeInListFor(ancestorIds) })
            and TYPE = 'PageTree'
          order by ${ makeOrderByListFor("PAGE_ID", ancestorIds) }
        ) as section_settings""")
      values.append(siteId)
      values.append(ancestorIds: _*) // for the in list
      values.append(ancestorIds: _*) // for the order by list
    }

    // Load default website settings.
    if (values.nonEmpty) sql.append(" union ")
    sql.append(s"""
      select NAME, TYPE, null PAGE_ID, TEXT_VALUE, LONG_VALUE, DOUBLE_VALUE, 999999 rownum
      from DW1_SETTINGS
      where TENANT_ID = ? and TYPE = 'WholeSite'
      """)
    values.append(siteId)

    // Sort settings so the most specific ones appear first.
    sql.append(") as settings_queries order by rownum asc")

    val allSettings = mutable.ArrayBuffer[SectionSettings]()

    db.query(sql.toString, values.toList, rs => {
      var lastRowNumDebug = -9999
      var lastSection: Option[Section] = None
      val namesAndValues = mutable.ArrayBuffer[(String, Any)]()

      while (rs.next()) {
        val name = rs.getString("NAME")
        val tyype = rs.getString("TYPE")
        val pageId = Option(rs.getString("PAGE_ID"))
        val textValue = Option(rs.getString("TEXT_VALUE"))
        val longValue = Option(rs.getLong("LONG_VALUE"))
        val doubleValue = Option(rs.getDouble("DOUBLE_VALUE"))
        val value = textValue.orElse(longValue).orElse(doubleValue).getOrDie("DwE8fiG0")

        val rowNumDebug = rs.getInt("rownum")
        // Is equal for page settings (then, -1) and site settings (then, 999999).
        assert(rowNumDebug >= lastRowNumDebug, "DwE77dhK5")
        lastRowNumDebug = rowNumDebug

        val section: Section = tyype match {
          case "WholeSite" => Section.WholeSite
          case "PageTree" => Section.PageTree(pageId getOrDie "DwE2DKf8")
          case "SinglePage" => Section.SinglePage(pageId getOrDie "DwE94G02")
          case x => assErr("DwE5fU04", s"Bad section: $x")
        }

        if (lastSection.isDefined && Some(section) != lastSection) {
          val moreSettings = SectionSettings(lastSection.get, namesAndValues.toVector)
          allSettings.append(moreSettings)
          namesAndValues.clear()
        }
        lastSection = Some(section)

        namesAndValues.append(name -> value)
      }

      lastSection foreach { section =>
        assErrIf(Section.WholeSite != section, "DwE84GL0")
        val lastSettings = SectionSettings(section, namesAndValues.toVector)
        allSettings.append(lastSettings)
      }
    })

    PageSettings(allSettings.toVector)
  }

}
