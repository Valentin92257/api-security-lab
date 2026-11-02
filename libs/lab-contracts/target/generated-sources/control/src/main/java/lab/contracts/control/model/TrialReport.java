package lab.contracts.control.model;

import java.net.URI;
import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lab.contracts.control.model.RunReport;
import org.springframework.lang.Nullable;
import tools.jackson.databind.annotation.JsonDeserialize;
import java.time.OffsetDateTime;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;


import java.util.*;
import jakarta.annotation.Generated;

/**
 * TrialReport
 */

@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", comments = "Generator version: 7.26.0")
public class TrialReport {

  private UUID trialId;

  private Integer seed;

  /**
   * Gets or Sets state
   */
  public enum StateEnum {
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

  private List<@Valid RunReport> runs = new ArrayList<>();

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

  public TrialReport() {
    super();
  }

  /**
   * Constructor with only required parameters
   */
  public TrialReport(UUID trialId, Integer seed, StateEnum state, List<@Valid RunReport> runs, Set<FamiliesEnum> families) {
    this.trialId = trialId;
    this.seed = seed;
    this.state = state;
    this.runs = runs;
    this.families = families;
  }

  public TrialReport trialId(UUID trialId) {
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

  public TrialReport seed(Integer seed) {
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

  public TrialReport state(StateEnum state) {
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

  public TrialReport runs(List<@Valid RunReport> runs) {
    this.runs = runs;
    return this;
  }

  public TrialReport addRunsItem(RunReport runsItem) {
    if (this.runs == null) {
      this.runs = new ArrayList<>();
    }
    this.runs.add(runsItem);
    return this;
  }

  /**
   * Get runs
   * @return runs
   */
  @NotNull @Size(min = 0, max = 2) 
  @JsonProperty("runs")
  public List<@Valid RunReport> getRuns() {
    return runs;
  }

  @JsonProperty("runs")
  public void setRuns(List<@Valid RunReport> runs) {
    this.runs = runs;
  }

  public TrialReport families(Set<FamiliesEnum> families) {
    this.families = families;
    return this;
  }

  public TrialReport addFamiliesItem(FamiliesEnum familiesItem) {
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

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    TrialReport trialReport = (TrialReport) o;
    return Objects.equals(this.trialId, trialReport.trialId) &&
        Objects.equals(this.seed, trialReport.seed) &&
        Objects.equals(this.state, trialReport.state) &&
        Objects.equals(this.runs, trialReport.runs) &&
        Objects.equals(this.families, trialReport.families);
  }

  @Override
  public int hashCode() {
    return Objects.hash(trialId, seed, state, runs, families);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class TrialReport {\n");
    sb.append("    trialId: ").append(toIndentedString(trialId)).append("\n");
    sb.append("    seed: ").append(toIndentedString(seed)).append("\n");
    sb.append("    state: ").append(toIndentedString(state)).append("\n");
    sb.append("    runs: ").append(toIndentedString(runs)).append("\n");
    sb.append("    families: ").append(toIndentedString(families)).append("\n");
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

