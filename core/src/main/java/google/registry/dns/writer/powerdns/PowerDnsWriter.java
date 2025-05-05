// Copyright 2025 The Nomulus Authors. All Rights Reserved.
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
import java.util.List;
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

  private final String tldZoneName;
  private final PowerDNSClient powerDnsClient;
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  /**
   * Class constructor.
   *
   * @param tldZoneName the name of the TLD associated with the update
   * @param dnsDefaultATtl the default TTL for A records
   * @param dnsDefaultNsTtl the default TTL for NS records
   * @param dnsDefaultDsTtl the default TTL for DS records
   * @param powerDnsHost the host of the PowerDNS server
   * @param powerDnsApiKey the API key for the PowerDNS server
   * @param clock the clock to use for the PowerDNS writer
   */
  @Inject
  public PowerDnsWriter(
      @DnsWriterZone String tldZoneName,
      @Config("dnsDefaultATtl") Duration dnsDefaultATtl,
      @Config("dnsDefaultNsTtl") Duration dnsDefaultNsTtl,
      @Config("dnsDefaultDsTtl") Duration dnsDefaultDsTtl,
      @Config("powerDnsHost") String powerDnsHost,
      @Config("powerDnsApiKey") String powerDnsApiKey,
      Clock clock) {

    // call the DnsUpdateWriter constructor, omitting the transport parameter
    // since we don't need it for PowerDNS
    super(tldZoneName, dnsDefaultATtl, dnsDefaultNsTtl, dnsDefaultDsTtl, null, clock);

    // Initialize the PowerDNS client
    this.tldZoneName = tldZoneName;
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
          "Committing updates to PowerDNS for TLD %s on server %s",
          tldZoneName, powerDnsClient.getServerId());

      // convert the update to a PowerDNS Zone object
      Zone zone = convertUpdateToZone(update);

      // call the PowerDNS API to commit the changes
      powerDnsClient.patchZone(zone);
    } catch (IOException e) {
      throw new RuntimeException("publishDomain failed for TLD: " + tldZoneName, e);
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
    ArrayList<RRSet> allRRSets = new ArrayList<RRSet>();
    ArrayList<RRSet> filteredRRSets = new ArrayList<RRSet>();
    for (Record r : update.getSection(Section.UPDATE)) {
      logger.atInfo().log("Processing TLD zone %s update record: %s", tldZoneName, r);

      // create the base PowerDNS RRSet object
      RRSet record = new RRSet();
      record.setName(r.getName().toString());
      record.setTtl(r.getTTL());
      record.setType(Type.string(r.getType()));

      // determine if this is a record update or a record deletion
      Boolean isDelete = r.getTTL() == 0 && r.rdataToString().equals("");

      // handle record updates and deletions
      if (isDelete) {
        // indicate that this is a record deletion
        record.setChangeType(RRSet.ChangeType.DELETE);
      } else {
        // add the record content
        RecordObject recordObject = new RecordObject();
        recordObject.setContent(r.rdataToString());
        recordObject.setDisabled(false);
        record.setRecords(new ArrayList<RecordObject>(Arrays.asList(recordObject)));

        // indicate that this is a record update
        record.setChangeType(RRSet.ChangeType.REPLACE);
      }

      // Add record to lists of all and filtered RRSets. The first list is used to track all RRSets
      // for the TLD zone, while the second list is used to track the RRSets that will be sent to
      // the PowerDNS API. By default, there is a deletion record created by the parent class for
      // every domain name and record type combination. However, PowerDNS only expects to see a
      // deletion record if the record should be removed from the TLD zone.
      allRRSets.add(record);
      filteredRRSets.add(record);
    }

    // remove deletion records for a domain if there is a subsequent update enqueued
    // for the same domain name and record type combination
    allRRSets.stream()
        .filter(r -> r.getChangeType() == RRSet.ChangeType.REPLACE)
        .forEach(
            r -> {
              filteredRRSets.removeIf(
                  fr ->
                      fr.getName().equals(r.getName())
                          && fr.getType().equals(r.getType())
                          && fr.getChangeType() == RRSet.ChangeType.DELETE);
            });

    // retrieve the TLD zone by name and prepare it for update using the filtered set of
    // RRSet records that will be sent to the PowerDNS API
    Zone tldZone = getTldZoneByName();
    Zone preparedTldZone = prepareTldZoneForUpdates(tldZone, filteredRRSets);

    // return the prepared TLD zone
    logger.atInfo().log("Prepared TLD zone %s for PowerDNS: %s", tldZoneName, preparedTldZone);
    return preparedTldZone;
  }

  /**
   * Prepare the TLD zone for updates by clearing the RRSets and incrementing the serial number.
   *
   * @param tldZone the TLD zone to prepare
   * @param records the set of RRSet records that will be sent to the PowerDNS API
   * @return the prepared TLD zone
   */
  private Zone prepareTldZoneForUpdates(Zone tldZone, List<RRSet> records) {
    tldZone.setRrsets(records);
    tldZone.setEditedSerial(tldZone.getSerial() + 1);
    return tldZone;
  }

  /**
   * Get the TLD zone by name.
   *
   * @return the TLD zone
   * @throws IOException if the TLD zone is not found
   */
  private Zone getTldZoneByName() throws IOException {
    for (Zone zone : powerDnsClient.listZones()) {
      if (zone.getName().equals(tldZoneName)) {
        return zone;
      }
    }
    throw new IOException("TLD zone not found: " + tldZoneName);
  }
}
