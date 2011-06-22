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
import org.powertac.common.AbstractCustomer
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
import org.powertac.common.enumerations.CustomerType
import org.powertac.common.enumerations.PowerType
import org.powertac.common.enumerations.TariffTransactionType
import org.powertac.common.msg.TariffRevoke
import org.powertac.common.msg.TariffStatus


class AbstractCustomerTests extends GroovyTestCase {
  def timeService  // autowire the time service
  def tariffMarketService // autowire the market
  def tariffMarketInitializationService

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

  protected void setUp()
  {
    super.setUp()

    // create a Competition, needed for initialization
    if (Competition.count() == 0) {
      comp = new Competition(name: 'abstract-customer-test')
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

    now = new DateTime(2011, 1, 10, 0, 0, 0, 0, DateTimeZone.UTC)
    timeService.currentTime = now.toInstant()

    // initialize the tariff market
    PluginConfig.findByRoleName('TariffMarket')?.delete()
    tariffMarketInitializationService.setDefaults()
    tariffMarketInitializationService.initialize(comp, ['AccountingService'])

    exp = new Instant(now.millis + TimeService.WEEK * 10)
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

    assertEquals("correct Default Tariff", defaultTariffSpec,
        tariffMarketService.getDefaultTariff(defaultTariffSpec.powerType).tariffSpec)
    assertEquals("One Tariff", 1, Tariff.count())
  }

  protected void tearDown() {
    super.tearDown()
  }

  void testCreationAndSubscriptionToDefault()
  {
    customerInfo = new CustomerInfo(name:"Anty", customerType: CustomerType.CustomerHousehold,
        powerTypes: [PowerType.CONSUMPTION])
    if (!customerInfo.validate()) {
      customerInfo.errors.each { println it.toString() }
      fail("Could not save customerInfo")
    }
    assert(customerInfo.save())

    customer = new AbstractCustomer(CustomerInfo: customerInfo)
    customer.init()
    customer.subscribeDefault()
    if (!customer.validate()) {
      customer.errors.each { println it.toString() }
      fail("Could not save customer")
    }
    assert(customer.save())
  }

  void testPowerConsumption()
  {
    customerInfo = new CustomerInfo(name:"Anty", customerType: CustomerType.CustomerHousehold,
        powerTypes: [PowerType.CONSUMPTION])
    customerInfo.save()
    customer = new AbstractCustomer(CustomerInfo: customerInfo)
    customer.init()
    customer.subscribeDefault()
    customer.save()

    timeService.setCurrentTime(new Instant(now.millis + (TimeService.HOUR)))
    customer.consumePower()

    assertFalse("Customer consumed power", customer.subscriptions?.totalUsage == 0)

    assertEquals("Tariff Transaction Created", 1,
        TariffTransaction.findByTxType(TariffTransactionType.CONSUME).count())
  }

  void testChangingSubscriptions()
  {
    customerInfo = new CustomerInfo(name:"Anty", customerType: CustomerType.CustomerHousehold,
        powerTypes: [PowerType.CONSUMPTION])
    customerInfo.save()
    customer = new AbstractCustomer(CustomerInfo: customerInfo)
    customer.init()
    customer.subscribeDefault()
    customer.save()

    def tsc1 = new TariffSpecification(broker: broker2,
        expiration: new Instant(now.millis + TimeService.DAY),
        minDuration: TimeService.WEEK * 8, powerType: PowerType.CONSUMPTION)
    def tsc2 = new TariffSpecification(broker: broker2,
        expiration: new Instant(now.millis + TimeService.DAY * 2),
        minDuration: TimeService.WEEK * 8, powerType: PowerType.CONSUMPTION)
    def tsc3 = new TariffSpecification(broker: broker2,
        expiration: new Instant(now.millis + TimeService.DAY * 3),
        minDuration: TimeService.WEEK * 8, powerType: PowerType.CONSUMPTION)
    Rate r2 = new Rate(value: 0.222)
    tsc1.addToRates(r2)
    tsc2.addToRates(r2)
    tsc3.addToRates(r2)
    tariffMarketService.processTariff(tsc1)
    tariffMarketService.processTariff(tsc2)
    tariffMarketService.processTariff(tsc3)

    assertEquals("Four tariffs", 4, Tariff.count())

    customer.changeSubscription(tariffMarketService.getDefaultTariff(defaultTariffSpec.powerType))

    List<Tariff> lastTariff = customer.subscriptions?.tariff

    lastTariff.each { tariff ->
      customer.changeSubscription(tariff,tariffMarketService.getDefaultTariff(defaultTariffSpec.powerType))
      customer.changeSubscription(tariffMarketService.getDefaultTariff(defaultTariffSpec.powerType), tariff, 5)
    }
    assertFalse("Changed from default tariff", customer.subscriptions?.tariff.toString() == tariffMarketService.getDefaultTariff(defaultTariffSpec.powerType).toString())
  }

  void testRevokingSubscriptions()
  {
    customerInfo = new CustomerInfo(name:"Anty", customerType: CustomerType.CustomerHousehold, powerTypes: [PowerType.CONSUMPTION])
    customerInfo.save()
    customer = new AbstractCustomer(CustomerInfo: customerInfo)
    customer.init()
    customer.subscribeDefault()
    customer.save()

    println(TariffSubscription.count())

    // create some tariffs
    def tsc1 = new TariffSpecification(broker: broker1,
        expiration: new Instant(now.millis + TimeService.DAY * 5),
        minDuration: TimeService.WEEK * 8, powerType: PowerType.CONSUMPTION)
    def tsc2 = new TariffSpecification(broker: broker1,
        expiration: new Instant(now.millis + TimeService.DAY * 7),
        minDuration: TimeService.WEEK * 8, powerType: PowerType.CONSUMPTION)
    def tsc3 = new TariffSpecification(broker: broker1,
        expiration: new Instant(now.millis + TimeService.DAY * 9),
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

    // make sure we have three active tariffs
    def tclist = tariffMarketService.getActiveTariffList(PowerType.CONSUMPTION)
    assertEquals("4 consumption tariffs", 4, tclist.size())
    assertEquals("three transaction", 3, TariffTransaction.count())

    TariffSubscription tsd =
        TariffSubscription.findByTariffAndCustomer(tariffMarketService.getDefaultTariff(PowerType.CONSUMPTION), customer)
    customer.unsubscribe(tsd,70)
    customer.subscribe(tc1, 23)
    customer.subscribe(tc2, 23)
    customer.subscribe(tc3, 24)
    TariffSubscription ts1 =
        TariffSubscription.findByTariffAndCustomer(tc1, customer)
    customer.unsubscribe(ts1, 12)
    TariffSubscription ts2 =
        TariffSubscription.findByTariffAndCustomer(tc2, customer)
    customer.unsubscribe(ts2, 11)
    TariffSubscription ts3 =
        TariffSubscription.findByTariffAndCustomer(tc3, customer)
    customer.unsubscribe(ts3, 20)

    println(TariffSubscription.count())

    assertEquals("4 Subscriptions for customer",3, customer.subscriptions?.size())

    timeService.currentTime = new Instant(timeService.currentTime.millis + TimeService.HOUR)
    TariffRevoke tex = new TariffRevoke(tariffId: tsc2.id, broker: tc2.broker)
    def status = tariffMarketService.processTariff(tex)
    assertNotNull("non-null status", status)
    assertEquals("success", TariffStatus.Status.success, status.status)
    assertTrue("tariff revoked", tc2.isRevoked())

    // should now be just two active tariffs
    tclist = tariffMarketService.getActiveTariffList(PowerType.CONSUMPTION)
    assertEquals("3 consumption tariffs", 3, tclist.size())

    // retrieve Charley's revoked-subscription list
    def revokedCustomer = tariffMarketService.getRevokedSubscriptionList(customer)
    assertEquals("one item in list", 1, revokedCustomer.size())
    assertEquals("it's the correct one", TariffSubscription.findByTariffAndCustomer(tc2,customer), revokedCustomer[0])

    customer.checkRevokedSubscriptions()

    println(TariffSubscription.count())
    assertEquals("3 Subscriptions for customer", 3, customer.subscriptions?.size())

    TariffRevoke tex3 = new TariffRevoke(tariffId: tsc3.id, broker: tc1.broker)
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

    TariffRevoke tex2 = new TariffRevoke(tariffId: tsc1.id, broker: tc1.broker)
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

}
