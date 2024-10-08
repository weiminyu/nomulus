// Copyright 2017 The Nomulus Authors. All Rights Reserved.
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

package google.registry.request.auth;

import static com.google.common.net.HttpHeaders.AUTHORIZATION;

import com.google.common.collect.ImmutableList;
import dagger.Module;
import dagger.Provides;
import google.registry.config.RegistryConfig.Config;
import google.registry.request.auth.OidcTokenAuthenticationMechanism.IapOidcAuthenticationMechanism;
import google.registry.request.auth.OidcTokenAuthenticationMechanism.RegularOidcAuthenticationMechanism;
import google.registry.request.auth.OidcTokenAuthenticationMechanism.TokenExtractor;
import google.registry.request.auth.OidcTokenAuthenticationMechanism.TokenVerifier;
import google.registry.util.RegistryEnvironment;
import java.util.Map;
import javax.annotation.Nullable;
import javax.inject.Qualifier;
import javax.inject.Singleton;

/** Dagger module for authentication routines. */
@Module
public class AuthModule {

  // IAP-signed JWT will be in this header.
  // See https://cloud.google.com/iap/docs/signed-headers-howto#securing_iap_headers.
  public static final String IAP_HEADER_NAME = "X-Goog-IAP-JWT-Assertion";
  public static final String BEARER_PREFIX = "Bearer ";
  // TODO (jianglai): Only use GKE audience once we are fully migrated to GKE.
  // See: https://cloud.google.com/iap/docs/signed-headers-howto#verifying_the_jwt_payload
  private static final String IAP_GAE_AUDIENCE_FORMAT = "/projects/%d/apps/%s";
  private static final String IAP_GKE_AUDIENCE_FORMAT = "/projects/%d/global/backendServices/%d";
  private static final String IAP_ISSUER_URL = "https://cloud.google.com/iap";
  private static final String REGULAR_ISSUER_URL = "https://accounts.google.com";

  /** Provides the custom authentication mechanisms. */
  @Provides
  ImmutableList<AuthenticationMechanism> provideApiAuthenticationMechanisms(
      IapOidcAuthenticationMechanism iapOidcAuthenticationMechanism,
      RegularOidcAuthenticationMechanism regularOidcAuthenticationMechanism) {
    return ImmutableList.of(iapOidcAuthenticationMechanism, regularOidcAuthenticationMechanism);
  }

  @Qualifier
  @interface IapOidc {}

  @Qualifier
  @interface RegularOidc {}

  @Qualifier
  @interface RegularOidcFallback {}

  @Provides
  @IapOidc
  @Singleton
  TokenVerifier provideIapTokenVerifier(
      @Config("projectId") String projectId,
      @Config("projectIdNumber") long projectIdNumber,
      @Config("backendServiceIds") Map<String, Long> backendServiceIds) {
    com.google.auth.oauth2.TokenVerifier.Builder tokenVerifierBuilder =
        com.google.auth.oauth2.TokenVerifier.newBuilder().setIssuer(IAP_ISSUER_URL);
    return (String service, String token) -> {
      String audience;
      if (RegistryEnvironment.isOnJetty()) {
        long backendServiceId = backendServiceIds.get(service);
        audience = String.format(IAP_GKE_AUDIENCE_FORMAT, projectIdNumber, backendServiceId);
      } else {
        audience = String.format(IAP_GAE_AUDIENCE_FORMAT, projectIdNumber, projectId);
      }
      return tokenVerifierBuilder.setAudience(audience).build().verify(token);
    };
  }

  @Provides
  @RegularOidc
  @Singleton
  TokenVerifier provideRegularTokenVerifier(@Config("oauthClientId") String clientId) {
    com.google.auth.oauth2.TokenVerifier tokenVerifier =
        com.google.auth.oauth2.TokenVerifier.newBuilder()
            .setAudience(clientId)
            .setIssuer(REGULAR_ISSUER_URL)
            .build();
    return (@Nullable String service, String token) -> {
      return tokenVerifier.verify(token);
    };
  }

  @Provides
  @IapOidc
  @Singleton
  TokenExtractor provideIapTokenExtractor() {
    return request -> request.getHeader(IAP_HEADER_NAME);
  }

  @Provides
  @RegularOidc
  @Singleton
  TokenExtractor provideRegularTokenExtractor() {
    return request -> {
      String rawToken = request.getHeader(AUTHORIZATION);
      if (rawToken != null && rawToken.startsWith(BEARER_PREFIX)) {
        return rawToken.substring(BEARER_PREFIX.length());
      }
      return null;
    };
  }
}
