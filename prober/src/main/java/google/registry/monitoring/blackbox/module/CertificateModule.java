// Copyright 2019 The Nomulus Authors. All Rights Reserved.
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

package google.registry.monitoring.blackbox.module;

import static com.google.common.base.Suppliers.memoizeWithExpiration;
import static google.registry.util.ResourceUtils.readResourceBytes;
import static google.registry.util.ResourceUtils.readResourceUtf8;

import com.google.common.collect.ImmutableList;
import dagger.Module;
import dagger.Provides;
import jakarta.inject.Provider;
import jakarta.inject.Qualifier;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.joda.time.Duration;

/**
 * Dagger module that provides bindings needed to inject server certificate chain and private key.
 *
 * <p>Currently the sandbox certificates and private key are stored as local secrets in a .p12 file,
 * however, in production, all certificates will be stored in a .pem file that is encrypted by Cloud
 * KMS. The .pem file can be generated by concatenating the .crt certificate files on the chain and
 * the .key private file.
 *
 * <p>The production certificates in the .pem file must be stored in order, where the next
 * certificate's subject is the previous certificate's issuer.
 *
 * @see <a href="https://cloud.google.com/kms/">Cloud Key Management Service</a>
 */
@Module
public class CertificateModule {

  /** {@link Qualifier} to identify components provided from Local Secrets. */
  // TODO - remove this qualifier and replace it with using KMS to retrieve private key and
  //  certificate
  @Qualifier
  public @interface LocalSecrets {}

  private static InputStream readResource(String filename) throws IOException {
    return readResourceBytes(CertificateModule.class, filename).openStream();
  }

  @Provides
  @LocalSecrets
  static Duration provideCacheDuration() {
    return Duration.standardSeconds(2);
  }

  @Singleton
  @Provides
  @LocalSecrets
  static String keystorePasswordProvider() {
    return readResourceUtf8(CertificateModule.class, "secrets/keystore_password.txt");
  }

  @Provides
  @LocalSecrets
  static PrivateKey providePrivateKey(@LocalSecrets Provider<String> passwordProvider) {
    try {
      InputStream inStream = readResource("secrets/prober-client-tls-sandbox.p12");

      KeyStore ks = KeyStore.getInstance("PKCS12");
      ks.load(inStream, passwordProvider.get().toCharArray());

      String alias = ks.aliases().nextElement();
      return (PrivateKey) ks.getKey(alias, passwordProvider.get().toCharArray());
    } catch (IOException | GeneralSecurityException e) {
      throw new RuntimeException(e);
    }
  }

  @Provides
  @LocalSecrets
  static ImmutableList<X509Certificate> provideCertificates(
      @LocalSecrets Provider<String> passwordProvider) {
    try {
      InputStream inStream = readResource("secrets/prober-client-tls-sandbox.p12");

      KeyStore ks = KeyStore.getInstance("PKCS12");
      ks.load(inStream, passwordProvider.get().toCharArray());

      String alias = ks.aliases().nextElement();
      return ImmutableList.of((X509Certificate) ks.getCertificate(alias));
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @Singleton
  @Provides
  @LocalSecrets
  static Supplier<PrivateKey> providePrivatekeySupplier(
      @LocalSecrets Provider<PrivateKey> privateKeyProvider, @LocalSecrets Duration duration) {
    return memoizeWithExpiration(
        privateKeyProvider::get, duration.getStandardSeconds(), TimeUnit.SECONDS);
  }

  @Singleton
  @Provides
  @LocalSecrets
  static Supplier<ImmutableList<X509Certificate>> provideCertificatesSupplier(
      @LocalSecrets Provider<ImmutableList<X509Certificate>> certificatesProvider,
      @LocalSecrets Duration duration) {
    return memoizeWithExpiration(
        certificatesProvider::get, duration.getStandardSeconds(), TimeUnit.SECONDS);
  }
}
