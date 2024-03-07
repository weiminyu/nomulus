// Copyright 2021 The Nomulus Authors. All Rights Reserved.
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

package google.registry.batch;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.persistence.transaction.JpaTransactionManagerExtension.makeRegistrar1;
import static google.registry.testing.DatabaseHelper.loadByEntity;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.testing.DatabaseHelper.persistSimpleResources;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static org.apache.http.HttpStatus.SC_OK;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import google.registry.batch.SendExpiringCertificateNotificationEmailAction.CertificateType;
import google.registry.batch.SendExpiringCertificateNotificationEmailAction.RegistrarInfo;
import google.registry.flows.certs.CertificateChecker;
import google.registry.groups.GmailClient;
import google.registry.model.registrar.Registrar;
import google.registry.model.registrar.RegistrarAddress;
import google.registry.model.registrar.RegistrarPoc;
import google.registry.model.registrar.RegistrarPoc.Type;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaIntegrationTestExtension;
import google.registry.testing.FakeClock;
import google.registry.testing.FakeResponse;
import google.registry.util.SelfSignedCaCertificate;
import java.security.cert.X509Certificate;
import java.util.Optional;
import javax.annotation.Nullable;
import javax.mail.internet.InternetAddress;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link SendExpiringCertificateNotificationEmailAction}. */
class SendExpiringCertificateNotificationEmailActionTest {

  private static final String EXPIRATION_WARNING_EMAIL_BODY_TEXT =
      " Dear %1$s,\n"
          + '\n'
          + "We would like to inform you that your %2$s certificate will expire at %3$s."
          + '\n'
          + " Kind update your account using the following steps: "
          + '\n'
          + "  1. Navigate to support and login using your %4$s@registry.example credentials.\n"
          + "  2. Click Settings -> Privacy on the top left corner.\n"
          + "  3. Click Edit and enter certificate string."
          + "  3. Click Save"
          + "Regards,"
          + "Example Registry";

  private static final String EXPIRATION_WARNING_EMAIL_SUBJECT_TEXT = "Expiration Warning Email";

  @RegisterExtension
  final JpaIntegrationTestExtension jpa =
      new JpaTestExtensions.Builder().buildIntegrationTestExtension();

  private final FakeClock clock = new FakeClock(DateTime.parse("2021-05-24T20:21:22Z"));
  private final GmailClient sendEmailService = mock(GmailClient.class);
  private CertificateChecker certificateChecker;
  private SendExpiringCertificateNotificationEmailAction action;
  private Registrar sampleRegistrar;
  private FakeResponse response;

  @BeforeEach
  void beforeEach() throws Exception {
    response = new FakeResponse();

    certificateChecker =
        new CertificateChecker(
            ImmutableSortedMap.of(START_OF_TIME, 825, DateTime.parse("2020-09-01T00:00:00Z"), 398),
            30,
            15,
            2048,
            ImmutableSet.of("secp256r1", "secp384r1"),
            clock);

    action =
        new SendExpiringCertificateNotificationEmailAction(
            EXPIRATION_WARNING_EMAIL_BODY_TEXT,
            EXPIRATION_WARNING_EMAIL_SUBJECT_TEXT,
            sendEmailService,
            certificateChecker,
            response);

    sampleRegistrar =
        persistResource(createRegistrar("clientId", "sampleRegistrar", null, null).build());
  }

  @Test
  void sendNotificationEmail_techEMailAsRecipient_returnsTrue() throws Exception {
    X509Certificate expiringCertificate =
        SelfSignedCaCertificate.create(
                "www.example.tld",
                DateTime.parse("2020-09-02T00:00:00Z"),
                DateTime.parse("2021-06-01T00:00:00Z"))
            .cert();
    Optional<String> cert =
        Optional.of(certificateChecker.serializeCertificate(expiringCertificate));
    Registrar registrar =
        persistResource(
            makeRegistrar1()
                .asBuilder()
                .setFailoverClientCertificate(cert.get(), clock.nowUtc())
                .build());
    persistSampleContacts(registrar, Type.TECH);
    assertThat(
            action.sendNotificationEmail(registrar, START_OF_TIME, CertificateType.FAILOVER, cert))
        .isEqualTo(true);
  }

  @Test
  void sendNotificationEmail_adminEMailAsRecipient_returnsTrue() throws Exception {
    X509Certificate expiringCertificate =
        SelfSignedCaCertificate.create(
                "www.example.tld",
                DateTime.parse("2020-09-02T00:00:00Z"),
                DateTime.parse("2021-06-01T00:00:00Z"))
            .cert();
    Optional<String> cert =
        Optional.of(certificateChecker.serializeCertificate(expiringCertificate));
    Registrar registrar =
        persistResource(
            makeRegistrar1()
                .asBuilder()
                .setFailoverClientCertificate(cert.get(), clock.nowUtc())
                .build());
    persistSampleContacts(registrar, Type.ADMIN);
    assertThat(
            action.sendNotificationEmail(registrar, START_OF_TIME, CertificateType.FAILOVER, cert))
        .isEqualTo(true);
  }

  @Test
  void sendNotificationEmail_returnsFalse_unsupportedEmailType() throws Exception {
    Registrar registrar =
        persistResource(
            createRegistrar(
                    "testId",
                    "testName",
                    SelfSignedCaCertificate.create(
                            "www.example.tld",
                            DateTime.parse("2020-09-02T00:00:00Z"),
                            DateTime.parse("2021-06-01T00:00:00Z"))
                        .cert(),
                    null)
                .build());
    persistSampleContacts(registrar, Type.LEGAL);
    assertThat(
            action.sendNotificationEmail(
                registrar,
                START_OF_TIME,
                CertificateType.FAILOVER,
                Optional.of(
                    certificateChecker.serializeCertificate(
                        SelfSignedCaCertificate.create(
                                "www.example.tld",
                                DateTime.parse("2020-09-02T00:00:00Z"),
                                DateTime.parse("2021-06-01T00:00:00Z"))
                            .cert()))))
        .isEqualTo(false);
  }

  @Test
  void sendNotificationEmail_returnsFalse_noEmailRecipients() throws Exception {
    X509Certificate expiringCertificate =
        SelfSignedCaCertificate.create(
                "www.example.tld",
                DateTime.parse("2020-09-02T00:00:00Z"),
                DateTime.parse("2021-06-02T00:00:00Z"))
            .cert();
    Optional<String> cert =
        Optional.of(certificateChecker.serializeCertificate(expiringCertificate));
    assertThat(
            action.sendNotificationEmail(
                sampleRegistrar, START_OF_TIME, CertificateType.FAILOVER, cert))
        .isEqualTo(false);
  }

  @Test
  void sendNotificationEmail_throwsRunTimeException() throws Exception {
    doThrow(new RuntimeException("this is a runtime exception"))
        .when(sendEmailService)
        .sendEmail(any());
    X509Certificate expiringCertificate =
        SelfSignedCaCertificate.create(
                "www.example.tld",
                DateTime.parse("2020-09-02T00:00:00Z"),
                DateTime.parse("2021-06-01T00:00:00Z"))
            .cert();
    Optional<String> cert =
        Optional.of(certificateChecker.serializeCertificate(expiringCertificate));
    Registrar registrar =
        persistResource(
            makeRegistrar1()
                .asBuilder()
                .setFailoverClientCertificate(cert.get(), clock.nowUtc())
                .build());
    ImmutableList<RegistrarPoc> contacts =
        ImmutableList.of(
            new RegistrarPoc.Builder()
                .setRegistrar(registrar)
                .setName("Will Doe")
                .setEmailAddress("will@example-registrar.tld")
                .setPhoneNumber("+1.3105551213")
                .setFaxNumber("+1.3105551213")
                .setTypes(ImmutableSet.of(RegistrarPoc.Type.TECH))
                .setVisibleInWhoisAsAdmin(true)
                .setVisibleInWhoisAsTech(false)
                .build());
    persistSimpleResources(contacts);
    RuntimeException thrown =
        assertThrows(
            RuntimeException.class,
            () ->
                action.sendNotificationEmail(
                    registrar, START_OF_TIME, CertificateType.FAILOVER, cert));
    assertThat(thrown)
        .hasMessageThat()
        .contains(
            String.format(
                "Failed to send expiring certificate notification email to registrar %s",
                registrar.getRegistrarName()));
  }

  @Test
  void sendNotificationEmail_returnsFalse_noCertificate() {
    assertThat(
            action.sendNotificationEmail(
                sampleRegistrar, START_OF_TIME, CertificateType.FAILOVER, Optional.empty()))
        .isEqualTo(false);
  }

  @Test
  void sendNotificationEmails_allEmailsBeingSent_onlyMainCertificates() throws Exception {
    for (int i = 1; i <= 10; i++) {
      Registrar registrar =
          persistResource(
              createRegistrar(
                      "oldcert" + i,
                      "name" + i,
                      SelfSignedCaCertificate.create(
                              "www.example.tld",
                              DateTime.parse("2020-09-02T00:00:00Z"),
                              DateTime.parse("2021-06-01T00:00:00Z"))
                          .cert(),
                      null)
                  .build());
      persistSampleContacts(registrar, Type.TECH);
    }
    assertThat(action.sendNotificationEmails()).isEqualTo(10);
  }

  @Test
  void sendNotificationEmails_allEmailsBeingSent_onlyFailOverCertificates() throws Exception {
    for (int i = 1; i <= 10; i++) {
      Registrar registrar =
          persistResource(
              createRegistrar(
                      "oldcert" + i,
                      "name" + i,
                      null,
                      SelfSignedCaCertificate.create(
                              "www.example.tld",
                              DateTime.parse("2020-09-02T00:00:00Z"),
                              DateTime.parse("2021-06-01T00:00:00Z"))
                          .cert())
                  .build());
      persistSampleContacts(registrar, Type.TECH);
    }
    assertThat(action.sendNotificationEmails()).isEqualTo(10);
  }

  @Test
  void sendNotificationEmails_allEmailsBeingSent_mixedOfCertificates() throws Exception {
    X509Certificate expiringCertificate =
        SelfSignedCaCertificate.create(
                "www.example.tld",
                DateTime.parse("2020-09-02T00:00:00Z"),
                DateTime.parse("2021-06-01T00:00:00Z"))
            .cert();

    for (int i = 1; i <= 3; i++) {
      Registrar registrar =
          persistResource(
              createRegistrar(
                      "cl" + i, "regWIthexpiringFailOverOnly" + i, null, expiringCertificate)
                  .build());
      persistSampleContacts(registrar, Type.TECH);
    }
    for (int i = 1; i <= 5; i++) {
      Registrar registrar =
          persistResource(
              createRegistrar(
                      "cli" + i, "regWithexpiringPrimaryOnly" + i, expiringCertificate, null)
                  .build());
      persistSampleContacts(registrar, Type.TECH);
    }
    for (int i = 1; i <= 4; i++) {
      Registrar registrar =
          persistResource(
              createRegistrar(
                      "client" + i,
                      "regWithTwoExpiring" + i,
                      expiringCertificate,
                      expiringCertificate)
                  .build());
      persistSampleContacts(registrar, Type.ADMIN);
    }
    assertThat(action.sendNotificationEmails()).isEqualTo(16);
  }

  @Test
  void updateLastNotificationSentDate_updatedSuccessfully_primaryCertificate() throws Exception {
    X509Certificate expiringCertificate =
        SelfSignedCaCertificate.create(
                "www.example.tld",
                DateTime.parse("2020-09-02T00:00:00Z"),
                DateTime.parse("2021-06-02T00:00:00Z"))
            .cert();
    Registrar registrar =
        createRegistrar("testClientId", "registrar", expiringCertificate, null).build();
    persistResource(registrar);
    action.updateLastNotificationSentDate(registrar, clock.nowUtc(), CertificateType.PRIMARY);
    assertThat(loadByEntity(registrar).getLastExpiringCertNotificationSentDate())
        .isEqualTo(clock.nowUtc());
  }

  @Test
  void updateLastNotificationSentDate_updatedSuccessfully_failOverCertificate() throws Exception {
    X509Certificate expiringCertificate =
        SelfSignedCaCertificate.create(
                "www.example.tld",
                DateTime.parse("2020-09-02T00:00:00Z"),
                DateTime.parse("2021-06-01T00:00:00Z"))
            .cert();
    Registrar registrar =
        createRegistrar("testClientId", "registrar", null, expiringCertificate).build();
    persistResource(registrar);
    action.updateLastNotificationSentDate(registrar, clock.nowUtc(), CertificateType.FAILOVER);
    assertThat(loadByEntity(registrar).getLastExpiringFailoverCertNotificationSentDate())
        .isEqualTo(clock.nowUtc());
  }

  @Test
  void updateLastNotificationSentDate_noUpdates_noLastNotificationSentDate() throws Exception {
    X509Certificate expiringCertificate =
        SelfSignedCaCertificate.create(
                "www.example.tld",
                DateTime.parse("2020-09-02T00:00:00Z"),
                DateTime.parse("2021-06-01T00:00:00Z"))
            .cert();
    Registrar registrar =
        createRegistrar("testClientId", "registrar", null, expiringCertificate).build();
    persistResource(registrar);
    RuntimeException thrown =
        assertThrows(
            RuntimeException.class,
            () -> action.updateLastNotificationSentDate(registrar, null, CertificateType.FAILOVER));
    assertThat(thrown)
        .hasMessageThat()
        .contains("Failed to update the last notification sent date to Registrar");
  }

  @Test
  void updateLastNotificationSentDate_noUpdates_invalidCertificateType() throws Exception {
    X509Certificate expiringCertificate =
        SelfSignedCaCertificate.create(
                "www.example.tld",
                DateTime.parse("2020-09-02T00:00:00Z"),
                DateTime.parse("2021-06-01T00:00:00Z"))
            .cert();
    Registrar registrar =
        createRegistrar("testClientId", "registrar", null, expiringCertificate).build();
    persistResource(registrar);
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                action.updateLastNotificationSentDate(
                    registrar, clock.nowUtc(), CertificateType.valueOf("randomType")));
    assertThat(thrown).hasMessageThat().contains("No enum constant");
  }

  @Test
  void getRegistrarsWithExpiringCertificates_returnsPartOfRegistrars() throws Exception {
    X509Certificate expiringCertificate =
        SelfSignedCaCertificate.create(
                "www.example.tld",
                DateTime.parse("2020-09-02T00:00:00Z"),
                DateTime.parse("2021-06-01T00:00:00Z"))
            .cert();
    X509Certificate certificate =
        SelfSignedCaCertificate.create(
                "www.example.tld",
                DateTime.parse("2020-09-02T00:00:00Z"),
                DateTime.parse("2021-10-01T00:00:00Z"))
            .cert();
    int numOfRegistrars = 10;
    int numOfRegistrarsWithExpiringCertificates = 2;
    for (int i = 1; i <= numOfRegistrarsWithExpiringCertificates; i++) {
      persistResource(
          createRegistrar("oldcert" + i, "name" + i, expiringCertificate, null).build());
    }
    for (int i = numOfRegistrarsWithExpiringCertificates; i <= numOfRegistrars; i++) {
      persistResource(createRegistrar("goodcert" + i, "name" + i, certificate, null).build());
    }

    ImmutableList<RegistrarInfo> results = action.getRegistrarsWithExpiringCertificates();
    assertThat(results).hasSize(numOfRegistrarsWithExpiringCertificates);
  }

  @Test
  void getRegistrarsWithExpiringCertificates_returnsPartOfRegistrars_failOverCertificateBranch()
      throws Exception {
    X509Certificate expiringCertificate =
        SelfSignedCaCertificate.create(
                "www.example.tld",
                DateTime.parse("2020-09-02T00:00:00Z"),
                DateTime.parse("2021-06-01T00:00:00Z"))
            .cert();
    X509Certificate certificate =
        SelfSignedCaCertificate.create(
                "www.example.tld",
                DateTime.parse("2020-09-02T00:00:00Z"),
                DateTime.parse("2021-10-01T00:00:00Z"))
            .cert();
    int numOfRegistrars = 10;
    int numOfRegistrarsWithExpiringCertificates = 2;
    for (int i = 1; i <= numOfRegistrarsWithExpiringCertificates; i++) {
      persistResource(
          createRegistrar("oldcert" + i, "name" + i, null, expiringCertificate).build());
    }
    for (int i = numOfRegistrarsWithExpiringCertificates; i <= numOfRegistrars; i++) {
      persistResource(createRegistrar("goodcert" + i, "name" + i, null, certificate).build());
    }

    assertThat(action.getRegistrarsWithExpiringCertificates().size())
        .isEqualTo(numOfRegistrarsWithExpiringCertificates);
  }

  @Test
  void getRegistrarsWithExpiringCertificates_returnsAllRegistrars() throws Exception {
    X509Certificate expiringCertificate =
        SelfSignedCaCertificate.create(
                "www.example.tld",
                DateTime.parse("2020-09-02T00:00:00Z"),
                DateTime.parse("2021-06-01T00:00:00Z"))
            .cert();

    int numOfRegistrarsWithExpiringCertificates = 5;
    for (int i = 1; i <= numOfRegistrarsWithExpiringCertificates; i++) {
      persistResource(
          createRegistrar("oldcert" + i, "name" + i, expiringCertificate, null).build());
    }
    assertThat(action.getRegistrarsWithExpiringCertificates().size())
        .isEqualTo(numOfRegistrarsWithExpiringCertificates);
  }

  @Test
  void getRegistrarsWithExpiringCertificates_returnsNoRegistrars() throws Exception {
    X509Certificate certificate =
        SelfSignedCaCertificate.create(
                "www.example.tld",
                DateTime.parse("2020-09-02T00:00:00Z"),
                DateTime.parse("2021-10-01T00:00:00Z"))
            .cert();
    int numOfRegistrars = 10;
    for (int i = 1; i <= numOfRegistrars; i++) {
      persistResource(createRegistrar("goodcert" + i, "name" + i, certificate, null).build());
    }
    assertThat(action.getRegistrarsWithExpiringCertificates()).isEmpty();
  }

  @Test
  void getRegistrarsWithExpiringCertificates_noRegistrarsInDatabase() {
    assertThat(action.getRegistrarsWithExpiringCertificates()).isEmpty();
  }

  @Test
  void getEmailAddresses_success_returnsAnEmptyList() {
    assertThat(action.getEmailAddresses(sampleRegistrar, Type.TECH)).isEmpty();
    assertThat(action.getEmailAddresses(sampleRegistrar, Type.ADMIN)).isEmpty();
  }

  @Test
  void getEmailAddresses_success_returnsAListOfEmails() throws Exception {
    Registrar registrar = persistResource(makeRegistrar1());
    ImmutableList<RegistrarPoc> contacts =
        ImmutableList.of(
            new RegistrarPoc.Builder()
                .setRegistrar(registrar)
                .setName("John Doe")
                .setEmailAddress("jd@example-registrar.tld")
                .setPhoneNumber("+1.3105551213")
                .setFaxNumber("+1.3105551213")
                .setTypes(ImmutableSet.of(RegistrarPoc.Type.TECH))
                .setVisibleInWhoisAsAdmin(true)
                .setVisibleInWhoisAsTech(false)
                .build(),
            new RegistrarPoc.Builder()
                .setRegistrar(registrar)
                .setName("John Smith")
                .setEmailAddress("js@example-registrar.tld")
                .setPhoneNumber("+1.1111111111")
                .setFaxNumber("+1.1111111111")
                .setTypes(ImmutableSet.of(RegistrarPoc.Type.TECH))
                .build(),
            new RegistrarPoc.Builder()
                .setRegistrar(registrar)
                .setName("Will Doe")
                .setEmailAddress("will@example-registrar.tld")
                .setPhoneNumber("+1.3105551213")
                .setFaxNumber("+1.3105551213")
                .setTypes(ImmutableSet.of(RegistrarPoc.Type.TECH))
                .setVisibleInWhoisAsAdmin(true)
                .setVisibleInWhoisAsTech(false)
                .build(),
            new RegistrarPoc.Builder()
                .setRegistrar(registrar)
                .setName("Mike Doe")
                .setEmailAddress("mike@example-registrar.tld")
                .setPhoneNumber("+1.1111111111")
                .setFaxNumber("+1.1111111111")
                .setTypes(ImmutableSet.of(RegistrarPoc.Type.ADMIN))
                .build(),
            new RegistrarPoc.Builder()
                .setRegistrar(registrar)
                .setName("John T")
                .setEmailAddress("john@example-registrar.tld")
                .setPhoneNumber("+1.3105551215")
                .setFaxNumber("+1.3105551216")
                .setTypes(ImmutableSet.of(RegistrarPoc.Type.ADMIN))
                .setVisibleInWhoisAsTech(true)
                .build());
    persistSimpleResources(contacts);
    assertThat(action.getEmailAddresses(registrar, Type.TECH))
        .containsExactly(
            new InternetAddress("will@example-registrar.tld"),
            new InternetAddress("jd@example-registrar.tld"),
            new InternetAddress("js@example-registrar.tld"));
    assertThat(action.getEmailAddresses(registrar, Type.ADMIN))
        .containsExactly(
            new InternetAddress("janedoe@theregistrar.com"), // comes with makeRegistrar1()
            new InternetAddress("mike@example-registrar.tld"),
            new InternetAddress("john@example-registrar.tld"));
  }

  @Test
  void getEmailAddresses_failure_returnsPartialListOfEmails_skipInvalidEmails() {
    // when building a new RegistrarContact object, there's already an email validation process.
    // if the registrarContact is created successful, the email address of the contact object
    // should already be validated. Ideally, there should not be an AddressException when creating
    // a new InternetAddress using the email address string of the contact object.
  }

  @Test
  void getEmailBody_returnsEmailBodyText() {
    String registrarName = "good registrar";
    String certExpirationDateStr = "2021-06-15";
    CertificateType certificateType = CertificateType.PRIMARY;
    String registrarId = "registrarid";
    String emailBody =
        action.getEmailBody(
            registrarName, certificateType, DateTime.parse(certExpirationDateStr), registrarId);
    assertThat(emailBody).contains(registrarName);
    assertThat(emailBody).contains(certificateType.getDisplayName());
    assertThat(emailBody).contains(certExpirationDateStr);
    assertThat(emailBody).contains(registrarId + "@registry.example");
    assertThat(emailBody).doesNotContain("%1$s");
    assertThat(emailBody).doesNotContain("%2$s");
    assertThat(emailBody).doesNotContain("%3$s");
    assertThat(emailBody).doesNotContain("%4$s");
  }

  @Test
  void getEmailBody_throwsIllegalArgumentException_noExpirationDate() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                action.getEmailBody(
                    "good registrar", CertificateType.FAILOVER, null, "registrarId"));
    assertThat(thrown).hasMessageThat().contains("Expiration date cannot be null");
  }

  @Test
  void getEmailBody_throwsIllegalArgumentException_noCertificateType() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                action.getEmailBody(
                    "good registrar", null, DateTime.parse("2021-06-15"), "registrarId"));
    assertThat(thrown).hasMessageThat().contains("Certificate type cannot be null");
  }

  @Test
  void getEmailBody_throwsIllegalArgumentException_noRegistrarId() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                action.getEmailBody(
                    "good registrar",
                    CertificateType.FAILOVER,
                    DateTime.parse("2021-06-15"),
                    null));
    assertThat(thrown).hasMessageThat().contains("Registrar Id cannot be null");
  }

  @Test
  void run_sentZeroEmail_responseStatusIs200() {
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_OK);
    assertThat(response.getPayload())
        .isEqualTo("Done. Sent 0 expiring certificate notification emails in total.");
  }

  @Test
  void run_sentEmails_responseStatusIs200() throws Exception {
    for (int i = 1; i <= 5; i++) {
      Registrar registrar =
          persistResource(
              createRegistrar(
                      "id_" + i,
                      "name" + i,
                      SelfSignedCaCertificate.create(
                              "www.example.tld",
                              DateTime.parse("2020-09-02T00:00:00Z"),
                              DateTime.parse("2021-06-01T00:00:00Z"))
                          .cert(),
                      null)
                  .build());
      persistSampleContacts(registrar, Type.TECH);
    }
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_OK);
    assertThat(response.getPayload())
        .isEqualTo("Done. Sent 5 expiring certificate notification emails in total.");
  }

  /**
   * Returns a sample registrar builder with a customized registrar name, client id and certificate.
   */
  private Registrar.Builder createRegistrar(
      String registrarId,
      String registrarName,
      @Nullable X509Certificate certificate,
      @Nullable X509Certificate failOverCertificate)
      throws Exception {
    // set up only required fields sample test data
    Registrar.Builder builder =
        new Registrar.Builder()
            .setRegistrarId(registrarId)
            .setRegistrarName(registrarName)
            .setType(Registrar.Type.REAL)
            .setIanaIdentifier(8L)
            .setState(Registrar.State.ACTIVE)
            .setInternationalizedAddress(
                new RegistrarAddress.Builder()
                    .setStreet(ImmutableList.of("very fake street"))
                    .setCity("city")
                    .setState("state")
                    .setZip("99999")
                    .setCountryCode("US")
                    .build())
            .setPhoneNumber("+0.000000000")
            .setFaxNumber("+9.999999999")
            .setEmailAddress("contact-us@test.example")
            .setWhoisServer("whois.registrar.example")
            .setUrl("http://www.test.example");

    if (failOverCertificate != null) {
      builder.setFailoverClientCertificate(
          certificateChecker.serializeCertificate(failOverCertificate), clock.nowUtc());
    }
    if (certificate != null) {
      builder.setClientCertificate(
          certificateChecker.serializeCertificate(certificate), clock.nowUtc());
    }
    return builder;
  }

  /** Returns persisted sample contacts with a customized contact email type. */
  private static ImmutableList<RegistrarPoc> persistSampleContacts(
      Registrar registrar, RegistrarPoc.Type emailType) {
    return persistSimpleResources(
        ImmutableList.of(
            new RegistrarPoc.Builder()
                .setRegistrar(registrar)
                .setName("Will Doe")
                .setEmailAddress("will@example-registrar.tld")
                .setPhoneNumber("+1.0105551213")
                .setFaxNumber("+1.0105551213")
                .setTypes(ImmutableSet.of(emailType))
                .build(),
            new RegistrarPoc.Builder()
                .setRegistrar(registrar)
                .setName("Will Smith")
                .setEmailAddress("will@test-registrar.tld")
                .setPhoneNumber("+1.3105551213")
                .setFaxNumber("+1.3105551213")
                .setTypes(ImmutableSet.of(emailType))
                .build()));
  }
}
