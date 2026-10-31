package lab.contracts.runtime.model;

import java.net.URI;
import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.time.OffsetDateTime;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.lang.Nullable;
import java.time.OffsetDateTime;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;


import java.util.*;
import jakarta.annotation.Generated;

/**
 * RunSetup
 */

@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", comments = "Generator version: 7.26.0")
public class RunSetup {

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

  @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
  private OffsetDateTime businessTimeBase;

  @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
  private OffsetDateTime deadlineAt;

  public RunSetup() {
    super();
  }

  /**
   * Constructor with only required parameters
   */
  public RunSetup(ModeEnum mode, OffsetDateTime businessTimeBase, OffsetDateTime deadlineAt) {
    this.mode = mode;
    this.businessTimeBase = businessTimeBase;
    this.deadlineAt = deadlineAt;
  }

  public RunSetup mode(ModeEnum mode) {
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

  public RunSetup businessTimeBase(OffsetDateTime businessTimeBase) {
    this.businessTimeBase = businessTimeBase;
    return this;
  }

  /**
   * Get businessTimeBase
   * @return businessTimeBase
   */
  @NotNull @Valid 
  @JsonProperty("businessTimeBase")
  public OffsetDateTime getBusinessTimeBase() {
    return businessTimeBase;
  }

  @JsonProperty("businessTimeBase")
  public void setBusinessTimeBase(OffsetDateTime businessTimeBase) {
    this.businessTimeBase = businessTimeBase;
  }

  public RunSetup deadlineAt(OffsetDateTime deadlineAt) {
    this.deadlineAt = deadlineAt;
    return this;
  }

  /**
   * Get deadlineAt
   * @return deadlineAt
   */
  @NotNull @Valid 
  @JsonProperty("deadlineAt")
  public OffsetDateTime getDeadlineAt() {
    return deadlineAt;
  }

  @JsonProperty("deadlineAt")
  public void setDeadlineAt(OffsetDateTime deadlineAt) {
    this.deadlineAt = deadlineAt;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    RunSetup runSetup = (RunSetup) o;
    return Objects.equals(this.mode, runSetup.mode) &&
        Objects.equals(this.businessTimeBase, runSetup.businessTimeBase) &&
        Objects.equals(this.deadlineAt, runSetup.deadlineAt);
  }

  @Override
  public int hashCode() {
    return Objects.hash(mode, businessTimeBase, deadlineAt);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class RunSetup {\n");
    sb.append("    mode: ").append(toIndentedString(mode)).append("\n");
    sb.append("    businessTimeBase: ").append(toIndentedString(businessTimeBase)).append("\n");
    sb.append("    deadlineAt: ").append(toIndentedString(deadlineAt)).append("\n");
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

