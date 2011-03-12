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

import java.math.BigDecimal;
import java.util.List;

import org.joda.time.Instant;
import org.powertac.common.*
import org.powertac.common.msg.*
import org.powertac.common.enumerations.TariffTransactionType;
import org.powertac.common.exceptions.*

import groovy.transform.Synchronized

/**
 * Implementation of {@link org.powertac.common.interfaces.Accounting}
 *
 * @author John Collins
 */
class AccountingService implements org.powertac.common.interfaces.Accounting 
{
  static transactional = true
  
  def timeService // autowire reference
  
  def pendingTransactions = []
  
  // transaction ID counter
  int idCount = 0

  void activate (Instant time, int phaseNumber)
  {
    // TODO Auto-generated method stub
    
  }

  MarketTransaction addMarketTransaction (Product product,
                                          BigDecimal positionChange,
                                          BigDecimal cashChange)
  {
    MarketTransaction mtx = new MarketTransaction ()
  }

  @Synchronized
  public TariffTransaction addTariffTransaction (TariffTransactionType txType,
                                                 Tariff tariff,
                                                 CustomerInfo customer,
                                                 int customerCount,
                                                 BigDecimal amount,
                                                 BigDecimal charge)
  {
    TariffTransaction ttx = new TariffTransaction(id: idCount++, broker: tariff.broker,
            postedTime: timeService.currentTime, txType:txType, tariff:tariff, 
            customerInfo:customer, customerCount:customerCount,
            amount:amount, charge:charge)
    assert ttx.save()
    pendingTransactions.add(ttx)
    return ttx
  }

  List<TariffTransaction> getTariffTransactions (Broker broker)
  {
    return pendingTransactions.findAll { tx ->
      tx instanceof TariffTransaction &&
      tx.broker == broker && 
      (tx.txType == TariffTransactionType.CONSUME || 
       tx.txType == TariffTransactionType.PRODUCE) }
  }

  BigDecimal getCurrentNetMktPosition (Broker broker)
  {
    // TODO Auto-generated method stub
    return null;
  }
}
