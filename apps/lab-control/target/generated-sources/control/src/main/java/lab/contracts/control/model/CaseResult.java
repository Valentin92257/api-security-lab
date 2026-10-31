package lab.contracts.control.model;

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
 * CaseResult
 */

@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", comments = "Generator version: 7.26.0")
public class CaseResult {

  private String caseId;

  private String episodeId;

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
      throw new IllegalArgumentException("Unexpected value '" + value + "'");
    }
  }

  private FamilyEnum family;

  /**
   * Gets or Sets label
   */
  public enum LabelEnum {
    ATTACK("ATTACK"),
    
    BENIGN("BENIGN");

    private final String value;

    LabelEnum(String value) {
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
    public static LabelEnum fromValue(String value) {
      for (LabelEnum b : LabelEnum.values()) {
        if (b.value.equals(value)) {
          return b;
        }
      }
      throw new IllegalArgumentException("Unexpected value '" + value + "'");
    }
  }

  private LabelEnum label;

  /**
   * Gets or Sets effect
   */
  public enum EffectEnum {
    EFFECT_OBSERVED("EFFECT_OBSERVED"),
    
    EFFECT_NOT_OBSERVED("EFFECT_NOT_OBSERVED"),
    
    INCONCLUSIVE("INCONCLUSIVE");

    private final String value;

    EffectEnum(String value) {
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
    public static EffectEnum fromValue(String value) {
      for (EffectEnum b : EffectEnum.values()) {
        if (b.value.equals(value)) {
          return b;
        }
      }
      throw new IllegalArgumentException("Unexpected value '" + value + "'");
    }
  }

  private EffectEnum effect;

  private Set<UUID> requestIds = new LinkedHashSet<>();

  private String reason;

  /**
   * Вычисляется для episodeId по итоговым IncidentAssessment и полноте шагов согласно semantics.md; одинаково у всех cases эпизода, независимо от effect.
   */
  public enum DetectionEnum {
    DETECTED("DETECTED"),
    
    MISSED("MISSED"),
    
    CLEAN("CLEAN"),
    
    FALSE_POSITIVE("FALSE_POSITIVE"),
    
    INCONCLUSIVE("INCONCLUSIVE");

    private final String value;

    DetectionEnum(String value) {
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
    public static DetectionEnum fromValue(String value) {
      for (DetectionEnum b : DetectionEnum.values()) {
        if (b.value.equals(value)) {
          return b;
        }
      }
      throw new IllegalArgumentException("Unexpected value '" + value + "'");
    }
  }

  private DetectionEnum detection;

  public CaseResult() {
    super();
  }

  /**
   * Constructor with only required parameters
   */
  public CaseResult(String caseId, String episodeId, FamilyEnum family, LabelEnum label, EffectEnum effect, Set<UUID> requestIds, String reason, DetectionEnum detection) {
    this.caseId = caseId;
    this.episodeId = episodeId;
    this.family = family;
    this.label = label;
    this.effect = effect;
    this.requestIds = requestIds;
    this.reason = reason;
    this.detection = detection;
  }

  public CaseResult caseId(String caseId) {
    this.caseId = caseId;
    return this;
  }

  /**
   * Get caseId
   * @return caseId
   */
  @NotNull @Size(min = 1, max = 128) 
  @JsonProperty("caseId")
  public String getCaseId() {
    return caseId;
  }

  @JsonProperty("caseId")
  public void setCaseId(String caseId) {
    this.caseId = caseId;
  }

  public CaseResult episodeId(String episodeId) {
    this.episodeId = episodeId;
    return this;
  }

  /**
   * Общая группа проверок одной кампании для recall/FPR.
   * @return episodeId
   */
  @NotNull @Size(min = 1, max = 128) 
  @JsonProperty("episodeId")
  public String getEpisodeId() {
    return episodeId;
  }

  @JsonProperty("episodeId")
  public void setEpisodeId(String episodeId) {
    this.episodeId = episodeId;
  }

  public CaseResult family(FamilyEnum family) {
    this.family = family;
    return this;
  }

  /**
   * Get family
   * @return family
   */
  @NotNull 
  @JsonProperty("family")
  public FamilyEnum getFamily() {
    return family;
  }

  @JsonProperty("family")
  public void setFamily(FamilyEnum family) {
    this.family = family;
  }

  public CaseResult label(LabelEnum label) {
    this.label = label;
    return this;
  }

  /**
   * Get label
   * @return label
   */
  @NotNull 
  @JsonProperty("label")
  public LabelEnum getLabel() {
    return label;
  }

  @JsonProperty("label")
  public void setLabel(LabelEnum label) {
    this.label = label;
  }

  public CaseResult effect(EffectEnum effect) {
    this.effect = effect;
    return this;
  }

  /**
   * Get effect
   * @return effect
   */
  @NotNull 
  @JsonProperty("effect")
  public EffectEnum getEffect() {
    return effect;
  }

  @JsonProperty("effect")
  public void setEffect(EffectEnum effect) {
    this.effect = effect;
  }

  public CaseResult requestIds(Set<UUID> requestIds) {
    this.requestIds = requestIds;
    return this;
  }

  public CaseResult addRequestIdsItem(UUID requestIdsItem) {
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
  @NotNull @Size(min = 0, max = 10000) 
  @JsonProperty("requestIds")
  public Set<UUID> getRequestIds() {
    return requestIds;
  }

  @JsonDeserialize(as = LinkedHashSet.class)
  @JsonProperty("requestIds")
  public void setRequestIds(Set<UUID> requestIds) {
    this.requestIds = requestIds;
  }

  public CaseResult reason(String reason) {
    this.reason = reason;
    return this;
  }

  /**
   * Get reason
   * @return reason
   */
  @NotNull 
  @JsonProperty("reason")
  public String getReason() {
    return reason;
  }

  @JsonProperty("reason")
  public void setReason(String reason) {
    this.reason = reason;
  }

  public CaseResult detection(DetectionEnum detection) {
    this.detection = detection;
    return this;
  }

  /**
   * Вычисляется для episodeId по итоговым IncidentAssessment и полноте шагов согласно semantics.md; одинаково у всех cases эпизода, независимо от effect.
   * @return detection
   */
  @NotNull 
  @JsonProperty("detection")
  public DetectionEnum getDetection() {
    return detection;
  }

  @JsonProperty("detection")
  public void setDetection(DetectionEnum detection) {
    this.detection = detection;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    CaseResult caseResult = (CaseResult) o;
    return Objects.equals(this.caseId, caseResult.caseId) &&
        Objects.equals(this.episodeId, caseResult.episodeId) &&
        Objects.equals(this.family, caseResult.family) &&
        Objects.equals(this.label, caseResult.label) &&
        Objects.equals(this.effect, caseResult.effect) &&
        Objects.equals(this.requestIds, caseResult.requestIds) &&
        Objects.equals(this.reason, caseResult.reason) &&
        Objects.equals(this.detection, caseResult.detection);
  }

  @Override
  public int hashCode() {
    return Objects.hash(caseId, episodeId, family, label, effect, requestIds, reason, detection);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class CaseResult {\n");
    sb.append("    caseId: ").append(toIndentedString(caseId)).append("\n");
    sb.append("    episodeId: ").append(toIndentedString(episodeId)).append("\n");
    sb.append("    family: ").append(toIndentedString(family)).append("\n");
    sb.append("    label: ").append(toIndentedString(label)).append("\n");
    sb.append("    effect: ").append(toIndentedString(effect)).append("\n");
    sb.append("    requestIds: ").append(toIndentedString(requestIds)).append("\n");
    sb.append("    reason: ").append(toIndentedString(reason)).append("\n");
    sb.append("    detection: ").append(toIndentedString(detection)).append("\n");
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

