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
import org.powertac.common.enumerations.PowerType
import org.powertac.common.enumerations.TariffTransactionType
import org.powertac.common.interfaces.CompetitionControl
import org.powertac.common.msg.TariffRevoke
import org.powertac.common.msg.TariffStatus


class AbstractCustomerServiceTests extends GroovyTestCase {

 def timeService
 def tariffMarketService
 def tariffMarketInitializationService
 def abstractCustomerService
 def abstractCustomerInitializationService

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
   PluginConfig.findByRoleName('AbstractCustomer')?.delete()

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

 void initializeService () {
   abstractCustomerInitializationService.setDefaults()
   PluginConfig config = PluginConfig.findByRoleName('AbstractCustomer')
   config.configuration['numberOfCustomers'] = '2'
   abstractCustomerInitializationService.initialize(comp, ['TariffMarket', 'DefaultBroker'])
 }

 void testNormalInitialization () {

   abstractCustomerInitializationService.setDefaults()
   PluginConfig config = PluginConfig.findByRoleName('AbstractCustomer')
   assertNotNull("config created correctly", config)
   def result = abstractCustomerInitializationService.initialize(comp, ['TariffMarket', 'DefaultBroker'])
   assertEquals("correct return value", 'AbstractCustomer', result)
   //assertEquals("correct number of customers", 2, abstractCustomerService.numberOfCustomers)
 }

 void testBogusInitialization () {

   PluginConfig config = PluginConfig.findByRoleName('AbstractCustomer')
   assertNull("config not created", config)
   def result = abstractCustomerInitializationService.initialize(comp, ['TariffMarket', 'DefaultBroker'])
   assertEquals("failure return value", 'fail', result)
 }

 void testServiceInitialization() {
   initializeService()

   assertEquals("Two Customers Created", 2, AbstractCustomer.count())
   assertFalse("Customer 1 subscribed", AbstractCustomer.findByCustomerInfo(CustomerInfo.findByName("Customer 1")).subscriptions == null)
   assertFalse("Customer 2 subscribed", AbstractCustomer.findByCustomerInfo(CustomerInfo.findByName("Customer 2")).subscriptions == null)
   assertFalse("Customer 1 subscribed to default", AbstractCustomer.findByCustomerInfo(CustomerInfo.findByName("Customer 1")).subscriptions == tariffMarketService.getDefaultTariff(PowerType.CONSUMPTION))
   assertFalse("Customer 2 subscribed to default", AbstractCustomer.findByCustomerInfo(CustomerInfo.findByName("Customer 2")).subscriptions == tariffMarketService.getDefaultTariff(PowerType.CONSUMPTION))
 }

 void testPowerConsumption() {
   initializeService()

   timeService.setCurrentTime(new Instant(now.millis + (TimeService.HOUR)))
   abstractCustomerService.activate(timeService.currentTime, 1)
   AbstractCustomer.list().each { customer ->
     assertFalse("Customer consumed power", customer.subscriptions?.totalUsage == null || customer.subscriptions?.totalUsage == 0)
   }
   assertEquals("Tariff Transactions Created", AbstractCustomer.count(), TariffTransaction.findByTxType(TariffTransactionType.CONSUME).count())
 }

 void testChangingSubscriptions() {
   initializeService()
   
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
   assertEquals("Five tariff specifications", 5, TariffSpecification.count())
   assertEquals("Four tariffs", 4, Tariff.count())
   AbstractCustomer.list().each {customer ->
     customer.changeSubscription(tariffMarketService.getDefaultTariff(defaultTariffSpec.powerType), true)
     assertFalse("Changed from default tariff", customer.subscriptions?.tariff.toString() == tariffMarketService.getDefaultTariff(defaultTariffSpec.powerType).toString())
   }
 }

 void testRevokingSubscriptions() {

   initializeService()

   println("Number Of Subscriptions in DB: ${TariffSubscription.count()}")
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

   // householdCustomerService.activate(timeService.currentTime, 1)

   AbstractCustomer.list().each{ customer ->
     customer.unsubscribe(tariffMarketService.getDefaultTariff(PowerType.CONSUMPTION),3)
     customer.subscribe(tc1, 3)
     customer.subscribe(tc2, 3)
     customer.subscribe(tc3, 4)
     customer.unsubscribe(tc1, 2)
     customer.unsubscribe(tc2, 1)
     customer.unsubscribe(tc3, 2)
     println("Number Of Subscriptions in DB: ${TariffSubscription.count()}")
     assertEquals("4 Subscriptions for customer",4, customer.subscriptions?.size())
     timeService.currentTime = new Instant(timeService.currentTime.millis + TimeService.HOUR)
   }

   TariffRevoke tex = new TariffRevoke(tariffId: tsc2.id, broker: tc2.broker)
   def status = tariffMarketService.processTariff(tex)
   assertNotNull("non-null status", status)
   assertEquals("success", TariffStatus.Status.success, status.status)
   assertTrue("tariff revoked", tc2.isRevoked())

   // should now be just two active tariffs
   tclist = tariffMarketService.getActiveTariffList(PowerType.CONSUMPTION)
   assertEquals("3 consumption tariffs", 3, tclist.size())

   AbstractCustomer.list().each{ customer ->
     // retrieve revoked-subscription list
     def revokedCustomer = tariffMarketService.getRevokedSubscriptionList(customer)
     assertEquals("one item in list", 1, revokedCustomer.size())
     assertEquals("it's the correct one", TariffSubscription.findByTariffAndCustomer(tc2,customer), revokedCustomer[0])
   }

   abstractCustomerService.activate(timeService.currentTime, 1)

   AbstractCustomer.list().each{ customer ->
     assertEquals("3 Subscriptions for customer", 3, customer.subscriptions?.size())
   }

   println("Number Of Subscriptions in DB: ${TariffSubscription.count()}")

   TariffRevoke tex3 = new TariffRevoke(tariffId: tsc3.id, broker: tc1.broker)
   def status3 = tariffMarketService.processTariff(tex3)
   assertNotNull("non-null status", status3)
   assertEquals("success", TariffStatus.Status.success, status3.status)
   assertTrue("tariff revoked", tc3.isRevoked())

   // should now be just two active tariffs
   def tclist3 = tariffMarketService.getActiveTariffList(PowerType.CONSUMPTION)
   assertEquals("2 consumption tariffs", 2, tclist3.size())
   // retrieve revoked-subscription list

   AbstractCustomer.list().each{ customer ->
     def revokedCustomer3 = tariffMarketService.getRevokedSubscriptionList(customer)
     assertEquals("one item in list", 1, revokedCustomer3.size())
     assertEquals("it's the correct one", TariffSubscription.findByTariffAndCustomer(tc3,customer), revokedCustomer3[0])
     log.info "Revoked Tariffs ${revokedCustomer3.toString()} "
   }
   abstractCustomerService.activate(timeService.currentTime, 1)

   AbstractCustomer.list().each{ customer ->
     assertEquals("2 Subscriptions for customer", 2, customer.subscriptions?.size())
   }


   TariffRevoke tex2 = new TariffRevoke(tariffId: tsc1.id, broker: tc1.broker)
   def status2 = tariffMarketService.processTariff(tex2)
   assertNotNull("non-null status", status2)
   assertEquals("success", TariffStatus.Status.success, status2.status)
   assertTrue("tariff revoked", tc1.isRevoked())

   // should now be just two active tariffs
   def tclist2 = tariffMarketService.getActiveTariffList(PowerType.CONSUMPTION)
   assertEquals("1 consumption tariffs", 1, tclist2.size())

   AbstractCustomer.list().each{ customer ->
     // retrieve revoked-subscription list
     def revokedCustomer2 = tariffMarketService.getRevokedSubscriptionList(customer)
     assertEquals("one item in list", 1, revokedCustomer2.size())
     assertEquals("it's the correct one", TariffSubscription.findByTariffAndCustomer(tc1,customer), revokedCustomer2[0])
     log.info "Revoked Tariffs ${revokedCustomer2.toString()} "
   }

   abstractCustomerService.activate(timeService.currentTime, 1)

   AbstractCustomer.list().each{ customer ->
     assertEquals("1 Subscriptions for customer", 1, customer.subscriptions?.size())
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

   tariffMarketInitializationService.initialize(comp, ['AccountingService'])
   initializeService()
   assertEquals("correct phase", tariffMarketService.simulationPhase, registrationPhase)
   start = new DateTime(2011, 1, 1, 12, 0, 0, 0, DateTimeZone.UTC).toInstant()
   // current time is noon. Set pub interval to 3 hours.
   tariffMarketService.publicationInterval = 3 // hours
   //assertEquals("newTariffs list is empty", 0, Tariff.findAllByState(Tariff.State.PENDING).size())
   assertEquals("two registration", 2, tariffMarketService.registrations.size())
   
   // publish some tariffs over a period of three hours, check for publication
   def tsc1 = new TariffSpecification(broker: broker1,
       expiration: new Instant(start.millis + TimeService.DAY),
       minDuration: TimeService.WEEK * 8, powerType: PowerType.CONSUMPTION)
   Rate r1 = new Rate(value: 0.222)
   tsc1.addToRates(r1)
   tariffMarketService.processTariff(tsc1)
   timeService.currentTime += TimeService.HOUR


   // it's 13:00
   abstractCustomerService.activate(timeService.currentTime, 1)
   abstractCustomerService.activate(timeService.currentTime, 2)
   tariffMarketService.activate(timeService.currentTime, 2)

   def tsc2 = new TariffSpecification(broker: broker1,
       expiration: new Instant(start.millis + TimeService.DAY * 2),
       minDuration: TimeService.WEEK * 8, powerType: PowerType.CONSUMPTION)
   tsc2.addToRates(r1)
   tariffMarketService.processTariff(tsc2)
   def tsc3 = new TariffSpecification(broker: broker1,
       expiration: new Instant(start.millis + TimeService.DAY * 3),
       minDuration: TimeService.WEEK * 8, powerType: PowerType.CONSUMPTION)
   tsc3.addToRates(r1)
   tariffMarketService.processTariff(tsc3)
   timeService.currentTime += TimeService.HOUR


   // it's 14:00
   abstractCustomerService.activate(timeService.currentTime, 1)
   abstractCustomerService.activate(timeService.currentTime, 2)
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
   abstractCustomerService.activate(timeService.currentTime, 1)
   tariffMarketService.activate(timeService.currentTime, 2)
   abstractCustomerService.activate(timeService.currentTime, 2)

   assertEquals("newTariffs list is again empty", 0, Tariff.findAllByState(Tariff.State.PENDING).size())
 }

 void testEvaluatingTariffs() {
   
   initializeService()
   
   println("Number Of Subscriptions in DB: ${TariffSubscription.count()}")
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
   
   AbstractCustomer.list().each{ customer ->
     customer.possibilityEvaluationNewTariffs(Tariff.list())
   }
    
 }
 
}

