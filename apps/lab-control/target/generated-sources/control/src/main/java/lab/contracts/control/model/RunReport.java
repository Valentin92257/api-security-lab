package lab.contracts.control.model;

import java.net.URI;
import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import lab.contracts.control.model.CaseResult;
import lab.contracts.control.model.IncidentAssessment;
import org.springframework.lang.Nullable;
import java.time.OffsetDateTime;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;


import java.util.*;
import jakarta.annotation.Generated;

/**
 * RunReport
 */

@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", comments = "Generator version: 7.26.0")
public class RunReport {

  private UUID runId;

  /**
   * Gets or Sets mode
   */
  public enum ModeEnum {
    VULNERABLE("VULNERABLE"),
    
    PROTECTED("PROTECTED");

    private final String value;

    ModeEnum(String value) {
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
    public static ModeEnum fromValue(String value) {
      for (ModeEnum b : ModeEnum.values()) {
        if (b.value.equals(value)) {
          return b;
        }
      }
      throw new IllegalArgumentException("Unexpected value '" + value + "'");
    }
  }

  private ModeEnum mode;

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

  private List<@Valid CaseResult> cases = new ArrayList<>();

  private List<@Valid IncidentAssessment> incidents;

  private @Nullable String error = null;

  public RunReport() {
    super();
  }

  /**
   * Constructor with only required parameters
   */
  public RunReport(UUID runId, ModeEnum mode, StateEnum state, List<@Valid CaseResult> cases, List<@Valid IncidentAssessment> incidents) {
    this.runId = runId;
    this.mode = mode;
    this.state = state;
    this.cases = cases;
    this.incidents = incidents;
  }

  public RunReport runId(UUID runId) {
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

  public RunReport mode(ModeEnum mode) {
    this.mode = mode;
    return this;
  }

  /**
   * Get mode
   * @return mode
   */
  @NotNull 
  @JsonProperty("mode")
  public ModeEnum getMode() {
    return mode;
  }

  @JsonProperty("mode")
  public void setMode(ModeEnum mode) {
    this.mode = mode;
  }

  public RunReport state(StateEnum state) {
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

  public RunReport cases(List<@Valid CaseResult> cases) {
    this.cases = cases;
    return this;
  }

  public RunReport addCasesItem(CaseResult casesItem) {
    if (this.cases == null) {
      this.cases = new ArrayList<>();
    }
    this.cases.add(casesItem);
    return this;
  }

  /**
   * Get cases
   * @return cases
   */
  @NotNull @Size(min = 0, max = 1000) 
  @JsonProperty("cases")
  public List<@Valid CaseResult> getCases() {
    return cases;
  }

  @JsonProperty("cases")
  public void setCases(List<@Valid CaseResult> cases) {
    this.cases = cases;
  }

  public RunReport incidents(List<@Valid IncidentAssessment> incidents) {
    this.incidents = incidents;
    return this;
  }

  public RunReport addIncidentsItem(IncidentAssessment incidentsItem) {
    if (this.incidents == null) {
      this.incidents = new ArrayList<>();
    }
    this.incidents.add(incidentsItem);
    return this;
  }

  /**
   * null при незавершённом анализе; пустой массив при отсутствии инцидентов.
   * @return incidents
   */
  @Size(min = 0, max = 10000) 
  @JsonProperty("incidents")
  public List<@Valid IncidentAssessment> getIncidents() {
    return incidents;
  }

  @JsonProperty("incidents")
  public void setIncidents(List<@Valid IncidentAssessment> incidents) {
    this.incidents = incidents;
  }

  public RunReport error(@Nullable String error) {
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
    RunReport runReport = (RunReport) o;
    return Objects.equals(this.runId, runReport.runId) &&
        Objects.equals(this.mode, runReport.mode) &&
        Objects.equals(this.state, runReport.state) &&
        Objects.equals(this.cases, runReport.cases) &&
        Objects.equals(this.incidents, runReport.incidents) &&
        Objects.equals(this.error, runReport.error);
  }

  @Override
  public int hashCode() {
    return Objects.hash(runId, mode, state, cases, incidents, error);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class RunReport {\n");
    sb.append("    runId: ").append(toIndentedString(runId)).append("\n");
    sb.append("    mode: ").append(toIndentedString(mode)).append("\n");
    sb.append("    state: ").append(toIndentedString(state)).append("\n");
    sb.append("    cases: ").append(toIndentedString(cases)).append("\n");
    sb.append("    incidents: ").append(toIndentedString(incidents)).append("\n");
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

