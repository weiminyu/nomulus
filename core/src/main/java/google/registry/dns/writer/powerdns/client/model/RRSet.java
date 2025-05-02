package google.registry.dns.writer.powerdns.client.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public class RRSet {
  @JsonProperty("name")
  private String name;

  @JsonProperty("type")
  private String type;

  @JsonProperty("ttl")
  private Integer ttl;

  @JsonProperty("changetype")
  private ChangeType changetype;

  @JsonProperty("records")
  private List<RecordObject> records;

  @JsonProperty("comments")
  private List<Comment> comments;

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getType() {
    return type;
  }

  public void setType(String type) {
    this.type = type;
  }

  public Integer getTtl() {
    return ttl;
  }

  public void setTtl(Integer ttl) {
    this.ttl = ttl;
  }

  public ChangeType getChangetype() {
    return changetype;
  }

  public void setChangetype(ChangeType changetype) {
    this.changetype = changetype;
  }

  public List<RecordObject> getRecords() {
    return records;
  }

  public void setRecords(List<RecordObject> records) {
    this.records = records;
  }

  public List<Comment> getComments() {
    return comments;
  }

  public void setComments(List<Comment> comments) {
    this.comments = comments;
  }

  public enum ChangeType {
    REPLACE,
    DELETE
  }
}
