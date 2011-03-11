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

import org.powertac.common.*
import org.powertac.common.msg.*
import org.powertac.common.exceptions.*

/**
 * Default implementation of {@link org.powertac.common.interfaces.Accounting}
 *
 * @see org.powertac.common.interfaces.Accounting
 * @author Carsten Block
 * @version 0.1 - January 13, 2011
 */
class AccountingService implements org.powertac.common.interfaces.Accounting {

  /**
   * Method processes positionDoUpdateCmd objects adjusting the booked amounts
   * of a product (e.g. energy futures) for particular broker and a particular timeslot
   *
   * @param positionDoUpdateCmd the object that describes what position change to book in the database
   * @return PositionUpdate Latest {@link PositionUpdate} which contains relative change, new overall balance, origin and reason for the position change
   * @throws org.powertac.common.exceptions.PositionUpdateException is thrown if a position updated fails
   */
  PositionUpdate processPositionUpdate(PositionDoUpdateCmd positionDoUpdateCmd) throws PositionUpdateException {
    if (!positionDoUpdateCmd) return null
    if (!positionDoUpdateCmd.validate()) throw new PositionUpdateException("Failed to validate incoming PositionDoUpdateCommand: ${positionDoUpdateCmd.errors.allErrors}")
    try {
      BigDecimal overallBalance = 0.0
      PositionUpdate positionUpdate = PositionUpdate.withCriteria(uniqueResult: true) {
        eq('broker', positionDoUpdateCmd.broker)
        eq('product', positionDoUpdateCmd.product)
        eq('timeslot', positionDoUpdateCmd.timeslot)
      }
      if (positionUpdate) {
        //oldPositionUpdate.latest = false
        //oldPositionUpdate.save()
        overallBalance = positionUpdate.overallBalance
        positionUpdate.relativeChange = positionDoUpdateCmd.relativeChange
        positionUpdate.reason = positionDoUpdateCmd.reason
        positionUpdate.origin = positionDoUpdateCmd.origin
      }
      else {
        positionUpdate = new PositionUpdate(positionDoUpdateCmd.properties)
      }
      positionUpdate.overallBalance = (overallBalance + positionDoUpdateCmd.relativeChange)
      if (!positionUpdate.transactionId) positionUpdate.transactionId = IdGenerator.createId()
      //positionUpdate.latest = true
      if (positionUpdate.validate() && positionUpdate.save()) {
        return positionUpdate
      } 
      else {
        positionUpdate.errors.allErrors.each { println it.toString() }
        throw new PositionUpdateException("Failed to save PositionUpdate: $positionUpdate.errors.allErrors")
      }

    } catch (Exception ex) {
      throw new PositionUpdateException('An error occurred during processPositionUpdate.', ex)
    }
  }

  /**
   * Method processes cashDoUpdateCmd objects adjusting the booked amounts of cash for a specific broker.
   * @param cashDoUpdateCmd the object that describes what cash change to book in the database
   * @return CashUpdate Latest {@link CashUpdate} which contains relative change, new overall balance, origin and reason for the cash update
   * @throws org.powertac.common.exceptions.CashUpdateException is thrown if a cash update fails
   */
  CashUpdate processCashUpdate(CashDoUpdateCmd cashDoUpdateCmd) 
  {
    if (!cashDoUpdateCmd) return null
    if (!cashDoUpdateCmd.validate()) {
      log.error("Failed to validate incoming CashDoUpdateCmd: ${cashDoUpdateCmd.errors.allErrors}")
      return null
    }

    try {
      BigDecimal overallBalance = 0.0
      CashUpdate cashUpdate = CashUpdate.withCriteria(uniqueResult: true) {
        eq('broker', cashDoUpdateCmd.broker)
        //eq('latest', true)
      }
      if (cashUpdate) {
        //oldCashUpdate.latest = false
        //oldCashUpdate.save()
        overallBalance = cashUpdate.overallBalance
      }
      else {
        cashUpdate = new CashUpdate(cashDoUpdateCmd.properties)
      }
      cashUpdate.relativeChange = cashDoUpdateCmd.relativeChange
      cashUpdate.reason = cashDoUpdateCmd.reason
      cashUpdate.origin = cashDoUpdateCmd.origin
      cashUpdate.overallBalance = (overallBalance + cashDoUpdateCmd.relativeChange)
      if (!cashUpdate.transactionId) cashUpdate.transactionId = IdGenerator.createId()
      //cashUpdate.latest = true
      if (cashUpdate.validate() && cashUpdate.save()) {
        return cashUpdate
      } 
      else {
        log.error("Failed to save CashUpdate: ${cashUpdate.errors.allErrors}")
        println "Failed to save CashUpdate: ${cashUpdate.errors.allErrors}"
      }

    } catch (Exception ex) {
      log.error('An error occurred during processCashUpdate.')
      println 'An error occurred during processCashUpdate.'
    }
    return null
  }

/**
 * Publishes the list of available customers (which might be empty)
 *
 * @return a list of all available customers, which might be empty if no customers are available
 */
//  List<CustomerInfo> publishCustomersAvailable() {
//    Competition competition = Competition.currentCompetition()
//    if (!competition) {
//      log.error("Failed to determine current competition during AccountingService.publishCustomersAvailable()")
//      return []
//    } else {
//      return CustomerInfo.findAllByCompetition(competition)
//    }
//  }
}
