/**
 * Copyright (c) 2011 Kaj Magnus Lindberg (born 1979)
 */

package com.debiki.v0


class RelDbDaoFactory(val db: RelDb) extends DbDaoFactory {

  val systemDaoSpi = new RelDbSystemDaoSpi(db)

  def buildTenantDaoSpi(quotaConsumers: QuotaConsumers): TenantDaoSpi =
    new RelDbTenantDaoSpi(quotaConsumers, systemDaoSpi)

}

// vim: fdm=marker et ts=2 sw=2 tw=80 fo=tcqwn list

