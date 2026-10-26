package lab.contracts.control.model;

import java.net.URI;
import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import lab.contracts.control.model.Incident;
import org.springframework.lang.Nullable;
import tools.jackson.databind.annotation.JsonDeserialize;
import java.time.OffsetDateTime;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;


import java.util.*;
import jakarta.annotation.Generated;

/**
 * IncidentAssessment
 */

@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", comments = "Generator version: 7.26.0")
public class IncidentAssessment {

  private Incident incident;

  /**
   * Gets or Sets verdict
   */
  public enum VerdictEnum {
    TRUE_POSITIVE("TRUE_POSITIVE"),
    
    FALSE_POSITIVE("FALSE_POSITIVE"),
    
    UNASSESSED("UNASSESSED");

    private final String value;

    VerdictEnum(String value) {
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
    public static VerdictEnum fromValue(String value) {
      for (VerdictEnum b : VerdictEnum.values()) {
        if (b.value.equals(value)) {
          return b;
        }
      }
      throw new IllegalArgumentException("Unexpected value '" + value + "'");
    }
  }

  private VerdictEnum verdict;

  private Set<@Size(min = 1, max = 128)String> matchedEpisodeIds = new LinkedHashSet<>();

  private String reason;

  public IncidentAssessment() {
    super();
  }

  /**
   * Constructor with only required parameters
   */
  public IncidentAssessment(Incident incident, VerdictEnum verdict, Set<@Size(min = 1, max = 128)String> matchedEpisodeIds, String reason) {
    this.incident = incident;
    this.verdict = verdict;
    this.matchedEpisodeIds = matchedEpisodeIds;
    this.reason = reason;
  }

  public IncidentAssessment incident(Incident incident) {
    this.incident = incident;
    return this;
  }

  /**
   * Get incident
   * @return incident
   */
  @NotNull @Valid 
  @JsonProperty("incident")
  public Incident getIncident() {
    return incident;
  }

  @JsonProperty("incident")
  public void setIncident(Incident incident) {
    this.incident = incident;
  }

  public IncidentAssessment verdict(VerdictEnum verdict) {
    this.verdict = verdict;
    return this;
  }

  /**
   * Get verdict
   * @return verdict
   */
  @NotNull 
  @JsonProperty("verdict")
  public VerdictEnum getVerdict() {
    return verdict;
  }

  @JsonProperty("verdict")
  public void setVerdict(VerdictEnum verdict) {
    this.verdict = verdict;
  }

  public IncidentAssessment matchedEpisodeIds(Set<@Size(min = 1, max = 128)String> matchedEpisodeIds) {
    this.matchedEpisodeIds = matchedEpisodeIds;
    return this;
  }

  public IncidentAssessment addMatchedEpisodeIdsItem(String matchedEpisodeIdsItem) {
    if (this.matchedEpisodeIds == null) {
      this.matchedEpisodeIds = new LinkedHashSet<>();
    }
    this.matchedEpisodeIds.add(matchedEpisodeIdsItem);
    return this;
  }

  /**
   * Связанные episodeId из cases по evidence запросов; TRUE_POSITIVE включает ATTACK, FALSE_POSITIVE только BENIGN, UNASSESSED указывает недоказанную связь.
   * @return matchedEpisodeIds
   */
  @NotNull @Size(min = 0, max = 1000) 
  @JsonProperty("matchedEpisodeIds")
  public Set<@Size(min = 1, max = 128)String> getMatchedEpisodeIds() {
    return matchedEpisodeIds;
  }

  @JsonDeserialize(as = LinkedHashSet.class)
  @JsonProperty("matchedEpisodeIds")
  public void setMatchedEpisodeIds(Set<@Size(min = 1, max = 128)String> matchedEpisodeIds) {
    this.matchedEpisodeIds = matchedEpisodeIds;
  }

  public IncidentAssessment reason(String reason) {
    this.reason = reason;
    return this;
  }

  /**
   * Get reason
   * @return reason
   */
  @NotNull @Size(min = 1, max = 1000) 
  @JsonProperty("reason")
  public String getReason() {
    return reason;
  }

  @JsonProperty("reason")
  public void setReason(String reason) {
    this.reason = reason;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    IncidentAssessment incidentAssessment = (IncidentAssessment) o;
    return Objects.equals(this.incident, incidentAssessment.incident) &&
        Objects.equals(this.verdict, incidentAssessment.verdict) &&
        Objects.equals(this.matchedEpisodeIds, incidentAssessment.matchedEpisodeIds) &&
        Objects.equals(this.reason, incidentAssessment.reason);
  }

  @Override
  public int hashCode() {
    return Objects.hash(incident, verdict, matchedEpisodeIds, reason);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class IncidentAssessment {\n");
    sb.append("    incident: ").append(toIndentedString(incident)).append("\n");
    sb.append("    verdict: ").append(toIndentedString(verdict)).append("\n");
    sb.append("    matchedEpisodeIds: ").append(toIndentedString(matchedEpisodeIds)).append("\n");
    sb.append("    reason: ").append(toIndentedString(reason)).append("\n");
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

