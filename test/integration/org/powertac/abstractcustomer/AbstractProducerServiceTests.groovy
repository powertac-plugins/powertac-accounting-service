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

package org.powertac.abstractcustomer

import grails.test.*

import org.joda.time.DateTime
import org.joda.time.DateTimeZone
import org.joda.time.Instant
import org.powertac.common.AbstractProducer
import org.powertac.common.Broker
import org.powertac.common.Competition
import org.powertac.common.CustomerInfo
import org.powertac.common.PluginConfig
import org.powertac.common.Rate
import org.powertac.common.Tariff
import org.powertac.common.TariffSpecification
import org.powertac.common.TariffSubscription
import org.powertac.common.TariffTransaction
import org.powertac.common.TimeService
import org.powertac.common.enumerations.PowerType
import org.powertac.common.enumerations.TariffTransactionType
import org.powertac.common.interfaces.CompetitionControl
import org.powertac.common.msg.TariffRevoke
import org.powertac.common.msg.TariffStatus


class AbstractProducerServiceTests extends GroovyTestCase {

  def timeService
  def tariffMarketService
  def tariffMarketInitializationService
  def abstractProducerService
  def abstractProducerInitializationService

  Competition comp
  Tariff tariff
  TariffSpecification defaultTariffSpec
  Broker broker1
  Broker broker2
  Instant exp
  Instant start
  Instant now

  protected void setUp() {
    super.setUp()
    PluginConfig.findByRoleName('AbstractProducer')?.delete()

    // create a Competition, needed for initialization
    if (Competition.count() == 0) {
      comp = new Competition(name: 'abstract-producer-service-test')
      assert comp.save()
    }
    else {
      comp = Competition.list().first()
    }

    TariffSpecification.list()*.delete()
    Tariff.list()*.delete()
    //Broker.list()*.delete()
    Broker.findByUsername('Joe')?.delete()
    broker1 = new Broker(username: "Joe")
    broker1.save()
    Broker.findByUsername('Anna')?.delete()
    broker2 = new Broker(username: "Anna")
    broker2.save()

    now = new DateTime(2011, 1, 1, 12, 0, 0, 0, DateTimeZone.UTC).toInstant()
    timeService.currentTime = now
    timeService.base = now.millis

    // initialize the tariff market
    PluginConfig.findByRoleName('TariffMarket')?.delete()
    tariffMarketInitializationService.setDefaults()
    tariffMarketInitializationService.initialize(comp, ['AccountingService'])

    exp = new Instant(now.millis + TimeService.WEEK * 10)
    TariffSpecification tariffSpec =
        new TariffSpecification(broker: broker1,
        expiration: exp,powerType:PowerType.PRODUCTION,
        minDuration: TimeService.WEEK * 8)
    tariffSpec.addToRates(new Rate(value: 0.121))
    tariffSpec.save()

    defaultTariffSpec = new TariffSpecification(broker: broker1,
        expiration: exp,powerType:PowerType.PRODUCTION,
        minDuration: TimeService.WEEK * 8)
    defaultTariffSpec.addToRates(new Rate(value: 0.222))
    defaultTariffSpec.save()

    tariffMarketService.setDefaultTariff(defaultTariffSpec)

    assertEquals("correct Default Tariff", defaultTariffSpec,
        tariffMarketService.getDefaultTariff(defaultTariffSpec.powerType).tariffSpec)
    assertEquals("One Tariff", 1, Tariff.count())

  }

  protected void tearDown() {
    super.tearDown()
  }

  void initializeService () {
    abstractProducerInitializationService.setDefaults()
    PluginConfig config = PluginConfig.findByRoleName('AbstractProducer')
    config.configuration['numberOfProducers'] = '2'
    abstractProducerInitializationService.initialize(comp, [
      'TariffMarket',
      'DefaultBroker'
    ])
  }


  void testNormalInitialization () {

    abstractProducerInitializationService.setDefaults()
    PluginConfig config = PluginConfig.findByRoleName('AbstractProducer')
    assertNotNull("config created correctly", config)
    def result = abstractProducerInitializationService.initialize(comp, [
      'TariffMarket',
      'DefaultBroker'
    ])
    assertEquals("correct return value", 'AbstractProducer', result)
    //assertEquals("correct number of customers", 0, AbstractProducer.count())
  }

  void testBogusInitialization () {
    PluginConfig config = PluginConfig.findByRoleName('AbstractProducer')
    assertNull("config not created", config)
    def result = abstractProducerInitializationService.initialize(comp, [
      'TariffMarket',
      'DefaultBroker'
    ])
    assertEquals("failure return value", 'fail', result)
  }

  void testServiceInitialization() {
    initializeService()
    assertEquals("Two Customers Created", 2, AbstractProducer.count())
    // customers no longer subscribe at init()
    tariffMarketService.publishTariffs()
    assertFalse("Producer 1 subscribed", AbstractProducer.findByCustomerInfo(CustomerInfo.findByName("Producer 1")).subscriptions == null)
    assertFalse("Producer 2 subscribed", AbstractProducer.findByCustomerInfo(CustomerInfo.findByName("Producer 2")).subscriptions == null)
    assertFalse("Producer 1 subscribed to default", AbstractProducer.findByCustomerInfo(CustomerInfo.findByName("Producer 1")).subscriptions == tariffMarketService.getDefaultTariff(PowerType.PRODUCTION))
    assertFalse("Producer 2 subscribed to default", AbstractProducer.findByCustomerInfo(CustomerInfo.findByName("Producer 2")).subscriptions == tariffMarketService.getDefaultTariff(PowerType.PRODUCTION))
  }


  void testPowerConsumption() {
    initializeService()
    timeService.setCurrentTime(new Instant(now.millis + (TimeService.HOUR)))
    abstractProducerService.activate(timeService.currentTime, 1)
    AbstractProducer.list().each { producer ->
      assertFalse("Customer produced power", producer.subscriptions?.totalUsage == null || producer.subscriptions?.totalUsage == 0)
    }
    assertEquals("Tariff Transactions Created", AbstractProducer.count(), TariffTransaction.findByTxType(TariffTransactionType.PRODUCE).count())
  }


  void testChangingSubscriptions() {
    initializeService()
    def tsc1 = new TariffSpecification(broker: broker2,
        expiration: new Instant(now.millis + TimeService.DAY),
        minDuration: TimeService.WEEK * 8, powerType: PowerType.PRODUCTION)
    def tsc2 = new TariffSpecification(broker: broker2,
        expiration: new Instant(now.millis + TimeService.DAY * 2),
        minDuration: TimeService.WEEK * 8, powerType: PowerType.PRODUCTION)
    def tsc3 = new TariffSpecification(broker: broker2,
        expiration: new Instant(now.millis + TimeService.DAY * 3),
        minDuration: TimeService.WEEK * 8, powerType: PowerType.PRODUCTION)
    Rate r2 = new Rate(value: 0.222)
    tsc1.addToRates(r2)
    tsc2.addToRates(r2)
    tsc3.addToRates(r2)
    tariffMarketService.processTariff(tsc1)
    tariffMarketService.processTariff(tsc2)
    tariffMarketService.processTariff(tsc3)
    assertEquals("Five tariff specifications", 5, TariffSpecification.count())
    assertEquals("Four tariffs", 4, Tariff.count())
    AbstractProducer.list().each {producer ->
      producer.changeSubscription(tariffMarketService.getDefaultTariff(defaultTariffSpec.powerType))
      List<Tariff> lastTariff = producer.subscriptions?.tariff
      lastTariff.each { tariff ->
        producer.changeSubscription(tariff,tariffMarketService.getDefaultTariff(defaultTariffSpec.powerType))
        producer.changeSubscription(tariffMarketService.getDefaultTariff(defaultTariffSpec.powerType), tariff, 5)
      }
      assertFalse("Changed from default tariff", producer.subscriptions?.tariff.toString() == tariffMarketService.getDefaultTariff(defaultTariffSpec.powerType).toString())
    }
  }


  void testRevokingSubscriptions() {
    initializeService()
    println("Number Of Subscriptions in DB: ${TariffSubscription.count()}")
    // create some tariffs
    def tsc1 = new TariffSpecification(broker: broker1,
        expiration: new Instant(now.millis + TimeService.DAY * 5),
        minDuration: TimeService.WEEK * 8, powerType: PowerType.PRODUCTION)
    def tsc2 = new TariffSpecification(broker: broker1,
        expiration: new Instant(now.millis + TimeService.DAY * 7),
        minDuration: TimeService.WEEK * 8, powerType: PowerType.PRODUCTION)
    def tsc3 = new TariffSpecification(broker: broker1,
        expiration: new Instant(now.millis + TimeService.DAY * 9),
        minDuration: TimeService.WEEK * 8, powerType: PowerType.PRODUCTION)
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
    // make sure we have three active tariffs
    def tclist = tariffMarketService.getActiveTariffList(PowerType.PRODUCTION)
    assertEquals("4 production tariffs", 4, tclist.size())
    assertEquals("Three transaction", 3, TariffTransaction.count())
    // householdCustomerService.activate(timeService.currentTime, 1)
    AbstractProducer.list().each{ producer ->
      TariffSubscription tsd =
          TariffSubscription.findByTariffAndCustomer(tariffMarketService.getDefaultTariff(PowerType.CONSUMPTION), producer)
      producer.subscribe(tc1, 3)
      producer.subscribe(tc2, 3)
      producer.subscribe(tc3, 4)
      TariffSubscription ts1 =
          TariffSubscription.findByTariffAndCustomer(tc1, producer)
      producer.unsubscribe(ts1, 2)
      TariffSubscription ts2 =
          TariffSubscription.findByTariffAndCustomer(tc2, producer)
      producer.unsubscribe(ts2, 1)
      TariffSubscription ts3 =
          TariffSubscription.findByTariffAndCustomer(tc3, producer)
      producer.unsubscribe(ts3, 2)
      println("Number Of Subscriptions in DB: ${TariffSubscription.count()}")
      assertEquals("4 Subscriptions for producer",4, producer.subscriptions?.size())

      timeService.currentTime = new Instant(timeService.currentTime.millis + TimeService.HOUR)
    }

    TariffRevoke tex = new TariffRevoke(tariffId: tsc2.id, broker: tc2.broker)
    def status = tariffMarketService.processTariff(tex)
    assertNotNull("non-null status", status)
    assertEquals("success", TariffStatus.Status.success, status.status)
    assertTrue("tariff revoked", tc2.isRevoked())
    // should now be just two active tariffs
    tclist = tariffMarketService.getActiveTariffList(PowerType.PRODUCTION)
    assertEquals("3 production tariffs", 3, tclist.size())
    AbstractProducer.list().each{ producer ->
      // retrieve revoked-subscription list
      def revokedCustomer = tariffMarketService.getRevokedSubscriptionList(producer)
      assertEquals("one item in list", 1, revokedCustomer.size())
      assertEquals("it's the correct one", TariffSubscription.findByTariffAndCustomer(tc2,producer), revokedCustomer[0])
    }
    abstractProducerService.activate(timeService.currentTime, 1)
    AbstractProducer.list().each{ producer ->
      assertEquals("3 Subscriptions for producer", 3, producer.subscriptions?.size())
    }

    println("Number Of Subscriptions in DB: ${TariffSubscription.count()}")
    TariffRevoke tex3 = new TariffRevoke(tariffId: tsc3.id, broker: tc1.broker)
    def status3 = tariffMarketService.processTariff(tex3)
    assertNotNull("non-null status", status3)
    assertEquals("success", TariffStatus.Status.success, status3.status)
    assertTrue("tariff revoked", tc3.isRevoked())
    // should now be just two active tariffs
    def tclist3 = tariffMarketService.getActiveTariffList(PowerType.PRODUCTION)
    assertEquals("2 production tariffs", 2, tclist3.size())
    // retrieve revoked-subscription list
    AbstractProducer.list().each{ producer ->
      def revokedCustomer3 = tariffMarketService.getRevokedSubscriptionList(producer)
      assertEquals("one item in list", 1, revokedCustomer3.size())
      assertEquals("it's the correct one", TariffSubscription.findByTariffAndCustomer(tc3,producer), revokedCustomer3[0])
      log.info "Revoked Tariffs ${revokedCustomer3.toString()} "
    }
    abstractProducerService.activate(timeService.currentTime, 1)
    AbstractProducer.list().each{ producer ->
      assertEquals("2 Subscriptions for producer", 2, producer.subscriptions?.size())
    }

    TariffRevoke tex2 = new TariffRevoke(tariffId: tsc1.id, broker: tc1.broker)
    def status2 = tariffMarketService.processTariff(tex2)
    assertNotNull("non-null status", status2)
    assertEquals("success", TariffStatus.Status.success, status2.status)
    assertTrue("tariff revoked", tc1.isRevoked())
    // should now be just two active tariffs
    def tclist2 = tariffMarketService.getActiveTariffList(PowerType.PRODUCTION)
    assertEquals("1 production tariffs", 1, tclist2.size())
    AbstractProducer.list().each{ producer ->
      // retrieve revoked-subscription list
      def revokedCustomer2 = tariffMarketService.getRevokedSubscriptionList(producer)
      assertEquals("one item in list", 1, revokedCustomer2.size())
      assertEquals("it's the correct one", TariffSubscription.findByTariffAndCustomer(tc1,producer), revokedCustomer2[0])
      log.info "Revoked Tariffs ${revokedCustomer2.toString()} "
    }
    abstractProducerService.activate(timeService.currentTime, 1)
    AbstractProducer.list().each{ producer ->
      assertEquals("1 Subscriptions for producer", 1, producer.subscriptions?.size())
    }
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
    tariffMarketService.registrations = []
    tariffMarketService.competitionControlService = competitionControlService
    //tariffMarketService.afterPropertiesSet()
    tariffMarketInitializationService.initialize(comp, ['AccountingService'])
    //assertEquals("correct thing", tariffMarketService, registrationThing)
    assertEquals("correct phase", tariffMarketService.simulationPhase, registrationPhase)
    start = new DateTime(2011, 1, 1, 12, 0, 0, 0, DateTimeZone.UTC).toInstant()
    initializeService()
    // current time is noon. Set pub interval to 3 hours.
    tariffMarketService.publicationInterval = 3 // hours
    assertEquals("one registration", 1, tariffMarketService.registrations.size())
    // publish some tariffs over a period of three hours, check for publication
    def tsc1 = new TariffSpecification(broker: broker1,
        expiration: new Instant(start.millis + TimeService.DAY),
        minDuration: TimeService.WEEK * 8, powerType: PowerType.PRODUCTION)
    Rate r1 = new Rate(value: 0.222)
    tsc1.addToRates(r1)
    tariffMarketService.processTariff(tsc1)
    timeService.currentTime += TimeService.HOUR
    // it's 13:00
    abstractProducerService.activate(timeService.currentTime, 1)
    abstractProducerService.activate(timeService.currentTime, 2)
    tariffMarketService.activate(timeService.currentTime, 2)
    def tsc2 = new TariffSpecification(broker: broker1,
        expiration: new Instant(start.millis + TimeService.DAY * 2),
        minDuration: TimeService.WEEK * 8, powerType: PowerType.PRODUCTION)
    tsc2.addToRates(r1)
    tariffMarketService.processTariff(tsc2)
    def tsc3 = new TariffSpecification(broker: broker1,
        expiration: new Instant(start.millis + TimeService.DAY * 3),
        minDuration: TimeService.WEEK * 8, powerType: PowerType.PRODUCTION)
    tsc3.addToRates(r1)
    tariffMarketService.processTariff(tsc3)
    timeService.currentTime += TimeService.HOUR
    // it's 14:00
    abstractProducerService.activate(timeService.currentTime, 1)
    abstractProducerService.activate(timeService.currentTime, 2)
    tariffMarketService.activate(timeService.currentTime, 2)
    def tsp1 = new TariffSpecification(broker: broker1,
        expiration: new Instant(start.millis + TimeService.DAY),
        minDuration: TimeService.WEEK * 8, powerType: PowerType.PRODUCTION)
    def tsp2 = new TariffSpecification(broker: broker1,
        expiration: new Instant(start.millis + TimeService.DAY * 2),
        minDuration: TimeService.WEEK * 8, powerType: PowerType.PRODUCTION)
    Rate r2 = new Rate(value: 0.119)
    tsp1.addToRates(r2)
    tsp2.addToRates(r2)
    tariffMarketService.processTariff(tsp1)
    tariffMarketService.processTariff(tsp2)
    assertEquals("six tariffs", 6, Tariff.count())
    timeService.currentTime += TimeService.HOUR
    // it's 15:00 - time to publish
    abstractProducerService.activate(timeService.currentTime, 1)
    tariffMarketService.activate(timeService.currentTime, 2)
    abstractProducerService.activate(timeService.currentTime, 2)
    assertEquals("newTariffs list is again empty", 0, Tariff.findAllByState(Tariff.State.PENDING).size())
  }

  void testEvaluatingTariffs() {
    initializeService()
    println("Number Of Subscriptions in DB: ${TariffSubscription.count()}")
    // create some tariffs
    def tsc1 = new TariffSpecification(broker: broker1,
        expiration: new Instant(now.millis + TimeService.DAY * 5),
        minDuration: TimeService.WEEK * 8, powerType: PowerType.PRODUCTION)
    def tsc2 = new TariffSpecification(broker: broker1,
        expiration: new Instant(now.millis + TimeService.DAY * 7),
        minDuration: TimeService.WEEK * 8, powerType: PowerType.PRODUCTION)
    def tsc3 = new TariffSpecification(broker: broker1,
        expiration: new Instant(now.millis + TimeService.DAY * 9),
        minDuration: TimeService.WEEK * 8, powerType: PowerType.PRODUCTION)
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
    // make sure we have three active tariffs
    def tclist = tariffMarketService.getActiveTariffList(PowerType.PRODUCTION)
    assertEquals("4 production tariffs", 4, tclist.size())
    assertEquals("three transaction", 3, TariffTransaction.count())
    AbstractProducer.list().each{ producer ->
      producer.possibilityEvaluationNewTariffs(Tariff.list())
    }
  }


}

