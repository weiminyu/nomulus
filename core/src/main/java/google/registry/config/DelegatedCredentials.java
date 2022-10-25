package google.registry.config;

import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpBackOffIOExceptionHandler;
import com.google.api.client.http.HttpBackOffUnsuccessfulResponseHandler;
import com.google.api.client.http.HttpBackOffUnsuccessfulResponseHandler.BackOffRequired;
import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpRequestFactory;
import com.google.api.client.http.HttpResponse;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.UrlEncodedContent;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.JsonObjectParser;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.json.webtoken.JsonWebSignature;
import com.google.api.client.json.webtoken.JsonWebToken;
import com.google.api.client.util.ExponentialBackOff;
import com.google.api.client.util.GenericData;
import com.google.api.client.util.StringUtils;
import com.google.appengine.repackaged.com.google.api.client.util.Clock;
import com.google.auth.ServiceAccountSigner;
import com.google.auth.http.HttpTransportFactory;
import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.Date;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import org.apache.commons.codec.binary.Base64;

public class DelegatedCredentials extends GoogleCredentials {

  private static final String DEFAULT_TOKEN_URI = "https://accounts.google.com/o/oauth2/token";
  static final JsonFactory JSON_FACTORY = GsonFactory.getDefaultInstance();

  static final HttpTransport HTTP_TRANSPORT = new NetHttpTransport();
  static final HttpTransportFactory HTTP_TRANSPORT_FACTORY = new DefaultHttpTransportFactory();

  private static final String GRANT_TYPE = "urn:ietf:params:oauth:grant-type:jwt-bearer";

  private static String VALUE_NOT_FOUND_MESSAGE = "%sExpected value %s not found.";
  private static String VALUE_WRONG_TYPE_MESSAGE = "%sExpected %s value %s of wrong type.";
  private static final String PARSE_ERROR_PREFIX = "Error parsing token refresh response. ";

  private final ServiceAccountSigner signer;
  private final String tokenUri;
  private final ImmutableList<String> scopes;

  private final String delegatedServiceAccountEmail;
  private final String delegatingUserEmail;
  private final int lifetime;

  private final HttpTransportFactory transportFactory;

  DelegatedCredentials(
      ServiceAccountSigner signer,
      String delegatedServiceAccountEmail,
      Optional<String> tokenUri,
      Collection<String> scopes,
      String delegatingUserEmail) {
    this.signer = signer;
    this.tokenUri = tokenUri.orElse(DEFAULT_TOKEN_URI);
    this.scopes = ImmutableList.copyOf(scopes);

    this.delegatedServiceAccountEmail = delegatedServiceAccountEmail;
    this.delegatingUserEmail = delegatingUserEmail;

    this.transportFactory =
        getFromServiceLoader(HttpTransportFactory.class, HTTP_TRANSPORT_FACTORY);

    this.lifetime = 900; // seconds
  }

  String createAssertion(JsonFactory jsonFactory, long currentTime) throws IOException {
    JsonWebSignature.Header header = new JsonWebSignature.Header();
    header.setAlgorithm("RS256");
    header.setType("JWT");

    JsonWebToken.Payload payload = new JsonWebToken.Payload();
    payload.setIssuer(this.delegatedServiceAccountEmail);
    payload.setIssuedAtTimeSeconds(currentTime / 1000);
    payload.setExpirationTimeSeconds(currentTime / 1000 + this.lifetime);
    payload.setSubject(this.delegatingUserEmail);
    payload.put("scope", Joiner.on(' ').join(scopes));

    payload.setAudience(DEFAULT_TOKEN_URI);

    String assertion = signAssertion(jsonFactory, header, payload);
    return assertion;
  }

  String signAssertion(
      JsonFactory jsonFactory, JsonWebSignature.Header header, JsonWebToken.Payload payload)
      throws IOException {
    String content =
        Base64.encodeBase64URLSafeString(jsonFactory.toByteArray(header))
            + "."
            + Base64.encodeBase64URLSafeString(jsonFactory.toByteArray(payload));
    byte[] contentBytes = StringUtils.getBytesUtf8(content);
    byte[] signature = this.signer.sign(contentBytes);
    return content + "." + Base64.encodeBase64URLSafeString(signature);
  }

  /**
   * Refreshes the OAuth2 access token by getting a new access token using a JSON Web Token (JWT).
   */
  @Override
  public AccessToken refreshAccessToken() throws IOException {
    JsonFactory jsonFactory = JSON_FACTORY;
    long currentTime = Clock.SYSTEM.currentTimeMillis();
    String assertion = createAssertion(jsonFactory, currentTime);

    GenericData tokenRequest = new GenericData();
    tokenRequest.set("grant_type", GRANT_TYPE);
    tokenRequest.set("assertion", assertion);
    UrlEncodedContent content = new UrlEncodedContent(tokenRequest);

    HttpRequestFactory requestFactory = transportFactory.create().createRequestFactory();
    HttpRequest request = requestFactory.buildPostRequest(new GenericUrl(tokenUri), content);
    request.setParser(new JsonObjectParser(jsonFactory));

    request.setIOExceptionHandler(new HttpBackOffIOExceptionHandler(new ExponentialBackOff()));
    request.setUnsuccessfulResponseHandler(
        new HttpBackOffUnsuccessfulResponseHandler(new ExponentialBackOff())
            .setBackOffRequired(
                new BackOffRequired() {
                  @Override
                  public boolean isRequired(HttpResponse response) {
                    int code = response.getStatusCode();
                    return (
                    // Server error --- includes timeout errors, which use 500 instead of 408
                    code / 100 == 5
                        // Forbidden error --- for historical reasons, used for rate_limit_exceeded
                        // errors instead of 429, but there currently seems no robust automatic way
                        // to
                        // distinguish these cases: see
                        // https://github.com/google/google-api-java-client/issues/662
                        || code == 403);
                  }
                }));

    HttpResponse response;
    try {
      response = request.execute();
    } catch (IOException e) {
      throw new IOException(
          String.format("Error getting access token for service account: %s", e.getMessage()), e);
    }

    GenericData responseData = response.parseAs(GenericData.class);
    String accessToken = validateString(responseData, "access_token", PARSE_ERROR_PREFIX);
    int expiresInSeconds = validateInt32(responseData, "expires_in", PARSE_ERROR_PREFIX);
    long expiresAtMilliseconds = Clock.SYSTEM.currentTimeMillis() + expiresInSeconds * 1000L;
    return new AccessToken(accessToken, new Date(expiresAtMilliseconds));
  }

  static class DefaultHttpTransportFactory implements HttpTransportFactory {

    @Override
    public HttpTransport create() {
      return HTTP_TRANSPORT;
    }
  }

  protected static <T> T getFromServiceLoader(Class<? extends T> clazz, T defaultInstance) {
    return Iterables.getFirst(ServiceLoader.load(clazz), defaultInstance);
  }

  /** Return the specified string from JSON or throw a helpful error message. */
  static String validateString(Map<String, Object> map, String key, String errorPrefix)
      throws IOException {
    Object value = map.get(key);
    if (value == null) {
      throw new IOException(String.format(VALUE_NOT_FOUND_MESSAGE, errorPrefix, key));
    }
    if (!(value instanceof String)) {
      throw new IOException(String.format(VALUE_WRONG_TYPE_MESSAGE, errorPrefix, "string", key));
    }
    return (String) value;
  }

  /** Return the specified integer from JSON or throw a helpful error message. */
  static int validateInt32(Map<String, Object> map, String key, String errorPrefix)
      throws IOException {
    Object value = map.get(key);
    if (value == null) {
      throw new IOException(String.format(VALUE_NOT_FOUND_MESSAGE, errorPrefix, key));
    }
    if (value instanceof BigDecimal) {
      BigDecimal bigDecimalValue = (BigDecimal) value;
      return bigDecimalValue.intValueExact();
    }
    if (!(value instanceof Integer)) {
      throw new IOException(String.format(VALUE_WRONG_TYPE_MESSAGE, errorPrefix, "integer", key));
    }
    return (Integer) value;
  }
}
