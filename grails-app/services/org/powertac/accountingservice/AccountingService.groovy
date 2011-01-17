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

import org.joda.time.LocalDateTime
import org.powertac.common.enumerations.TariffState
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
   * Method processes incoming tariffDoPublishCmd of a broker. The method does not
   * return any results objects as tariffs are published only periodically through the
   * {@code publishTariffList ( )} method
   *
   * @param tariffDoPublishCmd command object that contains the tariff detais to be published
   * @throws org.powertac.common.exceptions.TariffPublishException is thrown if the tariff publishing fails
   */
  Tariff processTariffPublished(TariffDoPublishCmd tariffDoPublishCmd) throws TariffPublishException {
    if (!tariffDoPublishCmd) throw new TariffPublishException("TariffDoPublishCmd is null.")
    //TODO: Add following line as soon as TariffDoPublishCmd in powertac-common plugin is @Validateable
    //if (!tariffDoPublishCmd.validate()) throw new TariffPublishException("Failed to validate TariffDoPublishCmd: ${tariffDoPublishCmd.errors.allErrors}")
    try {
      Tariff tariff = new Tariff(tariffDoPublishCmd.properties)
      tariff.id = tariffDoPublishCmd.id
      tariff.parent = null
      tariff.latest = true
      tariff.tariffState = TariffState.Published
      tariff.transactionId = IdGenerator.createId()
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
   * Method processes incoming tariffDoReplyCmd of a broker or customer. The main task
   * of this method is to persistently record the tariffDoReplyCmd and then to forward it
   * downstream for further processing.
   *
   * @param tariffDoReplyCmd the tariff reply to store in the database
   * @return the processed tariffDoReplyCmd object
   * @throws org.powertac.common.exceptions.TariffReplyException is thrown if the tariff publishing fails
   */
  Tariff processTariffReply(TariffDoReplyCmd tariffDoReplyCmd) throws TariffReplyException {
    if (!tariffDoReplyCmd) throw new TariffReplyException("TariffDoReplyCmd is null.")
    if (!tariffDoReplyCmd.validate()) throw new TariffReplyException("Failed to validate TariffDoReplyCmd: ${tariffDoReplyCmd.errors.allErrors}")
    try {
      Tariff originalTariff = Tariff.withCriteria(uniqueResult: true) {
        eq('competition', tariffDoReplyCmd.competition)
        eq('broker', tariffDoReplyCmd.broker)
        eq('parent', tariffDoReplyCmd.parent)
        eq('latest', true)
      }
      if (originalTariff.parent) {
        //set to false only for tariffs that have a parent reference (i.e. that are not the initially published instances of brokers), i.e. that are not the originally published tariffs. For the latter ones, setting latest to false would mean to revoke the tariff offer completely
        originalTariff.latest = false
        originalTariff.save()
      }
      Tariff tariff = new Tariff(tariffDoReplyCmd.properties)
      tariff.latest = true
      tariff.tariffState = TariffState.Published
      tariff.transactionId = IdGenerator.createId()
      if (!tariff.validate()) {
        throw new TariffReplyException("Failed to validate new Tariff: ${tariff.errors.allErrors}")
      } else {
        tariff.save()
      }
      return tariff
    } catch (Exception ex) {
      throw new TariffReplyException("An exception occurred during processTariffPublished()", ex)
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
      def originalTariff = tariffDoRevokeCmd.tariff
      Tariff.withTransaction {tx ->
        originalTariff.latest = false
        originalTariff.save()
        Tariff newTariff = new Tariff(originalTariff.properties)
        newTariff.latest = true
        newTariff.parent = originalTariff
        newTariff.dateCreated = new LocalDateTime()
        newTariff.tariffState = TariffState.Revoked
        newTariff.transactionId = IdGenerator.createId()
        if (!newTariff.validate()) {
          throw new TariffRevokeException("Failed to validate new Tariff: ${newTariff.errors.allErrors}")
        } else {
          newTariff.save()
        }
        return newTariff
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
      def originalTariff = tariffDoSubscribeCmd.tariff
      Tariff.withTransaction {tx ->
        Tariff newTariff = new Tariff(originalTariff.properties)
        newTariff.latest = true
        newTariff.parent = originalTariff
        newTariff.customer = tariffDoSubscribeCmd.customer
        newTariff.dateCreated = new LocalDateTime()
        newTariff.tariffState = TariffState.Subscribed
        newTariff.transactionId = IdGenerator.createId()
        if (!newTariff.validate()) {
          throw new TariffSubscriptionException("Failed to validate Tariff subscription: ${newTariff.errors.allErrors}")
        } else {
          newTariff.save()
        }
        if (originalTariff.signupFee) {
          def cashUpdate = processCashUpdate(new CashDoUpdateCmd(competition: originalTariff.competition, broker: originalTariff.broker, relativeChange: originalTariff.signupFee, reason: "Signup fee for subscription of customer ${newTariff.customer}", transactionId: newTariff.transactionId, origin: 'AccountingService'))
          return [newTariff, cashUpdate]
        } else {
          return [newTariff]
        }
      }
    }
    catch (Exception ex) {
      throw new TariffSubscriptionException("An exception occurred during processTariffSubscribe()", ex)
    }
  }

  /**
   * Method processes incoming {@link TariffDoRevokeCmd}. The method implements the logic required to unsubscribe a customer from a tariff ahead of the originally agreed contract end.
   * @param tariffDoEarlyExitCmd contains references to the customer who wishes to exit the tariff contract ahead of time as well as to the tariff contract that should be cancelled.
   * @return List of objects which can include {@link CashUpdate} and {@link Tariff}. The tariff object reflects the cancellation of the tariff subscription while the (optional) {@link CashUpdate} contains the booking of the early exit fee into the broker's cash account
   * @throws TariffEarlyExitException is thrown if the tariff contract cancellation fails.
   */
  List processTariffEarlyExit(TariffDoEarlyExitCmd tariffDoEarlyExitCmd) throws TariffEarlyExitException {
    if (!tariffDoEarlyExitCmd) throw new TariffEarlyExitException("TariffDoEarlyExitCmd is null.")
    if (!tariffDoEarlyExitCmd.validate()) throw new TariffEarlyExitException("Failed to validate TariffDoEarlyExitCmd: ${tariffDoEarlyExitCmd.errors.allErrors}")
    try {
      def originalTariff = tariffDoEarlyExitCmd.tariff
      Tariff.withTransaction {tx ->
        originalTariff.latest = false
        originalTariff.save()
        Tariff newTariff = new Tariff(originalTariff.properties)
        newTariff.latest = true
        newTariff.parent = originalTariff
        newTariff.dateCreated = new LocalDateTime()
        newTariff.tariffState = TariffState.EarlyCustomerExit
        newTariff.transactionId = IdGenerator.createId()
        if (!newTariff.validate()) {
          throw new TariffEarlyExitException("Failed to validate early tariff exit object: ${newTariff.errors.allErrors}")
        } else {
          newTariff.save()
        }
        if (originalTariff.earlyExitFee) {
          def cashUpdate = processCashUpdate(new CashDoUpdateCmd(competition: originalTariff.competition, broker: originalTariff.broker, relativeChange: originalTariff.earlyExitFee, reason: "Early exit fee from customer ${originalTariff.customer}", transactionId: newTariff.transactionId, origin: 'Accounting Service'))
          return [newTariff, cashUpdate]
        } else {
          return [newTariff]
        }
      }
    } catch (Exception ex) {
      throw new TariffEarlyExitException("An exception occurred during processTariffEarlyExit()", ex)
    }
  }

  /**
   * Method processes incoming {@link TariffDoUpdateCmd}. The method implements the logic required to update the conditions of an existing tariff for all subscribed customers.
   * @param tariffDoUpdateCmd contains the new (revised) tariff conditions
   * @return List of {@link Tariff} objects which reflect the updated individual subscriptions for
   * all customers subscribed to the updated tariff
   * @throws TariffUpdateException is thrown if the tariff updating fails.
   */
  List processTariffUpdate(TariffDoUpdateCmd tariffDoUpdateCmd) throws TariffUpdateException {
    if (!tariffDoUpdateCmd) throw new  TariffUpdateException('TariffDoUpdateCmd is null')
    if (!tariffDoUpdateCmd.validate()) throw new TariffUpdateException("Failed to validate TariffDoUpdateCmd: ${tariffDoUpdateCmd.errors.allErrors}")
    try {
      def originalTariff = tariffDoUpdateCmd.parent

    }
    catch (Exception ex) {
    throw new TariffUpdateException('An exception occurred during processTariffUpdate()', ex)
  }
}

/**
 * Returns a list of all currently active (i.e. subscribeable) tariffs (which might be empty)
 *
 * @return a list of all active tariffs, which might be empty if no tariffs are published
 */
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

/**
 * Publishes the list of available customers (which might be empty)
 *
 * @return a list of all available customers, which might be empty if no customers are available
 */
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
