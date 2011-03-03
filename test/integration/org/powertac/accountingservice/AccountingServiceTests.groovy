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
import org.powertac.common.msg.CashDoUpdateCmd
import org.powertac.common.msg.PositionDoUpdateCmd
import org.powertac.common.enumerations.CustomerType
import org.powertac.common.enumerations.ProductType
import org.powertac.common.exceptions.PositionUpdateException
import org.powertac.common.*

class AccountingServiceTests extends GroovyTestCase {

  AccountingService accountingService

  Competition competition
  CustomerInfo customerInfo
  Product product
  Timeslot timeslot
  TariffSpecification tariffSpec
  Broker broker
  String userName
  String apiKey
  int nameCounter = 0

  def timeService // dependency injection

  protected void setUp() {
    super.setUp()
    timeService.setCurrentTime(new DateTime(2011, 1, 26, 12, 0, 0, 0, DateTimeZone.UTC))
    userName = 'testBroker'
    apiKey = 'testApiKey-which-needs-to-be-longer-than-32-characters'
    competition = new Competition(name: "test", current: true)
    assert (competition != null)
    assert (competition.name != null)
    if (!competition.validate()) {
      competition.errors.allErrors.each {
        println it.toString()
      }
      fail("could not validate competition")
    }
    assert (competition.validate() && competition.save())
    broker = new Broker(userName: userName, apiKey: apiKey)
    assert (broker.validate() && broker.save())
    product = new Product(productType: ProductType.Future)
    assert (product.validate() && product.save())
    timeslot = new Timeslot(serialNumber: 0,
                            startInstant: timeService.currentTime,
                            endInstant: new Instant(timeService.currentTime.millis + timeService.HOUR))
    assert (timeslot.validate() && timeslot.save())
    customerInfo = new CustomerInfo(name: 'testCustomer', customerType: CustomerType.CustomerHousehold, multiContracting: false, canNegotiate: false, upperPowerCap: 100.0, lowerPowerCap: 10.0, carbonEmissionRate: 20.0, windToPowerConversion: 0.0, sunToPowerConversion: 0.0, tempToPowerConversion: 0.0)
    assertTrue(customerInfo.validate() && customerInfo.save())
    //tariff = new Tariff(transactionId: 'someTransactionId1', competition: competition, broker: broker, tariffState: TariffState.Published, isDynamic: false, isNegotiable: false, latest: true, signupFee: 1.0, earlyExitFee: 1.0, baseFee: 1.0)
    //tariff.setFlatPowerConsumptionPrice(9.0)
    //tariff.setFlatPowerProductionPrice(11.0)
    //assertTrue(tariff.validate() && tariff.save())
  }

  protected void tearDown() {
    super.tearDown()
  }

  void testAccountingServiceNotNull() {
    assertNotNull(accountingService)
  }

  //processCashUpdate tests

  void testProcessCashUpdateNull() {
    assertNull(accountingService.processCashUpdate(null))
  }

  void testProcessCashUpdateInvalidCommandObject() {
    assertNull(accountingService.processCashUpdate(new CashDoUpdateCmd()))
  }

  void testProcessCashUpdateValidCommandNoPreviousPosition() {
    CashDoUpdateCmd cmd = new CashDoUpdateCmd(broker: broker, relativeChange: 1.0, reason: 'someReason', origin: 'someOrigin')
    assertTrue(cmd.validate())
    def cashUpdate = accountingService.processCashUpdate(cmd)
    assertNotNull("result should not be null", cashUpdate)
    assertEquals(1.0, cashUpdate.relativeChange)
    assertEquals(1.0, cashUpdate.overallBalance)
    assertNotNull(cashUpdate.transactionId)
    assertEquals('someReason', cashUpdate.reason)
    assertEquals('someOrigin', cashUpdate.origin)
    assertEquals(1, CashUpdate.count())
  }

  void testProcessCashUpdateValidCommandWithPreviousPositions() {
    CashUpdate cashUpdate1 = new CashUpdate(broker: broker, relativeChange: 1.0, overallBalance: 1.0, latest: true, transactionId: 'someTransactionId')
    assertTrue(cashUpdate1.validate() && cashUpdate1.save())

    CashDoUpdateCmd cmd = new CashDoUpdateCmd(broker: broker, relativeChange: -2.0, reason: 'someReason', origin: 'someOrigin')
    assertTrue(cmd.validate())

    def cashUpdate2 = accountingService.processCashUpdate(cmd)
    assertEquals(-2.0, cashUpdate2.relativeChange)
    assertEquals(-1.0, cashUpdate2.overallBalance)
    assertNotNull(cashUpdate2.transactionId)
    assertEquals('someReason', cashUpdate2.reason)
    assertEquals('someOrigin', cashUpdate2.origin)
    //assertEquals(2, CashUpdate.count())
  }

  void testProcessCashUpdateAutomaticTransactionId() {
    CashDoUpdateCmd cmd = new CashDoUpdateCmd(broker: broker, relativeChange: -2.0, transactionId: 'someTransactionId')
    assertTrue(cmd.validate())
    def cashUpdate = accountingService.processCashUpdate(cmd)
    assertEquals(1, CashUpdate.count())
    assertEquals('someTransactionId', cashUpdate.transactionId)

    CashDoUpdateCmd cmd2 = new CashDoUpdateCmd(broker: broker, relativeChange: -2.0)
    assertTrue(cmd2.validate())
    def cashUpdate2 = accountingService.processCashUpdate(cmd2)
    //assertEquals(2, CashUpdate.count())
    assertNotNull(cashUpdate.transactionId)
  }

  // processPositionUpdate tests

  void testProcessPositionUpdateNull() {
    assertNull(accountingService.processPositionUpdate(null))
  }

  void testProcessPositionUpdateInvalidCommandObject() {
    shouldFail(PositionUpdateException) {
      accountingService.processPositionUpdate(new PositionDoUpdateCmd())
    }
  }

  void testProcessPositionUpdateValidCommandNoPreviousPosition() {
    PositionDoUpdateCmd cmd = new PositionDoUpdateCmd(broker: broker, product: product, timeslot: timeslot, relativeChange: 1.0, reason: 'someReason', origin: 'someOrigin')
    assertTrue(cmd.validate())
    def positionUpdate = accountingService.processPositionUpdate(cmd)
    assertEquals(1.0, positionUpdate.relativeChange)
    assertEquals(1.0, positionUpdate.overallBalance)
    assertNotNull(positionUpdate.transactionId)
    assertEquals('someReason', positionUpdate.reason)
    assertEquals('someOrigin', positionUpdate.origin)
    assertEquals(1, PositionUpdate.count())
  }

  void testProcessPositionUpdateValidCommandWithPreviousPositions() {
    PositionUpdate positionUpdate1 = new PositionUpdate(broker: broker, product: product, timeslot: timeslot, relativeChange: 1.0, overallBalance: 1.0, latest: true, transactionId: 'someTransactionId')
    assertTrue(positionUpdate1.validate() && positionUpdate1.save())

    PositionDoUpdateCmd cmd = new PositionDoUpdateCmd(broker: broker, product: product, timeslot: timeslot, relativeChange: -2.0, reason: 'someReason', origin: 'someOrigin')
    assertTrue(cmd.validate())

    def positionUpdate2 = accountingService.processPositionUpdate(cmd)
    assertEquals(-2.0, positionUpdate2.relativeChange)
    assertEquals(-1.0, positionUpdate2.overallBalance)
    assertNotNull(positionUpdate2.transactionId)
    assertEquals('someReason', positionUpdate2.reason)
    assertEquals('someOrigin', positionUpdate2.origin)
    //assertEquals(2, PositionUpdate.count())
  }

  void testPositionUpdateAutomaticTransactionIdGeneration() {
    PositionDoUpdateCmd cmd = new PositionDoUpdateCmd(broker: broker, product: product, timeslot: timeslot, relativeChange: 1.0, transactionId: 'someTransactionId')
    assertTrue(cmd.validate())
    def positionUpdate = accountingService.processPositionUpdate(cmd)
    assertEquals(1, PositionUpdate.count())
    assertEquals('someTransactionId', positionUpdate.transactionId)

    PositionDoUpdateCmd cmd2 = new PositionDoUpdateCmd(broker: broker, product: product, timeslot: timeslot, relativeChange: 1.0)
    assertTrue(cmd2.validate())
    def positionUpdate2 = accountingService.processPositionUpdate(cmd2)
    //assertEquals(2, PositionUpdate.count())
    assertNotNull(positionUpdate2.transactionId)
  }


//  void testPublishCustomersAvailable() {
//    competition.current = true
//    competition.save()
//    assertEquals('testCustomer', accountingService.publishCustomersAvailable().first().name)
//
//    competition.current = false
//    competition.save()
//    assertEquals([], accountingService.publishCustomersAvailable())
//
//  }

//  void testPublishTariffList() {
//
//    //No tariffs -> empty list
//    assertEquals([], accountingService.publishTariffList())
//
//    competition.current = true
//    competition.save()
//    tariff.delete() //delete tariff generated in setUp() method
//    assertEquals([], accountingService.publishTariffList())
//    Tariff tariff1 = new Tariff(transactionId: 'someTransactionId1', competition: competition, broker: broker, tariffState: TariffState.Published, isDynamic: false, isNegotiable: false, latest: true, signupFee: 1.0, earlyExitFee: 1.0, baseFee: 1.0)
//    tariff1.setFlatPowerConsumptionPrice(9.0)
//    tariff1.setFlatPowerProductionPrice(11.0)
//    assertTrue(tariff1.validate() && tariff1.save())
//
//    //One tariff in one active competition
//    def tariffList = accountingService.publishTariffList()
//    assertEquals(1, tariffList.size())
//    assertEquals(TariffState.Published, tariffList.first().tariffState)
//    assertEquals('someTransactionId1', tariffList.first().transactionId)
//    assertNull(tariffList.first().parent)
//
//    Tariff tariff2 = new Tariff(transactionId: 'someTransactionId2', competition: competition, broker: broker, tariffState: TariffState.Published, isDynamic: false, isNegotiable: false, latest: true, signupFee: 100.0, earlyExitFee: 100.0, baseFee: 100.0)
//    tariff2.setFlatPowerConsumptionPrice(90.0)
//    tariff2.setFlatPowerProductionPrice(110.0)
//    assertTrue(tariff2.validate() && tariff2.save())
//
//    tariffList = accountingService.publishTariffList()
//    assertEquals(2, tariffList.size())
//    assertEquals(1, tariffList.findAll {it.transactionId == 'someTransactionId1'}.size())
//    assertEquals(1, tariffList.findAll {it.transactionId == 'someTransactionId2'}.size())
//
//    tariff1.tariffState = TariffState.Revoked
//    tariff1.save()
//
//    tariffList = accountingService.publishTariffList()
//    assertEquals(1, tariffList.size())
//    assertEquals('someTransactionId2', tariffList.first().transactionId)
//
//    tariff2.latest = false
//    tariff2.save()
//
//    assertEquals([], accountingService.publishTariffList())
//
//    tariff2.latest = true
//    tariff2.save()
//    assertEquals(1, tariffList.size())
//
//    competition.current = false
//    competition.save()
//
//    assertEquals([], accountingService.publishTariffList())
//  }


  // this is incorrect.
//  void testProcessTariffPublished() {
//    tariff.delete() //delete tariff automatically generated in setUp() method
//    def contractStartDate = new DateTime()
//    def contractEndDate = new DateTime()
//    TariffDoPublishCmd cmd = new TariffSpecification()
//    shouldFail(TariffPublishException) {
//      accountingService.processTariffPublished(cmd)
//    }
//    cmd.id = 'testId'
//    cmd.competition = competition
//    cmd.broker = broker
//    cmd.isDynamic = false
//    cmd.isNegotiable = false
//    cmd.signupFee = 10.0
//    cmd.earlyExitFee = 20.0
//    cmd.baseFee = 30.0
//    cmd.changeLeadTime = 1
//    cmd.contractStartDate = contractStartDate
//    cmd.contractEndDate = contractEndDate
//    cmd.minimumContractRuntime = 1
//    cmd.maximumContractRuntime = 10
//    cmd.powerConsumptionThreshold = 100.0
//    cmd.powerConsumptionSurcharge = 10.0
//    cmd.powerProductionThreshold = 88.0
//    cmd.powerProductionSurcharge = 888.0
//
//    for (i in 0..23) {
//      cmd."powerConsumptionPrice$i" = (i + 1.0)
//      cmd."powerProductionPrice$i" = (i + 9.0)
//    }
//
//    assertNotNull(accountingService.processTariffPublished(cmd))
//
//    assertEquals(1, Tariff.count())
//    def tariff1 = Tariff.get('testId')
//    assertNotNull(tariff1)
//    assertNull(tariff1.parent)
//    assertEquals(tariff1.competition, competition)
//    assertEquals(tariff1.broker, broker)
//    assertFalse(tariff1.isDynamic)
//    assertFalse(tariff1.isNegotiable)
//    assertEquals(10.0, tariff1.signupFee)
//    assertEquals(20.0, tariff1.earlyExitFee)
//    assertEquals(30.0, tariff1.baseFee)
//    assertEquals(1, tariff1.changeLeadTime)
//    assertEquals(contractStartDate, tariff1.contractStartDate)
//    assertEquals(contractEndDate, tariff1.contractEndDate)
//    assertEquals(1, tariff1.minimumContractRuntime)
//    assertEquals(10, tariff1.maximumContractRuntime)
//    assertEquals(100.0, tariff1.powerConsumptionThreshold)
//    assertEquals(10.0, tariff1.powerConsumptionSurcharge)
//    assertEquals(88.0, tariff1.powerProductionThreshold)
//    assertEquals(888.0, tariff1.powerProductionSurcharge)
//    for (i in 0..23) {
//      assertEquals((i + 1.0), tariff1."powerConsumptionPrice$i")
//      assertEquals((i + 9.0), tariff1."powerProductionPrice$i")
//    }
//  }

}
