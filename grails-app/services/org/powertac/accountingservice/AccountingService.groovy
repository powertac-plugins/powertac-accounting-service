package org.powertac.accountingservice

import org.powertac.common.enumerations.TariffState
import org.powertac.common.exceptions.PositionUpdateException
import org.powertac.common.*
import org.powertac.common.command.*

class AccountingService implements org.powertac.common.interfaces.AccountingService {

  PositionUpdate processPositionUpdate(PositionDoUpdateCmd positionDoUpdateCmd) throws PositionUpdateException {
    assert (positionDoUpdateCmd.validate())
    BigDecimal overallBalance = 0.0

    PositionUpdate oldPositionUpdate = PositionUpdate.withCriteria(uniqueResult: true) {
      eq('competition', positionDoUpdateCmd.competition)
      eq('broker', positionDoUpdateCmd.broker)
      eq('product', positionDoUpdateCmd.product)
      eq('timeslot', positionDoUpdateCmd.timeslot)
      eq('latest', true)
    }
    if (oldPositionUpdate) {
      oldPositionUpdate.latest = false
      oldPositionUpdate.save()
      overallBalance = oldPositionUpdate.overallBalance
    }

    def positionUpdate = new PositionUpdate(positionDoUpdateCmd.properties)
    positionUpdate.overallBalance = (overallBalance + positionDoUpdateCmd.relativeChange)
    positionUpdate.transactionId = IdGenerator.createId()
    positionUpdate.latest = true
    assert (positionUpdate.validate() && positionUpdate.save())
    return null
  }

  CashUpdate processCashUpdate(CashDoUpdateCmd cashDoUpdateCmd) {
    assert (cashDoUpdateCmd.validate())
    BigDecimal overallBalance = 0.0
    CashUpdate oldCashUpdate = CashUpdate.withCriteria(uniqueResult: true) {
      eq('competition', cashDoUpdateCmd.competition)
      eq('broker', cashDoUpdateCmd.broker)
      eq('latest', true)
    }
    if (oldCashUpdate) {
      oldCashUpdate.latest = false
      oldCashUpdate.save()
      overallBalance = oldCashUpdate.overallBalance
    }

    def cashUpdate = new CashUpdate(cashDoUpdateCmd.properties)
    cashUpdate.overallBalance = overallBalance
    cashUpdate.transactionId = IdGenerator.createId()
    cashUpdate.latest = true
    assert (cashUpdate.validate() && cashUpdate.save())
    return cashUpdate
  }

  void processTariffPublished(TariffDoPublishCmd tariffDoPublishCmd) {
    //To change body of implemented methods use File | Settings | File Templates.
  }

  TariffDoReplyCmd processTariffReply(TariffDoReplyCmd tariffDoReplyCmd) {
    return null  //To change body of implemented methods use File | Settings | File Templates.
  }

  Tariff processTariffRevoke(TariffDoRevokeCmd tariffDoRevokeCmd) {
    return null  //To change body of implemented methods use File | Settings | File Templates.
  }

  List<Tariff> publishTariffList() {
    Competition competition = Competition.currentCompetition
    if (!competition) {
      log.error("Failed to determine current competition during AccountingService.publishTariffList()")
      return []
    } else {
      return Tariff.withCriteria {
        eq('competition', competition)
        eq('tariffState', TariffState.Published)
        eq('latest', true)
      }
    }
  }

  List<Customer> publishCustomersAvailable() {
    Competition competition = Competition.currentCompetition
    if (!competition) {
      log.error("Failed to determine current competition during AccountingService.publishCustomersAvailable()")
      return []
    } else {
      return Customer.findAllByCompetition(competition)
    }
  }

  void competitionBeforeStart(Competition competition) { }

  void competitionAfterStart(Competition competition) { }

  void competitionBeforeStop(Competition competition) { }

  void competitionAfterStop(Competition competition) { }

  void competitionReset(Competition competition) { }
}
