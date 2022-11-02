// Copyright 2022 The Nomulus Authors. All Rights Reserved.
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

package google.registry.batch.cannedscript;

import static com.google.common.base.Suppliers.memoize;

import com.google.api.services.bigquery.Bigquery;
import com.google.api.services.bigquery.model.Dataset;
import com.google.common.flogger.FluentLogger;
import dagger.Component;
import dagger.Module;
import dagger.Provides;
import google.registry.config.CredentialModule;
import google.registry.config.CredentialModule.ApplicationDefaultCredential;
import google.registry.config.RegistryConfig.Config;
import google.registry.config.RegistryConfig.ConfigModule;
import google.registry.util.GoogleCredentialsBundle;
import google.registry.util.UtilsModule;
import java.io.IOException;
import java.util.function.Supplier;
import javax.inject.Singleton;

/**
 * Verifies that the credential with the {@link ApplicationDefaultCredential} annotation can be used
 * to access the Bigquery API.
 */
// TODO(b/234424397): remove class after credential changes are rolled out.
public class BigQueryChecker {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  static final Supplier<BigqueryComponent> COMPONENT_SUPPLIER =
      memoize(DaggerBigQueryChecker_BigqueryComponent::create);

  public static void runBigqueryCheck() {
    BigqueryComponent component = COMPONENT_SUPPLIER.get();

    Bigquery bigquery = component.bigquery();
    try {
      Dataset dataSet =
          bigquery.datasets().get(component.projectId(), "cloud_sql_icann_reporting").execute();
      logger.atInfo().log("Found Dataset %s", dataSet.getDatasetReference().getDatasetId());
    } catch (IOException ioe) {
      throw new RuntimeException(ioe);
    }
  }

  @Singleton
  @Component(
      modules = {
        ConfigModule.class,
        CredentialModule.class,
        BigqueryModule.class,
        UtilsModule.class
      })
  interface BigqueryComponent {
    Bigquery bigquery();

    @Config("projectId")
    String projectId();
  }

  @Module
  static class BigqueryModule {

    @Provides
    static Bigquery provideBigquery(
        @ApplicationDefaultCredential GoogleCredentialsBundle credentialsBundle,
        @Config("projectId") String projectId) {
      return new Bigquery.Builder(
              credentialsBundle.getHttpTransport(),
              credentialsBundle.getJsonFactory(),
              credentialsBundle.getHttpRequestInitializer())
          .setApplicationName(projectId)
          .build();
    }
  }
}
