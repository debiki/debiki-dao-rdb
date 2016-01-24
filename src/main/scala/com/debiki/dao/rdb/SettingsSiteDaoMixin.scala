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
import Rdb._
import RdbUtil._


/** Creates, updates, deletes and loads settings for e.g. the whole webite, a section
  * of the site (e.g. a blog or a forum), single pages, and roles (users/groups).
  */
trait SettingsSiteDaoMixin extends SiteTransaction {
  self: RdbSiteDao =>


  def saveSetting(target: SettingsTarget, setting: SettingNameValue[_]) {
    val settingName = setting._1
    val anyValue = setting._2
    deleteSettingImpl(target, settingName)
    anyValue foreach { value =>
      insertSettingImpl(target, settingName, value)
    }
  }


  def loadSettings(targets: Seq[SettingsTarget]): Seq[RawSettings] = {
    targets.map(loadSettings)
  }


  /** Returns the number of settings deleted.
    */
  private def deleteSettingImpl(target: SettingsTarget, settingName: String): Int = {
    val (sql, values) = target match {
      case SettingsTarget.WholeSite =>
        val sql = """
          delete from DW1_SETTINGS
          where SITE_ID = ? and NAME = ? and TARGET = 'WholeSite'
          """
        (sql, List(siteId, settingName))
      case SettingsTarget.PageTree(rootPageId) =>
        val sql = """
          delete from DW1_SETTINGS
          where SITE_ID = ? and NAME = ? and TARGET = 'PageTree' and PAGE_ID = ?
          """
        (sql, List(siteId, settingName, rootPageId))
      case SettingsTarget.SinglePage(pageId) =>
        val sql = """
          delete from DW1_SETTINGS
          where SITE_ID = ? and NAME = ? and TARGET = 'SinglePage' and PAGE_ID = ?
          """
        (sql, List(siteId, settingName, pageId))
    }
    runUpdate(sql, values)
  }


  private def insertSettingImpl(target: SettingsTarget, settingName: String, settingValue: Any) {
    val sql = """
      insert into DW1_SETTINGS(
        SITE_ID, TARGET, PAGE_ID, NAME, DATATYPE, TEXT_VALUE, LONG_VALUE, DOUBLE_VALUE)
      values (?, ?, ?, ?, ?, ?, ?, ?)
      """

    val targetStr = target match {
      case SettingsTarget.WholeSite => "WholeSite"
      case _: SettingsTarget.PageTree => "PageTree"
      case _: SettingsTarget.SinglePage => "SinglePage"
    }

    val (datatype, textValue, longValue, doubleValue) = settingValue match {
      case x: String => ("Text", x, NullInt, NullDouble)
      case x: Int => ("Long", NullVarchar, x.asAnyRef, NullDouble)
      case x: Long => ("Long", NullVarchar, x.asAnyRef, NullDouble)
      case x: Float => ("Double", NullVarchar, NullInt, x.asAnyRef)
      case x: Double => ("Double", NullVarchar, NullInt, x.asAnyRef)
      case x: Boolean => ("Bool", if (x) "T" else "F", NullInt, NullDouble)
      case x => die("DwE77Xkf5", s"Bad setting type: ${classNameOf(x)}, setting value: '$x'")
    }

    val pageIdOrNull = target match {
      case SettingsTarget.WholeSite => NullVarchar
      case SettingsTarget.PageTree(rootId) => rootId
      case SettingsTarget.SinglePage(id) => id
    }

    val values = List[AnyRef](
      siteId, targetStr, pageIdOrNull, settingName, datatype, textValue, longValue, doubleValue)

    runUpdate(sql, values)
  }


  private def loadSettings(target: SettingsTarget): RawSettings = {
    val (sqlQuery, values) = target match {
      case SettingsTarget.WholeSite =>
        val sql = """
          select NAME, DATATYPE, TEXT_VALUE, LONG_VALUE, DOUBLE_VALUE
          from DW1_SETTINGS
          where SITE_ID = ? and TARGET = 'WholeSite'
          """
        (sql, List(siteId))
      case SettingsTarget.PageTree(rootPageId) =>
        val sql = """
          select NAME, DATATYPE, TEXT_VALUE, LONG_VALUE, DOUBLE_VALUE
          from DW1_SETTINGS
          where SITE_ID = ?
            and PAGE_ID = ?
            and TARGET = 'PageTree'
          """
        (sql, List(siteId, rootPageId))
      case SettingsTarget.SinglePage(pageId) =>
        val sql = """
          select NAME, DATATYPE, TEXT_VALUE, LONG_VALUE, DOUBLE_VALUE
          from DW1_SETTINGS
          where SITE_ID = ?
            and PAGE_ID = ?
            and TARGET = 'SinglePage'"""
        (sql, List(siteId, pageId))
    }

    val valuesBySettingName = mutable.HashMap[String, Any]()

    runQuery(sqlQuery, values, rs => {
      while (rs.next()) {
        val name = rs.getString("NAME")
        val datatype = rs.getString("DATATYPE")
        val value = datatype match {
          case "Text" => rs.getString("TEXT_VALUE")
          case "Long" => rs.getLong("LONG_VALUE")
          case "Double" => rs.getDouble("DOUBLE_VALUE")
          case "Bool" => rs.getString("TEXT_VALUE") == "T"
        }
        assErrIf(rs.wasNull,
          "DwE8fiG0", s"Bad setting: `$name', value is null, target: $target, site: `$siteId'")
        valuesBySettingName(name) = value
      }
    })

    RawSettings(target, valuesBySettingName.toMap)
  }

}
