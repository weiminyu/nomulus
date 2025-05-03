package google.registry.dns.writer.powerdns;

import com.google.common.flogger.FluentLogger;
import google.registry.config.RegistryConfig.Config;
import google.registry.dns.writer.DnsWriterZone;
import google.registry.dns.writer.dnsupdate.DnsUpdateWriter;
import google.registry.dns.writer.powerdns.client.PowerDNSClient;
import google.registry.dns.writer.powerdns.client.model.RRSet;
import google.registry.dns.writer.powerdns.client.model.RecordObject;
import google.registry.dns.writer.powerdns.client.model.Zone;
import google.registry.util.Clock;
import jakarta.inject.Inject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import org.joda.time.Duration;
import org.xbill.DNS.Record;
import org.xbill.DNS.Section;
import org.xbill.DNS.Type;
import org.xbill.DNS.Update;

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
    try {
      // persist staged changes to PowerDNS
      logger.atInfo().log(
          "Committing updates to PowerDNS for zone %s on server %s",
          zoneName, powerDnsClient.getServerId());

      // convert the update to a PowerDNS Zone object
      Zone zone = convertUpdateToZone(update);

      // call the PowerDNS API to commit the changes
      powerDnsClient.patchZone(zone.getId(), zone);
    } catch (IOException e) {
      throw new RuntimeException("publishDomain failed for zone: " + zoneName, e);
    }
  }

  /**
   * Convert the parent class's update object (org.xbill.DNS.Update) into a PowerDNS Zone object
   * (google.registry.dns.writer.powerdns.client.Zone)
   *
   * @param update the update object to convert
   * @return the PowerDNS Zone object
   * @throws IOException if the zone is not found
   */
  private Zone convertUpdateToZone(Update update) throws IOException {
    // Iterate the update records and prepare them as PowerDNS RRSet objects, referencing the
    // following source code to determine the usage of the org.xbill.DNS.Record object:
    //
    // https://www.javadoc.io/doc/dnsjava/dnsjava/3.2.1/org/xbill/DNS/Record.html
    // https://github.com/dnsjava/dnsjava/blob/master/src/main/java/org/xbill/DNS/Record.java#L324-L350
    ArrayList<RRSet> updatedRRSets = new ArrayList<RRSet>();
    for (Record r : update.getSection(Section.UPDATE)) {
      logger.atInfo().log("Processing zone update record: %s", r);

      // create a PowerDNS RRSet object
      RRSet record = new RRSet();
      record.setName(r.getName().toString());
      record.setTtl(r.getTTL());
      record.setType(Type.string(r.getType()));

      // add the record content
      RecordObject recordObject = new RecordObject();
      recordObject.setContent(r.rdataToString());
      recordObject.setDisabled(false);
      record.setRecords(new ArrayList<RecordObject>(Arrays.asList(recordObject)));

      // TODO: need to figure out how to handle the change type of
      // the record set. How to handle new and deleted records?
      record.setChangeType(RRSet.ChangeType.REPLACE);

      // add the RRSet to the list of updated RRSets
      updatedRRSets.add(record);
    }

    // retrieve the zone by name and check that it exists
    Zone zone = getZoneByName();

    // prepare the zone for updates
    Zone preparedZone = prepareZoneForUpdates(zone);
    preparedZone.setRrsets(updatedRRSets);

    // return the prepared zone
    return preparedZone;
  }

  /**
   * Prepare the zone for updates by clearing the RRSets and incrementing the serial number.
   *
   * @param zone the zone to prepare
   * @return the prepared zone
   */
  private Zone prepareZoneForUpdates(Zone zone) {
    zone.setRrsets(new ArrayList<RRSet>());
    zone.setEditedSerial(zone.getSerial() + 1);
    return zone;
  }

  /**
   * Get the zone by name.
   *
   * @return the zone
   * @throws IOException if the zone is not found
   */
  private Zone getZoneByName() throws IOException {
    for (Zone zone : powerDnsClient.listZones()) {
      if (zone.getName().equals(zoneName)) {
        return zone;
      }
    }
    throw new IOException("Zone not found: " + zoneName);
  }
}
