package lab.contracts.detector.model;

import java.net.URI;
import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.lang.Nullable;
import tools.jackson.databind.annotation.JsonDeserialize;
import java.time.OffsetDateTime;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;


import java.util.*;
import jakarta.annotation.Generated;

/**
 * AnalysisStatus
 */

@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", comments = "Generator version: 7.26.0")
public class AnalysisStatus {

  private UUID instanceId;

  /**
   * Gets or Sets state
   */
  public enum StateEnum {
    ACCEPTING("ACCEPTING"),
    
    FINISHING("FINISHING"),
    
    COMPLETED("COMPLETED"),
    
    FAILED("FAILED");

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

  private @Nullable String error = null;

  private Set<UUID> observedRequestIds;

  public AnalysisStatus() {
    super();
  }

  /**
   * Constructor with only required parameters
   */
  public AnalysisStatus(UUID instanceId, StateEnum state, Set<UUID> observedRequestIds) {
    this.instanceId = instanceId;
    this.state = state;
    this.observedRequestIds = observedRequestIds;
  }

  public AnalysisStatus instanceId(UUID instanceId) {
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

  public AnalysisStatus state(StateEnum state) {
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

  public AnalysisStatus error(@Nullable String error) {
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

  public AnalysisStatus observedRequestIds(Set<UUID> observedRequestIds) {
    this.observedRequestIds = observedRequestIds;
    return this;
  }

  public AnalysisStatus addObservedRequestIdsItem(UUID observedRequestIdsItem) {
    if (this.observedRequestIds == null) {
      this.observedRequestIds = new LinkedHashSet<>();
    }
    this.observedRequestIds.add(observedRequestIdsItem);
    return this;
  }

  /**
   * Уникальные requestId обоих producers после COMPLETED; до завершения null. Все Shop-запросы проходят через Gateway.
   * @return observedRequestIds
   */
  @Size(min = 0, max = 100000) 
  @JsonProperty("observedRequestIds")
  public Set<UUID> getObservedRequestIds() {
    return observedRequestIds;
  }

  @JsonDeserialize(as = LinkedHashSet.class)
  @JsonProperty("observedRequestIds")
  public void setObservedRequestIds(Set<UUID> observedRequestIds) {
    this.observedRequestIds = observedRequestIds;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    AnalysisStatus analysisStatus = (AnalysisStatus) o;
    return Objects.equals(this.instanceId, analysisStatus.instanceId) &&
        Objects.equals(this.state, analysisStatus.state) &&
        Objects.equals(this.error, analysisStatus.error) &&
        Objects.equals(this.observedRequestIds, analysisStatus.observedRequestIds);
  }

  @Override
  public int hashCode() {
    return Objects.hash(instanceId, state, error, observedRequestIds);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class AnalysisStatus {\n");
    sb.append("    instanceId: ").append(toIndentedString(instanceId)).append("\n");
    sb.append("    state: ").append(toIndentedString(state)).append("\n");
    sb.append("    error: ").append(toIndentedString(error)).append("\n");
    sb.append("    observedRequestIds: ").append(toIndentedString(observedRequestIds)).append("\n");
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

