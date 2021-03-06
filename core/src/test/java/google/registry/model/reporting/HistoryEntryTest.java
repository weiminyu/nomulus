// Copyright 2017 The Nomulus Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package google.registry.model.reporting;

import static google.registry.model.ImmutableObjectSubject.assertAboutImmutableObjects;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.persistence.transaction.TransactionManagerUtil.transactIfJpaTm;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.persistActiveDomain;
import static google.registry.testing.DatabaseHelper.persistResource;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import google.registry.model.EntityTestCase;
import google.registry.model.domain.DomainBase;
import google.registry.model.domain.DomainHistory;
import google.registry.model.domain.Period;
import google.registry.model.eppcommon.Trid;
import google.registry.model.reporting.DomainTransactionRecord.TransactionReportField;
import google.registry.testing.DualDatabaseTest;
import google.registry.testing.TestOfyAndSql;
import google.registry.testing.TestOfyOnly;
import org.junit.jupiter.api.BeforeEach;

/** Unit tests for {@link HistoryEntry}. */
@DualDatabaseTest
class HistoryEntryTest extends EntityTestCase {

  private HistoryEntry historyEntry;

  @BeforeEach
  void setUp() {
    createTld("foobar");
    DomainBase domain = persistActiveDomain("foo.foobar");
    DomainTransactionRecord transactionRecord =
        new DomainTransactionRecord.Builder()
            .setTld("foobar")
            .setReportingTime(fakeClock.nowUtc())
            .setReportField(TransactionReportField.NET_ADDS_1_YR)
            .setReportAmount(1)
            .build();
    // Set up a new persisted HistoryEntry entity.
    historyEntry =
        new DomainHistory.Builder()
            .setParent(domain)
            .setType(HistoryEntry.Type.DOMAIN_CREATE)
            .setPeriod(Period.create(1, Period.Unit.YEARS))
            .setXmlBytes("<xml></xml>".getBytes(UTF_8))
            .setModificationTime(fakeClock.nowUtc())
            .setClientId("TheRegistrar")
            .setOtherClientId("otherClient")
            .setTrid(Trid.create("ABC-123", "server-trid"))
            .setBySuperuser(false)
            .setReason("reason")
            .setRequestedByRegistrar(false)
            .setDomainTransactionRecords(ImmutableSet.of(transactionRecord))
            .build();
    persistResource(historyEntry);
  }

  @TestOfyAndSql
  void testPersistence() {
    transactIfJpaTm(
        () -> {
          HistoryEntry fromDatabase = tm().loadByEntity(historyEntry);
          assertAboutImmutableObjects()
              .that(fromDatabase)
              .isEqualExceptFields(historyEntry, "nsHosts", "domainTransactionRecords");
          assertAboutImmutableObjects()
              .that(Iterables.getOnlyElement(fromDatabase.getDomainTransactionRecords()))
              .isEqualExceptFields(
                  Iterables.getOnlyElement(historyEntry.getDomainTransactionRecords()), "id");
        });
  }

  @TestOfyOnly
  void testIndexing() throws Exception {
    verifyIndexing(historyEntry.asHistoryEntry(), "modificationTime", "clientId");
  }
}
