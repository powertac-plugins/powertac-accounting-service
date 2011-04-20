/*
 * Copyright 2011 the original author or authors.
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

package org.powertac.tariffmarket

import grails.test.GrailsUnitTestCase
//import groovy.mock.interceptor.MockFor
//import groovy.mock.interceptor.StubFor

import org.joda.time.DateTime
import org.joda.time.DateTimeZone
import org.joda.time.Instant
//import org.junit.After
//import org.junit.Before
//import org.junit.Test
import org.powertac.common.Broker
import org.powertac.common.HourlyCharge
import org.powertac.common.enumerations.PowerType
import org.powertac.common.enumerations.CustomerType
import org.powertac.common.enumerations.TariffTransactionType
import org.powertac.common.interfaces.BrokerProxy
import org.powertac.common.AbstractCustomer
import org.powertac.common.CustomerInfo
import org.powertac.common.Rate
import org.powertac.common.Tariff
import org.powertac.common.interfaces.CompetitionControl
import org.powertac.common.interfaces.NewTariffListener
import org.powertac.common.TariffTransaction
import org.powertac.common.TariffSpecification
import org.powertac.common.TariffSubscription
import org.powertac.common.msg.TariffExpire
import org.powertac.common.msg.TariffRevoke
import org.powertac.common.msg.TariffStatus
import org.powertac.common.msg.VariableRateUpdate
import org.powertac.common.TimeService

/**
 * @author John Collins
 */
class TariffMarketServiceTests extends GrailsUnitTestCase
{
  def timeService // dependency injection
  TariffMarketService tariffMarketService
  
  TariffSpecification tariffSpec // instance var

  Instant start
  Instant exp
  Broker broker
  def txs = []
  def msgs = []

  void setUp ()
  {
    super.setUp()
    
    // mock the brokerProxyService
    def brokerProxy =
      [sendMessage: { broker, message ->
        msgs << message
      },
      sendMessages: { broker, messageList ->
        messageList.each { message ->
          msgs << message
        }
      },
      broadcastMessage: { message ->
        msgs << message
      },
      broadcastMessages: { messageList ->
        messageList.each { message ->
          msgs << message
        }
      },
      registerBrokerTariffListener: { thing ->
        println "tariff listener registration"
      }] as BrokerProxy
    tariffMarketService.brokerProxyService = brokerProxy

    // clean up
    TariffSpecification.list()*.delete()
    Tariff.list()*.delete()
    txs = []
    msgs = []
    tariffMarketService.registrations = []
    tariffMarketService.newTariffs = []
    
    // init time service
    start = new DateTime(2011, 1, 1, 12, 0, 0, 0, DateTimeZone.UTC).toInstant()
    timeService.setCurrentTime(start)
    
    // create useful objects, set parameters
    broker = new Broker (username: 'testBroker', password: 'testPassword')
    assert broker.save()
    tariffMarketService.configuration.configuration['tariffPublicationFee'] = '42.0'
    tariffMarketService.configuration.configuration['tariffRevocationFee'] = '420.0'
    exp = new DateTime(2011, 3, 1, 12, 0, 0, 0, DateTimeZone.UTC).toInstant()
    tariffSpec = new TariffSpecification(broker: broker, expiration: exp,
                                         minDuration: TimeService.WEEK * 8)
    Rate r1 = new Rate(value: 0.121)
    tariffSpec.addToRates(r1)
  }

  /**
   * @throws java.lang.Exception
   */
  void tearDown ()
  {
    super.tearDown()
  }

  // null tariffSpec
  void testProcessTariffSpecJunk ()
  {
    TariffStatus status = tariffMarketService.processTariff("abc")
    assertNull("should be null status", status)
    status = tariffMarketService.processTariff(null)
    assertNull("another null status", status)
  }
  
  // invalid tariffSpec
  void testProcessTariffInvalid ()
  {
    tariffSpec.broker = null
    tariffMarketService.receiveMessage(tariffSpec)
    TariffStatus status = msgs[0]
    assertNull("no broker, null status", status)
    
    tariffSpec.broker = broker
    tariffSpec.minDuration = null
    tariffMarketService.receiveMessage(tariffSpec)
    status = msgs[0]
    assertNotNull("bad spec, non-null status", status)
    assertEquals("correct broker", broker, status.broker)
    assertEquals("correct spec ID", tariffSpec.id, status.tariffId)
    assertEquals("correct status ID", tariffSpec.id, status.updateId)
    assertEquals("correct status", TariffStatus.Status.invalidTariff, status.status)
    println status.message
  }
  
  // valid tariffSpec
  void testProcessTariffSpec ()
  {
    tariffMarketService.receiveMessage(tariffSpec)
    TariffStatus status = msgs[0]
    // check the status return
    assertNotNull("non-null status", status)
    assertEquals("broker", tariffSpec.broker, status.broker)
    assertEquals("tariff ID", tariffSpec.id, status.tariffId)
    assertEquals("status ID", tariffSpec.id, status.updateId)
    assertEquals("success", TariffStatus.Status.success, status.status)
    // find and check the tariff
    assertEquals("one tariff", 1, Tariff.count())
    Tariff tf = Tariff.findBySpecId(tariffSpec.id)
    assertNotNull("found a tariff", tf)
    // find and check the transaction
    assertEquals("one transaction", 1, TariffTransaction.count())
    TariffTransaction ttx = TariffTransaction.findByPostedTime(timeService.currentTime)
    assertNotNull("found transaction", ttx)
    assertEquals("correct tariff", tf, ttx.tariff)
    assertEquals("correct type", TariffTransactionType.PUBLISH, ttx.txType)
    assertEquals("correct amount", 42.0, ttx.charge, 1e-6)
    // make sure the tariff is in the output list
    // this should be replaced with a check on an output channel.
    //assertEquals("correct number of tariffs queued", 1, tariffMarketService.broadcast.size())
    //assertEquals("correct first element", tariffSpec, tariffMarketService.broadcast.remove(0))
  }

  // bogus expiration
  void testProcessTariffExpireBogus ()
  {
    TariffStatus status = tariffMarketService.processTariff(tariffSpec)
    assertEquals("success", TariffStatus.Status.success, status.status)
    Tariff tf = Tariff.findBySpecId(tariffSpec.id)
    assertEquals("Correct expiration", exp, tf.expiration)
    Instant newExp = new DateTime(2011, 3, 1, 10, 0, 0, 0, DateTimeZone.UTC).toInstant()
    TariffExpire tex = new TariffExpire(tariffId: "bogus",
                                        broker: tariffSpec.broker,
                                        newExpiration: newExp)
    status = tariffMarketService.processTariff(tex)
    assertNotNull("non-null status", status)
    assertEquals("correct status ID", tex.id, status.updateId)
    assertEquals("No such tariff", TariffStatus.Status.noSuchTariff, status.status)
    assertEquals("tariff not updated", exp, tf.expiration)
    println status.message
  }
  
  // null exp time
  void testProcessTariffExpireNull ()
  {
    TariffStatus status = tariffMarketService.processTariff(tariffSpec)
    assertEquals("success", TariffStatus.Status.success, status.status)
    Tariff tf = Tariff.findBySpecId(tariffSpec.id)
    assertEquals("Correct expiration", exp, tf.expiration)
    Instant newExp = new DateTime(2011, 3, 1, 8, 0, 0, 0, DateTimeZone.UTC).toInstant()
    timeService.currentTime = newExp
    TariffExpire tex = new TariffExpire(tariffId: tariffSpec.id,
                                        broker: tariffSpec.broker)
    status = tariffMarketService.processTariff(tex)
    assertNotNull("non-null status", status)
    assertEquals("correct status ID", tex.id, status.updateId)
    assertEquals("invalid", TariffStatus.Status.invalidUpdate, status.status)
    assertEquals("tariff not updated", exp, tf.expiration)
  }

  // normal expiration
  void testProcessTariffExpire ()
  {
    TariffStatus status = tariffMarketService.processTariff(tariffSpec)
    assertEquals("success", TariffStatus.Status.success, status.status)
    Tariff tf = Tariff.findBySpecId(tariffSpec.id)
    assertEquals("Correct expiration", exp, tf.expiration)
    Instant newExp = new DateTime(2011, 3, 1, 10, 0, 0, 0, DateTimeZone.UTC).toInstant()
    TariffExpire tex = new TariffExpire(tariffId: tariffSpec.id,
                                        broker: tariffSpec.broker,
                                        newExpiration: newExp)
    status = tariffMarketService.processTariff(tex)
    assertNotNull("non-null status", status)
    assertEquals("correct status ID", tex.id, status.updateId)
    assertEquals("success", TariffStatus.Status.success, status.status)
    assertEquals("tariff updated", newExp, tf.expiration)
    assertFalse("tariff not expired", tf.isExpired())
    timeService.currentTime = newExp
    assertTrue("tariff is expired", tf.isExpired())
  }

  // TODO - bogus revoke
  
  // normal revoke
  void testProcessTariffRevoke ()
  {
    TariffStatus status = tariffMarketService.processTariff(tariffSpec)
    assertEquals("success", TariffStatus.Status.success, status.status)
    Tariff tf = Tariff.findBySpecId(tariffSpec.id)
    assertFalse("not revoked", tf.isRevoked())
    TariffRevoke tex = new TariffRevoke(tariffId: tariffSpec.id,
                                        broker: tariffSpec.broker)
    status = tariffMarketService.processTariff(tex)
    assertNotNull("non-null status", status)
    assertEquals("correct status ID", tex.id, status.updateId)
    assertEquals("success", TariffStatus.Status.success, status.status)
    assertTrue("tariff revoked", tf.isRevoked())
  }

  // variable rate update - nominal case, 2 tariffs
  void testVariableRateUpdate ()
  {
    // what the broker does...
    TariffSpecification ts2 =
          new TariffSpecification(broker: broker, expiration: new DateTime(2011, 3, 1, 12, 0, 0, 0, DateTimeZone.UTC).toInstant(),
                                  minDuration: TimeService.WEEK * 4)
    Rate r1 = new Rate(isFixed: false, minValue: 0.05, maxValue: 0.50,
                       noticeInterval: 0, expectedMean: 0.10)
    ts2.addToRates(r1)
    Instant lastHr = new Instant(start.millis - TimeService.HOUR)
    r1.addToRateHistory(new HourlyCharge(value: 0.07, atTime: lastHr))

    // send to market
    TariffStatus status1 = tariffMarketService.processTariff(tariffSpec)
    TariffStatus status2 = tariffMarketService.processTariff(ts2)

    // check the status return2
    assertNotNull("non-null status 1", status1)
    assertNotNull("non-null status 2", status2)
    assertEquals("broker ID 1", tariffSpec.broker, status1.broker)
    assertEquals("broker ID 2", ts2.broker, status2.broker)
    assertEquals("tariff ID 1", tariffSpec.id, status1.tariffId)
    assertEquals("tariff ID 2", ts2.id, status2.tariffId)
    assertEquals("status ID 1", tariffSpec.id, status1.updateId)
    assertEquals("status ID 2", ts2.id, status2.updateId)
    assertEquals("success 1", TariffStatus.Status.success, status1.status)
    assertEquals("success 2", TariffStatus.Status.success, status2.status)

    // find and check the tariffs
    assertEquals("two tariffs", 2, Tariff.count())
    Tariff tf1 = Tariff.findBySpecId(tariffSpec.id)
    Tariff tf2 = Tariff.findBySpecId(ts2.id)
    assertNotNull("found tariff 1", tf1)
    assertNotNull("found tariff 2", tf2)
    
    // update the hourly rate on tariff 2
    HourlyCharge hc = new HourlyCharge(value: 0.09, atTime: start)
    VariableRateUpdate vru = new VariableRateUpdate(payload: hc, broker: broker, tariffId: ts2.id, rateId: r1.id)
    TariffStatus vrs = tariffMarketService.processTariff(vru)
    assertNotNull("non-null vru status", vrs)
    assertEquals("success vru", TariffStatus.Status.success, vrs.status)
    
    assertEquals("Correct rate at 11:00", 0.07, tf2.getUsageCharge(lastHr), 1e-6)
    assertEquals("Correct rate at 12:00", 0.09, tf2.getUsageCharge(start), 1e-6)

    // make sure both tariffs are on the output list
    // TODO - this should be replaced with a check on an output channel.
    //assertEquals("correct number of notifications queued", 3, tariffMarketService.broadcast.size())
    //assertEquals("correct first element", tariffSpec, tariffMarketService.broadcast.remove(0))
    //assertEquals("correct second element", ts2, tariffMarketService.broadcast.remove(0))
    //assertEquals("correct third element", vru, tariffMarketService.broadcast.remove(0))
  }
  
  // TODO - invalid variable-rate update

  // check evolution of active tariff list
  void testGetActiveTariffList ()
  {
    // initially, there should be no active tariffs
    assertEquals("no initial tariffs", 0,
          tariffMarketService.getActiveTariffList(PowerType.CONSUMPTION).size())
    // first, add multiple tariffs for more than one power type, multiple expirations
    def tsc1 = new TariffSpecification(broker: broker, 
          expiration: new Instant(start.millis + TimeService.DAY),
          minDuration: TimeService.WEEK * 8, powerType: PowerType.CONSUMPTION)
    def tsc2 = new TariffSpecification(broker: broker, 
          expiration: new Instant(start.millis + TimeService.DAY * 2),
          minDuration: TimeService.WEEK * 8, powerType: PowerType.CONSUMPTION)
    def tsc3 = new TariffSpecification(broker: broker, 
          expiration: new Instant(start.millis + TimeService.DAY * 3),
          minDuration: TimeService.WEEK * 8, powerType: PowerType.CONSUMPTION)
    Rate r1 = new Rate(value: 0.222)
    tsc1.addToRates(r1)
    tsc2.addToRates(r1)
    tsc3.addToRates(r1)
    tariffMarketService.processTariff(tsc1)
    tariffMarketService.processTariff(tsc2)
    tariffMarketService.processTariff(tsc3)
    def tsp1 = new TariffSpecification(broker: broker, 
          expiration: new Instant(start.millis + TimeService.DAY),
          minDuration: TimeService.WEEK* 8, powerType: PowerType.PRODUCTION)
    def tsp2 = new TariffSpecification(broker: broker, 
          expiration: new Instant(start.millis + TimeService.DAY * 2),
          minDuration: TimeService.WEEK * 8, powerType: PowerType.PRODUCTION)
    Rate r2 = new Rate(value: 0.119)
    tsp1.addToRates(r2)
    tsp2.addToRates(r2)
    tariffMarketService.processTariff(tsp1)
    tariffMarketService.processTariff(tsp2)
    assertEquals("five tariffs", 5, Tariff.count())
    
    // make sure all tariffs are active
    def tclist = tariffMarketService.getActiveTariffList(PowerType.CONSUMPTION)
    assertEquals("3 consumption tariffs", 3, tclist.size())
    def tplist = tariffMarketService.getActiveTariffList(PowerType.PRODUCTION)
    assertEquals("2 production tariffs", 2, tplist.size())
    
    // forward one day, try again
    timeService.currentTime += TimeService.DAY
    tclist = tariffMarketService.getActiveTariffList(PowerType.CONSUMPTION)
    assertEquals("2 consumption tariffs", 2, tclist.size())
    tplist = tariffMarketService.getActiveTariffList(PowerType.PRODUCTION)
    assertEquals("1 production tariffs", 1, tplist.size())
    
    // forward another day, try again
    timeService.currentTime += TimeService.DAY
    tclist = tariffMarketService.getActiveTariffList(PowerType.CONSUMPTION)
    assertEquals("1 consumption tariff", 1, tclist.size())
    tplist = tariffMarketService.getActiveTariffList(PowerType.PRODUCTION)
    assertEquals("no production tariffs", 0, tplist.size())
    
    // forward another day, try again
    timeService.currentTime += TimeService.DAY
    tclist = tariffMarketService.getActiveTariffList(PowerType.CONSUMPTION)
    assertEquals("no consumption tariffs", 0, tclist.size())
  }
  
  // test batch-publication of new tariffs
  void testBatchPublication ()
  {
    // test competitionControl registration
    def registrationThing = null
    def registrationPhase = -1
    def competitionControlService =
        [registerTimeslotPhase: { thing, phase ->
          registrationThing = thing
          registrationPhase = phase
        }] as CompetitionControl
    tariffMarketService.competitionControlService = competitionControlService
    tariffMarketService.afterPropertiesSet()
    //assertEquals("correct thing", tariffMarketService, registrationThing)
    assertEquals("correct phase", tariffMarketService.simulationPhase, registrationPhase)
        
    // current time is noon. Set pub interval to 3 hours.
    tariffMarketService.configuration.configuration['publicationInterval'] = '3' // hours
    assertEquals("newTariffs list is empty", 0, tariffMarketService.newTariffs.size())
    // register a NewTariffListener 
    def publishedTariffs = []
    def listener = 
        [publishNewTariffs:{ 
          tariffList -> publishedTariffs = tariffList 
        }] as NewTariffListener
    tariffMarketService.registerNewTariffListener(listener)
    assertEquals("one registration", 1, tariffMarketService.registrations.size())
    assertEquals("no tariffs at 12:00", 0, publishedTariffs.size())
    // publish some tariffs over a period of three hours, check for publication
    def tsc1 = new TariffSpecification(broker: broker,
        expiration: new Instant(start.millis + TimeService.DAY),
        minDuration: TimeService.WEEK * 8, powerType: PowerType.CONSUMPTION)
    Rate r1 = new Rate(value: 0.222)
    tsc1.addToRates(r1)
    tariffMarketService.processTariff(tsc1)
    def tsc1a = new TariffSpecification(broker: broker,
        expiration: new Instant(start.millis + TimeService.DAY),
        minDuration: TimeService.WEEK * 8, powerType: PowerType.CONSUMPTION)
    Rate r1a = new Rate(value: 0.223)
    tsc1a.addToRates(r1a)
    tariffMarketService.processTariff(tsc1a)
    timeService.currentTime += TimeService.HOUR
    // it's 13:00
    tariffMarketService.activate(timeService.currentTime, 2)
    assertEquals("no tariffs at 13:00", 0, publishedTariffs.size())
    
    def tsc2 = new TariffSpecification(broker: broker,
        expiration: new Instant(start.millis + TimeService.DAY * 2),
        minDuration: TimeService.WEEK * 8, powerType: PowerType.CONSUMPTION)
    tsc2.addToRates(r1)
    tariffMarketService.processTariff(tsc2)
    def tsc3 = new TariffSpecification(broker: broker,
        expiration: new Instant(start.millis + TimeService.DAY * 3),
        minDuration: TimeService.WEEK * 8, powerType: PowerType.CONSUMPTION)
    tsc3.addToRates(r1)
    tariffMarketService.processTariff(tsc3)
    timeService.currentTime += TimeService.HOUR
    // it's 14:00
    tariffMarketService.activate(timeService.currentTime, 2)
    assertEquals("no tariffs at 14:00", 0, publishedTariffs.size())

    def tsp1 = new TariffSpecification(broker: broker,
        expiration: new Instant(start.millis + TimeService.DAY),
        minDuration: TimeService.WEEK * 8, powerType: PowerType.PRODUCTION)
    def tsp2 = new TariffSpecification(broker: broker,
        expiration: new Instant(start.millis + TimeService.DAY * 2),
        minDuration: TimeService.WEEK * 8, powerType: PowerType.PRODUCTION)
    Rate r2 = new Rate(value: 0.119)
    tsp1.addToRates(r2)
    tsp2.addToRates(r2)
    tariffMarketService.processTariff(tsp1)
    tariffMarketService.processTariff(tsp2)
    assertEquals("six tariffs", 6, Tariff.count())
    
    TariffRevoke tex = new TariffRevoke(tariffId: tsc1a.id, broker: tsc1a.broker)
    tariffMarketService.processTariff(tex)

    timeService.currentTime += TimeService.HOUR
    // it's 15:00 - time to publish
    tariffMarketService.activate(timeService.currentTime, 2)
    assertEquals("5 tariffs at 15:00", 5, publishedTariffs.size())
    assertEquals("newTariffs list is again empty", 0, tariffMarketService.newTariffs.size())
  }

  // create some subscriptions and then revoke a tariff
  void testGetRevokedSubscriptionList ()
  {    
    // create some tariffs
    def tsc1 = new TariffSpecification(broker: broker, 
          expiration: new Instant(start.millis + TimeService.DAY * 5),
          minDuration: TimeService.WEEK * 8, powerType: PowerType.CONSUMPTION)
    def tsc2 = new TariffSpecification(broker: broker, 
          expiration: new Instant(start.millis + TimeService.DAY * 7),
          minDuration: TimeService.WEEK * 8, powerType: PowerType.CONSUMPTION)
    def tsc3 = new TariffSpecification(broker: broker, 
          expiration: new Instant(start.millis + TimeService.DAY * 9),
          minDuration: TimeService.WEEK * 8, powerType: PowerType.CONSUMPTION)
    Rate r1 = new Rate(value: 0.222)
    tsc1.addToRates(r1)
    tsc2.addToRates(r1)
    tsc3.addToRates(r1)
    tariffMarketService.processTariff(tsc1)
    tariffMarketService.processTariff(tsc2)
    tariffMarketService.processTariff(tsc3)
    Tariff tc1 = Tariff.findBySpecId(tsc1.id)
    assertNotNull("first tariff found", tc1)
    Tariff tc2 = Tariff.findBySpecId(tsc2.id)
    assertNotNull("second tariff found", tc2)
    Tariff tc3 = Tariff.findBySpecId(tsc3.id)
    assertNotNull("third tariff found", tc3)
    
    // create two customers who can subscribe
    def charleyInfo = new CustomerInfo(name:"Charley", customerType: CustomerType.CustomerHousehold)
    def sallyInfo = new CustomerInfo(name:"Sally", customerType: CustomerType.CustomerHousehold)
    assert charleyInfo.save()
    assert sallyInfo.save()
	
	def charley = new AbstractCustomer(customerInfo: charleyInfo)
	def sally = new AbstractCustomer(customerInfo: sallyInfo)
    charley.init()
	sally.init()
	assert charley.save()
	assert sally.save()
	
    // make sure we have three active tariffs
    def tclist = tariffMarketService.getActiveTariffList(PowerType.CONSUMPTION)
    assertEquals("3 consumption tariffs", 3, tclist.size())
    assertEquals("three transaction", 3, TariffTransaction.count())
    
    // create some subscriptions
    def cs1 = tariffMarketService.subscribeToTariff(tc1, charley, 3) 
    def cs2 = tariffMarketService.subscribeToTariff(tc2, charley, 31) 
    def cs3 = tariffMarketService.subscribeToTariff(tc3, charley, 13) 
    def ss1 = tariffMarketService.subscribeToTariff(tc1, sally, 4) 
    def ss2 = tariffMarketService.subscribeToTariff(tc2, sally, 24) 
    def ss3 = tariffMarketService.subscribeToTariff(tc3, sally, 42)
    assertEquals("3 customers for cs1", 3, cs1.customersCommitted)
    assertEquals("42 customers for ss3", 42, ss3.customersCommitted)
    assertEquals("Charley has 3 subscriptions", 3, TariffSubscription.findAllByCustomer(charley).size())
    
    // forward an hour, revoke the second tariff
    timeService.currentTime = new Instant(timeService.currentTime.millis + TimeService.HOUR)
    TariffRevoke tex = new TariffRevoke(tariffId: tsc2.id,
                                        broker: tc2.broker)
    def status = tariffMarketService.processTariff(tex)
    assertNotNull("non-null status", status)
    assertEquals("success", TariffStatus.Status.success, status.status)
    assertTrue("tariff revoked", tc2.isRevoked())

    // should now be just two active tariffs
    tclist = tariffMarketService.getActiveTariffList(PowerType.CONSUMPTION)
    assertEquals("2 consumption tariffs", 2, tclist.size())

    // retrieve Charley's revoked-subscription list
    def revokedCharley = tariffMarketService.getRevokedSubscriptionList(charley)
    assertEquals("one item in list", 1, revokedCharley.size())
    assertEquals("it's cs2", cs2, revokedCharley[0])

    // find and check the transaction
    assertEquals("one more transaction", 4, TariffTransaction.count())
    TariffTransaction ttx = TariffTransaction.findByPostedTime(timeService.currentTime)
    assertNotNull("found transaction", ttx)
    assertEquals("correct tariff", tc2, ttx.tariff)
    assertEquals("correct type", TariffTransactionType.REVOKE, ttx.txType)
    assertEquals("correct amount", 420.0, ttx.charge, 1e-6)
    // make sure the revoke msg is in the output list
    // this should be replaced with a check on an output channel.
    //assertEquals("correct number of msgs queued", 4, tariffMarketService.broadcast.size())
    //assertEquals("correct fourth element", tex, tariffMarketService.broadcast.remove(3))
  }

  // check default tariffs
  void testGetDefaultTariff ()
  {
    // set defaults for consumption and production
    def tsc1 = new TariffSpecification(broker: broker, 
          expiration: new Instant(start.millis + TimeService.WEEK),
          minDuration: TimeService.WEEK * 8, powerType: PowerType.CONSUMPTION)
    Rate r1 = new Rate(value: 0.222)
    tsc1.addToRates(r1)
    assertTrue("add consumption default", tariffMarketService.setDefaultTariff(tsc1))
    def tsp1 = new TariffSpecification(broker: broker, 
          expiration: new Instant(start.millis + TimeService.WEEK),
          minDuration: TimeService.WEEK * 8, powerType: PowerType.PRODUCTION)
    r1 = new Rate(value: 0.122)
    tsp1.addToRates(r1)
    assertTrue("add production default", tariffMarketService.setDefaultTariff(tsp1))
    
    // find the resulting tariffs
    Tariff tc1 = Tariff.findBySpecId(tsc1.id)
    assertNotNull("consumption tariff found", tc1)
    assertEquals("correct consumption tariff", tsc1.id, tc1.specId)
    Tariff tp1 = Tariff.findBySpecId(tsp1.id)
    assertNotNull("production tariff found", tp1)
    assertEquals("correct production tariff", tsp1.id, tp1.specId)

    // retrieve and check the defaults
    assertEquals("default consumption tariff", tc1, tariffMarketService.getDefaultTariff(PowerType.CONSUMPTION))
    assertEquals("default production tariff", tp1, tariffMarketService.getDefaultTariff(PowerType.PRODUCTION))
    assertNull("no solar tariff", tariffMarketService.getDefaultTariff(PowerType.SOLAR_PRODUCTION))
  }

}
