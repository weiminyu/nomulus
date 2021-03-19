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

package google.registry.config;

import static com.google.common.base.Suppliers.memoize;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSortedMap.toImmutableSortedMap;
import static google.registry.config.ConfigUtils.makeUrl;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static google.registry.util.ResourceUtils.readResourceUtf8;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static java.util.Comparator.naturalOrder;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Ascii;
import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import dagger.Module;
import dagger.Provides;
import google.registry.util.TaskQueueUtils;
import google.registry.util.YamlUtils;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.net.URI;
import java.net.URL;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import javax.inject.Named;
import javax.inject.Qualifier;
import javax.inject.Singleton;
import javax.mail.internet.AddressException;
import javax.mail.internet.InternetAddress;
import org.joda.time.DateTime;
import org.joda.time.DateTimeConstants;
import org.joda.time.Duration;

/**
 * Central clearing-house for all configuration.
 *
 * <p>This class does not represent the total configuration of the Nomulus service. It's <b>only
 * meant for settings that need to be configured <i>once</i></b>. Settings which may be subject to
 * change in the future, should instead be retrieved from Datastore. The {@link
 * google.registry.model.registry.Registry Registry} class is one such example of this.
 *
 * <p>Note: Only settings that are actually configurable belong in this file. It's not a catch-all
 * for anything widely used throughout the code base.
 */
public final class RegistryConfig {

  private static final String ENVIRONMENT_CONFIG_FORMAT = "files/nomulus-config-%s.yaml";
  private static final String YAML_CONFIG_PROD =
      readResourceUtf8(RegistryConfig.class, "files/default-config.yaml");

  /** Dagger qualifier for configuration settings. */
  @Qualifier
  @Retention(RUNTIME)
  @Documented
  public @interface Config {
    String value() default "";
  }

  /**
   * Loads the {@link RegistryConfigSettings} POJO from the YAML configuration files.
   *
   * <p>The {@code default-config.yaml} file in this directory is loaded first, and a fatal error is
   * thrown if it cannot be found or if there is an error parsing it. Separately, the
   * environment-specific config file named {@code nomulus-config-ENVIRONMENT.yaml} is also loaded
   * and those values merged into the POJO.
   */
  static RegistryConfigSettings getConfigSettings() {
    String configFilePath =
        String.format(
            ENVIRONMENT_CONFIG_FORMAT, Ascii.toLowerCase(RegistryEnvironment.get().name()));
    String customYaml = readResourceUtf8(RegistryConfig.class, configFilePath);
    return YamlUtils.getConfigSettings(YAML_CONFIG_PROD, customYaml, RegistryConfigSettings.class);
  }

  /** Dagger module for providing configuration settings. */
  @Module
  public static final class ConfigModule {

    @Provides
    @Config("projectId")
    public static String provideProjectId(RegistryConfigSettings config) {
      return config.appEngine.projectId;
    }

    /**
     * The filename of the logo to be displayed in the header of the registrar console.
     *
     * @see google.registry.ui.server.registrar.ConsoleUiAction
     */
    @Provides
    @Config("logoFilename")
    public static String provideLogoFilename(RegistryConfigSettings config) {
      return config.registrarConsole.logoFilename;
    }

    /**
     * The product name of this specific registry. Used throughout the registrar console.
     *
     * @see google.registry.ui.server.registrar.ConsoleUiAction
     */
    @Provides
    @Config("productName")
    public static String provideProductName(RegistryConfigSettings config) {
      return config.registryPolicy.productName;
    }

    /**
     * Returns the roid suffix to be used for the roids of all contacts and hosts. E.g. a value of
     * "ROID" would end up creating roids that look like "ABC123-ROID".
     *
     * @see <a href="http://www.iana.org/assignments/epp-repository-ids/epp-repository-ids.xhtml">
     *     Extensible Provisioning Protocol (EPP) Repository Identifiers</a>
     */
    @Provides
    @Config("contactAndHostRoidSuffix")
    public static String provideContactAndHostRoidSuffix(RegistryConfigSettings config) {
      return config.registryPolicy.contactAndHostRoidSuffix;
    }

    /**
     * The e-mail address for questions about integrating with the registry. Used in the
     * "contact-us" section of the registrar console.
     *
     * @see google.registry.ui.server.registrar.ConsoleUiAction
     */
    @Provides
    @Config("integrationEmail")
    public static String provideIntegrationEmail(RegistryConfigSettings config) {
      return config.registrarConsole.integrationEmailAddress;
    }

    /**
     * The e-mail address for general support. Used in the "contact-us" section of the registrar
     * console.
     *
     * @see google.registry.ui.server.registrar.ConsoleUiAction
     */
    @Provides
    @Config("supportEmail")
    public static String provideSupportEmail(RegistryConfigSettings config) {
      return config.registrarConsole.supportEmailAddress;
    }

    /**
     * The "From" e-mail address for announcements. Used in the "contact-us" section of the
     * registrar console.
     *
     * @see google.registry.ui.server.registrar.ConsoleUiAction
     */
    @Provides
    @Config("announcementsEmail")
    public static String provideAnnouncementsEmail(RegistryConfigSettings config) {
      return config.registrarConsole.announcementsEmailAddress;
    }

    /**
     * The contact phone number. Used in the "contact-us" section of the registrar console.
     *
     * @see google.registry.ui.server.registrar.ConsoleUiAction
     */
    @Provides
    @Config("supportPhoneNumber")
    public static String provideSupportPhoneNumber(RegistryConfigSettings config) {
      return config.registrarConsole.supportPhoneNumber;
    }

    /**
     * The URL for technical support docs. Used in the "contact-us" section of the registrar
     * console.
     *
     * @see google.registry.ui.server.registrar.ConsoleUiAction
     */
    @Provides
    @Config("technicalDocsUrl")
    public static String provideTechnicalDocsUrl(RegistryConfigSettings config) {
      return config.registrarConsole.technicalDocsUrl;
    }

    /**
     * Configuration for analytics services installed in the web console.
     *
     * @see google.registry.ui.server.registrar.ConsoleUiAction
     * @see google.registry.ui.soy.AnalyticsSoyInfo
     */
    @Provides
    @Config("analyticsConfig")
    public static Map<String, Object> provideAnalyticsConfig(RegistryConfigSettings config) {
      // Can't be an ImmutableMap because it may contain null values.
      HashMap<String, Object> analyticsConfig = new HashMap<>();
      analyticsConfig.put(
          "googleAnalyticsId", config.registrarConsole.analyticsConfig.googleAnalyticsId);
      return Collections.unmodifiableMap(analyticsConfig);
    }

    /**
     * Returns the Google Cloud Storage bucket for storing zone files.
     *
     * @see google.registry.backup.ExportCommitLogDiffAction
     */
    @Provides
    @Config("zoneFilesBucket")
    public static String provideZoneFilesBucket(@Config("projectId") String projectId) {
      return projectId + "-zonefiles";
    }

    /**
     * Returns the Google Cloud Storage bucket for storing commit logs.
     *
     * @see google.registry.backup.ExportCommitLogDiffAction
     */
    @Provides
    @Config("commitLogGcsBucket")
    public static String provideCommitLogGcsBucket(@Config("projectId") String projectId) {
      return projectId + "-commits";
    }

    /** @see RegistryConfig#getCommitLogDatastoreRetention() */
    @Provides
    @Config("commitLogDatastoreRetention")
    public static Duration provideCommitLogDatastoreRetention() {
      return RegistryConfig.getCommitLogDatastoreRetention();
    }

    /**
     * The GCS bucket for exporting domain lists.
     *
     * @see google.registry.export.ExportDomainListsAction
     */
    @Provides
    @Config("domainListsGcsBucket")
    public static String provideDomainListsGcsBucket(@Config("projectId") String projectId) {
      return projectId + "-domain-lists";
    }

    /**
     * Batch size for the number of transactions' worth of commit log data to process at once when
     * exporting a commit log diff.
     *
     * @see google.registry.backup.ExportCommitLogDiffAction
     */
    @Provides
    @Config("commitLogDiffExportBatchSize")
    public static int provideCommitLogDiffExportBatchSize() {
      return 100;
    }

    /**
     * Returns the Google Cloud Storage bucket for staging BRDA escrow deposits.
     *
     * @see google.registry.rde.PendingDepositChecker
     */
    @Provides
    @Config("brdaBucket")
    public static String provideBrdaBucket(@Config("projectId") String projectId) {
      return projectId + "-icann-brda";
    }

    /** @see google.registry.rde.BrdaCopyAction */
    @Provides
    @Config("brdaDayOfWeek")
    public static int provideBrdaDayOfWeek() {
      return DateTimeConstants.TUESDAY;
    }

    /**
     * Amount of time between BRDA deposits.
     *
     * @see google.registry.rde.PendingDepositChecker
     */
    @Provides
    @Config("brdaInterval")
    public static Duration provideBrdaInterval() {
      return Duration.standardDays(7);
    }

    /**
     * The maximum number of domain and host updates to batch together to send to
     * PublishDnsUpdatesAction, to avoid exceeding AppEngine's limits.
     *
     * @see google.registry.dns.ReadDnsQueueAction
     */
    @Provides
    @Config("dnsTldUpdateBatchSize")
    public static int provideDnsTldUpdateBatchSize() {
      return 100;
    }

    /**
     * The maximum time we allow publishDnsUpdates to run.
     *
     * <p>This is the maximum lock duration for publishing the DNS updates, meaning it should allow
     * the various DnsWriters to publish and commit an entire batch (with a maximum number of items
     * set by provideDnsTldUpdateBatchSize).
     *
     * <p>Any update that takes longer than this timeout will be killed and retried from scratch.
     * Hence, a timeout that's too short can result in batches that retry over and over again,
     * failing forever.
     *
     * <p>If there are lock contention issues, they should be solved by changing the batch sizes or
     * the cron job rate, NOT by making this value smaller.
     *
     * @see google.registry.dns.PublishDnsUpdatesAction
     */
    @Provides
    @Config("publishDnsUpdatesLockDuration")
    public static Duration providePublishDnsUpdatesLockDuration() {
      return Duration.standardMinutes(3);
    }

    /**
     * The requested maximum duration for ReadDnsQueueAction.
     *
     * <p>ReadDnsQueueAction reads update tasks from the dns-pull queue. It will continue reading
     * tasks until either the queue is empty, or this duration has passed.
     *
     * <p>This time is the maximum duration between the first and last attempt to lease tasks from
     * the dns-pull queue. The actual running time might be slightly longer, as we process the
     * tasks.
     *
     * <p>This value should be less than the cron-job repeat rate for ReadDnsQueueAction, to make
     * sure we don't have multiple ReadDnsActions leasing tasks simultaneously.
     *
     * @see google.registry.dns.ReadDnsQueueAction
     */
    @Provides
    @Config("readDnsQueueActionRuntime")
    public static Duration provideReadDnsQueueRuntime() {
      return Duration.standardSeconds(45);
    }

    /**
     * Returns the default time to live for DNS A and AAAA records.
     *
     * @see google.registry.dns.writer.clouddns.CloudDnsWriter
     */
    @Provides
    @Config("dnsDefaultATtl")
    public static Duration provideDnsDefaultATtl() {
      return Duration.standardHours(1);
    }

    /**
     * Returns the default time to live for DNS NS records.
     *
     * @see google.registry.dns.writer.clouddns.CloudDnsWriter
     */
    @Provides
    @Config("dnsDefaultNsTtl")
    public static Duration provideDnsDefaultNsTtl() {
      return Duration.standardHours(3);
    }

    /**
     * Returns the default time to live for DNS DS records.
     *
     * @see google.registry.dns.writer.clouddns.CloudDnsWriter
     */
    @Provides
    @Config("dnsDefaultDsTtl")
    public static Duration provideDnsDefaultDsTtl() {
      return Duration.standardHours(1);
    }

    /**
     * Number of sharded entity group roots used for performing strongly consistent scans.
     *
     * <p><b>Warning:</b> This number may increase but never decrease.
     *
     * @see google.registry.model.index.EppResourceIndex
     */
    @Provides
    @Config("eppResourceIndexBucketCount")
    public static int provideEppResourceIndexBucketCount(RegistryConfigSettings config) {
      return config.datastore.eppResourceIndexBucketsNum;
    }

    @Provides
    @Config("cloudSqlJdbcUrl")
    public static String providesCloudSqlJdbcUrl(RegistryConfigSettings config) {
      return config.cloudSql.jdbcUrl;
    }

    @Provides
    @Config("cloudSqlUsername")
    public static String providesCloudSqlUsername(RegistryConfigSettings config) {
      return config.cloudSql.username;
    }

    @Provides
    @Config("cloudSqlInstanceConnectionName")
    public static String providesCloudSqlInstanceConnectionName(RegistryConfigSettings config) {
      return config.cloudSql.instanceConnectionName;
    }

    @Provides
    @Config("cloudSqlDbInstanceName")
    public static String providesCloudSqlDbInstance(RegistryConfigSettings config) {
      // Format of instanceConnectionName: project-id:region:instance-name
      int lastColonIndex = config.cloudSql.instanceConnectionName.lastIndexOf(':');
      return config.cloudSql.instanceConnectionName.substring(lastColonIndex + 1);
    }

    @Provides
    @Config("cloudDnsRootUrl")
    public static Optional<String> getCloudDnsRootUrl(RegistryConfigSettings config) {
      return Optional.ofNullable(config.cloudDns.rootUrl);
    }

    @Provides
    @Config("cloudDnsServicePath")
    public static Optional<String> getCloudDnsServicePath(RegistryConfigSettings config) {
      return Optional.ofNullable(config.cloudDns.servicePath);
    }

    /**
     * Returns size of Google Cloud Storage client connection buffer in bytes.
     *
     * @see google.registry.gcs.GcsUtils
     */
    @Provides
    @Config("gcsBufferSize")
    public static int provideGcsBufferSize() {
      return 1024 * 1024;
    }

    /**
     * Returns the email address of the admin account on the G Suite app used to perform
     * administrative actions.
     *
     * @see google.registry.groups.DirectoryGroupsConnection
     */
    @Provides
    @Config("gSuiteAdminAccountEmailAddress")
    public static String provideGSuiteAdminAccountEmailAddress(RegistryConfigSettings config) {
      return config.gSuite.adminAccountEmailAddress;
    }

    /**
     * Returns the email address of the group containing emails of support accounts.
     *
     * <p>These accounts will have "ADMIN" access to the registrar console.
     *
     * @see google.registry.groups.DirectoryGroupsConnection
     */
    @Provides
    @Config("gSuiteSupportGroupEmailAddress")
    public static Optional<String> provideGSuiteSupportGroupEmailAddress(
        RegistryConfigSettings config) {
      return Optional.ofNullable(Strings.emptyToNull(config.gSuite.supportGroupEmailAddress));
    }

    /**
     * Returns the email address(es) that notifications of registrar and/or registrar contact
     * updates should be sent to, or the empty list if updates should not be sent.
     *
     * @see google.registry.ui.server.registrar.RegistrarSettingsAction
     */
    @Provides
    @Config("registrarChangesNotificationEmailAddresses")
    public static ImmutableList<String> provideRegistrarChangesNotificationEmailAddresses(
        RegistryConfigSettings config) {
      return ImmutableList.copyOf(config.registryPolicy.registrarChangesNotificationEmailAddresses);
    }

    /**
     * Returns the publicly accessible domain name for the running G Suite instance.
     *
     * @see google.registry.export.SyncGroupMembersAction
     * @see google.registry.tools.server.CreateGroupsAction
     */
    @Provides
    @Config("gSuiteDomainName")
    public static String provideGSuiteDomainName(RegistryConfigSettings config) {
      return config.gSuite.domainName;
    }

    /**
     * Returns the mode that TMCH certificate authority should run in.
     *
     * @see google.registry.tmch.TmchCertificateAuthority
     */
    @Provides
    @Config("tmchCaMode")
    public static TmchCaMode provideTmchCaMode(RegistryConfigSettings config) {
      return TmchCaMode.valueOf(config.registryPolicy.tmchCaMode);
    }

    /** The mode that the {@code TmchCertificateAuthority} operates in. */
    public enum TmchCaMode {

      /** Production mode, suitable for live environments hosting TLDs. */
      PRODUCTION,

      /** Pilot mode, for everything else (e.g. sandbox). */
      PILOT
    }

    /**
     * ICANN TMCH Certificate Revocation List URL.
     *
     * <p>This file needs to be downloaded at least once a day and verified to make sure it was
     * signed by {@code icann-tmch.crt} or {@code icann-tmch-pilot.crt} depending on TMCH CA mode.
     *
     * @see google.registry.tmch.TmchCrlAction
     * @see <a href="http://tools.ietf.org/html/draft-lozano-tmch-func-spec-08#section-5.2.3.2">TMCH
     *     RFC</a>
     */
    @Provides
    @Config("tmchCrlUrl")
    public static URL provideTmchCrlUrl(RegistryConfigSettings config) {
      return makeUrl(config.registryPolicy.tmchCrlUrl);
    }

    /**
     * URL prefix for communicating with MarksDB ry interface.
     *
     * <p>This URL is used for DNL, SMDRL, and LORDN.
     *
     * @see google.registry.tmch.Marksdb
     * @see google.registry.tmch.NordnUploadAction
     */
    @Provides
    @Config("tmchMarksdbUrl")
    public static String provideTmchMarksdbUrl(RegistryConfigSettings config) {
      return config.registryPolicy.tmchMarksDbUrl;
    }

    /**
     * The email address that outgoing emails from the app are sent from.
     *
     * @see google.registry.ui.server.SendEmailUtils
     */
    @Provides
    @Config("gSuiteOutgoingEmailAddress")
    public static InternetAddress provideGSuiteOutgoingEmailAddress(RegistryConfigSettings config) {
      return parseEmailAddress(config.gSuite.outgoingEmailAddress);
    }

    /**
     * The display name that is used on outgoing emails sent by Nomulus.
     *
     * @see google.registry.ui.server.SendEmailUtils
     */
    @Provides
    @Config("gSuiteOutgoingEmailDisplayName")
    public static String provideGSuiteOutgoingEmailDisplayName(RegistryConfigSettings config) {
      return config.gSuite.outgoingEmailDisplayName;
    }

    /**
     * Returns the name of the GCS bucket for storing Beam templates and results.
     *
     * @see google.registry.reporting.billing.GenerateInvoicesAction
     */
    @Provides
    @Config("apacheBeamBucket")
    public static String provideApacheBeamBucket(@Config("projectId") String projectId) {
      return projectId + "-beam";
    }

    /**
     * Returns the URL of the GCS location for storing Apache Beam related objects.
     *
     * @see google.registry.reporting.billing.GenerateInvoicesAction
     */
    @Provides
    @Config("apacheBeamBucketUrl")
    public static String provideApacheBeamBucketUrl(@Config("apacheBeamBucket") String beamBucket) {
      return "gs://" + beamBucket;
    }

    /**
     * Returns the URL of the GCS location for storing the monthly invoicing Beam template.
     *
     * @see google.registry.reporting.billing.GenerateInvoicesAction
     * @see google.registry.beam.invoicing.InvoicingPipeline
     */
    @Provides
    @Config("invoiceTemplateUrl")
    public static String provideInvoiceTemplateUrl(
        @Config("apacheBeamBucketUrl") String beamBucketUrl) {
      return beamBucketUrl + "/templates/invoicing";
    }

    /**
     * Returns the URL of the GCS location for storing the monthly spec11 Beam template.
     *
     * @see google.registry.beam.spec11.Spec11Pipeline
     */
    @Provides
    @Config("spec11TemplateUrl")
    public static String provideSpec11TemplateUrl(
        @Config("apacheBeamBucketUrl") String beamBucketUrl) {
      return beamBucketUrl + "/templates/spec11";
    }

    /**
     * Returns whether an SSL certificate hash is required to log in via EPP and run flows.
     *
     * @see google.registry.flows.TlsCredentials
     */
    @Provides
    @Config("requireSslCertificates")
    public static boolean provideRequireSslCertificates(RegistryConfigSettings config) {
      return config.registryPolicy.requireSslCertificates;
    }

    /**
     * Returns the default job region to run Apache Beam (Cloud Dataflow) jobs in.
     *
     * @see google.registry.beam.invoicing.InvoicingPipeline
     * @see google.registry.beam.spec11.Spec11Pipeline
     */
    @Provides
    @Config("defaultJobRegion")
    public static String provideDefaultJobRegion(RegistryConfigSettings config) {
      return config.beam.defaultJobRegion;
    }

    /**
     * Returns the default job zone to run Apache Beam (Cloud Dataflow) jobs in.
     *
     * @see google.registry.reporting.billing.GenerateInvoicesAction
     * @see google.registry.reporting.spec11.GenerateSpec11ReportAction
     */
    @Provides
    @Config("defaultJobZone")
    public static String provideDefaultJobZone(RegistryConfigSettings config) {
      return config.beam.defaultJobZone;
    }

    /** Returns the URL string to the GCS bucket that contains all staged BEAM flex templates. */
    @Provides
    @Config("beamStagedTemplateBucketUrl")
    public static String provideBeamStagingProjectId(
        RegistryConfigSettings config, @Config("projectId") String projectId) {
      String stagingProject = Optional.ofNullable(config.beam.stagingProjectId).orElse(projectId);
      return String.format("gs://%s-deploy/%s/beam/", stagingProject, config.beam.releaseTag);
    }

    /**
     * Returns the URL of the GCS location we store jar dependencies for beam pipelines.
     *
     * @see google.registry.beam.invoicing.InvoicingPipeline
     * @see google.registry.beam.spec11.Spec11Pipeline
     */
    @Provides
    @Config("beamStagingUrl")
    public static String provideInvoiceStagingUrl(
        @Config("apacheBeamBucketUrl") String beamBucketUrl) {
      return beamBucketUrl + "/staging";
    }

    /**
     * Returns the Google Cloud Storage bucket for Spec11 and ICANN transaction and activity reports
     * to be uploaded.
     *
     * @see google.registry.reporting.icann.IcannReportingUploadAction
     */
    @Provides
    @Config("reportingBucket")
    public static String provideReportingBucket(@Config("projectId") String projectId) {
      return projectId + "-reporting";
    }

    /**
     * Returns the Google Cloud Storage bucket URL for Spec11 and ICANN transaction and activity
     * reports to be uploaded.
     *
     * @see google.registry.reporting.icann.IcannReportingUploadAction
     * @see google.registry.reporting.spec11.PublishSpec11ReportAction
     */
    @Provides
    @Config("reportingBucketUrl")
    public static String provideReportingBucketUrl(
        @Config("reportingBucket") String reportingBucket) {
      return "gs://" + reportingBucket;
    }

    /**
     * Returns the URL we send HTTP PUT requests for ICANN monthly transactions reports.
     *
     * @see google.registry.reporting.icann.IcannHttpReporter
     */
    @Provides
    @Config("icannTransactionsReportingUploadUrl")
    public static String provideIcannTransactionsReportingUploadUrl(RegistryConfigSettings config) {
      return config.icannReporting.icannTransactionsReportingUploadUrl;
    }

    /**
     * Returns the URL we send HTTP PUT requests for ICANN monthly activity reports.
     *
     * @see google.registry.reporting.icann.IcannHttpReporter
     */
    @Provides
    @Config("icannActivityReportingUploadUrl")
    public static String provideIcannActivityReportingUploadUrl(RegistryConfigSettings config) {
      return config.icannReporting.icannActivityReportingUploadUrl;
    }

    /**
     * Returns name of the GCS bucket we store invoices and detail reports in.
     *
     * @see google.registry.reporting.billing.CopyDetailReportsAction
     * @see google.registry.reporting.billing.BillingEmailUtils
     */
    @Provides
    @Config("billingBucket")
    public static String provideBillingBucket(@Config("projectId") String projectId) {
      return projectId + "-billing";
    }

    /**
     * Returns the URL of the GCS bucket we store invoices and detail reports in.
     *
     * @see google.registry.beam.invoicing.InvoicingPipeline
     */
    @Provides
    @Config("billingBucketUrl")
    public static String provideBillingBucketUrl(@Config("billingBucket") String billingBucket) {
      return "gs://" + billingBucket;
    }

    /**
     * Returns whether or not we should publish invoices to partners automatically by default.
     *
     * @see google.registry.reporting.billing.BillingModule
     */
    @Provides
    @Config("defaultShouldPublishInvoices")
    public static boolean provideDefaultShouldPublishInvoices() {
      return true;
    }

    /**
     * Returns the list of addresses that receive monthly invoicing emails.
     *
     * @see google.registry.reporting.billing.BillingEmailUtils
     */
    @Provides
    @Config("invoiceEmailRecipients")
    public static ImmutableList<InternetAddress> provideInvoiceEmailRecipients(
        RegistryConfigSettings config) {
      return config.billing.invoiceEmailRecipients.stream()
          .map(RegistryConfig::parseEmailAddress)
          .collect(toImmutableList());
    }

    /**
     * Returns the file prefix for the invoice CSV file.
     *
     * @see google.registry.beam.invoicing.InvoicingPipeline
     * @see google.registry.reporting.billing.BillingEmailUtils
     */
    @Provides
    @Config("invoiceFilePrefix")
    public static String provideInvoiceFilePrefix(RegistryConfigSettings config) {
      return config.billing.invoiceFilePrefix;
    }

    /**
     * Returns the Google Cloud Storage bucket for staging escrow deposits pending upload.
     *
     * @see google.registry.rde.RdeStagingAction
     */
    @Provides
    @Config("rdeBucket")
    public static String provideRdeBucket(@Config("projectId") String projectId) {
      return projectId + "-rde";
    }

    /**
     * Amount of time between RDE deposits.
     *
     * @see google.registry.rde.PendingDepositChecker
     * @see google.registry.rde.RdeReportAction
     * @see google.registry.rde.RdeUploadAction
     */
    @Provides
    @Config("rdeInterval")
    public static Duration provideRdeInterval() {
      return Duration.standardDays(1);
    }

    /**
     * Maximum amount of time for sending a small XML file to ICANN via HTTP, before killing.
     *
     * @see google.registry.rde.RdeReportAction
     */
    @Provides
    @Config("rdeReportLockTimeout")
    public static Duration provideRdeReportLockTimeout() {
      return Duration.standardMinutes(1);
    }

    /**
     * URL of ICANN's HTTPS server to which the RDE report should be {@code PUT}.
     *
     * <p>You must append {@code "/TLD/ID"} to this URL.
     *
     * @see google.registry.rde.RdeReportAction
     */
    @Provides
    @Config("rdeReportUrlPrefix")
    public static String provideRdeReportUrlPrefix(RegistryConfigSettings config) {
      return config.rde.reportUrlPrefix;
    }

    /**
     * Maximum amount of time generating an escrow deposit for a TLD could take, before killing.
     *
     * @see google.registry.rde.RdeStagingReducer
     */
    @Provides
    @Config("rdeStagingLockTimeout")
    public static Duration provideRdeStagingLockTimeout() {
      return Duration.standardHours(2);
    }

    /**
     * Maximum amount of time it should ever take to upload an escrow deposit, before killing.
     *
     * @see google.registry.rde.RdeUploadAction
     */
    @Provides
    @Config("rdeUploadLockTimeout")
    public static Duration provideRdeUploadLockTimeout() {
      return Duration.standardMinutes(30);
    }

    /**
     * Minimum amount of time to wait between consecutive SFTP uploads on a single TLD.
     *
     * <p>This value was communicated to us by the escrow provider.
     *
     * @see google.registry.rde.RdeStagingReducer
     */
    @Provides
    @Config("rdeUploadSftpCooldown")
    public static Duration provideRdeUploadSftpCooldown() {
      return Duration.standardHours(2);
    }

    /**
     * Returns the identity (an email address) used for the SSH keys used in RDE SFTP uploads.
     *
     * @see google.registry.keyring.api.Keyring#getRdeSshClientPublicKey()
     * @see google.registry.keyring.api.Keyring#getRdeSshClientPrivateKey()
     */
    @Provides
    @Config("rdeSshIdentity")
    public static String provideSshIdentity(RegistryConfigSettings config) {
      return config.rde.sshIdentityEmailAddress;
    }

    /**
     * Returns SFTP URL containing a username, hostname, port (optional), and directory (optional)
     * to which cloud storage files are uploaded. The password should not be included, as it's
     * better to use public key authentication.
     *
     * @see google.registry.rde.RdeUploadAction
     */
    @Provides
    @Config("rdeUploadUrl")
    public static URI provideRdeUploadUrl(RegistryConfigSettings config) {
      return URI.create(config.rde.uploadUrl);
    }

    /**
     * Whether or not the registrar console is enabled.
     *
     * @see google.registry.ui.server.registrar.ConsoleUiAction
     */
    @Provides
    @Config("registrarConsoleEnabled")
    public static boolean provideRegistrarConsoleEnabled() {
      return true;
    }

    /**
     * Maximum amount of time for syncing a spreadsheet, before killing.
     *
     * @see google.registry.export.sheet.SyncRegistrarsSheetAction
     */
    @Provides
    @Config("sheetLockTimeout")
    public static Duration provideSheetLockTimeout() {
      return Duration.standardHours(1);
    }

    /**
     * Returns ID of Google Spreadsheet to which Registrar entities should be synced.
     *
     * <p>This ID, as you'd expect, comes from the URL of the spreadsheet.
     *
     * @see google.registry.export.sheet.SyncRegistrarsSheetAction
     */
    @Provides
    @Config("sheetRegistrarId")
    public static Optional<String> provideSheetRegistrarId(RegistryConfigSettings config) {
      return Optional.ofNullable(config.misc.sheetExportId);
    }

    /**
     * Returns the email address we send various alert e-mails to.
     *
     * <p>This allows us to easily verify the success or failure of periodic tasks by passively
     * checking e-mail.
     *
     * @see google.registry.reporting.billing.BillingEmailUtils
     * @see google.registry.reporting.spec11.Spec11EmailUtils
     */
    @Provides
    @Config("alertRecipientEmailAddress")
    public static InternetAddress provideAlertRecipientEmailAddress(RegistryConfigSettings config) {
      return parseEmailAddress(config.misc.alertRecipientEmailAddress);
    }

    /**
     * Returns the email address to which spec 11 email should be replied.
     *
     * @see google.registry.reporting.spec11.Spec11EmailUtils
     */
    @Provides
    @Config("spec11OutgoingEmailAddress")
    public static InternetAddress provideSpec11OutgoingEmailAddress(RegistryConfigSettings config) {
      return parseEmailAddress(config.misc.spec11OutgoingEmailAddress);
    }

    /**
     * Returns the email addresses to which we will BCC Spec11 emails.
     *
     * @see google.registry.reporting.spec11.Spec11EmailUtils
     */
    @Provides
    @Config("spec11BccEmailAddresses")
    public static ImmutableList<InternetAddress> provideSpec11BccEmailAddresses(
        RegistryConfigSettings config) {
      return config.misc.spec11BccEmailAddresses.stream()
          .map(RegistryConfig::parseEmailAddress)
          .collect(toImmutableList());
    }

    /**
     * Returns the name of the registry, for use in spec 11 emails.
     *
     * @see google.registry.reporting.spec11.Spec11EmailUtils
     */
    @Provides
    @Config("registryName")
    public static String provideRegistryName(RegistryConfigSettings config) {
      return config.registryPolicy.registryName;
    }

    /**
     * Returns a list of resources we send to registrars when informing them of spec 11 threats.
     *
     * @see google.registry.reporting.spec11.Spec11EmailUtils
     */
    @Provides
    @Config("spec11WebResources")
    public static ImmutableList<String> provideSpec11WebResources(RegistryConfigSettings config) {
      return ImmutableList.copyOf(config.registryPolicy.spec11WebResources);
    }

    /**
     * Returns SSH client connection and read timeout.
     *
     * @see google.registry.rde.RdeUploadAction
     */
    @Provides
    @Config("sshTimeout")
    public static Duration provideSshTimeout() {
      return Duration.standardSeconds(30);
    }

    /**
     * Duration after watermark where we shouldn't deposit, because transactions might be pending.
     *
     * @see google.registry.rde.RdeStagingAction
     */
    @Provides
    @Config("transactionCooldown")
    public static Duration provideTransactionCooldown() {
      return Duration.standardMinutes(5);
    }

    /**
     * Number of times to retry a GAE operation when {@code TransientFailureException} is thrown.
     *
     * <p>The number of milliseconds it'll sleep before giving up is {@code (2^n - 2) * 100}.
     *
     * <p>Note that this uses {@code @Named} instead of {@code @Config} so that it can be used from
     * the low-level util package, which cannot have a dependency on the config package.
     *
     * @see TaskQueueUtils
     */
    @Provides
    @Named("transientFailureRetries")
    public static int provideTransientFailureRetries(RegistryConfigSettings config) {
      return config.misc.transientFailureRetries;
    }

    /**
     * Amount of time public HTTP proxies are permitted to cache our WHOIS responses.
     *
     * @see google.registry.whois.WhoisHttpAction
     */
    @Provides
    @Config("whoisHttpExpires")
    public static Duration provideWhoisHttpExpires() {
      return Duration.standardDays(1);
    }

    /**
     * Maximum number of results to return for an RDAP search query
     *
     * @see google.registry.rdap.RdapActionBase
     */
    @Provides
    @Config("rdapResultSetMaxSize")
    public static int provideRdapResultSetMaxSize() {
      return 100;
    }

    /**
     * Redaction text for email address in WHOIS
     *
     * @see google.registry.whois.WhoisResponse
     */
    @Provides
    @Config("whoisRedactedEmailText")
    public static String provideWhoisRedactedEmailText(RegistryConfigSettings config) {
      return config.registryPolicy.whoisRedactedEmailText;
    }

    /**
     * Disclaimer displayed at the end of WHOIS query results.
     *
     * @see google.registry.whois.WhoisResponse
     */
    @Provides
    @Config("whoisDisclaimer")
    public static String provideWhoisDisclaimer(RegistryConfigSettings config) {
      return config.registryPolicy.whoisDisclaimer;
    }

    /**
     * Maximum QPS for the Google Cloud Monitoring V3 (aka Stackdriver) API. The QPS limit can be
     * adjusted by contacting Cloud Support.
     *
     * @see com.google.monitoring.metrics.stackdriver.StackdriverWriter
     */
    @Provides
    @Config("stackdriverMaxQps")
    public static int provideStackdriverMaxQps(RegistryConfigSettings config) {
      return config.monitoring.stackdriverMaxQps;
    }

    /**
     * Maximum number of points that can be sent to Stackdriver in a single {@code
     * TimeSeries.Create} API call.
     *
     * @see com.google.monitoring.metrics.stackdriver.StackdriverWriter
     */
    @Provides
    @Config("stackdriverMaxPointsPerRequest")
    public static int provideStackdriverMaxPointsPerRequest(RegistryConfigSettings config) {
      return config.monitoring.stackdriverMaxPointsPerRequest;
    }

    /**
     * The reporting interval for {@link com.google.monitoring.metrics.Metric} instances to be sent
     * to a {@link com.google.monitoring.metrics.MetricWriter}.
     *
     * @see com.google.monitoring.metrics.MetricReporter
     */
    @Provides
    @Config("metricsWriteInterval")
    public static Duration provideMetricsWriteInterval(RegistryConfigSettings config) {
      return Duration.standardSeconds(config.monitoring.writeIntervalSeconds);
    }

    /**
     * The global automatic transfer length for contacts. After this amount of time has elapsed, the
     * transfer is automatically approved.
     *
     * @see google.registry.flows.contact.ContactTransferRequestFlow
     */
    @Provides
    @Config("contactAutomaticTransferLength")
    public static Duration provideContactAutomaticTransferLength(RegistryConfigSettings config) {
      return Duration.standardDays(config.registryPolicy.contactAutomaticTransferDays);
    }

    /**
     * Returns the maximum number of entities that can be checked at one time in an EPP check flow.
     */
    @Provides
    @Config("maxChecks")
    public static int provideMaxChecks() {
      return 50;
    }

    /**
     * Returns the delay before executing async delete flow mapreduces.
     *
     * <p>This delay should be sufficiently longer than a transaction, to solve the following
     * problem:
     *
     * <ul>
     *   <li>a domain mutation flow starts a transaction
     *   <li>the domain flow non-transactionally reads a resource and sees that it's not in
     *       PENDING_DELETE
     *   <li>the domain flow creates a new reference to this resource
     *   <li>a contact/host delete flow runs and marks the resource PENDING_DELETE and commits
     *   <li>the domain flow commits
     * </ul>
     *
     * <p>Although we try not to add references to a PENDING_DELETE resource, strictly speaking that
     * is ok as long as the mapreduce eventually sees the new reference (and therefore
     * asynchronously fails the delete). Without this delay, the mapreduce might have started before
     * the domain flow committed, and could potentially miss the reference.
     *
     * <p>If you are using EPP resource caching (eppResourceCachingEnabled in YAML), then this
     * duration should also be longer than that cache duration (eppResourceCachingSeconds).
     *
     * @see google.registry.config.RegistryConfigSettings.Caching
     * @see google.registry.batch.AsyncTaskEnqueuer
     */
    @Provides
    @Config("asyncDeleteFlowMapreduceDelay")
    public static Duration provideAsyncDeleteFlowMapreduceDelay(RegistryConfigSettings config) {
      return Duration.standardSeconds(config.misc.asyncDeleteDelaySeconds);
    }

    /**
     * The server ID used in the 'svID' element of an EPP 'greeting'.
     *
     * @see <a href="https://tools.ietf.org/html/rfc5730">RFC 7530</a>
     */
    @Provides
    @Config("greetingServerId")
    public static String provideGreetingServerId(RegistryConfigSettings config) {
      return config.registryPolicy.greetingServerId;
    }

    @Provides
    @Config("activeKeyring")
    public static String provideKeyring(RegistryConfigSettings config) {
      return config.keyring.activeKeyring;
    }

    /**
     * The name to use for the Cloud KMS KeyRing containing encryption keys for Nomulus secrets.
     *
     * @see <a
     *     href="https://cloud.google.com/kms/docs/reference/rest/v1/projects.locations.keyRings#KeyRing">projects.locations.keyRings</a>
     */
    @Provides
    @Config("cloudKmsKeyRing")
    public static String provideCloudKmsKeyRing(RegistryConfigSettings config) {
      return config.keyring.kms.keyringName;
    }

    @Provides
    @Config("cloudKmsProjectId")
    public static String provideCloudKmsProjectId(RegistryConfigSettings config) {
      return config.keyring.kms.projectId;
    }

    @Provides
    @Config("customLogicFactoryClass")
    public static String provideCustomLogicFactoryClass(RegistryConfigSettings config) {
      return config.registryPolicy.customLogicFactoryClass;
    }

    @Provides
    @Config("whoisCommandFactoryClass")
    public static String provideWhoisCommandFactoryClass(RegistryConfigSettings config) {
      return config.registryPolicy.whoisCommandFactoryClass;
    }

    @Provides
    @Config("allocationTokenCustomLogicClass")
    public static String provideAllocationTokenCustomLogicClass(RegistryConfigSettings config) {
      return config.registryPolicy.allocationTokenCustomLogicClass;
    }

    @Provides
    @Config("dnsCountQueryCoordinatorClass")
    public static String dnsCountQueryCoordinatorClass(RegistryConfigSettings config) {
      return config.registryPolicy.dnsCountQueryCoordinatorClass;
    }

    /** Returns the disclaimer text for the exported premium terms. */
    @Provides
    @Config("premiumTermsExportDisclaimer")
    public static String providePremiumTermsExportDisclaimer(RegistryConfigSettings config) {
      return formatComments(config.registryPolicy.premiumTermsExportDisclaimer);
    }

    /**
     * Returns the header text at the top of the reserved terms exported list.
     *
     * @see google.registry.export.ExportUtils#exportReservedTerms
     */
    @Provides
    @Config("reservedTermsExportDisclaimer")
    public static String provideReservedTermsExportDisclaimer(RegistryConfigSettings config) {
      return formatComments(config.registryPolicy.reservedTermsExportDisclaimer);
    }

    /** Returns the clientId of the registrar used by the {@code CheckApiServlet}. */
    // TODO(b/80417678): remove this once CheckApiAction no longer uses this id.
    @Provides
    @Config("checkApiServletRegistrarClientId")
    public static String provideCheckApiServletRegistrarClientId(RegistryConfigSettings config) {
      return config.registryPolicy.checkApiServletClientId;
    }

    /**
     * Returns the clientId of the registrar that admins are automatically logged in as if they
     * aren't otherwise associated with one.
     */
    @Provides
    @Config("registryAdminClientId")
    public static String provideRegistryAdminClientId(RegistryConfigSettings config) {
      return config.registryPolicy.registryAdminClientId;
    }

    @Singleton
    @Provides
    static RegistryConfigSettings provideRegistryConfigSettings() {
      return CONFIG_SETTINGS.get();
    }

    /**
     * Provides the OAuth scopes that authentication logic should detect on access tokens.
     *
     * <p>This list should be a superset of the required OAuth scope set provided below. Note that
     * ideally, this setting would not be required and all scopes on an access token would be
     * detected automatically, but that is not the case due to the way {@code OAuthService} works.
     *
     * <p>This is an independent setting from the required OAuth scopes (below) to support use cases
     * where certain actions require some additional scope (e.g. access to a user's Google Drive)
     * but that scope shouldn't be required for authentication alone; in that case the Drive scope
     * would be specified only for this setting, allowing that action to check for its presence.
     */
    @Provides
    @Config("availableOauthScopes")
    public static ImmutableSet<String> provideAvailableOauthScopes(RegistryConfigSettings config) {
      return ImmutableSet.copyOf(config.oAuth.availableOauthScopes);
    }

    /**
     * Provides the OAuth scopes that are required for authenticating successfully.
     *
     * <p>This set contains the scopes which must be present to authenticate a user. It should be a
     * subset of the scopes we request from the OAuth interface, provided above.
     *
     * <p>If we feel the need, we could define additional fixed scopes, similar to the Java remote
     * API, which requires at least one of:
     *
     * <ul>
     *   <li>https://www.googleapis.com/auth/appengine.apis
     *   <li>https://www.googleapis.com/auth/cloud-platform
     * </ul>
     */
    @Provides
    @Config("requiredOauthScopes")
    public static ImmutableSet<String> provideRequiredOauthScopes(RegistryConfigSettings config) {
      return ImmutableSet.copyOf(config.oAuth.requiredOauthScopes);
    }

    /** Provides the allowed OAuth client IDs (could be multibinding). */
    @Provides
    @Config("allowedOauthClientIds")
    public static ImmutableSet<String> provideAllowedOauthClientIds(RegistryConfigSettings config) {
      return ImmutableSet.copyOf(config.oAuth.allowedOauthClientIds);
    }

    /**
     * Provides the OAuth scopes required for accessing Google APIs using the default credential.
     */
    @Provides
    @Config("defaultCredentialOauthScopes")
    public static ImmutableList<String> provideServiceAccountCredentialOauthScopes(
        RegistryConfigSettings config) {
      return ImmutableList.copyOf(config.credentialOAuth.defaultCredentialOauthScopes);
    }

    /** Provides the OAuth scopes required for delegated admin access to G Suite domain. */
    @Provides
    @Config("delegatedCredentialOauthScopes")
    public static ImmutableList<String> provideDelegatedCredentialOauthScopes(
        RegistryConfigSettings config) {
      return ImmutableList.copyOf(config.credentialOAuth.delegatedCredentialOauthScopes);
    }

    /** Provides the OAuth scopes required for credentials created locally for the nomulus tool. */
    @Provides
    @Config("localCredentialOauthScopes")
    public static ImmutableList<String> provideLocalCredentialOauthScopes(
        RegistryConfigSettings config) {
      return ImmutableList.copyOf(config.credentialOAuth.localCredentialOauthScopes);
    }

    /** OAuth client ID used by the nomulus tool. */
    @Provides
    @Config("toolsClientId")
    public static String provideToolsClientId(RegistryConfigSettings config) {
      return config.registryTool.clientId;
    }

    /** OAuth client secret used by the nomulus tool. */
    @Provides
    @Config("toolsClientSecret")
    public static String provideToolsClientSecret(RegistryConfigSettings config) {
      return config.registryTool.clientSecret;
    }

    @Provides
    @Config("toolsCloudSqlUsername")
    public static String providesToolsCloudSqlUsername(RegistryConfigSettings config) {
      return config.registryTool.username;
    }

    @Provides
    @Config("rdapTos")
    public static ImmutableList<String> provideRdapTos(RegistryConfigSettings config) {
      return ImmutableList.copyOf(Splitter.on('\n').split(config.registryPolicy.rdapTos));
    }

    /**
     * Link to static Web page with RDAP terms of service. Displayed in RDAP responses.
     *
     * @see google.registry.rdap.RdapJsonFormatter
     */
    @Provides
    @Config("rdapTosStaticUrl")
    @Nullable
    public static String provideRdapTosStaticUrl(RegistryConfigSettings config) {
      return config.registryPolicy.rdapTosStaticUrl;
    }

    @Provides
    @Config("maxValidityDaysSchedule")
    public static ImmutableSortedMap<DateTime, Integer> provideValidityDaysMap(
        RegistryConfigSettings config) {
      return config.sslCertificateValidation.maxValidityDaysSchedule.entrySet().stream()
          .collect(
              toImmutableSortedMap(
                  naturalOrder(),
                  e ->
                      e.getKey().equals("START_OF_TIME")
                          ? START_OF_TIME
                          : DateTime.parse(e.getKey()),
                  e -> e.getValue()));
    }

    @Provides
    @Config("expirationWarningDays")
    public static int provideDaysToExpiration(RegistryConfigSettings config) {
      return config.sslCertificateValidation.expirationWarningDays;
    }

    @Provides
    @Config("minimumRsaKeyLength")
    public static int provideMinimumRsaKeyLength(RegistryConfigSettings config) {
      return config.sslCertificateValidation.minimumRsaKeyLength;
    }

    @Provides
    @Config("allowedEcdsaCurves")
    public static ImmutableSet<String> provideAllowedEcdsaCurves(RegistryConfigSettings config) {
      return ImmutableSet.copyOf(config.sslCertificateValidation.allowedEcdsaCurves);
    }
  }

  /** Returns the App Engine project ID, which is based off the environment name. */
  public static String getProjectId() {
    return CONFIG_SETTINGS.get().appEngine.projectId;
  }

  /**
   * Returns the Google Cloud Storage bucket for storing Datastore backups.
   *
   * @see google.registry.export.BackupDatastoreAction
   */
  public static String getDatastoreBackupsBucket() {
    return "gs://" + getProjectId() + "-datastore-backups";
  }

  /**
   * Number of sharded commit log buckets.
   *
   * <p>This number is crucial for determining how much transactional throughput the system can
   * allow, because it determines how many entity groups are available for writing commit logs.
   * Since entity groups have a one transaction per second SLA (which is actually like ten in
   * practice), a registry that wants to be able to handle one hundred transactions per second
   * should have one hundred buckets.
   *
   * <p><b>Warning:</b> This can be raised but never lowered.
   *
   * @see google.registry.model.ofy.CommitLogBucket
   */
  public static int getCommitLogBucketCount() {
    return CONFIG_SETTINGS.get().datastore.commitLogBucketsNum;
  }

  /**
   * Returns the length of time before commit logs should be deleted from Datastore.
   *
   * <p>The only reason you'll want to retain this commit logs in Datastore is for performing
   * point-in-time restoration queries for subsystems like RDE.
   *
   * @see google.registry.backup.DeleteOldCommitLogsAction
   * @see google.registry.model.translators.CommitLogRevisionsTranslatorFactory
   */
  public static Duration getCommitLogDatastoreRetention() {
    return Duration.standardDays(30);
  }

  public static boolean areServersLocal() {
    return CONFIG_SETTINGS.get().appEngine.isLocal;
  }

  /**
   * Returns the address of the Nomulus app default HTTP server.
   *
   * <p>This is used by the {@code nomulus} tool to connect to the App Engine remote API.
   */
  public static URL getDefaultServer() {
    return makeUrl(CONFIG_SETTINGS.get().appEngine.defaultServiceUrl);
  }

  /**
   * Returns the address of the Nomulus app backend HTTP server.
   *
   * <p>This is used by the {@code nomulus} tool to connect to the App Engine remote API.
   */
  public static URL getBackendServer() {
    return makeUrl(CONFIG_SETTINGS.get().appEngine.backendServiceUrl);
  }

  /**
   * Returns the address of the Nomulus app tools HTTP server.
   *
   * <p>This is used by the {@code nomulus} tool to connect to the App Engine remote API.
   */
  public static URL getToolsServer() {
    return makeUrl(CONFIG_SETTINGS.get().appEngine.toolsServiceUrl);
  }

  /**
   * Returns the address of the Nomulus app pubapi HTTP server.
   *
   * <p>This is used by the {@code nomulus} tool to connect to the App Engine remote API.
   */
  public static URL getPubapiServer() {
    return makeUrl(CONFIG_SETTINGS.get().appEngine.pubapiServiceUrl);
  }

  /** Returns the amount of time a singleton should be cached, before expiring. */
  public static Duration getSingletonCacheRefreshDuration() {
    return Duration.standardSeconds(CONFIG_SETTINGS.get().caching.singletonCacheRefreshSeconds);
  }

  /**
   * Returns the amount of time a domain label list should be cached in memory before expiring.
   *
   * @see google.registry.model.registry.label.ReservedList
   * @see google.registry.model.registry.label.PremiumList
   */
  public static Duration getDomainLabelListCacheDuration() {
    return Duration.standardSeconds(CONFIG_SETTINGS.get().caching.domainLabelCachingSeconds);
  }

  /** Returns the amount of time a singleton should be cached in persist mode, before expiring. */
  public static Duration getSingletonCachePersistDuration() {
    return Duration.standardSeconds(CONFIG_SETTINGS.get().caching.singletonCachePersistSeconds);
  }

  /**
   * Returns the maximum number of premium list entries across all TLDs to keep in in-memory cache.
   */
  public static int getStaticPremiumListMaxCachedEntries() {
    return CONFIG_SETTINGS.get().caching.staticPremiumListMaxCachedEntries;
  }

  public static boolean isEppResourceCachingEnabled() {
    return CONFIG_SETTINGS.get().caching.eppResourceCachingEnabled;
  }

  @VisibleForTesting
  public static void overrideIsEppResourceCachingEnabledForTesting(boolean enabled) {
    CONFIG_SETTINGS.get().caching.eppResourceCachingEnabled = enabled;
  }

  /**
   * Returns the amount of time an EPP resource or key should be cached in memory before expiring.
   */
  public static Duration getEppResourceCachingDuration() {
    return Duration.standardSeconds(CONFIG_SETTINGS.get().caching.eppResourceCachingSeconds);
  }

  /** Returns the maximum number of EPP resources and keys to keep in in-memory cache. */
  public static int getEppResourceMaxCachedEntries() {
    return CONFIG_SETTINGS.get().caching.eppResourceMaxCachedEntries;
  }

  /** Returns the email address that outgoing emails from the app are sent from. */
  public static InternetAddress getGSuiteOutgoingEmailAddress() {
    return parseEmailAddress(CONFIG_SETTINGS.get().gSuite.outgoingEmailAddress);
  }

  /** Returns the display name that outgoing emails from the app are sent from. */
  public static String getGSuiteOutgoingEmailDisplayName() {
    return CONFIG_SETTINGS.get().gSuite.outgoingEmailDisplayName;
  }

  /**
   * Returns default WHOIS server to use when {@code Registrar#getWhoisServer()} is {@code null}.
   *
   * @see "google.registry.whois.DomainWhoisResponse"
   * @see "google.registry.whois.RegistrarWhoisResponse"
   */
  public static String getDefaultRegistrarWhoisServer() {
    return CONFIG_SETTINGS.get().registryPolicy.defaultRegistrarWhoisServer;
  }

  /** Returns the number of {@code EppResourceIndex} buckets to be used. */
  public static int getEppResourceIndexBucketCount() {
    return CONFIG_SETTINGS.get().datastore.eppResourceIndexBucketsNum;
  }

  /** Returns the base retry duration that gets doubled after each failure within {@code Ofy}. */
  public static Duration getBaseOfyRetryDuration() {
    return Duration.millis(CONFIG_SETTINGS.get().datastore.baseOfyRetryMillis);
  }

  /** Returns the default database transaction isolation. */
  public static String getHibernateConnectionIsolation() {
    return CONFIG_SETTINGS.get().hibernate.connectionIsolation;
  }

  /** Returns true if hibernate.show_sql is enabled. */
  public static String getHibernateLogSqlQueries() {
    return CONFIG_SETTINGS.get().hibernate.logSqlQueries;
  }

  /** Returns the connection timeout for HikariCP. */
  public static String getHibernateHikariConnectionTimeout() {
    return CONFIG_SETTINGS.get().hibernate.hikariConnectionTimeout;
  }

  /** Returns the minimum idle connections for HikariCP. */
  public static String getHibernateHikariMinimumIdle() {
    return CONFIG_SETTINGS.get().hibernate.hikariMinimumIdle;
  }

  /** Returns the maximum pool size for HikariCP. */
  public static String getHibernateHikariMaximumPoolSize() {
    return CONFIG_SETTINGS.get().hibernate.hikariMaximumPoolSize;
  }

  /** Returns the idle timeout for HikariCP. */
  public static String getHibernateHikariIdleTimeout() {
    return CONFIG_SETTINGS.get().hibernate.hikariIdleTimeout;
  }

  /**
   * Returns whether to replicate cloud SQL transactions to datastore.
   *
   * <p>If true, all cloud SQL transactions will be persisted as TransactionEntity objects in the
   * Transaction table and replayed against datastore in a cron job.
   */
  public static boolean getCloudSqlReplicateTransactions() {
    return CONFIG_SETTINGS.get().cloudSql.replicateTransactions;
  }

  @VisibleForTesting
  public static void overrideCloudSqlReplicateTransactions(boolean replicateTransactions) {
    CONFIG_SETTINGS.get().cloudSql.replicateTransactions = replicateTransactions;
  }

  /**
   * Returns whether or not to replay commit logs to the SQL database after export to GCS.
   *
   * <p>If true, we will trigger the {@link google.registry.backup.ReplayCommitLogsToSqlAction}
   * after the {@link google.registry.backup.ExportCommitLogDiffAction} to load the commit logs and
   * replay them to SQL.
   */
  public static boolean getCloudSqlReplayCommitLogs() {
    return CONFIG_SETTINGS.get().cloudSql.replayCommitLogs;
  }

  @VisibleForTesting
  public static void overrideCloudSqlReplayCommitLogs(boolean replayCommitLogs) {
    CONFIG_SETTINGS.get().cloudSql.replayCommitLogs = replayCommitLogs;
  }

  /** Returns the roid suffix to be used for the roids of all contacts and hosts. */
  public static String getContactAndHostRoidSuffix() {
    return CONFIG_SETTINGS.get().registryPolicy.contactAndHostRoidSuffix;
  }

  /** Returns the global automatic transfer length for contacts. */
  public static Duration getContactAutomaticTransferLength() {
    return Duration.standardDays(CONFIG_SETTINGS.get().registryPolicy.contactAutomaticTransferDays);
  }

  /**
   * Memoizes loading of the {@link RegistryConfigSettings} POJO.
   *
   * <p>Memoizing without cache expiration is used because the app must be re-deployed in order to
   * change the contents of the YAML config files.
   */
  @VisibleForTesting
  public static final Supplier<RegistryConfigSettings> CONFIG_SETTINGS =
      memoize(RegistryConfig::getConfigSettings);

  private static String formatComments(String text) {
    return Splitter.on('\n').omitEmptyStrings().trimResults().splitToList(text).stream()
        .map(s -> "# " + s)
        .collect(Collectors.joining("\n"));
  }

  private static InternetAddress parseEmailAddress(String email) {
    try {
      return new InternetAddress(email);
    } catch (AddressException e) {
      throw new IllegalArgumentException(String.format("Could not parse email address %s.", email));
    }
  }

  private RegistryConfig() {}
}
