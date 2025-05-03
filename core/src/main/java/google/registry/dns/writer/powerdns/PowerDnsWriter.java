package google.registry.dns.writer.powerdns;

import com.google.common.flogger.FluentLogger;
import google.registry.config.RegistryConfig.Config;
import google.registry.dns.writer.DnsWriterZone;
import google.registry.dns.writer.dnsupdate.DnsUpdateWriter;
import google.registry.dns.writer.powerdns.client.PowerDNSClient;
import google.registry.util.Clock;
import jakarta.inject.Inject;
import org.joda.time.Duration;

/**
 * A DnsWriter that sends updates to a PowerDNS backend server. Extends the peer DnsUpdateWriter
 * class, which already handles the logic for aggregating DNS changes into a single update request.
 * This request is then converted into a PowerDNS Zone object and sent to the PowerDNS API.
 */
public class PowerDnsWriter extends DnsUpdateWriter {
  public static final String NAME = "PowerDnsWriter";

  private final PowerDNSClient powerDnsClient;
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  /**
   * Class constructor.
   *
   * @param zoneName the name of the zone to write to
   * @param dnsDefaultATtl the default TTL for A records
   * @param dnsDefaultNsTtl the default TTL for NS records
   * @param dnsDefaultDsTtl the default TTL for DS records
   * @param powerDnsHost the host of the PowerDNS server
   * @param powerDnsApiKey the API key for the PowerDNS server
   * @param clock the clock to use for the PowerDNS writer
   */
  @Inject
  public PowerDnsWriter(
      @DnsWriterZone String zoneName,
      @Config("dnsDefaultATtl") Duration dnsDefaultATtl,
      @Config("dnsDefaultNsTtl") Duration dnsDefaultNsTtl,
      @Config("dnsDefaultDsTtl") Duration dnsDefaultDsTtl,
      @Config("powerDnsHost") String powerDnsHost,
      @Config("powerDnsApiKey") String powerDnsApiKey,
      Clock clock) {

    // call the DnsUpdateWriter constructor, omitting the transport parameter
    // since we don't need it for PowerDNS
    super(zoneName, dnsDefaultATtl, dnsDefaultNsTtl, dnsDefaultDsTtl, null, clock);

    // Initialize the PowerDNS client
    this.powerDnsClient = new PowerDNSClient(powerDnsHost, powerDnsApiKey);
  }

  /**
   * Prepare a domain for staging in PowerDNS. Handles the logic to clean up orphaned glue records
   * and adds the domain records to the update.
   *
   * @param domainName the fully qualified domain name, with no trailing dot
   */
  @Override
  public void publishDomain(String domainName) {
    logger.atInfo().log("Staging domain %s for PowerDNS", domainName);
    super.publishDomain(domainName);
  }

  /**
   * Determine whether the host should be published as a glue record for this zone. If so, add the
   * host records to the update.
   *
   * @param hostName the fully qualified host name, with no trailing dot
   */
  @Override
  public void publishHost(String hostName) {
    logger.atInfo().log("Staging host %s for PowerDNS", hostName);
    super.publishHost(hostName);
  }

  @Override
  protected void commitUnchecked() {
    // TODO: Convert the parent class's update object (org.xbill.DNS.Update) into
    // a PowerDNS Zone object (google.registry.dns.writer.powerdns.client.Zone)

    // TODO: Call the PowerDNS API to commit the changes
    logger.atWarning().log(
        "PowerDnsWriter for server ID %s not yet implemented; ignoring zone %s",
        powerDnsClient.getServerId(), zoneName);
  }
}
