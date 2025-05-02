package google.registry.dns.writer.powerdns.client.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public class RecordObject {
  @JsonProperty("content")
  private String content;

  @JsonProperty("disabled")
  private Boolean disabled;

  public String getContent() {
    return content;
  }

  public void setContent(String content) {
    this.content = content;
  }

  public Boolean getDisabled() {
    return disabled;
  }

  public void setDisabled(Boolean disabled) {
    this.disabled = disabled;
  }
}
