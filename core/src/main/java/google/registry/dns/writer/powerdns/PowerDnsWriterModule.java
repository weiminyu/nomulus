package google.registry.dns.writer.powerdns;

import dagger.Binds;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoMap;
import dagger.multibindings.IntoSet;
import dagger.multibindings.StringKey;
import google.registry.dns.writer.DnsWriter;
import jakarta.inject.Named;

/** Dagger module that provides a PowerDnsWriter. */
@Module
public abstract class PowerDnsWriterModule {

  @Binds
  @IntoMap
  @StringKey(PowerDnsWriter.NAME)
  abstract DnsWriter provideWriter(PowerDnsWriter writer);

  @Provides
  @IntoSet
  @Named("dnsWriterNames")
  static String provideWriterName() {
    return PowerDnsWriter.NAME;
  }
}
