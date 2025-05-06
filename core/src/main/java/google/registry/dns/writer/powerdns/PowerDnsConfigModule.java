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

import dagger.Module;
import dagger.Provides;
import google.registry.config.RegistryConfig.Config;

/** Dagger module that provides PowerDNS configuration settings. */
@Module
public class PowerDnsConfigModule {

  /** Host of the PowerDNS server. */
  @Provides
  @Config("powerDnsHost")
  public static String providePowerDnsHost() {
    return "localhost";
  }

  /** API key for the PowerDNS server. */
  @Provides
  @Config("powerDnsApiKey")
  public static String providePowerDnsApiKey() {
    return "dummy-api-key";
  }

  /** Default SOA MNAME for the TLD zone. */
  @Provides
  @Config("powerDnsDefaultSoaMName")
  public static String providePowerDnsDefaultSoaMName() {
    return "a.gtld-servers.net.";
  }

  /** Default SOA RNAME for the TLD zone. */
  @Provides
  @Config("powerDnsDefaultSoaRName")
  public static String providePowerDnsDefaultSoaRName() {
    return "nstld.verisign-grs.com.";
  }
}
