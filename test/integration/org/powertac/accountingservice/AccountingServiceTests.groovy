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

import org.powertac.common.command.CashDoUpdateCmd
import org.powertac.common.command.PositionDoUpdateCmd
import org.powertac.common.enumerations.ProductType
import org.powertac.common.exceptions.CashUpdateException
import org.powertac.common.exceptions.PositionUpdateException
import org.powertac.common.*
import org.powertac.common.enumerations.TariffState

class AccountingServiceTests extends GroovyTestCase {

  AccountingService accountingService

  Competition competition
  Product product
  Timeslot timeslot
  Broker broker
  String userName
  String apiKey


  protected void setUp() {
    super.setUp()
    userName = 'testBroker'
    apiKey = 'testApiKey-which-needs-to-be-longer-than-32-characters'
    competition = new Competition(name: "test")
    assert (competition.validate() && competition.save())
    broker = new Broker(competition: competition, userName: userName, apiKey: apiKey)
    assert (broker.validate() && broker.save())
    product = new Product(competition: competition, productType: ProductType.Future)
    assert (product.validate() && product.save())
    timeslot = new Timeslot(competition: competition, serialNumber: 0)
    assert (timeslot.validate() && timeslot.save())
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
    shouldFail(CashUpdateException) {
      accountingService.processCashUpdate(new CashDoUpdateCmd())
    }
  }

  void testProcessCashUpdateValidCommandNoPreviousPosition() {
    CashDoUpdateCmd cmd = new CashDoUpdateCmd(competition: competition, broker: broker, relativeChange: 1.0, reason: 'someReason', origin: 'someOrigin')
    assertTrue (cmd.validate())
    def cashUpdate = accountingService.processCashUpdate(cmd)
    assertEquals(1.0, cashUpdate.relativeChange)
    assertEquals(1.0, cashUpdate.overallBalance)
    assertTrue(cashUpdate.latest)
    assertNotNull(cashUpdate.transactionId)
    assertEquals('someReason', cashUpdate.reason)
    assertEquals('someOrigin', cashUpdate.origin)
    assertEquals(1, CashUpdate.count())
  }

  void testProcessCashUpdateValidCommandWithPreviousPositions() {
    CashUpdate cashUpdate1 = new CashUpdate(competition: competition, broker: broker, relativeChange: 1.0, overallBalance: 1.0, latest: true, transactionId: 'someTransactionId')
    assertTrue (cashUpdate1.validate() && cashUpdate1.save())

    CashDoUpdateCmd cmd = new CashDoUpdateCmd(competition: competition, broker: broker, relativeChange: -2.0, reason: 'someReason', origin: 'someOrigin')
    assertTrue (cmd.validate())

    def cashUpdate2 = accountingService.processCashUpdate(cmd)
    assertEquals(-2.0, cashUpdate2.relativeChange)
    assertEquals(-1.0, cashUpdate2.overallBalance)
    assertTrue(cashUpdate2.latest)
    assertFalse(cashUpdate1.latest)
    assertNotNull(cashUpdate2.transactionId)
    assertEquals('someReason', cashUpdate2.reason)
    assertEquals('someOrigin', cashUpdate2.origin)
    assertEquals(2, CashUpdate.count())
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
    PositionDoUpdateCmd cmd = new PositionDoUpdateCmd(competition: competition, broker: broker, product: product, timeslot: timeslot, relativeChange: 1.0, reason: 'someReason', origin: 'someOrigin')
    assertTrue (cmd.validate())
    def positionUpdate = accountingService.processPositionUpdate(cmd)
    assertEquals(1.0, positionUpdate.relativeChange)
    assertEquals(1.0, positionUpdate.overallBalance)
    assertTrue(positionUpdate.latest)
    assertNotNull(positionUpdate.transactionId)
    assertEquals('someReason', positionUpdate.reason)
    assertEquals('someOrigin', positionUpdate.origin)
    assertEquals(1, PositionUpdate.count())
  }

  void testProcessPositionUpdateValidCommandWithPreviousPositions() {
    PositionUpdate positionUpdate1 = new PositionUpdate(competition: competition, broker: broker, product: product, timeslot: timeslot, relativeChange: 1.0, overallBalance: 1.0, latest: true, transactionId: 'someTransactionId')
    assertTrue (positionUpdate1.validate() && positionUpdate1.save())

    PositionDoUpdateCmd cmd = new PositionDoUpdateCmd(competition: competition, broker: broker, product: product, timeslot: timeslot, relativeChange: -2.0, reason: 'someReason', origin: 'someOrigin')
    assertTrue (cmd.validate())

    def positionUpdate2 = accountingService.processPositionUpdate(cmd)
    assertEquals(-2.0, positionUpdate2.relativeChange)
    assertEquals(-1.0, positionUpdate2.overallBalance)
    assertTrue(positionUpdate2.latest)
    assertFalse(positionUpdate1.latest)
    assertNotNull(positionUpdate2.transactionId)
    assertEquals('someReason', positionUpdate2.reason)
    assertEquals('someOrigin', positionUpdate2.origin)
    assertEquals(2, PositionUpdate.count())
  }

  void testPublishTariffList() {

    //No tariffs -> empty list
    assertEquals ([], accountingService.publishTariffList())

    competition.current = true
    competition.save()
    assertEquals([], accountingService.publishTariffList())
    Tariff tariff1 = new Tariff(transactionId: 'someTransactionId1', competition: competition, broker: broker, tariffId: 'someTariffId1', tariffState: TariffState.Published, isDynamic: false, isNegotiable: false, latest: true, signupFee: 1.0, earlyExitFee: 1.0, baseFee: 1.0)
    tariff1.setFlatPowerConsumptionPrice(9.0)
    tariff1.setFlatPowerProductionPrice(11.0)
    assertTrue (tariff1.validate() && tariff1.save())

    //One tariff in one active competition
    def tariffList = accountingService.publishTariffList()
    assertEquals(1, tariffList.size())
    assertEquals('someTariffId1', tariffList.first().tariffId)

    Tariff tariff2 = new Tariff(transactionId: 'someTransactionId2', competition: competition, broker: broker, tariffId: 'someTariffId2', tariffState: TariffState.Published, isDynamic: false, isNegotiable: false, latest: true, signupFee: 100.0, earlyExitFee: 100.0, baseFee: 100.0)
    tariff2.setFlatPowerConsumptionPrice(90.0)
    tariff2.setFlatPowerProductionPrice(110.0)
    assertTrue (tariff2.validate() && tariff2.save())

    tariffList = accountingService.publishTariffList()
    assertEquals(2, tariffList.size())
    assertEquals(1, tariffList.findAll {it.tariffId == 'someTariffId1'}.size())
    assertEquals(1, tariffList.findAll {it.tariffId == 'someTariffId2'}.size())

    tariff1.tariffState = TariffState.Revoked
    tariff1.save()

    tariffList = accountingService.publishTariffList()
    assertEquals(1, tariffList.size())
    assertEquals('someTariffId2', tariffList.first().tariffId)

    tariff2.latest = false
    tariff2.save()

    assertEquals ([], accountingService.publishTariffList())

    tariff2.latest = true
    tariff2.save()
    assertEquals(1, tariffList.size())

    competition.current = false
    competition.save()

    assertEquals ([], accountingService.publishTariffList())
  }

}
