/**
 * Copyright (c) 2011 Kaj Magnus Lindberg (born 1979)
 */

package com.debiki.v0


class RelDbDaoFactory(val db: RelDb) extends DbDaoFactory {

  val systemDbDao = new RelDbSystemDbDao(db)

  def newTenantDbDao(quotaConsumers: QuotaConsumers): TenantDbDao =
    new RelDbTenantDbDao(quotaConsumers, systemDbDao)

}

// vim: fdm=marker et ts=2 sw=2 tw=80 fo=tcqwn list

