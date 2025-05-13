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
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
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
  private final String powerDnsDefaultSoaMName;
  private final String powerDnsDefaultSoaRName;
  private final PowerDNSClient powerDnsClient;

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private static final ArrayList<String> supportedRecordTypes =
      new ArrayList<>(Arrays.asList("DS", "NS"));
  private static final ConcurrentHashMap<String, String> zoneIdCache = new ConcurrentHashMap<>();

  /**
   * Class constructor.
   *
   * @param tldZoneName the name of the TLD associated with the update
   * @param dnsDefaultATtl the default TTL for A records
   * @param dnsDefaultNsTtl the default TTL for NS records
   * @param dnsDefaultDsTtl the default TTL for DS records
   * @param powerDnsBaseUrl the base URL of the PowerDNS server
   * @param powerDnsApiKey the API key for the PowerDNS server
   * @param clock the clock to use for the PowerDNS writer
   */
  @Inject
  public PowerDnsWriter(
      @DnsWriterZone String tldZoneName,
      @Config("dnsDefaultATtl") Duration dnsDefaultATtl,
      @Config("dnsDefaultNsTtl") Duration dnsDefaultNsTtl,
      @Config("dnsDefaultDsTtl") Duration dnsDefaultDsTtl,
      @Config("powerDnsBaseUrl") String powerDnsBaseUrl,
      @Config("powerDnsApiKey") String powerDnsApiKey,
      @Config("powerDnsDefaultSoaMName") String powerDnsDefaultSoaMName,
      @Config("powerDnsDefaultSoaRName") String powerDnsDefaultSoaRName,
      Clock clock) {

    // call the DnsUpdateWriter constructor, omitting the transport parameter
    // since we don't need it for PowerDNS
    super(tldZoneName, dnsDefaultATtl, dnsDefaultNsTtl, dnsDefaultDsTtl, null, clock);

    // Initialize the PowerDNS client
    this.tldZoneName = getCanonicalHostName(tldZoneName);
    this.powerDnsDefaultSoaMName = powerDnsDefaultSoaMName;
    this.powerDnsDefaultSoaRName = powerDnsDefaultSoaRName;
    this.powerDnsClient = new PowerDNSClient(powerDnsBaseUrl, powerDnsApiKey);
  }

  /**
   * Prepare a domain for staging in PowerDNS. Handles the logic to clean up orphaned glue records
   * and adds the domain records to the update.
   *
   * @param domainName the fully qualified domain name, with no trailing dot
   */
  @Override
  public void publishDomain(String domainName) {
    String normalizedDomainName = getSanitizedHostName(domainName);
    logger.atInfo().log("Staging domain %s for PowerDNS", normalizedDomainName);
    super.publishDomain(normalizedDomainName);
  }

  /**
   * Determine whether the host should be published as a glue record for this zone. If so, add the
   * host records to the update.
   *
   * @param hostName the fully qualified host name, with no trailing dot
   */
  @Override
  public void publishHost(String hostName) {
    String normalizedHostName = getSanitizedHostName(hostName);
    logger.atInfo().log("Staging host %s for PowerDNS", normalizedHostName);
    super.publishHost(normalizedHostName);
  }

  @Override
  protected void commitUnchecked() {
    try {
      // persist staged changes to PowerDNS
      logger.atInfo().log("Committing updates to PowerDNS for TLD %s", tldZoneName);

      // convert the update to a PowerDNS Zone object
      Zone zone = convertUpdateToZone(update);

      // call the PowerDNS API to commit the changes
      powerDnsClient.patchZone(zone);
    } catch (Exception e) {
      logger.atSevere().withCause(e).log("Commit to PowerDNS failed for TLD: %s", tldZoneName);
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
    // Convert the Update object to a Zone object
    logger.atInfo().log("Converting PowerDNS TLD zone %s update: %s", tldZoneName, update);

    // generate a list of records to process
    List<Record> updateRecordsToProcess = new ArrayList<>();
    for (Record r : update.getSection(Section.UPDATE)) {
      // determine if any updates exist for this domain
      Boolean isAnyDomainUpdate =
          update.getSection(Section.UPDATE).stream()
              .anyMatch(record -> record.getName().equals(r.getName()) && !isDeleteRecord(record));

      // special processing for ANY record deletions
      if (isDeleteRecord(r) && Type.string(r.getType()).equals("ANY")) {
        // only add a deletion record if there are no other updates for this domain
        if (!isAnyDomainUpdate) {
          // add a delete record for each of the supported record types
          for (String recordType : supportedRecordTypes) {
            Record deleteRecord =
                Record.newRecord(r.getName(), Type.value(recordType), r.getDClass(), r.getTTL());
            updateRecordsToProcess.add(deleteRecord);
          }
        }
      } else {
        // add the record to the list of records to process
        updateRecordsToProcess.add(r);
      }
    }

    // Iterate the update records and prepare them as PowerDNS RRSet objects, referencing the
    // following source code to determine the usage of the org.xbill.DNS.Record object:
    //
    // https://www.javadoc.io/doc/dnsjava/dnsjava/3.2.1/org/xbill/DNS/Record.html
    // https://github.com/dnsjava/dnsjava/blob/master/src/main/java/org/xbill/DNS/Record.java#L324-L350
    ArrayList<RRSet> allRRSets = new ArrayList<RRSet>();
    for (Record r : updateRecordsToProcess) {
      // skip unsupported record types
      if (!supportedRecordTypes.contains(Type.string(r.getType()))) {
        logger.atInfo().log(
            "Skipping unsupported PowerDNS update record type: %s", Type.string(r.getType()));
        continue;
      }

      // determine if this is a record update or a record deletion
      Boolean isDelete = isDeleteRecord(r);

      // find an existing RRSET matching record name and type, or create a new one
      // if an existing RRSET is not found
      logger.atInfo().log("Processing PowerDNS TLD zone %s update record: %s", tldZoneName, r);
      RRSet rrSet =
          allRRSets.stream()
              .filter(
                  rrset ->
                      rrset.getName().equals(r.getName().toString())
                          && rrset.getType().equals(Type.string(r.getType()))
                          && ((isDelete && rrset.getChangeType() == RRSet.ChangeType.DELETE)
                              || (!isDelete && rrset.getChangeType() == RRSet.ChangeType.REPLACE)))
              .findFirst()
              .orElse(
                  appendRRSet(
                      allRRSets, r.getName().toString(), Type.string(r.getType()), r.getTTL()));

      // handle record updates and deletions
      if (isDelete) {
        // indicate that this is a record deletion
        rrSet.setChangeType(RRSet.ChangeType.DELETE);
      } else {
        // indicate that this is a record update
        rrSet.setChangeType(RRSet.ChangeType.REPLACE);

        // add the record content
        RecordObject recordObject = new RecordObject();
        recordObject.setContent(r.rdataToString());
        recordObject.setDisabled(false);

        // append the record to the RRSet
        rrSet.getRecords().add(recordObject);
      }
    }

    // prepare a PowerDNS zone object containing the TLD record updates using the RRSet objects
    // that have a valid change type
    Zone preparedTldZone =
        getTldZoneForUpdate(
            allRRSets.stream().filter(v -> v.getChangeType() != null).collect(Collectors.toList()));

    // return the prepared TLD zone
    logger.atInfo().log(
        "Successfully processed PowerDNS TLD zone %s update record: %s",
        tldZoneName, preparedTldZone);
    return preparedTldZone;
  }

  /**
   * Create a new RRSet object.
   *
   * @param rrsets the list of RRSets
   * @param name the name of the RRSet
   * @param type the type of the RRSet
   * @param ttl the TTL of the RRSet
   * @return the new RRSet object
   */
  private RRSet appendRRSet(List<RRSet> rrsets, String name, String type, long ttl) {
    // create the base PowerDNS RRSet object
    RRSet rrset = new RRSet();
    rrset.setName(name);
    rrset.setType(type);
    rrset.setTtl(ttl);
    rrset.setRecords(new ArrayList<RecordObject>());

    // add the RRSet to the list of RRSets
    rrsets.add(rrset);

    // return the new RRSet object
    return rrset;
  }

  /**
   * Returns the presentation format ending in a dot used for an given hostname.
   *
   * @param hostName the fully qualified hostname
   */
  private String getCanonicalHostName(String hostName) {
    String normalizedHostName = hostName.endsWith(".") ? hostName : hostName + '.';
    String canonicalHostName =
        tldZoneName == null || normalizedHostName.endsWith(tldZoneName)
            ? normalizedHostName
            : normalizedHostName + tldZoneName;
    return canonicalHostName.toLowerCase(Locale.US);
  }

  /**
   * Returns the sanitized host name, which is the host name without the trailing dot.
   *
   * @param hostName the fully qualified hostname
   * @return the sanitized host name
   */
  private String getSanitizedHostName(String hostName) {
    // return the host name without the trailing dot
    return hostName.endsWith(".") ? hostName.substring(0, hostName.length() - 1) : hostName;
  }

  /**
   * Prepare the TLD zone for updates by clearing the RRSets and incrementing the serial number.
   *
   * @param records the set of RRSet records that will be sent to the PowerDNS API
   * @return the prepared TLD zone
   */
  private Zone getTldZoneForUpdate(List<RRSet> records) {
    Zone tldZone = new Zone();
    tldZone.setId(getTldZoneId());
    tldZone.setName(getSanitizedHostName(tldZoneName));
    tldZone.setRrsets(records);
    return tldZone;
  }

  /**
   * Get the TLD zone by name.
   *
   * @return the TLD zone
   * @throws IOException if the TLD zone is not found
   */
  private Zone getTldZoneByName() throws IOException {
    // retrieve an existing TLD zone by name
    for (Zone zone : powerDnsClient.listZones()) {
      if (zone.getName().equals(tldZoneName)) {
        return zone;
      }
    }

    // Attempt to create a new TLD zone if it does not exist. The zone will have a
    // basic SOA record, but will not have DNSSEC enabled. Adding DNSSEC is a follow
    // up step using pdnsutil command line tool.
    try {
      // base TLD zone object
      logger.atInfo().log("Creating new PowerDNS TLD zone %s", tldZoneName);
      Zone newTldZone = new Zone();
      newTldZone.setName(tldZoneName);
      newTldZone.setKind(Zone.ZoneKind.Master);

      // create an initial SOA record, which may be modified later by an administrator
      // or an automated onboarding process
      RRSet soaRecord = new RRSet();
      soaRecord.setChangeType(RRSet.ChangeType.REPLACE);
      soaRecord.setName(tldZoneName);
      soaRecord.setTtl(3600);
      soaRecord.setType("SOA");

      // add content to the SOA record content from default configuration
      RecordObject soaRecordContent = new RecordObject();
      soaRecordContent.setContent(
          String.format(
              "%s %s 1 900 1800 6048000 3600", powerDnsDefaultSoaMName, powerDnsDefaultSoaRName));
      soaRecordContent.setDisabled(false);
      soaRecord.setRecords(new ArrayList<RecordObject>(Arrays.asList(soaRecordContent)));

      // add the SOA record to the new TLD zone
      newTldZone.setRrsets(new ArrayList<RRSet>(Arrays.asList(soaRecord)));

      // create the TLD zone and log the result
      Zone createdTldZone = powerDnsClient.createZone(newTldZone);
      logger.atInfo().log("Successfully created PowerDNS TLD zone %s", tldZoneName);
      return createdTldZone;
    } catch (Exception e) {
      // log the error and continue
      logger.atWarning().log("Failed to create PowerDNS TLD zone %s: %s", tldZoneName, e);
    }

    // otherwise, throw an exception
    throw new IOException("TLD zone not found: " + tldZoneName);
  }

  /**
   * Get the TLD zone ID for the given TLD zone name from the cache, or compute it if it is not
   * present in the cache.
   *
   * @return the ID of the TLD zone
   */
  private String getTldZoneId() {
    return zoneIdCache.computeIfAbsent(
        tldZoneName,
        key -> {
          try {
            return getTldZoneByName().getId();
          } catch (Exception e) {
            // TODO: throw this exception once PowerDNS is available, but for now we are just
            // going to return a dummy ID
            logger.atWarning().log("Failed to get PowerDNS TLD zone ID for %s: %s", tldZoneName, e);
            return tldZoneName;
          }
        });
  }

  /**
   * Determine if a record is a delete record.
   *
   * @param r the record to check
   * @return true if the record is a delete record, false otherwise
   */
  private Boolean isDeleteRecord(Record r) {
    return r.getTTL() == 0 && r.rdataToString().equals("");
  }
}
