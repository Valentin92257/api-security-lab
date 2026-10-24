package lab.contracts.shop.model;

import java.net.URI;
import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonCreator;
import java.net.URI;
import java.util.UUID;
import org.springframework.lang.Nullable;
import java.time.OffsetDateTime;
import jakarta.validation.constraints.NotNull;


import java.util.*;
import jakarta.annotation.Generated;

/**
 * Problem
 */

@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", comments = "Generator version: 7.26.0")
public class Problem {

  private URI type;

  private String title;

  private Integer status;

  private String code;

  private UUID requestId;

  public Problem() {
    super();
  }

  /**
   * Constructor with only required parameters
   */
  public Problem(URI type, String title, Integer status, String code, UUID requestId) {
    this.type = type;
    this.title = title;
    this.status = status;
    this.code = code;
    this.requestId = requestId;
  }

  public Problem type(URI type) {
    this.type = type;
    return this;
  }

  /**
   * Get type
   * @return type
   */
  @NotNull
  @JsonProperty("type")
  public URI getType() {
    return type;
  }

  @JsonProperty("type")
  public void setType(URI type) {
    this.type = type;
  }

  public Problem title(String title) {
    this.title = title;
    return this;
  }

  /**
   * Get title
   * @return title
   */
  @NotNull
  @JsonProperty("title")
  public String getTitle() {
    return title;
  }

  @JsonProperty("title")
  public void setTitle(String title) {
    this.title = title;
  }

  public Problem status(Integer status) {
    this.status = status;
    return this;
  }

  /**
   * Get status
   * minimum: 400
   * maximum: 599
   * @return status
   */
  @NotNull
  @JsonProperty("status")
  public Integer getStatus() {
    return status;
  }

  @JsonProperty("status")
  public void setStatus(Integer status) {
    this.status = status;
  }

  public Problem code(String code) {
    this.code = code;
    return this;
  }

  /**
   * Get code
   * @return code
   */
  @NotNull
  @JsonProperty("code")
  public String getCode() {
    return code;
  }

  @JsonProperty("code")
  public void setCode(String code) {
    this.code = code;
  }

  public Problem requestId(UUID requestId) {
    this.requestId = requestId;
    return this;
  }

  /**
   * Get requestId
   * @return requestId
   */
  @NotNull
  @JsonProperty("requestId")
  public UUID getRequestId() {
    return requestId;
  }

  @JsonProperty("requestId")
  public void setRequestId(UUID requestId) {
    this.requestId = requestId;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    Problem problem = (Problem) o;
    return Objects.equals(this.type, problem.type) &&
        Objects.equals(this.title, problem.title) &&
        Objects.equals(this.status, problem.status) &&
        Objects.equals(this.code, problem.code) &&
        Objects.equals(this.requestId, problem.requestId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(type, title, status, code, requestId);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class Problem {\n");
    sb.append("    type: ").append(toIndentedString(type)).append("\n");
    sb.append("    title: ").append(toIndentedString(title)).append("\n");
    sb.append("    status: ").append(toIndentedString(status)).append("\n");
    sb.append("    code: ").append(toIndentedString(code)).append("\n");
    sb.append("    requestId: ").append(toIndentedString(requestId)).append("\n");
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

