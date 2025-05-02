package google.registry.dns.writer.powerdns.client.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public class Server {
  @JsonProperty("type")
  private String type;

  @JsonProperty("id")
  private String id;

  @JsonProperty("daemon_type")
  private String daemonType;

  @JsonProperty("version")
  private String version;

  @JsonProperty("url")
  private String url;

  @JsonProperty("config_url")
  private String configUrl;

  @JsonProperty("zones_url")
  private String zonesUrl;

  public String getType() {
    return type;
  }

  public void setType(String type) {
    this.type = type;
  }

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public String getDaemonType() {
    return daemonType;
  }

  public void setDaemonType(String daemonType) {
    this.daemonType = daemonType;
  }

  public String getVersion() {
    return version;
  }

  public void setVersion(String version) {
    this.version = version;
  }

  public String getUrl() {
    return url;
  }

  public void setUrl(String url) {
    this.url = url;
  }

  public String getConfigUrl() {
    return configUrl;
  }

  public void setConfigUrl(String configUrl) {
    this.configUrl = configUrl;
  }

  public String getZonesUrl() {
    return zonesUrl;
  }

  public void setZonesUrl(String zonesUrl) {
    this.zonesUrl = zonesUrl;
  }
}
