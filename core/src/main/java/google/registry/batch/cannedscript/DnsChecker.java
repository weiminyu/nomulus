package google.registry.batch.cannedscript;

import com.google.api.services.dns.Dns;
import com.google.api.services.dns.model.ResourceRecordSet;
import com.google.common.base.Suppliers;
import com.google.common.flogger.FluentLogger;
import dagger.Component;
import dagger.Module;
import dagger.Provides;
import google.registry.config.CredentialModule;
import google.registry.config.CredentialModule.ApplicationDefaultCredential;
import google.registry.config.RegistryConfig.Config;
import google.registry.config.RegistryConfig.ConfigModule;
import google.registry.dns.DnsModule;
import google.registry.dns.DnsQueue;
import google.registry.util.GoogleCredentialsBundle;
import google.registry.util.UtilsModule;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import javax.inject.Singleton;

public class DnsChecker {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private static final Supplier<DnsComponent> COMPONENT_SUPPLIER =
      Suppliers.memoize(DaggerDnsChecker_DnsComponent::create);

  public static void runDnsChecks() {
    DnsComponent component = COMPONENT_SUPPLIER.get();
    DnsQueue dnsQueue = component.dnsQueue();
    dnsQueue.addDomainRefreshTask("rich.app");
  }

  @Singleton
  @Component(
      modules = {ConfigModule.class, CredentialModule.class, DnsModule.class, UtilsModule.class})
  interface DnsComponent {
    DnsQueue dnsQueue();

    @Config("projectId")
    String projectId();
  }
}
