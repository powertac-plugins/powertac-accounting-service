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

import org.joda.time.Instant;
import org.powertac.common.*
import org.powertac.common.msg.*
import org.powertac.common.enumerations.TariffTransactionType;
import org.powertac.common.exceptions.*

import groovy.transform.Synchronized

/**
 * Implementation of {@link org.powertac.common.interfaces.Accounting}
 *
 * @author John Collins
 */
class AccountingService
    implements org.powertac.common.interfaces.Accounting,
               org.powertac.common.interfaces.TimeslotPhaseProcessor
{
  //static transactional = false
  
  def timeService // autowire reference
  def competitionControlService
  def brokerProxyService
  
  def pendingTransactions = []
  
  // read this from plugin config
  BigDecimal bankInterest = 0.0

  int simulationPhase = 3
  
  /**
   * Register for phase 3 activation, to drive tariff publication
   */
  void init (PluginConfig config)
  {
    competitionControlService?.registerTimeslotPhase(this, simulationPhase)
    BigDecimal value = config.configuration['bankInterest']?.toBigDecimal()
    if (value != null) {
      bankInterest = value
    }
    else {
      log.error "Bank interest not configured. Default to ${bankInterest}"
    }
  }

  @Synchronized
  MarketTransaction addMarketTransaction (Broker broker,
                                          Timeslot timeslot,
                                          BigDecimal price,
                                          BigDecimal quantity)
  {
    MarketTransaction mtx = new MarketTransaction(broker: broker,
                                                  timeslot: timeslot,
                                                  price: price,
                                                  quantity: quantity,
                                                  postedTime: timeService.currentTime)
    if (!mtx.validate()) {
      mtx.errors.allErrors.each { log.info it.toString() }
    }
    assert mtx.save()
    pendingTransactions.add(mtx)
    return mtx
  }

  @Synchronized
  public TariffTransaction addTariffTransaction(TariffTransactionType txType,
                                                Tariff tariff,
                                                CustomerInfo customer,
                                                int customerCount,
                                                BigDecimal quantity,
                                                BigDecimal charge)
  {
    // Note that tariff may be stale
    TariffTransaction ttx = new TariffTransaction(broker: Broker.get(tariff.broker.id),
            postedTime: timeService.currentTime, txType:txType, tariff:Tariff.get(tariff.id), 
            CustomerInfo:customer, customerCount:customerCount,
            quantity:quantity, charge:charge)
    ttx.save()
    pendingTransactions.add(ttx)
    return ttx
  }

  // Gets the net load. Note that this only works BEFORE the day's transactions
  // have been processed.
  @Synchronized
  BigDecimal getCurrentNetLoad (Broker broker)
  {
    BigDecimal netLoad = 0.0
    pendingTransactions.each { oldtx ->
      if (oldtx instanceof TariffTransaction) {
        TariffTransaction tx = TariffTransaction.get(oldtx.id)
        if (tx.broker == broker ) {
          if (tx.txType == TariffTransactionType.CONSUME ||
              tx.txType == TariffTransactionType.PRODUCE) {
            netLoad += tx.quantity
          }
        }
      }
    }
    return netLoad
  }

  /**
   * Gets the net market position for the current timeslot. This only works on
   * processed transactions, but it can be used before activation in case there
   * can be no new market transactions for the current timeslot. This is the
   * normal case.
   */
  @Synchronized
  BigDecimal getCurrentMarketPosition (Broker broker)
  {
    Timeslot current = Timeslot.currentTimeslot()
    log.debug "current timeslot: ${current.serialNumber}"
    MarketPosition position =
        MarketPosition.findByBrokerAndTimeslot(broker, current)
    if (position == null) {
      log.debug "null position for ts ${current.serialNumber}"
      return 0.0
    }
    return position.overallBalance
  }

  // keep in mind that this will likely be called in a different
  // session from the one in which the transaction was created, so
  // the transactions themselves may be stale.
  @Synchronized
  void activate (Instant time, int phaseNumber)
  {
    def brokerMsg = [:]
    Broker.list().each { broker ->
      // use username here rather than broker, because it seems that
      // the broker instance in the transaction and the broker instance
      // from the list are not necessarily the same object...
      brokerMsg[broker.username] = [] as Set
    }
    // walk through the pending transactions and run the updates
    pendingTransactions.each { oldtx ->
      // need to refresh the transaction first
      def tx = oldtx.class.get(oldtx.id)
      if (tx.broker == null) {
        log.error "${tx} has null broker"
      }
      if (brokerMsg[tx.broker.username] == null) {
        log.error "broker ${tx.broker} not in database"
      }
      brokerMsg[tx.broker.username] << tx
      processTransaction(tx, brokerMsg[tx.broker.username])
    }
    pendingTransactions.clear()
    // for each broker, compute interest and send messages
    BigDecimal rate = bankInterest/365.0
    Broker.list().each { broker ->
      // run interest payments at midnight
      if (timeService.hourOfDay == 0) {
        def brokerRate = rate
        CashPosition cash = broker.cash
        if (cash.balance >= 0.0) {
          // rate on positive balance is 1/2 of negative
          brokerRate /= 2.0
        }
        BigDecimal interest = cash.balance * brokerRate
        brokerMsg[broker.username] << 
            new BankTransaction(broker: broker, amount: interest,
                                postedTime: timeService.currentTime)
        cash.balance += interest
      }
      // add the cash position to the list and send messages
      brokerMsg[broker.username] << broker.cash
      brokerProxyService.sendMessages(broker, brokerMsg[broker.username] as List)
    }    
  }
  
  // process a tariff transaction
  private void processTransaction (TariffTransaction tx, Set messages)
  {
    CashPosition cash = tx.broker.cash
    cash.deposit tx.charge
    cash.addToTariffTransactions(tx)
    cash.save()
    tx.broker.save()
  }
  
  // process a market transaction
  private void processTransaction (MarketTransaction tx, Set messages)
  {
    Broker broker = tx.broker
    CashPosition cash = broker.cash
    cash.deposit(-tx.price * Math.abs(tx.quantity))
    cash.addToMarketTransactions(tx)
    MarketPosition mkt = 
        MarketPosition.findByBrokerAndTimeslot(broker, tx.timeslot)
    if (mkt == null) {
      mkt = new MarketPosition(broker: broker, timeslot: tx.timeslot)
      if (!mkt.validate()) {
        mkt.errors.allErrors.each { log.info it.toString() }
      }
      assert mkt.save()
      log.debug "New MarketPosition(${broker.username}, ${tx.timeslot.serialNumber}): ${mkt.id}"
      broker.addToMarketPositions(mkt)
      if (!broker.validate()) {
        broker.errors.each { log.info it.toString() }
      }
      assert broker.save()
    }
    mkt.updateBalance(tx.quantity)
    assert mkt.save()
    messages << mkt
    log.debug "MarketPosition count = ${MarketPosition.count()}"
  }
}
