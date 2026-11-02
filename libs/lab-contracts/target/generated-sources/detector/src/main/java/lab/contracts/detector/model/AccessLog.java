package lab.contracts.detector.model;

import java.net.URI;
import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonCreator;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.lang.Nullable;
import java.time.OffsetDateTime;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;


import java.util.*;
import jakarta.annotation.Generated;

/**
 * AccessLog
 */

@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", comments = "Generator version: 7.26.0")
public class AccessLog {

  private Integer sequence;

  private UUID requestId;

  @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
  private OffsetDateTime time;

  private String remoteAddr;

  private Long user = null;

  private String method;

  private String path;

  private String route = null;

  private Integer status;

  private Long bytes;

  private Double requestTime;

  private String userAgent = null;

  private String reqHash = null;

  public AccessLog() {
    super();
  }

  /**
   * Constructor with only required parameters
   */
  public AccessLog(Integer sequence, UUID requestId, OffsetDateTime time, String remoteAddr, Long user, String method, String path, String route, Integer status, Long bytes, Double requestTime, String userAgent, String reqHash) {
    this.sequence = sequence;
    this.requestId = requestId;
    this.time = time;
    this.remoteAddr = remoteAddr;
    this.user = user;
    this.method = method;
    this.path = path;
    this.route = route;
    this.status = status;
    this.bytes = bytes;
    this.requestTime = requestTime;
    this.userAgent = userAgent;
    this.reqHash = reqHash;
  }

  public AccessLog sequence(Integer sequence) {
    this.sequence = sequence;
    return this;
  }

  /**
   * Get sequence
   * minimum: 1
   * maximum: 100000
   * @return sequence
   */
  @NotNull @Min(value = 1) @Max(value = 100000) 
  @JsonProperty("sequence")
  public Integer getSequence() {
    return sequence;
  }

  @JsonProperty("sequence")
  public void setSequence(Integer sequence) {
    this.sequence = sequence;
  }

  public AccessLog requestId(UUID requestId) {
    this.requestId = requestId;
    return this;
  }

  /**
   * Get requestId
   * @return requestId
   */
  @NotNull @Valid 
  @JsonProperty("requestId")
  public UUID getRequestId() {
    return requestId;
  }

  @JsonProperty("requestId")
  public void setRequestId(UUID requestId) {
    this.requestId = requestId;
  }

  public AccessLog time(OffsetDateTime time) {
    this.time = time;
    return this;
  }

  /**
   * Get time
   * @return time
   */
  @NotNull @Valid 
  @JsonProperty("time")
  public OffsetDateTime getTime() {
    return time;
  }

  @JsonProperty("time")
  public void setTime(OffsetDateTime time) {
    this.time = time;
  }

  public AccessLog remoteAddr(String remoteAddr) {
    this.remoteAddr = remoteAddr;
    return this;
  }

  /**
   * Get remoteAddr
   * @return remoteAddr
   */
  @NotNull @Size(min = 2, max = 45) 
  @JsonProperty("remote_addr")
  public String getRemoteAddr() {
    return remoteAddr;
  }

  @JsonProperty("remote_addr")
  public void setRemoteAddr(String remoteAddr) {
    this.remoteAddr = remoteAddr;
  }

  public AccessLog user(Long user) {
    this.user = user;
    return this;
  }

  /**
   * Get user
   * minimum: 1
   * maximum: 9007199254740991
   * @return user
   */
  @Min(value = 1L) @Max(value = 9007199254740991L) 
  @JsonProperty("user")
  public Long getUser() {
    return user;
  }

  @JsonProperty("user")
  public void setUser(Long user) {
    this.user = user;
  }

  public AccessLog method(String method) {
    this.method = method;
    return this;
  }

  /**
   * Get method
   * @return method
   */
  @NotNull @Pattern(regexp = "^[A-Z][A-Z0-9!#$%&'*+.^_`|~-]*$") @Size(min = 1, max = 32) 
  @JsonProperty("method")
  public String getMethod() {
    return method;
  }

  @JsonProperty("method")
  public void setMethod(String method) {
    this.method = method;
  }

  public AccessLog path(String path) {
    this.path = path;
    return this;
  }

  /**
   * Get path
   * @return path
   */
  @NotNull @Pattern(regexp = "^/.*$") @Size(min = 1, max = 8192) 
  @JsonProperty("path")
  public String getPath() {
    return path;
  }

  @JsonProperty("path")
  public void setPath(String path) {
    this.path = path;
  }

  public AccessLog route(String route) {
    this.route = route;
    return this;
  }

  /**
   * Get route
   * @return route
   */
  @Pattern(regexp = "^/.*$") @Size(min = 1, max = 200) 
  @JsonProperty("route")
  public String getRoute() {
    return route;
  }

  @JsonProperty("route")
  public void setRoute(String route) {
    this.route = route;
  }

  public AccessLog status(Integer status) {
    this.status = status;
    return this;
  }

  /**
   * Get status
   * minimum: 100
   * maximum: 599
   * @return status
   */
  @NotNull @Min(value = 100) @Max(value = 599) 
  @JsonProperty("status")
  public Integer getStatus() {
    return status;
  }

  @JsonProperty("status")
  public void setStatus(Integer status) {
    this.status = status;
  }

  public AccessLog bytes(Long bytes) {
    this.bytes = bytes;
    return this;
  }

  /**
   * Get bytes
   * minimum: 0
   * @return bytes
   */
  @NotNull @Min(value = 0L) 
  @JsonProperty("bytes")
  public Long getBytes() {
    return bytes;
  }

  @JsonProperty("bytes")
  public void setBytes(Long bytes) {
    this.bytes = bytes;
  }

  public AccessLog requestTime(Double requestTime) {
    this.requestTime = requestTime;
    return this;
  }

  /**
   * Get requestTime
   * minimum: 0
   * @return requestTime
   */
  @NotNull @DecimalMin(value = "0") 
  @JsonProperty("request_time")
  public Double getRequestTime() {
    return requestTime;
  }

  @JsonProperty("request_time")
  public void setRequestTime(Double requestTime) {
    this.requestTime = requestTime;
  }

  public AccessLog userAgent(String userAgent) {
    this.userAgent = userAgent;
    return this;
  }

  /**
   * Get userAgent
   * @return userAgent
   */
  @Size(max = 1024) 
  @JsonProperty("user_agent")
  public String getUserAgent() {
    return userAgent;
  }

  @JsonProperty("user_agent")
  public void setUserAgent(String userAgent) {
    this.userAgent = userAgent;
  }

  public AccessLog reqHash(String reqHash) {
    this.reqHash = reqHash;
    return this;
  }

  /**
   * Get reqHash
   * @return reqHash
   */
  @Pattern(regexp = "^[0-9a-f]{64}$") @Size(min = 64, max = 64) 
  @JsonProperty("req_hash")
  public String getReqHash() {
    return reqHash;
  }

  @JsonProperty("req_hash")
  public void setReqHash(String reqHash) {
    this.reqHash = reqHash;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    AccessLog accessLog = (AccessLog) o;
    return Objects.equals(this.sequence, accessLog.sequence) &&
        Objects.equals(this.requestId, accessLog.requestId) &&
        Objects.equals(this.time, accessLog.time) &&
        Objects.equals(this.remoteAddr, accessLog.remoteAddr) &&
        Objects.equals(this.user, accessLog.user) &&
        Objects.equals(this.method, accessLog.method) &&
        Objects.equals(this.path, accessLog.path) &&
        Objects.equals(this.route, accessLog.route) &&
        Objects.equals(this.status, accessLog.status) &&
        Objects.equals(this.bytes, accessLog.bytes) &&
        Objects.equals(this.requestTime, accessLog.requestTime) &&
        Objects.equals(this.userAgent, accessLog.userAgent) &&
        Objects.equals(this.reqHash, accessLog.reqHash);
  }

  @Override
  public int hashCode() {
    return Objects.hash(sequence, requestId, time, remoteAddr, user, method, path, route, status, bytes, requestTime, userAgent, reqHash);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class AccessLog {\n");
    sb.append("    sequence: ").append(toIndentedString(sequence)).append("\n");
    sb.append("    requestId: ").append(toIndentedString(requestId)).append("\n");
    sb.append("    time: ").append(toIndentedString(time)).append("\n");
    sb.append("    remoteAddr: ").append(toIndentedString(remoteAddr)).append("\n");
    sb.append("    user: ").append(toIndentedString(user)).append("\n");
    sb.append("    method: ").append(toIndentedString(method)).append("\n");
    sb.append("    path: ").append(toIndentedString(path)).append("\n");
    sb.append("    route: ").append(toIndentedString(route)).append("\n");
    sb.append("    status: ").append(toIndentedString(status)).append("\n");
    sb.append("    bytes: ").append(toIndentedString(bytes)).append("\n");
    sb.append("    requestTime: ").append(toIndentedString(requestTime)).append("\n");
    sb.append("    userAgent: ").append(toIndentedString(userAgent)).append("\n");
    sb.append("    reqHash: ").append(toIndentedString(reqHash)).append("\n");
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

