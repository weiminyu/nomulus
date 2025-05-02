package google.registry.dns.writer.powerdns.client.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public class Comment {
  @JsonProperty("content")
  private String content;

  @JsonProperty("account")
  private String account;

  @JsonProperty("modified_at")
  private Long modifiedAt;

  public String getContent() {
    return content;
  }

  public void setContent(String content) {
    this.content = content;
  }

  public String getAccount() {
    return account;
  }

  public void setAccount(String account) {
    this.account = account;
  }

  public Long getModifiedAt() {
    return modifiedAt;
  }

  public void setModifiedAt(Long modifiedAt) {
    this.modifiedAt = modifiedAt;
  }
}
