package lab.contracts.detector.model;

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
 * Incident
 */

@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", comments = "Generator version: 7.26.0")
public class Incident {

  private UUID incidentId;

  /**
   * Gets or Sets family
   */
  public enum FamilyEnum {
    CREDENTIAL_STUFFING("CREDENTIAL_STUFFING"),
    
    BOLA("BOLA"),
    
    REPLAY("REPLAY"),
    
    INJECTION("INJECTION"),
    
    BUSINESS_LOGIC("BUSINESS_LOGIC");

    private final String value;

    FamilyEnum(String value) {
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
    public static FamilyEnum fromValue(String value) {
      for (FamilyEnum b : FamilyEnum.values()) {
        if (b.value.equals(value)) {
          return b;
        }
      }
      return null;
    }
  }

  private @Nullable FamilyEnum family = null;

  @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
  private OffsetDateTime detectedAt;

  private Set<UUID> requestIds = new LinkedHashSet<>();

  private String description;

  public Incident() {
    super();
  }

  /**
   * Constructor with only required parameters
   */
  public Incident(UUID incidentId, OffsetDateTime detectedAt, Set<UUID> requestIds, String description) {
    this.incidentId = incidentId;
    this.detectedAt = detectedAt;
    this.requestIds = requestIds;
    this.description = description;
  }

  public Incident incidentId(UUID incidentId) {
    this.incidentId = incidentId;
    return this;
  }

  /**
   * Get incidentId
   * @return incidentId
   */
  @NotNull @Valid 
  @JsonProperty("incidentId")
  public UUID getIncidentId() {
    return incidentId;
  }

  @JsonProperty("incidentId")
  public void setIncidentId(UUID incidentId) {
    this.incidentId = incidentId;
  }

  public Incident family(@Nullable FamilyEnum family) {
    this.family = family;
    return this;
  }

  /**
   * Get family
   * @return family
   */
  
  @JsonProperty("family")
  public @Nullable FamilyEnum getFamily() {
    return family;
  }

  @JsonProperty("family")
  public void setFamily(@Nullable FamilyEnum family) {
    this.family = family;
  }

  public Incident detectedAt(OffsetDateTime detectedAt) {
    this.detectedAt = detectedAt;
    return this;
  }

  /**
   * Get detectedAt
   * @return detectedAt
   */
  @NotNull @Valid 
  @JsonProperty("detectedAt")
  public OffsetDateTime getDetectedAt() {
    return detectedAt;
  }

  @JsonProperty("detectedAt")
  public void setDetectedAt(OffsetDateTime detectedAt) {
    this.detectedAt = detectedAt;
  }

  public Incident requestIds(Set<UUID> requestIds) {
    this.requestIds = requestIds;
    return this;
  }

  public Incident addRequestIdsItem(UUID requestIdsItem) {
    if (this.requestIds == null) {
      this.requestIds = new LinkedHashSet<>();
    }
    this.requestIds.add(requestIdsItem);
    return this;
  }

  /**
   * Get requestIds
   * @return requestIds
   */
  @NotNull @Size(min = 1, max = 10000) 
  @JsonProperty("requestIds")
  public Set<UUID> getRequestIds() {
    return requestIds;
  }

  @JsonDeserialize(as = LinkedHashSet.class)
  @JsonProperty("requestIds")
  public void setRequestIds(Set<UUID> requestIds) {
    this.requestIds = requestIds;
  }

  public Incident description(String description) {
    this.description = description;
    return this;
  }

  /**
   * Get description
   * @return description
   */
  @NotNull @Size(min = 1, max = 1000) 
  @JsonProperty("description")
  public String getDescription() {
    return description;
  }

  @JsonProperty("description")
  public void setDescription(String description) {
    this.description = description;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    Incident incident = (Incident) o;
    return Objects.equals(this.incidentId, incident.incidentId) &&
        Objects.equals(this.family, incident.family) &&
        Objects.equals(this.detectedAt, incident.detectedAt) &&
        Objects.equals(this.requestIds, incident.requestIds) &&
        Objects.equals(this.description, incident.description);
  }

  @Override
  public int hashCode() {
    return Objects.hash(incidentId, family, detectedAt, requestIds, description);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class Incident {\n");
    sb.append("    incidentId: ").append(toIndentedString(incidentId)).append("\n");
    sb.append("    family: ").append(toIndentedString(family)).append("\n");
    sb.append("    detectedAt: ").append(toIndentedString(detectedAt)).append("\n");
    sb.append("    requestIds: ").append(toIndentedString(requestIds)).append("\n");
    sb.append("    description: ").append(toIndentedString(description)).append("\n");
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

