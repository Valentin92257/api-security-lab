package lab.contracts.detector.model;

import java.net.URI;
import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import lab.contracts.detector.model.AccessLog;
import org.springframework.lang.Nullable;
import java.time.OffsetDateTime;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;


import java.util.*;
import jakarta.annotation.Generated;

/**
 * LogBatch
 */

@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", comments = "Generator version: 7.26.0")
public class LogBatch {

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

  private ProducerEnum producer;

  private List<@Valid AccessLog> entries = new ArrayList<>();

  public LogBatch() {
    super();
  }

  /**
   * Constructor with only required parameters
   */
  public LogBatch(ProducerEnum producer, List<@Valid AccessLog> entries) {
    this.producer = producer;
    this.entries = entries;
  }

  public LogBatch producer(ProducerEnum producer) {
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

  public LogBatch entries(List<@Valid AccessLog> entries) {
    this.entries = entries;
    return this;
  }

  public LogBatch addEntriesItem(AccessLog entriesItem) {
    if (this.entries == null) {
      this.entries = new ArrayList<>();
    }
    this.entries.add(entriesItem);
    return this;
  }

  /**
   * Get entries
   * @return entries
   */
  @NotNull @Size(min = 1, max = 100) 
  @JsonProperty("entries")
  public List<@Valid AccessLog> getEntries() {
    return entries;
  }

  @JsonProperty("entries")
  public void setEntries(List<@Valid AccessLog> entries) {
    this.entries = entries;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    LogBatch logBatch = (LogBatch) o;
    return Objects.equals(this.producer, logBatch.producer) &&
        Objects.equals(this.entries, logBatch.entries);
  }

  @Override
  public int hashCode() {
    return Objects.hash(producer, entries);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class LogBatch {\n");
    sb.append("    producer: ").append(toIndentedString(producer)).append("\n");
    sb.append("    entries: ").append(toIndentedString(entries)).append("\n");
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

