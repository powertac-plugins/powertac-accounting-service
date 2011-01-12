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


}
