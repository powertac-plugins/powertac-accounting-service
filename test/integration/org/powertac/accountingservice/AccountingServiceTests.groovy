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

import org.joda.time.DateTime
import org.joda.time.DateTimeZone;
import org.joda.time.Instant
import org.powertac.common.enumerations.CustomerType
import org.powertac.common.enumerations.PowerType;
import org.powertac.common.enumerations.TariffTransactionType;
import org.powertac.common.exceptions.PositionUpdateException
import org.powertac.common.interfaces.BrokerProxy
import org.powertac.common.*

class AccountingServiceTests extends GroovyTestCase 
{
  def timeService // dependency injection
  def accountingService
  def accountingInitializationService

  Competition comp
  CustomerInfo customerInfo1
  CustomerInfo customerInfo2
  CustomerInfo customerInfo3
  Tariff tariffB1
  Tariff tariffB2
  Tariff tariffJ1
  Broker bob
  Broker jim
  int nameCounter = 0

  protected void setUp() 
  {
    super.setUp()
    
    // clean up from other tests
    TariffTransaction.list()*.delete()
    //accountingService.idCount = 0
    accountingService.pendingTransactions = []
    Timeslot.list()*.delete()
    //Broker.list()*.delete()
    PluginConfig.findByRoleName('AccountingService')?.delete()

    // create a Competition, needed for initialization
    if (Competition.count() == 0) {
      comp = new Competition(name: 'accounting-test')
      assert comp.save()
    }
    else {
      comp = Competition.list().first()
    }
    
    // set the clock
    def now = new DateTime(2011, 1, 26, 12, 0, 0, 0, DateTimeZone.UTC).toInstant()
    timeService.setCurrentTime(now)
    
    // set up brokers and customers
    Broker.findByUsername('Bob')?.delete()
    bob = new Broker(username: "Bob")
    assert (bob.save())
    Broker.findByUsername('Jim')?.delete()
    jim = new Broker(username: "Jim")
    assert (jim.save())

    customerInfo1 = new CustomerInfo(name: 'downtown',
        customerType: CustomerType.CustomerHousehold, powerType: PowerType.CONSUMPTION)
    assert(customerInfo1.save())
    customerInfo2 = new CustomerInfo(name: 'suburbs',
        customerType: CustomerType.CustomerHousehold, powerType: PowerType.CONSUMPTION)
    assert(customerInfo2.save())
    customerInfo3 = new CustomerInfo(name: 'exburbs',
        customerType: CustomerType.CustomerHousehold, powerType: PowerType.CONSUMPTION)
    assert(customerInfo3.save())

    // set up tariffs - tariff1 for consumption, tariff2 for production
    Instant exp = new Instant(now.millis + TimeService.WEEK * 10)
    def tariffSpec = new TariffSpecification(broker: bob,
                                             expiration: exp, 
                                             minDuration: TimeService.WEEK * 8,
                                             periodicPayment: 0.02)
    tariffSpec.addToRates(new Rate(value: 0.121))
    assert tariffSpec.save()
    tariffB1 = new Tariff(tariffSpec: tariffSpec)
    tariffB1.init()
    assert tariffB1.save()
    tariffSpec = new TariffSpecification(broker: bob, 
                                         minDuration: TimeService.WEEK * 8,
                                         expiration: exp, powerType: PowerType.PRODUCTION)
    tariffSpec.addToRates(new Rate(value: 0.09))
    assert tariffSpec.save()
    tariffB2 = new Tariff(tariffSpec: tariffSpec)
    tariffB2.init()
    assert tariffB2.save()
    tariffSpec = new TariffSpecification(broker: jim, 
                                         minDuration: TimeService.WEEK * 8,
                                         expiration: exp, periodicPayment: 0.01)
    tariffSpec.addToRates(new Rate(value: 0.123))
    assert tariffSpec.save()
    tariffJ1 = new Tariff(tariffSpec: tariffSpec)
    tariffJ1.init()
    assert tariffJ1.save()
    
    // set up some timeslots
    def ts = new Timeslot(serialNumber: 3,
                          startInstant: new Instant(now.millis - TimeService.HOUR),
                          endInstant: now, enabled: false)
    assert(ts.save())
    ts = new Timeslot(serialNumber: 4,
                      startInstant: now,
                      endInstant: new Instant(now.millis + TimeService.HOUR), 
                      enabled: false)
    assert(ts.save())
    ts = new Timeslot(serialNumber: 5,
                      startInstant: new Instant(now.millis + TimeService.HOUR),
                      endInstant: new Instant(now.millis + TimeService.HOUR * 2), 
                      enabled: true)
    assert(ts.save())
    ts = new Timeslot(serialNumber: 6,
                      startInstant: new Instant(now.millis + TimeService.HOUR * 2),
                      endInstant: new Instant(now.millis + TimeService.HOUR * 3), 
                      enabled: true)
    assert(ts.save())
  }

  protected void tearDown() 
  {
    super.tearDown()
  }
  
  void initializeService () {
    accountingInitializationService.setDefaults()
    accountingInitializationService.initialize(comp, [])
  }

  void testAccountingServiceNotNull() 
  {
    assertNotNull(accountingInitializationService)
    assertNotNull(accountingService)
  }
  
  void testNormalInitialization ()
  {
    accountingInitializationService.setDefaults()
    PluginConfig config = PluginConfig.findByRoleName('AccountingService')
    assertNotNull("config created correctly", config)
    def result = accountingInitializationService.initialize(comp, [])
    assertEquals("correct return value", 'AccountingService', result)
    assertTrue("correct bank interest",
       accountingInitializationService.minInterest <= accountingService.getBankInterest())
    assertTrue("correct bank interest",
       accountingInitializationService.maxInterest >= accountingService.getBankInterest())
  }
  
  void testBogusInitialization ()
  {
    PluginConfig config = PluginConfig.findByRoleName('AccountingService')
    assertNull("config not created", config)
    def result = accountingInitializationService.initialize(comp, [])
    assertEquals("failure return value", 'fail', result)
  }
  
  void testBrokerDb ()
  {
    Broker b1 = Broker.get(bob.id)
    assertEquals("bob in db by id", bob, b1)
    Broker b2 = Broker.findByUsername("Bob")
    assertEquals("bob in db by name", bob, b2)
  }
  
  // create and test tariff transactions
  void testTariffTransaction ()
  {
    initializeService()
    accountingService.addTariffTransaction(TariffTransactionType.SIGNUP,
      tariffB1, customerInfo1, 2, 0.0, 42.1)
    accountingService.addTariffTransaction(TariffTransactionType.CONSUME,
      tariffB1, customerInfo2, 7, 77.0, 7.7)
    assertEquals("correct number in list", 2, accountingService.pendingTransactions.size())
    assertEquals("correct number in db", 2, TariffTransaction.count())
    def ttx = TariffTransaction.get(1)
    assertNotNull("first ttx not null", ttx)
    assertEquals("correct charge id 0", 42.1, ttx.charge, 1e-6)
    Broker b1 = ttx.broker
    Broker b2 = Broker.get(bob.id)
    assertEquals("same broker", b1, b2)
    ttx = TariffTransaction.get(2)
    assertNotNull("second ttx not null", ttx)
    assertEquals("correct amount id 1", 77.0, ttx.quantity, 1e-6)
  }
  
  void testCurrentNetLoad ()
  {
    initializeService()
    // some usage for Bob
    accountingService.addTariffTransaction(TariffTransactionType.CONSUME,
      tariffB1, customerInfo1, 7, 77.0, 7.7)
    accountingService.addTariffTransaction(TariffTransactionType.CONSUME,
      tariffB1, customerInfo2, 6, 83.0, 8.0)
    accountingService.addTariffTransaction(TariffTransactionType.PRODUCE,
      tariffB2, customerInfo3, 3, -55.0, -4.5)
    // some usage for Jim
    accountingService.addTariffTransaction(TariffTransactionType.CONSUME,
      tariffJ1, customerInfo2, 12, 120.0, 8.4)
    assertEquals("correct net load for Bob", (77.0 + 83.0 - 55.0),
                  accountingService.getCurrentNetLoad(bob), 1e-6)
    assertEquals("correct net load for Jim", 120.0,
                  accountingService.getCurrentNetLoad(jim), 1e-6)
  }
  
  // create and test market transactions
  void testMarketTransaction ()
  {
    initializeService()
    accountingService.addMarketTransaction(bob,
        Timeslot.findBySerialNumber(5), 45.0, 0.5)
    accountingService.addMarketTransaction(bob,
        Timeslot.findBySerialNumber(6), 43.0, 0.7)
    assertEquals("correct number in list", 2, accountingService.pendingTransactions.size())
    assertEquals("correct number in db", 2, MarketTransaction.count())
    def mtx = MarketTransaction.get(1)
    assertNotNull("first mtx not null", mtx)
    assertEquals("correct timeslot id 0", 5, mtx.timeslot.serialNumber)
    assertEquals("correct price id 0", 45.0, mtx.price, 1e-6)
    Broker b1 = mtx.broker
    Broker b2 = Broker.get(bob.id)
    assertEquals("same broker", b1, b2)
    mtx = MarketTransaction.get(2)
    assertNotNull("second mtx not null", mtx)
    assertEquals("correct quantity id 1", 0.7, mtx.quantity, 1e-6)
  }
  
  // simple activation
  void testSimpleActivate ()
  {
    initializeService()
    // need a Competition instance for interest calculation
    Competition comp = 
        new Competition(name: "test", bankInterest: 0.12)
    assert comp.save()
    
    // broker proxy
    def messages = [:]
    def proxy =
      [sendMessage: {broker, message ->
        if (messages[broker] == null) {
          messages[broker] = []
        }
        messages[broker] << message
      },
      sendMessages: {broker, messageList ->
        if (messages[broker] == null) {
          messages[broker] = []
        }
        messageList.each { message ->
          messages[broker] << message
        }
      }] as BrokerProxy
    accountingService.brokerProxyService = proxy
    
    // add a couple of transactions
    accountingService.addMarketTransaction(bob,
      Timeslot.findBySerialNumber(5), 45.0, 0.5)
    accountingService.addMarketTransaction(bob,
      Timeslot.findBySerialNumber(6), 43.0, 0.7)

    // activate and check messages
    accountingService.activate(timeService.currentTime, 3)
    // should have cash position, no market positions for jim
    assertEquals("one message", 1, messages[jim].size())
    assertTrue("it's a CashPosition", messages[jim][0] instanceof CashPosition)
    assertEquals("no balance", 0.0, messages[jim][0].balance)
    // should be market transactions, and cash and mkt positions for bob
    assertEquals("five messages", 5, messages[bob].size())
    def mtx1 = messages[bob].find { msg ->
      msg instanceof MarketTransaction &&
      msg.broker == bob &&
      msg.timeslot == Timeslot.findBySerialNumber(5)
    }
    assertNotNull("found 1st tx", mtx1)
    assertEquals("correct quantity", 0.5, mtx1.quantity)
    def mp1 = messages[bob].find { msg ->
      msg instanceof MarketPosition &&
      msg.broker == bob &&
      msg.timeslot == Timeslot.findBySerialNumber(5)
    }
    assertEquals("correct balance for ts5", 0.5, mp1.overallBalance)
    def cp1 = messages[bob].find { msg ->
      msg instanceof CashPosition && msg.broker == bob
    }
    assertEquals("correct cash position", -45.0 * 0.5 + -43.0 * 0.7, cp1.balance)
  }

  // test activation
  void testActivate ()
  {
    initializeService()
    // market transactions
    accountingService.addMarketTransaction(bob,
        Timeslot.findBySerialNumber(5), 45.0, 0.5)
    accountingService.addMarketTransaction(bob,
        Timeslot.findBySerialNumber(5), 31.0, 0.3)
    accountingService.addMarketTransaction(bob,
        Timeslot.findBySerialNumber(6), 43.0, 0.7)
    accountingService.addMarketTransaction(jim,
        Timeslot.findBySerialNumber(5), 35.0, 0.4)
    accountingService.addMarketTransaction(jim,
        Timeslot.findBySerialNumber(5), -20.0, -0.2)
    // tariff transactions
    accountingService.addTariffTransaction(TariffTransactionType.CONSUME,
        tariffB1, customerInfo1, 7, 77.0, 7.7)
    accountingService.addTariffTransaction(TariffTransactionType.CONSUME,
        tariffB1, customerInfo2, 6, 83.0, 8.0)
    accountingService.addTariffTransaction(TariffTransactionType.PRODUCE,
        tariffB2, customerInfo3, 3, -55.0, -4.5)
    accountingService.addTariffTransaction(TariffTransactionType.CONSUME,
        tariffJ1, customerInfo2, 12, 120.0, 8.4)
    assertEquals("correct number in list", 9, accountingService.pendingTransactions.size())
    
    // activate and check cash and market positions
    accountingService.activate(timeService.currentTime, 3)
    assertEquals("correct cash balance, Bob",
        (-45.0 * 0.5 - 31.0 * 0.3 - 43.0 * 0.7 + 7.7 + 8.0 -4.5), bob.cash.balance, 1e-6)
    assertEquals("correct cash balance, Jim",
        (-35.0 * 0.4 + 20.0 * 0.2 + 8.4), jim.cash.balance, 1e-6)
    assertEquals("3 mkt positions", 3, MarketPosition.count())
    MarketPosition mkt = 
        MarketPosition.findByBrokerAndTimeslot(bob, Timeslot.findBySerialNumber(5))
    assertNotNull("found market position b5", mkt)
    assertEquals("correct mkt position, Bob, ts5",  0.8, mkt.overallBalance, 1e-6)
    mkt = MarketPosition.findByBrokerAndTimeslot(bob, Timeslot.findBySerialNumber(6))
    assertNotNull("found market position b6", mkt)
    assertEquals("correct mkt position, Bob, ts6",  0.7, mkt.overallBalance, 1e-6)
    mkt = MarketPosition.findByBrokerAndTimeslot(jim, Timeslot.findBySerialNumber(5))
    assertNotNull("found market position j5", mkt)
    assertEquals("correct mkt position, Jim, ts5",  0.2, mkt.overallBalance, 1e-6)
  }
  
  // net market position only works after activation
  void testCurrentMarketPosition ()
  {
    initializeService()
    accountingService.addMarketTransaction(bob,
        Timeslot.findBySerialNumber(5), 45.0, 0.5)
    accountingService.addMarketTransaction(bob,
        Timeslot.findBySerialNumber(5), 31.0, 0.3)
    accountingService.addMarketTransaction(bob,
        Timeslot.findBySerialNumber(6), 43.0, 0.7)
    accountingService.addMarketTransaction(jim,
        Timeslot.findBySerialNumber(5), 35.0, 0.4)
    accountingService.addMarketTransaction(jim,
        Timeslot.findBySerialNumber(5), -20.0, -0.2)
    assertEquals("correct number in list", 5, accountingService.pendingTransactions.size())
    accountingService.activate(timeService.currentTime, 3)
    // current timeslot is 4, should be 0 mkt posn
    assertEquals("correct position, bob, ts4", 0.0,
        accountingService.getCurrentMarketPosition (bob), 1e-6)
    assertEquals("correct position, jim, ts4", 0.0,
        accountingService.getCurrentMarketPosition (jim), 1e-6)
    // move forward to timeslot 5 and try again
    timeService.currentTime = new Instant(timeService.currentTime.millis + TimeService.HOUR)
    assertEquals("correct position, bob, ts5", 0.8,
        accountingService.getCurrentMarketPosition (bob), 1e-6)
    assertEquals("correct position, jim, ts5", 0.2,
        accountingService.getCurrentMarketPosition (jim), 1e-6)
    // another hour and try again
    timeService.currentTime = new Instant(timeService.currentTime.millis + TimeService.HOUR)
    assertEquals("correct position, bob, ts5", 0.7,
        accountingService.getCurrentMarketPosition (bob), 1e-6)
    assertEquals("correct position, jim, ts5", 0.0,
        accountingService.getCurrentMarketPosition (jim), 1e-6)
  }
  
  // interest should be paid/charged at midnight activation
  void testInterestPayment ()
  {
    initializeService()
    //assertEquals("interest set by bootstrap", 0.10, accountingService.bankInterest)
    
    // set the interest to 12%
    accountingService.bankInterest = 0.12
    
    // broker proxy
    def messages = [:]
    def proxy =
      [sendMessage: {broker, message ->
        if (messages[broker] == null) {
          messages[broker] = []
        }
        messages[broker] << message
      },
      sendMessages: {broker, messageList ->
        if (messages[broker] == null) {
          messages[broker] = []
        }
        messageList.each { message ->
          messages[broker] << message
        }
      }] as BrokerProxy
    accountingService.brokerProxyService = proxy

    // bob is in the hole
    bob.cash.balance = -1000.0

    // move to midnight, activate and check messages
    timeService.currentTime = new DateTime(2011, 1, 27, 0, 0, 0, 0, DateTimeZone.UTC).toInstant()
    accountingService.activate(timeService.currentTime, 3)

    // should have bank tx and cash position for Bob
    assertEquals("two messages", 2, messages[bob].size())
    def btx1 = messages[bob].find { msg ->
      msg instanceof BankTransaction && msg.broker == bob
    }
    assertNotNull("found bank tx", btx1)
    assertEquals("correct amount", -1000.0 * 0.12 / 365.0, btx1.amount, 1e-6)
    def cp1 = messages[bob].find { msg ->
      msg instanceof CashPosition && msg.broker == bob
    }
    assertNotNull("found cash posn", cp1)
    assertEquals("correct amount", -1000.0 * (1.0 + 0.12 / 365.0), cp1.balance, 1e-6)
  }
}
