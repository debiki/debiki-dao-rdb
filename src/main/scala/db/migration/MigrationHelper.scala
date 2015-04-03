package db.migration

import com.debiki.dao.rdb.RdbSystemDao

object MigrationHelper {

  /** Makes a SystemDbDao available to Flyway Java migrations (well, Scala not Java). */
  var systemDbDao: RdbSystemDao = null

}
