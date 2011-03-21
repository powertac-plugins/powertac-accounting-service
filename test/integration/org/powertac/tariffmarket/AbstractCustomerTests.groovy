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


class AbstractCustomerTests extends GroovyTestCase 
{
  def timeService  // autowire the time service
  def tariffMarketService // autowire the market

  Tariff tariff
  TariffSpecification defaultTariffSpec
  Broker broker1
  Broker broker2
  Instant exp
  CustomerInfo customerInfo
  AbstractCustomer customer
  DateTime now
  int idCount = 0

  protected void setUp()
  {
    super.setUp()

    broker1 = new Broker(username: "Joe")
    broker1.save()
    broker2 = new Broker(username: "Anna")
    broker2.save()

    now = new DateTime(2011, 1, 10, 0, 0, 0, 0, DateTimeZone.UTC)
    timeService.currentTime = now.toInstant()

    exp = new Instant(now.millis + TimeService.WEEK * 10)
    TariffSpecification tariffSpec =
        new TariffSpecification(brokerId: broker1.getId(),
        expiration: exp,
        minDuration: TimeService.WEEK * 8)
    tariffSpec.addToRates(new Rate(value: 0.121))
    tariffSpec.save()

    defaultTariffSpec = new TariffSpecification(brokerId: broker1.getId(),
        expiration: exp,
        minDuration: TimeService.WEEK * 8)
    defaultTariffSpec.addToRates(new Rate(value: 0.222))
    defaultTariffSpec.save()

    tariffMarketService.setDefaultTariff(defaultTariffSpec)

    assertEquals("correct Default Tariff", defaultTariffSpec, tariffMarketService.getDefaultTariff(defaultTariffSpec.powerType).tariffSpec)
    assertEquals("One Tariff", 1, Tariff.count())
  }

  protected void tearDown() {
    super.tearDown()
  }

  void testCreationAndSubscriptionToDefault() 
  {
    customerInfo = new CustomerInfo(name:"Anty", customerType: CustomerType.CustomerHousehold, powerType: PowerType.CONSUMPTION)
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
    customerInfo = new CustomerInfo(name:"Anty", customerType: CustomerType.CustomerHousehold, powerType: PowerType.CONSUMPTION)
    customerInfo.save()
    customer = new AbstractCustomer(CustomerInfo: customerInfo)
    customer.init()
    customer.subscribeDefault()
    customer.save()

    timeService.setCurrentTime(new Instant(now.millis + (TimeService.HOUR)))
    customer.consumePower()

    assertFalse("Customer consumed power", customer.subscriptions?.totalUsage == 0)

    assertEquals("Tariff Transaction Created", 1, TariffTransaction.findByTxType(TariffTransactionType.CONSUME).count())
  }

  void testChangingSubscriptions()
  {
    customerInfo = new CustomerInfo(name:"Anty", customerType: CustomerType.CustomerHousehold, powerType: PowerType.CONSUMPTION)
    customerInfo.save()
    customer = new AbstractCustomer(CustomerInfo: customerInfo)
    customer.init()
    customer.subscribeDefault()
    customer.save()

    def tsc1 = new TariffSpecification(brokerId: broker2.id,
        expiration: new Instant(now.millis + TimeService.DAY),
        minDuration: TimeService.WEEK * 8, powerType: PowerType.CONSUMPTION)
    def tsc2 = new TariffSpecification(brokerId: broker2.id,
        expiration: new Instant(now.millis + TimeService.DAY * 2),
        minDuration: TimeService.WEEK * 8, powerType: PowerType.CONSUMPTION)
    def tsc3 = new TariffSpecification(brokerId: broker2.id,
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

    customer.changeSubscription(true)

    assertFalse("Changed from default tariff", customer.subscriptions?.toString() == tariffMarketService.getDefaultTariff(defaultTariffSpec.powerType).toString())
  }

  void testRevokingSubscriptions()
  {

    customerInfo = new CustomerInfo(name:"Anty", customerType: CustomerType.CustomerHousehold, powerType: PowerType.CONSUMPTION)
    customerInfo.save()
    customer = new AbstractCustomer(CustomerInfo: customerInfo)
    customer.init()
    customer.subscribeDefault()
    customer.save()

    def tsc1 = new TariffSpecification(brokerId: broker2.id,
        expiration: new Instant(now.millis + TimeService.DAY),
        minDuration: TimeService.WEEK * 8, powerType: PowerType.CONSUMPTION)
    def tsc2 = new TariffSpecification(brokerId: broker2.id,
        expiration: new Instant(now.millis + TimeService.DAY * 2),
        minDuration: TimeService.WEEK * 8, powerType: PowerType.CONSUMPTION)
    def tsc3 = new TariffSpecification(brokerId: broker2.id,
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
  }
}
