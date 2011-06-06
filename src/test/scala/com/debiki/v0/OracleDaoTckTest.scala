// vim: ts=2 sw=2 et
/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package com.debiki.v0

import java.io.{File, FileNotFoundException}
import java.{util => ju}
import net.liftweb.common._
import org.specs._
import org.specs.specification.PendingUntilFixed
import Prelude._

/*
These test schemas are dropped and recreated (data pump import, impdp)
before each test:

DEBIKI_TEST_0
DEBIKI_TEST_0_0_2_EMPTY
DEBIKI_TEST_0_0_2_DATA

Password (for all users): "auto-dropped"
*/

class OracleTestContext(override val dao: OracleDao) extends tck.TestContext {
  val schema = dao.schema
  val db = dao.schema.oradb

  override def createRestorePoint() {
    unimplemented
  }

  override def revertToRestorePoint() {
    unimplemented
  }
}

object OracleDaoTckTest {
  val connStr = "jdbc:oracle:thin:@//192.168.0.120:1521/debiki.ex.com"

  def testContextBuilder(what: tck.DaoTckTest.What, version: String) = {
    import tck.DaoTckTest._
    val schema = (version, what) match {
      case ("0", EmptySchema) => "DEBIKI_TEST_0"
      case ("0.0.2", EmptyTables) => "DEBIKI_TEST_0_0_2_EMPTY"
      case ("0.0.2", TablesWithData) => "DEBIKI_TEST_0_0_2_DATA"
      case _ => assErr("Broken test suite")
    }
    val dao = OracleDao.connectAndUpgradeSchemaThrow(
                          connStr, schema, "auto-dropped")
    new OracleTestContext(dao)
  }

  val testContextBuilder2 =
    (what: tck.DaoTckTest.What, version: String) => {
      val dao = OracleDao.connectAndUpgradeSchemaThrow(
        connStr, "DEBIKI_TEST", "apabanan454")
      new OracleTestContext(dao)
    }
}

import OracleDaoTckTest._


class OracleDaoTckTest extends tck.DaoTckTest(testContextBuilder) {
  // Tests defined in parent class DaoTckTest.

/*
  // Revert all schemas to their test structure, by flashing back to a
  // restore point.
  // COULD (should) use Oracle Workspace Manager instead, which allows
  // reverting individual tables (e.g. all tables in a schemas), instead
  // of the whole database. The function that is passed
  {
    val db = new OracleDb(connStr, "SYS as sysdba", "apabanan454")
    db.execUpdate2(recreateTestSchemas)

    // Requires DBA privileges, and Oracle Database Enterprise Edition:
    //  create restore point DEBIKI_TEST_SETUP guarantee flashback database
    //  flashback database to restore point DEBIKI_TEST_SETUP
  }

  val recreateTestSchemas =
// Based on Example 5-2 Importing a Dump File and Remapping All Schema Objects
// from book: Oracle® Database Utilities 11g Release 2 (11.2)
// chapter: 6 The Data Pump API
// find it e.g. here: <http://www.filibeto.org/sun/lib/nonsun/oracle/
//    11.2.0.1.0/E11882_01_200909/server.112/e10701/dp_api.htm#i1008009>
"""
DECLARE
  ind NUMBER;              -- Loop index
  h1 NUMBER;               -- Data Pump job handle
  percent_done NUMBER;     -- Percentage of job complete
  job_state VARCHAR2(30);  -- To keep track of job state
  le ku$_LogEntry;         -- For WIP and error messages
  js ku$_JobStatus;        -- The job status from get_status
  jd ku$_JobDesc;          -- The job description from get_status
  sts ku$_Status;          -- The status object returned by get_status
BEGIN

-- Create a (user-named) Data Pump job to do a "full" import (everything
-- in the dump file without filtering).

  h1 := DBMS_DATAPUMP.OPEN('IMPORT','FULL',NULL,'EXAMPLE2');

-- Specify the single dump file for the job (using the handle just returned)
-- and directory object, which must already be defined and accessible
-- to the user running this procedure. This is the dump file created by
-- the export operation in the first example.

  DBMS_DATAPUMP.ADD_FILE(h1,'example1.dmp','DMPDIR');

-- A metadata remap will map all schema objects from HR to BLAKE.

  DBMS_DATAPUMP.METADATA_REMAP(h1,'REMAP_SCHEMA','HR','BLAKE');

-- If a table already exists in the destination schema, skip it (leave
-- the preexisting table alone). This is the default, but it does not hurt
-- to specify it explicitly.

  DBMS_DATAPUMP.SET_PARAMETER(h1,'TABLE_EXISTS_ACTION','SKIP');

-- Start the job. An exception is returned if something is not set up properly.

  DBMS_DATAPUMP.START_JOB(h1);

-- The import job should now be running. In the following loop, the job is
-- monitored until it completes. In the meantime, progress information is
-- displayed. Note: this is identical to the export example.

 percent_done := 0;
  job_state := 'UNDEFINED';
  while (job_state != 'COMPLETED') and (job_state != 'STOPPED') loop
    dbms_datapump.get_status(h1,
           dbms_datapump.ku$_status_job_error +
           dbms_datapump.ku$_status_job_status +
           dbms_datapump.ku$_status_wip,-1,job_state,sts);
    js := sts.job_status;

-- If the percentage done changed, display the new value.

     if js.percent_done != percent_done
    then
      dbms_output.put_line('*** Job percent done = ' ||
                           to_char(js.percent_done));
      percent_done := js.percent_done;
    end if;

-- If any work-in-progress (WIP) or Error messages were received for the job,
-- display them.

       if (bitand(sts.mask,dbms_datapump.ku$_status_wip) != 0)
    then
      le := sts.wip;
    else
      if (bitand(sts.mask,dbms_datapump.ku$_status_job_error) != 0)
      then
        le := sts.error;
      else
        le := null;
      end if;
    end if;
    if le is not null
    then
      ind := le.FIRST;
      while ind is not null loop
        dbms_output.put_line(le(ind).LogText);
        ind := le.NEXT(ind);
      end loop;
    end if;
  end loop;

-- Indicate that the job finished and gracefully detach from it.

  dbms_output.put_line('Job has completed');
  dbms_output.put_line('Final job state = ' || job_state);
  dbms_datapump.detach(h1);
END;
/
"""
*/

}

// vim: fdm=marker et ts=2 sw=2 tw=80 fo=tcqwn list