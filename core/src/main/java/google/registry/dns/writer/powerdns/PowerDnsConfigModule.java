package google.registry.dns.writer.powerdns;

import dagger.Module;
import dagger.Provides;
import google.registry.config.RegistryConfig.Config;

/** Dagger module that provides DNS configuration settings. */
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
}
