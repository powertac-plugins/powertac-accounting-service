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

import grails.test.*
import org.joda.time.DateTime
import org.joda.time.DateTimeZone
import org.joda.time.Instant
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.powertac.common.Broker
import org.powertac.common.HourlyCharge
import org.powertac.common.enumerations.PowerType
import org.powertac.common.enumerations.CustomerType
import org.powertac.common.enumerations.TariffTransactionType
import org.powertac.common.AbstractCustomer
import org.powertac.common.Competition
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
import org.powertac.tariffmarket.TariffMarketService
import org.powertac.common.TimeService
import org.powertac.common.PluginConfig

class AbstractCustomerSchedTests extends GroovyTestCase {
  
  def timeService  // autowire the time service
  def tariffMarketService // autowire the market
  def tariffMarketInitializationService
  
  DateTime theBase
  DateTime theStart
  int theRate
  int theMod
  
  Competition comp
  Tariff tariff
  TariffSpecification defaultTariffSpec
  Broker broker1
  Broker broker2
  Instant exp
  Instant start
  CustomerInfo customerInfo
  AbstractCustomer customer
  DateTime now
  
  
  protected void setUp() {
    super.setUp()
    
    // create a Competition, needed for initialization
    if (Competition.count() == 0) {
      comp = new Competition(name: 'accounting-test')
      assert comp.save()
    }
    else {
      comp = Competition.list().first()
    }

    theBase = new DateTime(2007, 6, 21, 12, 0, 0, 0, DateTimeZone.UTC)
    theStart = new DateTime(DateTimeZone.UTC)
    theRate = 720
    theMod = 15*60*1000
    timeService.base = theBase.millis
    timeService.start = theStart.millis
    timeService.rate = theRate
    timeService.modulo = theMod
    timeService.updateTime()

    // initialize the tariff market
    PluginConfig.findByRoleName('TariffMarket')?.delete()
    tariffMarketInitializationService.setDefaults()
    tariffMarketInitializationService.initialize(comp, ['AccountingService'])
    
    tariffMarketService.registrations = []
    tariffMarketService.newTariffs = []
    
    //TariffSpecification.list()*.delete()
    //Tariff.list()*.delete()
    //Broker.list()*.delete()
    CustomerInfo.list()*.delete()
    Broker.findByUsername('Joe')?.delete()
    broker1 = new Broker(username: "Joe", password: "foo")
    assert broker1.save()
    Broker.findByUsername('Anna')?.delete()
    broker2 = new Broker(username: "Anna", password: "bar")
    assert broker2.save()
  
  //  now = new DateTime(2011, 1, 10, 0, 0, 0, 0, DateTimeZone.UTC)
  //  timeService.currentTime = now.toInstant()
  
    exp = new Instant(timeService.currentTime.millis + TimeService.WEEK * 10)

    TariffSpecification tariffSpec =
        new TariffSpecification(broker: broker1,
        expiration: exp,
        minDuration: TimeService.WEEK * 8)
    tariffSpec.addToRates(new Rate(value: 0.121))
    tariffSpec.save()
  
    defaultTariffSpec = new TariffSpecification(broker: broker1,
        expiration: exp,
        minDuration: TimeService.WEEK * 8)
    defaultTariffSpec.addToRates(new Rate(value: 0.222))
    defaultTariffSpec.save()

    tariffMarketService.setDefaultTariff(defaultTariffSpec)
  
    assertEquals("correct Default Tariff", defaultTariffSpec, tariffMarketService.getDefaultTariff(defaultTariffSpec.powerType).tariffSpec)
    assertEquals("One Tariff", 1, Tariff.count())
    
    customerInfo = new CustomerInfo(name:"Anty", customerType: CustomerType.CustomerHousehold, 
                                    powerTypes: [PowerType.CONSUMPTION])
    if (!customerInfo.validate()) {
      customerInfo.errors.each { println it.toString() }
      fail("Could not save customerInfo")
    }
    assert(customerInfo.save())

    customer = new AbstractCustomer(CustomerInfo: customerInfo)
    customer.init()
    if (!customer.validate()) {
      customer.errors.each { println it.toString() }
      fail("Could not save customer")
    }
    assert(customer.save())
    
    }

    protected void tearDown() {
        super.tearDown()
    }

    void testSimpleStepBehaviour() {

    customer.subscribeDefault()
    assertEquals("One Subscription", 1, customer.subscriptions?.size())
    
    println(timeService.actions?.toString())
    timeService.updateTime() // not yet
    Thread.sleep(5000) // 10 seconds -> 1 hour sim time
    timeService.updateTime()
    
    assertFalse("Customer consumed power", customer.subscriptions?.totalUsage == 0)
    
    assertEquals("Tariff Transaction Created", 1, TariffTransaction.findByTxType(TariffTransactionType.CONSUME).count())
    
    Thread.sleep(5000) // 10 seconds -> 1 hour sim time
    timeService.updateTime()
    
    assertFalse("Customer consumed power", customer.subscriptions?.totalUsage == 0)
    
    assertEquals("Tariff Transaction Created", 2, TariffTransaction.findByTxType(TariffTransactionType.CONSUME).count())
    
  }
  
  void testRevokingSubscriptions(){
    
    customer.subscribeDefault()
    customer.save()

    // create some tariffs
    def tsc1 = new TariffSpecification(broker: broker1,
          expiration: new Instant(timeService.currentTime.millis + TimeService.DAY * 5),
          minDuration: TimeService.WEEK * 8, powerType: PowerType.CONSUMPTION)
    def tsc2 = new TariffSpecification(broker: broker1, 
          expiration: new Instant(timeService.currentTime.millis + TimeService.DAY * 7),
          minDuration: TimeService.WEEK * 8, powerType: PowerType.CONSUMPTION)
    def tsc3 = new TariffSpecification(broker: broker1, 
          expiration: new Instant(timeService.currentTime.millis + TimeService.DAY * 9),
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
    
    assertEquals("Four tariffs", 4, Tariff.count())
    
    customer.unsubscribe(tariffMarketService.getDefaultTariff(PowerType.CONSUMPTION),70)
    customer.subscribe(tc1, 23)
    customer.subscribe(tc2, 23)
    customer.subscribe(tc3, 24)
    customer.unsubscribe(tc1, 12)
    customer.unsubscribe(tc2, 11)
    customer.unsubscribe(tc3, 20)
    
    println(TariffSubscription.count())
    
    assertEquals("4 Subscriptions for customer",4, customer.subscriptions?.size())
    Thread.sleep(5000)
    timeService.updateTime()
    
    timeService.currentTime = new Instant(timeService.currentTime.millis + TimeService.HOUR)
    TariffRevoke tex = new TariffRevoke(tariffId: tsc2.id, broker: tc2.broker)
    def status = tariffMarketService.processTariff(tex)
    assertNotNull("non-null status", status)
    assertEquals("success", TariffStatus.Status.success, status.status)
    assertTrue("tariff revoked", tc2.isRevoked())

    // should now be just two active tariffs
    def tclist = tariffMarketService.getActiveTariffList(PowerType.CONSUMPTION)
    assertEquals("3 consumption tariffs", 3, tclist.size())

    // retrieve Charley's revoked-subscription list
    def revokedCustomer = tariffMarketService.getRevokedSubscriptionList(customer)
    assertEquals("one item in list", 1, revokedCustomer.size())
    assertEquals("it's the correct one", TariffSubscription.findByTariffAndCustomer(tc2,customer), revokedCustomer[0])

    customer.checkRevokedSubscriptions()

    println(TariffSubscription.count())
    assertEquals("3 Subscriptions for customer", 3, customer.subscriptions?.size())
    Thread.sleep(5000)
    timeService.updateTime()
    
    TariffRevoke tex3 = new TariffRevoke(tariffId: tsc3.id, broker: tsc3.broker)
    def status3 = tariffMarketService.processTariff(tex3)
    assertNotNull("non-null status", status3)
    assertEquals("success", TariffStatus.Status.success, status3.status)
    assertTrue("tariff revoked", tc3.isRevoked())

    // should now be just two active tariffs
    def tclist3 = tariffMarketService.getActiveTariffList(PowerType.CONSUMPTION)
    assertEquals("2 consumption tariffs", 2, tclist3.size())

    // retrieve Charley's revoked-subscription list
    def revokedCustomer3 = tariffMarketService.getRevokedSubscriptionList(customer)
    assertEquals("one item in list", 1, revokedCustomer3.size())
    assertEquals("it's the correct one", TariffSubscription.findByTariffAndCustomer(tc3,customer), revokedCustomer3[0])
    println(revokedCustomer3.toString())
    customer.checkRevokedSubscriptions()
     
    assertEquals("2 Subscriptions for customer", 2, customer.subscriptions?.size())
    Thread.sleep(5000)
    timeService.updateTime()
    
    TariffRevoke tex2 = new TariffRevoke(tariffId: tsc1.id, broker: tsc1.broker)
    def status2 = tariffMarketService.processTariff(tex2)
    assertNotNull("non-null status", status2)
    assertEquals("success", TariffStatus.Status.success, status2.status)
    assertTrue("tariff revoked", tc1.isRevoked())

    // should now be just two active tariffs
    def tclist2 = tariffMarketService.getActiveTariffList(PowerType.CONSUMPTION)
    assertEquals("1 consumption tariffs", 1, tclist2.size())

    // retrieve Charley's revoked-subscription list
    def revokedCustomer2 = tariffMarketService.getRevokedSubscriptionList(customer)
    assertEquals("one item in list", 1, revokedCustomer2.size())
    assertEquals("it's the correct one", TariffSubscription.findByTariffAndCustomer(tc1,customer), revokedCustomer2[0])
    println(revokedCustomer2.toString())
    customer.checkRevokedSubscriptions()
     
    assertEquals("1 Subscriptions for customer", 1, customer.subscriptions?.size())
  } 

  void testTariffPublication() {
    
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
  
    
    customer.subscribeDefault()
    customer.save()
     
    // current time is noon. Set pub interval to 3 hours.
    tariffMarketService.configuration.configuration['publicationInterval'] = '3' // hours
    assertEquals("newTariffs list is empty", 0, tariffMarketService.newTariffs.size())
  
    assertEquals("one registration", 1, tariffMarketService.registrations.size())
    assertEquals("no tariffs at 12:00", 0, customer.publishedTariffs.size())
    // publish some tariffs over a period of three hours, check for publication
    def tsc1 = new TariffSpecification(broker: broker1,
        expiration: new Instant(timeService.currentTime.millis + TimeService.DAY),
        minDuration: TimeService.WEEK * 8, powerType: PowerType.CONSUMPTION)
    Rate r1 = new Rate(value: 0.222)
    tsc1.addToRates(r1)
    tariffMarketService.processTariff(tsc1)
    
    Thread.sleep(5000)
    timeService.updateTime()
    // it's 13:00
    tariffMarketService.activate(timeService.currentTime, 2)
    assertEquals("no tariffs at 13:00", 0, customer.publishedTariffs.size())
    
    def tsc2 = new TariffSpecification(broker: broker1,
        expiration: new Instant(timeService.currentTime.millis + TimeService.DAY * 2),
        minDuration: TimeService.WEEK * 8, powerType: PowerType.CONSUMPTION)
    tsc2.addToRates(r1)
    tariffMarketService.processTariff(tsc2)
    def tsc3 = new TariffSpecification(broker: broker1,
        expiration: new Instant(timeService.currentTime.millis + TimeService.DAY * 3),
        minDuration: TimeService.WEEK * 8, powerType: PowerType.CONSUMPTION)
    tsc3.addToRates(r1)
    tariffMarketService.processTariff(tsc3)
    
    Thread.sleep(5000)
    timeService.updateTime()
    // it's 14:00
    tariffMarketService.activate(timeService.currentTime, 2)
    assertEquals("no tariffs at 14:00", 0, customer.publishedTariffs.size())
 
    def tsp1 = new TariffSpecification(broker: broker1,
        expiration: new Instant(timeService.currentTime.millis + TimeService.DAY),
        minDuration: TimeService.WEEK * 8, powerType: PowerType.PRODUCTION)
    def tsp2 = new TariffSpecification(broker: broker1,
        expiration: new Instant(timeService.currentTime.millis + TimeService.DAY * 2),
        minDuration: TimeService.WEEK * 8, powerType: PowerType.PRODUCTION)
    Rate r2 = new Rate(value: 0.119)
    tsp1.addToRates(r2)
    tsp2.addToRates(r2)
    tariffMarketService.processTariff(tsp1)
    tariffMarketService.processTariff(tsp2)
    assertEquals("six tariffs", 6, Tariff.count())
    
    Thread.sleep(5000)
    timeService.updateTime()
    // it's 15:00 - time to publish
    tariffMarketService.activate(timeService.currentTime, 2)
    assertEquals("5 tariffs at 15:00", 5, customer.publishedTariffs.size())
    assertEquals("newTariffs list is again empty", 0, tariffMarketService.newTariffs.size())
  }



}
