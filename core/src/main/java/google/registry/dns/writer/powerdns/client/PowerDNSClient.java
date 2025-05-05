package google.registry.dns.writer.powerdns.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.flogger.FluentLogger;
import google.registry.dns.writer.powerdns.client.model.Server;
import google.registry.dns.writer.powerdns.client.model.Zone;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class PowerDNSClient {
  // static fields
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private final OkHttpClient httpClient;
  private final ObjectMapper objectMapper;
  private final String baseUrl;
  private final String apiKey;

  // dynamic fields
  private String serverId;

  public PowerDNSClient(String baseUrl, String apiKey) {
    // initialize the base URL, API key, and HTTP client
    this.baseUrl = baseUrl;
    this.apiKey = apiKey;
    this.httpClient = new OkHttpClient();
    this.objectMapper = new ObjectMapper();

    // initialize the Server ID by querying the server list and choosing
    // the first entry
    try {
      List<Server> servers = listServers();
      if (servers.isEmpty()) {
        throw new IOException("No servers found");
      }
      this.serverId = servers.get(0).getId();
    } catch (IOException e) {
      this.serverId = "unknown-server-id";
    }
  }

  private Response logAndExecuteRequest(Request request) throws IOException {
    // log the request and create timestamp for the start time
    logger.atInfo().log("Executing PowerDNS request: %s, body: %s", request, request.body());
    long startTime = System.currentTimeMillis();

    // execute the request and log the response
    Response response = httpClient.newCall(request).execute();
    logger.atInfo().log("PowerDNS response: %s", response);

    // log the response time and response code
    long endTime = System.currentTimeMillis();
    logger.atInfo().log(
        "Completed PowerDNS request in %d ms, success: %s, response code: %d",
        endTime - startTime, response.isSuccessful(), response.code());

    // return the response
    return response;
  }

  public List<Server> listServers() throws IOException {
    Request request =
        new Request.Builder().url(baseUrl + "/servers").header("X-API-Key", apiKey).get().build();

    try (Response response = logAndExecuteRequest(request)) {
      if (!response.isSuccessful()) {
        throw new IOException("Failed to list servers: " + response);
      }
      return objectMapper.readValue(
          Objects.requireNonNull(response.body()).string(),
          objectMapper.getTypeFactory().constructCollectionType(List.class, Server.class));
    }
  }

  public Server getServer() throws IOException {
    Request request =
        new Request.Builder()
            .url(baseUrl + "/servers/" + serverId)
            .header("X-API-Key", apiKey)
            .get()
            .build();

    try (Response response = logAndExecuteRequest(request)) {
      if (!response.isSuccessful()) {
        throw new IOException("Failed to get server: " + response);
      }
      return objectMapper.readValue(Objects.requireNonNull(response.body()).string(), Server.class);
    }
  }

  public String getServerId() {
    return serverId;
  }

  public void setServerId(String serverId) {
    this.serverId = serverId;
  }

  public List<Zone> listZones() throws IOException {
    Request request =
        new Request.Builder()
            .url(baseUrl + "/servers/" + serverId + "/zones")
            .header("X-API-Key", apiKey)
            .get()
            .build();

    try (Response response = logAndExecuteRequest(request)) {
      if (!response.isSuccessful()) {
        throw new IOException("Failed to list zones: " + response);
      }
      return objectMapper.readValue(
          Objects.requireNonNull(response.body()).string(),
          objectMapper.getTypeFactory().constructCollectionType(List.class, Zone.class));
    }
  }

  public Zone getZone(String zoneId) throws IOException {
    Request request =
        new Request.Builder()
            .url(baseUrl + "/servers/" + serverId + "/zones/" + zoneId)
            .header("X-API-Key", apiKey)
            .get()
            .build();

    try (Response response = logAndExecuteRequest(request)) {
      if (!response.isSuccessful()) {
        throw new IOException("Failed to get zone: " + response);
      }
      return objectMapper.readValue(Objects.requireNonNull(response.body()).string(), Zone.class);
    }
  }

  public Zone createZone(Zone zone) throws IOException {
    String json = objectMapper.writeValueAsString(zone);
    RequestBody body = RequestBody.create(json, MediaType.parse("application/json"));

    Request request =
        new Request.Builder()
            .url(baseUrl + "/servers/" + serverId + "/zones")
            .header("X-API-Key", apiKey)
            .post(body)
            .build();

    try (Response response = logAndExecuteRequest(request)) {
      if (!response.isSuccessful()) {
        throw new IOException("Failed to create zone: " + response);
      }
      return objectMapper.readValue(Objects.requireNonNull(response.body()).string(), Zone.class);
    }
  }

  public void deleteZone(String zoneId) throws IOException {
    Request request =
        new Request.Builder()
            .url(baseUrl + "/servers/" + serverId + "/zones/" + zoneId)
            .header("X-API-Key", apiKey)
            .delete()
            .build();

    try (Response response = logAndExecuteRequest(request)) {
      if (!response.isSuccessful()) {
        throw new IOException("Failed to delete zone: " + response);
      }
    }
  }

  public void patchZone(Zone zone) throws IOException {
    String json = objectMapper.writeValueAsString(zone);
    RequestBody body = RequestBody.create(json, MediaType.parse("application/json"));

    Request request =
        new Request.Builder()
            .url(baseUrl + "/servers/" + serverId + "/zones/" + zone.getId())
            .header("X-API-Key", apiKey)
            .patch(body)
            .build();

    try (Response response = logAndExecuteRequest(request)) {
      if (!response.isSuccessful()) {
        throw new IOException("Failed to patch zone: " + response);
      }
    }
  }

  public void notifyZone(String zoneId) throws IOException {
    Request request =
        new Request.Builder()
            .url(baseUrl + "/servers/" + serverId + "/zones/" + zoneId + "/notify")
            .header("X-API-Key", apiKey)
            .put(RequestBody.create("", MediaType.parse("application/json")))
            .build();

    try (Response response = logAndExecuteRequest(request)) {
      if (!response.isSuccessful()) {
        throw new IOException("Failed to notify zone: " + response);
      }
    }
  }
}
