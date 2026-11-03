package lab.contracts.control.model;

import java.net.URI;
import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.lang.Nullable;
import tools.jackson.databind.annotation.JsonDeserialize;
import java.time.OffsetDateTime;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;


import java.util.*;
import jakarta.annotation.Generated;

/**
 * Trial
 */

@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", comments = "Generator version: 7.26.0")
public class Trial {

  private UUID trialId;

  /**
   * Gets or Sets families
   */
  public enum FamiliesEnum {
    CREDENTIAL_STUFFING("CREDENTIAL_STUFFING"),
    
    BOLA("BOLA"),
    
    REPLAY("REPLAY"),
    
    INJECTION("INJECTION"),
    
    BUSINESS_LOGIC("BUSINESS_LOGIC");

    private final String value;

    FamiliesEnum(String value) {
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
    public static FamiliesEnum fromValue(String value) {
      for (FamiliesEnum b : FamiliesEnum.values()) {
        if (b.value.equals(value)) {
          return b;
        }
      }
      throw new IllegalArgumentException("Unexpected value '" + value + "'");
    }
  }

  private Set<FamiliesEnum> families = new LinkedHashSet<>();

  private Integer seed;

  /**
   * Gets or Sets state
   */
  public enum StateEnum {
    PREPARING("PREPARING"),
    
    RUNNING("RUNNING"),
    
    FINALIZING("FINALIZING"),
    
    COMPLETED("COMPLETED"),
    
    FAILED("FAILED"),
    
    CANCELLED("CANCELLED"),
    
    INCONCLUSIVE("INCONCLUSIVE");

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

  @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
  private OffsetDateTime createdAt;

  private Long plannedSteps;

  private Long finishedSteps;

  private Boolean cleanupSafe;

  private @Nullable String error = null;

  public Trial() {
    super();
  }

  /**
   * Constructor with only required parameters
   */
  public Trial(UUID trialId, Set<FamiliesEnum> families, Integer seed, StateEnum state, OffsetDateTime createdAt, Long plannedSteps, Long finishedSteps, Boolean cleanupSafe) {
    this.trialId = trialId;
    this.families = families;
    this.seed = seed;
    this.state = state;
    this.createdAt = createdAt;
    this.plannedSteps = plannedSteps;
    this.finishedSteps = finishedSteps;
    this.cleanupSafe = cleanupSafe;
  }

  public Trial trialId(UUID trialId) {
    this.trialId = trialId;
    return this;
  }

  /**
   * Get trialId
   * @return trialId
   */
  @NotNull @Valid 
  @JsonProperty("trialId")
  public UUID getTrialId() {
    return trialId;
  }

  @JsonProperty("trialId")
  public void setTrialId(UUID trialId) {
    this.trialId = trialId;
  }

  public Trial families(Set<FamiliesEnum> families) {
    this.families = families;
    return this;
  }

  public Trial addFamiliesItem(FamiliesEnum familiesItem) {
    if (this.families == null) {
      this.families = new LinkedHashSet<>();
    }
    this.families.add(familiesItem);
    return this;
  }

  /**
   * Get families
   * @return families
   */
  @NotNull @Size(min = 1, max = 5) 
  @JsonProperty("families")
  public Set<FamiliesEnum> getFamilies() {
    return families;
  }

  @JsonDeserialize(as = LinkedHashSet.class)
  @JsonProperty("families")
  public void setFamilies(Set<FamiliesEnum> families) {
    this.families = families;
  }

  public Trial seed(Integer seed) {
    this.seed = seed;
    return this;
  }

  /**
   * Get seed
   * minimum: 0
   * maximum: 2147483647
   * @return seed
   */
  @NotNull @Min(value = 0) @Max(value = 2147483647) 
  @JsonProperty("seed")
  public Integer getSeed() {
    return seed;
  }

  @JsonProperty("seed")
  public void setSeed(Integer seed) {
    this.seed = seed;
  }

  public Trial state(StateEnum state) {
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

  public Trial createdAt(OffsetDateTime createdAt) {
    this.createdAt = createdAt;
    return this;
  }

  /**
   * Get createdAt
   * @return createdAt
   */
  @NotNull @Valid 
  @JsonProperty("createdAt")
  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }

  @JsonProperty("createdAt")
  public void setCreatedAt(OffsetDateTime createdAt) {
    this.createdAt = createdAt;
  }

  public Trial plannedSteps(Long plannedSteps) {
    this.plannedSteps = plannedSteps;
    return this;
  }

  /**
   * Get plannedSteps
   * minimum: 0
   * maximum: 9007199254740991
   * @return plannedSteps
   */
  @NotNull @Min(value = 0L) @Max(value = 9007199254740991L) 
  @JsonProperty("plannedSteps")
  public Long getPlannedSteps() {
    return plannedSteps;
  }

  @JsonProperty("plannedSteps")
  public void setPlannedSteps(Long plannedSteps) {
    this.plannedSteps = plannedSteps;
  }

  public Trial finishedSteps(Long finishedSteps) {
    this.finishedSteps = finishedSteps;
    return this;
  }

  /**
   * Get finishedSteps
   * minimum: 0
   * maximum: 9007199254740991
   * @return finishedSteps
   */
  @NotNull @Min(value = 0L) @Max(value = 9007199254740991L) 
  @JsonProperty("finishedSteps")
  public Long getFinishedSteps() {
    return finishedSteps;
  }

  @JsonProperty("finishedSteps")
  public void setFinishedSteps(Long finishedSteps) {
    this.finishedSteps = finishedSteps;
  }

  public Trial cleanupSafe(Boolean cleanupSafe) {
    this.cleanupSafe = cleanupSafe;
    return this;
  }

  /**
   * Разрешена подготовка данных следующего испытания.
   * @return cleanupSafe
   */
  @NotNull 
  @JsonProperty("cleanupSafe")
  public Boolean getCleanupSafe() {
    return cleanupSafe;
  }

  @JsonProperty("cleanupSafe")
  public void setCleanupSafe(Boolean cleanupSafe) {
    this.cleanupSafe = cleanupSafe;
  }

  public Trial error(@Nullable String error) {
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
    Trial trial = (Trial) o;
    return Objects.equals(this.trialId, trial.trialId) &&
        Objects.equals(this.families, trial.families) &&
        Objects.equals(this.seed, trial.seed) &&
        Objects.equals(this.state, trial.state) &&
        Objects.equals(this.createdAt, trial.createdAt) &&
        Objects.equals(this.plannedSteps, trial.plannedSteps) &&
        Objects.equals(this.finishedSteps, trial.finishedSteps) &&
        Objects.equals(this.cleanupSafe, trial.cleanupSafe) &&
        Objects.equals(this.error, trial.error);
  }

  @Override
  public int hashCode() {
    return Objects.hash(trialId, families, seed, state, createdAt, plannedSteps, finishedSteps, cleanupSafe, error);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class Trial {\n");
    sb.append("    trialId: ").append(toIndentedString(trialId)).append("\n");
    sb.append("    families: ").append(toIndentedString(families)).append("\n");
    sb.append("    seed: ").append(toIndentedString(seed)).append("\n");
    sb.append("    state: ").append(toIndentedString(state)).append("\n");
    sb.append("    createdAt: ").append(toIndentedString(createdAt)).append("\n");
    sb.append("    plannedSteps: ").append(toIndentedString(plannedSteps)).append("\n");
    sb.append("    finishedSteps: ").append(toIndentedString(finishedSteps)).append("\n");
    sb.append("    cleanupSafe: ").append(toIndentedString(cleanupSafe)).append("\n");
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

