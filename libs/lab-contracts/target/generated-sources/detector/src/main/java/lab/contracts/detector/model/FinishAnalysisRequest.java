package lab.contracts.detector.model;

import java.net.URI;
import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonCreator;
import java.time.OffsetDateTime;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.lang.Nullable;
import java.time.OffsetDateTime;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;


import java.util.*;
import jakarta.annotation.Generated;

/**
 * FinishAnalysisRequest
 */

@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", comments = "Generator version: 7.26.0")
public class FinishAnalysisRequest {

  private Integer gatewayLastSequence;

  private Integer shopLastSequence;

  @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
  private OffsetDateTime deadlineAt;

  public FinishAnalysisRequest() {
    super();
  }

  /**
   * Constructor with only required parameters
   */
  public FinishAnalysisRequest(Integer gatewayLastSequence, Integer shopLastSequence, OffsetDateTime deadlineAt) {
    this.gatewayLastSequence = gatewayLastSequence;
    this.shopLastSequence = shopLastSequence;
    this.deadlineAt = deadlineAt;
  }

  public FinishAnalysisRequest gatewayLastSequence(Integer gatewayLastSequence) {
    this.gatewayLastSequence = gatewayLastSequence;
    return this;
  }

  /**
   * Get gatewayLastSequence
   * minimum: 0
   * maximum: 100000
   * @return gatewayLastSequence
   */
  @NotNull @Min(value = 0) @Max(value = 100000) 
  @JsonProperty("gatewayLastSequence")
  public Integer getGatewayLastSequence() {
    return gatewayLastSequence;
  }

  @JsonProperty("gatewayLastSequence")
  public void setGatewayLastSequence(Integer gatewayLastSequence) {
    this.gatewayLastSequence = gatewayLastSequence;
  }

  public FinishAnalysisRequest shopLastSequence(Integer shopLastSequence) {
    this.shopLastSequence = shopLastSequence;
    return this;
  }

  /**
   * Get shopLastSequence
   * minimum: 0
   * maximum: 100000
   * @return shopLastSequence
   */
  @NotNull @Min(value = 0) @Max(value = 100000) 
  @JsonProperty("shopLastSequence")
  public Integer getShopLastSequence() {
    return shopLastSequence;
  }

  @JsonProperty("shopLastSequence")
  public void setShopLastSequence(Integer shopLastSequence) {
    this.shopLastSequence = shopLastSequence;
  }

  public FinishAnalysisRequest deadlineAt(OffsetDateTime deadlineAt) {
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
    FinishAnalysisRequest finishAnalysisRequest = (FinishAnalysisRequest) o;
    return Objects.equals(this.gatewayLastSequence, finishAnalysisRequest.gatewayLastSequence) &&
        Objects.equals(this.shopLastSequence, finishAnalysisRequest.shopLastSequence) &&
        Objects.equals(this.deadlineAt, finishAnalysisRequest.deadlineAt);
  }

  @Override
  public int hashCode() {
    return Objects.hash(gatewayLastSequence, shopLastSequence, deadlineAt);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class FinishAnalysisRequest {\n");
    sb.append("    gatewayLastSequence: ").append(toIndentedString(gatewayLastSequence)).append("\n");
    sb.append("    shopLastSequence: ").append(toIndentedString(shopLastSequence)).append("\n");
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

