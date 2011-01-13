/*
 * Copyright 2009-2011 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an
 * "AS IS" BASIS,  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language
 * governing permissions and limitations under the License.
 */

package org.powertac.accountingservice

import org.powertac.common.exceptions.CashUpdateException
import org.powertac.common.exceptions.PositionUpdateException
import org.powertac.common.*
import org.powertac.common.command.*

class AccountingService implements org.powertac.common.interfaces.AccountingService {

  PositionUpdate processPositionUpdate(PositionDoUpdateCmd positionDoUpdateCmd) throws PositionUpdateException {
    if (!positionDoUpdateCmd) return null
    if (!positionDoUpdateCmd.validate()) throw new PositionUpdateException("Failed to validate incoming PositionDoUpdateCommand: ${positionDoUpdateCmd.errors.allErrors}")
    try {
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
      if (positionUpdate.validate() && positionUpdate.save()) {
        return positionUpdate
      } else {
        throw new PositionUpdateException("Failed to save PositionUpdate: $positionUpdate.errors.allErrors")
      }

    } catch (Exception ex) {
      throw new PositionUpdateException('An error occurred during processPositionUpdate.', ex)
    }
  }

  CashUpdate processCashUpdate(CashDoUpdateCmd cashDoUpdateCmd) throws CashUpdateException {
    if (!cashDoUpdateCmd) return null
    if (!cashDoUpdateCmd.validate()) throw new CashUpdateException("Failed to validate incoming CashDoUpdateCmd: ${cashDoUpdateCmd.errors.allErrors}")
    try {
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
      cashUpdate.overallBalance = (overallBalance + cashDoUpdateCmd.relativeChange)
      cashUpdate.transactionId = IdGenerator.createId()
      cashUpdate.latest = true
      if(cashUpdate.validate() && cashUpdate.save()) {
        return cashUpdate
      } else {
        throw new CashUpdate("Failed to save CashUpdate: ${cashUpdate.errors.allErrors}")
      }

    } catch (Exception ex) {
      throw new CashUpdateException('An error occurred during processCashUpdate.', ex)
    }

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
    def competition = Competition.currentCompetition()
    if (!competition) {
      log.error("Failed to determine current competition during AccountingService.publishTariffList()")
      return []
    } else {
      return Tariff.withCriteria {
        eq('competition', competition)
        eq('tariffState', org.powertac.common.enumerations.TariffState.Published)
        eq('latest', true)
      }
    }
  }

  List<Customer> publishCustomersAvailable() {
    Competition competition = Competition.currentCompetition()
    if (!competition) {
      log.error("Failed to determine current competition during AccountingService.publishCustomersAvailable()")
      return []
    } else {
      return Customer.findAllByCompetition(competition)
    }
  }
}
