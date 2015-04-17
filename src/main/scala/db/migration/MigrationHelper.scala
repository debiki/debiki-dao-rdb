package db.migration

import com.debiki.core.ScalaBasedDatabaseMigrations
import com.debiki.dao.rdb.RdbSystemDao

object MigrationHelper {

  var scalaBasedMigrations: ScalaBasedDatabaseMigrations = null

  /** Makes a SystemDbDao available to Flyway Java migrations (well, Scala not Java). */
  var systemDbDao: RdbSystemDao = null

}
