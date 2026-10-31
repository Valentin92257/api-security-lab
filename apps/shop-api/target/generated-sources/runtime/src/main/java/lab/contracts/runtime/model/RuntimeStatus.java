package lab.contracts.runtime.model;

import java.net.URI;
import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.UUID;
import org.springframework.lang.Nullable;
import java.time.OffsetDateTime;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;


import java.util.*;
import jakarta.annotation.Generated;

/**
 * RuntimeStatus
 */

@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", comments = "Generator version: 7.26.0")
public class RuntimeStatus {

  private UUID instanceId;

  /**
   * Gets or Sets state
   */
  public enum StateEnum {
    PREPARED("PREPARED"),
    
    OPEN("OPEN"),
    
    CLOSING("CLOSING"),
    
    CLOSED("CLOSED"),
    
    ERROR("ERROR");

    private final String value;

    StateEnum(String value) {
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
    public static StateEnum fromValue(String value) {
      for (StateEnum b : StateEnum.values()) {
        if (b.value.equals(value)) {
          return b;
        }
      }
      throw new IllegalArgumentException("Unexpected value '" + value + "'");
    }
  }

  private StateEnum state;

  private Integer lastSequence;

  private @Nullable String error = null;

  public RuntimeStatus() {
    super();
  }

  /**
   * Constructor with only required parameters
   */
  public RuntimeStatus(UUID instanceId, StateEnum state, Integer lastSequence) {
    this.instanceId = instanceId;
    this.state = state;
    this.lastSequence = lastSequence;
  }

  public RuntimeStatus instanceId(UUID instanceId) {
    this.instanceId = instanceId;
    return this;
  }

  /**
   * Get instanceId
   * @return instanceId
   */
  @NotNull @Valid 
  @JsonProperty("instanceId")
  public UUID getInstanceId() {
    return instanceId;
  }

  @JsonProperty("instanceId")
  public void setInstanceId(UUID instanceId) {
    this.instanceId = instanceId;
  }

  public RuntimeStatus state(StateEnum state) {
    this.state = state;
    return this;
  }

  /**
   * Get state
   * @return state
   */
  @NotNull 
  @JsonProperty("state")
  public StateEnum getState() {
    return state;
  }

  @JsonProperty("state")
  public void setState(StateEnum state) {
    this.state = state;
  }

  public RuntimeStatus lastSequence(Integer lastSequence) {
    this.lastSequence = lastSequence;
    return this;
  }

  /**
   * Get lastSequence
   * minimum: 0
   * maximum: 100000
   * @return lastSequence
   */
  @NotNull @Min(value = 0) @Max(value = 100000) 
  @JsonProperty("lastSequence")
  public Integer getLastSequence() {
    return lastSequence;
  }

  @JsonProperty("lastSequence")
  public void setLastSequence(Integer lastSequence) {
    this.lastSequence = lastSequence;
  }

  public RuntimeStatus error(@Nullable String error) {
    this.error = error;
    return this;
  }

  /**
   * Get error
   * @return error
   */
  @Size(max = 500) 
  @JsonProperty("error")
  public @Nullable String getError() {
    return error;
  }

  @JsonProperty("error")
  public void setError(@Nullable String error) {
    this.error = error;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    RuntimeStatus runtimeStatus = (RuntimeStatus) o;
    return Objects.equals(this.instanceId, runtimeStatus.instanceId) &&
        Objects.equals(this.state, runtimeStatus.state) &&
        Objects.equals(this.lastSequence, runtimeStatus.lastSequence) &&
        Objects.equals(this.error, runtimeStatus.error);
  }

  @Override
  public int hashCode() {
    return Objects.hash(instanceId, state, lastSequence, error);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class RuntimeStatus {\n");
    sb.append("    instanceId: ").append(toIndentedString(instanceId)).append("\n");
    sb.append("    state: ").append(toIndentedString(state)).append("\n");
    sb.append("    lastSequence: ").append(toIndentedString(lastSequence)).append("\n");
    sb.append("    error: ").append(toIndentedString(error)).append("\n");
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

