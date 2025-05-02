package google.registry.dns.writer.powerdns;

import com.google.common.flogger.FluentLogger;
import google.registry.config.RegistryConfig.Config;
import google.registry.dns.writer.BaseDnsWriter;
import google.registry.dns.writer.DnsWriterZone;
import google.registry.dns.writer.powerdns.client.PowerDNSClient;
import google.registry.util.Clock;
import jakarta.inject.Inject;
import java.util.HashSet;
import java.util.Set;

/** A DnsWriter that implements the PowerDNS API. */
public class PowerDnsWriter extends BaseDnsWriter {
  public static final String NAME = "PowerDnsWriter";

  private final Clock clock;
  private final PowerDNSClient powerDnsClient;
  private final String zoneName;

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private final Set<String> names = new HashSet<>();

  /**
   * Class constructor.
   *
   * @param zoneName the name of the zone to write to
   * @param powerDnsHost the host of the PowerDNS server
   * @param powerDnsApiKey the API key for the PowerDNS server
   * @param clock the clock to use for the PowerDNS writer
   */
  @Inject
  public PowerDnsWriter(
      @DnsWriterZone String zoneName,
      @Config("powerDnsHost") String powerDnsHost,
      @Config("powerDnsApiKey") String powerDnsApiKey,
      Clock clock) {

    // Initialize the PowerDNS client and zone name
    this.powerDnsClient = new PowerDNSClient(powerDnsHost, powerDnsApiKey);
    this.zoneName = zoneName;
    this.clock = clock;
  }

  @Override
  public void publishDomain(String domainName) {
    // TODO: Implement the logic to stage the domain zone files to PowerDNS for commit
    names.add(domainName);
  }

  @Override
  public void publishHost(String hostName) {
    // TODO: Implement the logic to stage the host glue records to PowerDNS for commit
    names.add(hostName);
  }

  @Override
  protected void commitUnchecked() {
    // TODO: Call the PowerDNS API to commit the changes
    logger.atWarning().log(
        "PowerDnsWriter for server ID %s not yet implemented; ignoring %s names to commit: %s at"
            + " %s",
        powerDnsClient.getServerId(), zoneName, names, clock.nowUtc());
  }
}
