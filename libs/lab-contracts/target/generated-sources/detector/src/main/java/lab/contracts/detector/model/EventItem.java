package lab.contracts.detector.model;

import java.net.URI;
import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.UUID;
import lab.contracts.detector.model.AccessLog;
import org.springframework.lang.Nullable;
import java.time.OffsetDateTime;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;


import java.util.*;
import jakarta.annotation.Generated;

/**
 * EventItem
 */

@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", comments = "Generator version: 7.26.0")
public class EventItem {

  @JsonInclude(JsonInclude.Include.NON_NULL)
  private UUID runId;

  /**
   * Gets or Sets producer
   */
  public enum ProducerEnum {
    GATEWAY("GATEWAY"),
    
    SHOP("SHOP");

    private final String value;

    ProducerEnum(String value) {
      this.value = value;
    }

    @JsonValue
    public String getValue() {
      return value;
    }

    @Override
    public String toString() {
      return String.valueOf(value);
    }

    @JsonCreator
    public static ProducerEnum fromValue(String value) {
      for (ProducerEnum b : ProducerEnum.values()) {
        if (b.value.equals(value)) {
          return b;
        }
      }
      throw new IllegalArgumentException("Unexpected value '" + value + "'");
    }
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  private ProducerEnum producer;

  @JsonInclude(JsonInclude.Include.NON_NULL)
  private AccessLog log;

  public EventItem() {
    super();
  }

  /**
   * Constructor with only required parameters
   */
  public EventItem(UUID runId, ProducerEnum producer, AccessLog log) {
    this.runId = runId;
    this.producer = producer;
    this.log = log;
  }

  public EventItem runId(UUID runId) {
    this.runId = runId;
    return this;
  }

  /**
   * Get runId
   * @return runId
   */
  @NotNull @Valid 
  @JsonProperty("runId")
  public UUID getRunId() {
    return runId;
  }

  @JsonProperty("runId")
  public void setRunId(UUID runId) {
    this.runId = runId;
  }

  public EventItem producer(ProducerEnum producer) {
    this.producer = producer;
    return this;
  }

  /**
   * Get producer
   * @return producer
   */
  @NotNull 
  @JsonProperty("producer")
  public ProducerEnum getProducer() {
    return producer;
  }

  @JsonProperty("producer")
  public void setProducer(ProducerEnum producer) {
    this.producer = producer;
  }

  public EventItem log(AccessLog log) {
    this.log = log;
    return this;
  }

  /**
   * Get log
   * @return log
   */
  @NotNull @Valid 
  @JsonProperty("log")
  public AccessLog getLog() {
    return log;
  }

  @JsonProperty("log")
  public void setLog(AccessLog log) {
    this.log = log;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    EventItem eventItem = (EventItem) o;
    return Objects.equals(this.runId, eventItem.runId) &&
        Objects.equals(this.producer, eventItem.producer) &&
        Objects.equals(this.log, eventItem.log);
  }

  @Override
  public int hashCode() {
    return Objects.hash(runId, producer, log);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class EventItem {\n");
    sb.append("    runId: ").append(toIndentedString(runId)).append("\n");
    sb.append("    producer: ").append(toIndentedString(producer)).append("\n");
    sb.append("    log: ").append(toIndentedString(log)).append("\n");
    sb.append("}");
    return sb.toString();
  }

  /**
   * Convert the given object to string with each line indented by 4 spaces
   * (except the first line).
   */
  private String toIndentedString(@Nullable Object o) {
    return o == null ? "null" : o.toString().replace("\n", "\n    ");
  }
}

