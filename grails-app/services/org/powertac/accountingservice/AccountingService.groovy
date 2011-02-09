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
import org.powertac.common.command.*
import org.powertac.common.exceptions.*

/**
 * Default implementation of {@link org.powertac.common.interfaces.AccountingService}
 *
 * @see org.powertac.common.interfaces.AccountingService
 * @author Carsten Block
 * @version 0.1 - January 13, 2011
 */
class AccountingService implements org.powertac.common.interfaces.AccountingService {

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
      if (!positionUpdate.transactionId) positionUpdate.transactionId = IdGenerator.createId()
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

  /**
   * Method processes cashDoUpdateCmd objects adjusting the booked amounts of cash for a specific broker.
   * @param cashDoUpdateCmd the object that describes what cash change to book in the database
   * @return CashUpdate Latest {@link CashUpdate} which contains relative change, new overall balance, origin and reason for the cash update
   * @throws org.powertac.common.exceptions.CashUpdateException is thrown if a cash update fails
   */
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
      if (!cashUpdate.transactionId) cashUpdate.transactionId = IdGenerator.createId()
      cashUpdate.latest = true
      if (cashUpdate.validate() && cashUpdate.save()) {
        return cashUpdate
      } else {
        throw new CashUpdateException("Failed to save CashUpdate: ${cashUpdate.errors.allErrors}")
      }

    } catch (Exception ex) {
      throw new CashUpdateException('An error occurred during processCashUpdate.', ex)
    }

  }

  /**
   * Method processes incoming tariffSpec of a broker. The method does not
   * return any results objects as tariffs are published only periodically through the
   * {@code publishTariffList ( )} method
   *
   * @param tariffSpec command object that contains the tariff detais to be published
   * @throws org.powertac.common.exceptions.TariffPublishException is thrown if the tariff publishing fails
   */
  Tariff processTariffPublished(TariffSpecification tariffSpec) throws TariffPublishException {
    if (!tariffSpec) throw new TariffPublishException("TariffSpec is null.")
    if (!tariffSpec.validate()) throw new TariffPublishException("Failed to validate TariffSpec: ${tariffSpec.errors.allErrors}")
    try {
      Tariff tariff = new Tariff(tariffSpec: tariffSpec)
      if (!tariff.validate()) {
        throw new TariffPublishException("Failed to validate new Tariff: ${tariff.errors.allErrors}")
      } else {
        tariff.save()
      }
      return tariff
    } catch (Exception ex) {
      throw new TariffPublishException("An exception occurred during processTariffPublished()", ex)
    }
  }

  /**
   * Method processes incoming tariffDoRevokeCmd of a broker. This method needs to
   * implement logic that leads to the given tariff being revoked from the list of
   * published tariffs.
   *
   * @param tariffDoRevokeCmd describing the tariff to be revoked
   * @return Tariff updated tariff object that reflects the revocation of the tariff
   * @throws org.powertac.common.exceptions.TariffRevokeException is thrown if the tariff publishing fails
   */
  Tariff processTariffRevoke(TariffDoRevokeCmd tariffDoRevokeCmd) throws TariffRevokeException {
    if (!tariffDoRevokeCmd) throw new TariffRevokeException("TariffDoRevokeCmd is null.")
    if (!tariffDoRevokeCmd.validate()) throw new TariffRevokeException("Failed to validate TariffDoRevokeCmd: ${tariffDoRevokeCmd.errors.allErrors}")
    try {
      def tariff = Tariff.get(tariffDoRevokeCmd.tariffId)
      Tariff.withTransaction {tx ->
        tariff.state = Tariff.State.WITHDRAWN
        //newTariff.transactionId = IdGenerator.createId()
        if (!tariff.validate()) {
          throw new TariffRevokeException("Failed to validate new Tariff: ${tariff.errors.allErrors}")
        } else {
          tariff.save()
        }
        return tariff
      }
    }
    catch (Exception ex) {
      throw new TariffRevokeException("An exception occurred during processTariffRevoke()", ex)
    }
  }

  /**
   * Method processes incoming {@link TariffDoSubscribeCmd}. This method implements the
   * logic required to make a customer subscribe to a particular tariff given either
   * (i) a published or (ii) an individually agreed tariff instance to subscribe to.
   *
   * @param tariffDoSubscribeCmd contains references to the subscribing customer and to the tariff instance to subscribe to
   * @return List of objects which can include {@link CashUpdate} and {@link Tariff}. The tariff object reflects the subscription of the customer defined in the {@link TariffDoSubscribeCmd} while the (optional) {@link CashUpdate} contains the cash booking of the (optional) signupFee into the broker's cash account
   * @throws TariffSubscriptionException is thrown if the subscription fails
   */
  List processTariffSubscribe(TariffDoSubscribeCmd tariffDoSubscribeCmd) throws TariffSubscriptionException {
    if (!tariffDoSubscribeCmd) throw new TariffSubscriptionException("TariffDoSubscribeCmd is null.")
    if (!tariffDoSubscribeCmd.validate()) throw new TariffSubscriptionException("Failed to validate TariffDoSubscribeCmd: ${tariffDoSubscribeCmd.errors.allErrors}")
    try {
      def tariff = Tariff.get(tariffDoSubscribeCmd.tariffId)
      Tariff.withTransaction {tx ->
        if (tariff.signupPayment) {
          def cashUpdate = 
              processCashUpdate(new CashDoUpdateCmd(competition: tariff.competition,
                                                    broker: tariff.broker, 
                                                    relativeChange: tariff.signupPayment, 
                                                    reason: "Signup fee for subscription of customer ${newTariff.customer}", 
                                                    //transactionId: tariff.transactionId, 
                                                    origin: 'AccountingService'))
          return [tariff, cashUpdate]
        } else {
          return [tariff]
        }
      }
    }
    catch (Exception ex) {
      throw new TariffSubscriptionException("An exception occurred during processTariffSubscribe()", ex)
    }
  }

/**
 * Returns a list of all currently active (i.e. subscribeable) tariffs (which might be empty)
 *
 * @return a list of all active tariffs, which might be empty if no tariffs are published
 */
//  List<TariffSpecification> publishTariffList() {
//    def competition = Competition.currentCompetition()
//    if (!competition) {
//      log.error("Failed to determine current competition during AccountingService.publishTariffList()")
//      return []
//    } else {
//      return Tariff.withCriteria {
//        eq('competition', competition)
//        eq('tariffState', org.powertac.common.enumerations.TariffState.Published)
//      }
//    }
//  }

/**
 * Publishes the list of available customers (which might be empty)
 *
 * @return a list of all available customers, which might be empty if no customers are available
 */
  List<CustomerInfo> publishCustomersAvailable() {
    Competition competition = Competition.currentCompetition()
    if (!competition) {
      log.error("Failed to determine current competition during AccountingService.publishCustomersAvailable()")
      return []
    } else {
      return CustomerInfo.findAllByCompetition(competition)
    }
  }
}
