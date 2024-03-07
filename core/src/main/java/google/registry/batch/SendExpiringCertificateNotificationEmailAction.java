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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.util.PreconditionsUtils.checkArgumentNotNull;
import static org.apache.http.HttpStatus.SC_INTERNAL_SERVER_ERROR;
import static org.apache.http.HttpStatus.SC_OK;
import static org.joda.time.DateTimeZone.UTC;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Streams;
import com.google.common.flogger.FluentLogger;
import com.google.common.net.MediaType;
import google.registry.config.RegistryConfig.Config;
import google.registry.flows.certs.CertificateChecker;
import google.registry.groups.GmailClient;
import google.registry.model.registrar.Registrar;
import google.registry.model.registrar.RegistrarPoc;
import google.registry.model.registrar.RegistrarPoc.Type;
import google.registry.request.Action;
import google.registry.request.Response;
import google.registry.request.auth.Auth;
import google.registry.util.EmailMessage;
import java.util.Optional;
import javax.inject.Inject;
import javax.mail.internet.AddressException;
import javax.mail.internet.InternetAddress;
import org.joda.time.DateTime;
import org.joda.time.Duration;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;

/** An action that sends notification emails to registrars whose certificates are expiring soon. */
@Action(
    service = Action.Service.BACKEND,
    path = SendExpiringCertificateNotificationEmailAction.PATH,
    auth = Auth.AUTH_API_ADMIN)
public class SendExpiringCertificateNotificationEmailAction implements Runnable {

  public static final String PATH = "/_dr/task/sendExpiringCertificateNotificationEmail";
  /**
   * Used as an offset when storing the last notification email sent date.
   *
   * <p>This is used to handle edges cases when the update happens in between the day switch. For
   * instance,if the job starts at 2:00 am every day and it finishes at 2:03 of the same day, then
   * next day at 2am, the date difference will be less than a day, which will lead to the date
   * difference between two successive email sent date being the expected email interval days + 1;
   */
  protected static final Duration UPDATE_TIME_OFFSET = Duration.standardMinutes(10);

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormat.forPattern("yyyy-MM-dd");

  private final CertificateChecker certificateChecker;
  private final String expirationWarningEmailBodyText;
  private final GmailClient gmailClient;
  private final String expirationWarningEmailSubjectText;
  private final Response response;

  @Inject
  public SendExpiringCertificateNotificationEmailAction(
      @Config("expirationWarningEmailBodyText") String expirationWarningEmailBodyText,
      @Config("expirationWarningEmailSubjectText") String expirationWarningEmailSubjectText,
      GmailClient gmailClient,
      CertificateChecker certificateChecker,
      Response response) {
    this.certificateChecker = certificateChecker;
    this.expirationWarningEmailSubjectText = expirationWarningEmailSubjectText;
    this.gmailClient = gmailClient;
    this.expirationWarningEmailBodyText = expirationWarningEmailBodyText;
    this.response = response;
  }

  @Override
  public void run() {
    response.setContentType(MediaType.PLAIN_TEXT_UTF_8);
    try {
      int numEmailsSent = sendNotificationEmails();
      String message =
          String.format(
              "Done. Sent %d expiring certificate notification emails in total.", numEmailsSent);
      logger.atInfo().log(message);
      response.setStatus(SC_OK);
      response.setPayload(message);
    } catch (Exception e) {
      logger.atWarning().withCause(e).log(
          "Exception thrown when sending expiring certificate notification emails.");
      response.setStatus(SC_INTERNAL_SERVER_ERROR);
      response.setPayload(String.format("Exception thrown with cause: %s", e));
    }
  }

  /**
   * Returns a list of registrars that should receive expiring notification emails.
   *
   * <p>There are two certificates that should be considered (the main certificate and failOver
   * certificate). The registrars should receive notifications if one of the certificate checks
   * returns true.
   */
  @VisibleForTesting
  ImmutableList<RegistrarInfo> getRegistrarsWithExpiringCertificates() {
    logger.atInfo().log(
        "Getting a list of registrars that should receive expiring notification emails.");
    return Streams.stream(Registrar.loadAllCached())
        .map(
            registrar ->
                new RegistrarInfo(
                    registrar,
                    registrar.getClientCertificate().isPresent()
                        && certificateChecker.shouldReceiveExpiringNotification(
                            registrar.getLastExpiringCertNotificationSentDate(),
                            registrar.getClientCertificate().get()),
                    registrar.getFailoverClientCertificate().isPresent()
                        && certificateChecker.shouldReceiveExpiringNotification(
                            registrar.getLastExpiringFailoverCertNotificationSentDate(),
                            registrar.getFailoverClientCertificate().get())))
        .filter(
            registrarInfo ->
                registrarInfo.isCertExpiring() || registrarInfo.isFailOverCertExpiring())
        .collect(toImmutableList());
  }

  /**
   * Sends a notification email to the registrar regarding the expiring certificate and returns true
   * if it's sent successfully.
   */
  @VisibleForTesting
  boolean sendNotificationEmail(
      Registrar registrar,
      DateTime lastExpiringCertNotificationSentDate,
      CertificateType certificateType,
      Optional<String> certificate) {
    if (certificate.isEmpty()
        || !certificateChecker.shouldReceiveExpiringNotification(
            lastExpiringCertNotificationSentDate, certificate.get())) {
      return false;
    }
    try {
      ImmutableSet<InternetAddress> recipients = getEmailAddresses(registrar, Type.TECH);
      ImmutableSet<InternetAddress> ccs = getEmailAddresses(registrar, Type.ADMIN);
      DateTime expirationDate =
          new DateTime(certificateChecker.getCertificate(certificate.get()).getNotAfter());
      logger.atInfo().log(
          " %s SSL certificate of registrar '%s' will expire on %s.",
          certificateType.getDisplayName(), registrar.getRegistrarName(), expirationDate);
      if (recipients.isEmpty() && ccs.isEmpty()) {
        logger.atWarning().log(
            "Registrar %s contains no TECH nor ADMIN email addresses to receive notification"
                + " email.",
            registrar.getRegistrarName());
        return false;
      }
      gmailClient.sendEmail(
          EmailMessage.newBuilder()
              .setSubject(expirationWarningEmailSubjectText)
              .setBody(
                  getEmailBody(
                      registrar.getRegistrarName(),
                      certificateType,
                      expirationDate,
                      registrar.getRegistrarId()))
              .setRecipients(recipients)
              .setCcs(ccs)
              .build());
      /*
       * A duration time offset is used here to ensure that date comparison between two
       * successive dates is always greater than 1 day. This date is set as last updated date,
       * for applicable certificate.
       */
      updateLastNotificationSentDate(
          registrar,
          DateTime.now(UTC).minusMinutes((int) UPDATE_TIME_OFFSET.getStandardMinutes()),
          certificateType);
      return true;
    } catch (Exception e) {
      throw new RuntimeException(
          String.format(
              "Failed to send expiring certificate notification email to registrar %s.",
              registrar.getRegistrarName()));
    }
  }

  /** Updates the last notification sent date in database. */
  @VisibleForTesting
  void updateLastNotificationSentDate(
      Registrar registrar, DateTime now, CertificateType certificateType) {
    try {
      tm().transact(
              () -> {
                Registrar.Builder newRegistrar = tm().loadByEntity(registrar).asBuilder();
                switch (certificateType) {
                  case PRIMARY:
                    newRegistrar.setLastExpiringCertNotificationSentDate(now);
                    tm().put(newRegistrar.build());
                    logger.atInfo().log(
                        "Updated last notification email sent date to %s for %s certificate of "
                            + "registrar %s.",
                        DATE_FORMATTER.print(now),
                        certificateType.getDisplayName(),
                        registrar.getRegistrarName());
                    break;
                  case FAILOVER:
                    newRegistrar.setLastExpiringFailoverCertNotificationSentDate(now);
                    tm().put(newRegistrar.build());
                    logger.atInfo().log(
                        "Updated last notification email sent date to %s for %s certificate of "
                            + "registrar %s.",
                        DATE_FORMATTER.print(now),
                        certificateType.getDisplayName(),
                        registrar.getRegistrarName());
                    break;
                  default:
                    throw new IllegalArgumentException(
                        String.format(
                            "Unsupported certificate type: %s being passed in when updating "
                                + "the last notification sent date to registrar %s.",
                            certificateType.toString(), registrar.getRegistrarName()));
                }
              });
    } catch (Exception e) {
      throw new RuntimeException(
          String.format(
              "Failed to update the last notification sent date to Registrar %s for the %s "
                  + "certificate.",
              registrar.getRegistrarName(), certificateType.getDisplayName()));
    }
  }

  /** Sends notification emails to registrars with expiring certificates. */
  @VisibleForTesting
  int sendNotificationEmails() {
    int numEmailsSent = 0;
    for (RegistrarInfo registrarInfo : getRegistrarsWithExpiringCertificates()) {
      Registrar registrar = registrarInfo.registrar();
      if (registrarInfo.isCertExpiring()
          && sendNotificationEmail(
              registrar,
              registrar.getLastExpiringCertNotificationSentDate(),
              CertificateType.PRIMARY,
              registrar.getClientCertificate())) {
        numEmailsSent++;
      }
      if (registrarInfo.isFailOverCertExpiring()
          && sendNotificationEmail(
              registrar,
              registrar.getLastExpiringFailoverCertNotificationSentDate(),
              CertificateType.FAILOVER,
              registrar.getFailoverClientCertificate())) {
        numEmailsSent++;
      }
    }
    return numEmailsSent;
  }

  /**
   * Returns a list of email addresses of the registrar that should receive a notification email.
   */
  @VisibleForTesting
  ImmutableSet<InternetAddress> getEmailAddresses(Registrar registrar, Type contactType) {
    ImmutableSortedSet<RegistrarPoc> contacts = registrar.getContactsOfType(contactType);
    ImmutableSet.Builder<InternetAddress> recipientEmails = new ImmutableSet.Builder<>();
    for (RegistrarPoc contact : contacts) {
      try {
        recipientEmails.add(new InternetAddress(contact.getEmailAddress()));
      } catch (AddressException e) {
        logger.atWarning().withCause(e).log(
            "Registrar Contact email address %s of Registrar %s is invalid; skipping.",
            contact.getEmailAddress(), registrar.getRegistrarName());
      }
    }
    return recipientEmails.build();
  }

  /**
   * Generates email content by taking registrar name, certificate type and expiration date as
   * parameters.
   */
  @VisibleForTesting
  @SuppressWarnings("lgtm[java/dereferenced-value-may-be-null]")
  String getEmailBody(
      String registrarName, CertificateType type, DateTime expirationDate, String registrarId) {
    checkArgumentNotNull(expirationDate, "Expiration date cannot be null");
    checkArgumentNotNull(type, "Certificate type cannot be null");
    checkArgumentNotNull(registrarId, "Registrar Id cannot be null");
    return String.format(
        expirationWarningEmailBodyText,
        registrarName,
        type.getDisplayName(),
        DATE_FORMATTER.print(expirationDate),
        registrarId);
  }

  /**
   * Certificate types for X509Certificate.
   *
   * <p><b>Note:</b> These types are only used to indicate the type of expiring certificate in
   * notification emails.
   */
  protected enum CertificateType {
    PRIMARY("primary"),
    FAILOVER("fail-over");

    private final String displayName;

    CertificateType(String displayName) {
      this.displayName = displayName;
    }

    public String getDisplayName() {
      return displayName;
    }
  }

  record RegistrarInfo(
      Registrar registrar, boolean isCertExpiring, boolean isFailOverCertExpiring) {}
}
