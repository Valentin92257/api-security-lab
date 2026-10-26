package lab.contracts.shop.model;

import java.net.URI;
import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonCreator;
import org.springframework.lang.Nullable;
import java.time.OffsetDateTime;
import jakarta.validation.constraints.NotNull;


import java.util.*;
import jakarta.annotation.Generated;

/**
 * SelectCouponRequest
 */

@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", comments = "Generator version: 7.26.0")
public class SelectCouponRequest {

  private String code;

  public SelectCouponRequest() {
    super();
  }

  /**
   * Constructor with only required parameters
   */
  public SelectCouponRequest(String code) {
    this.code = code;
  }

  public SelectCouponRequest code(String code) {
    this.code = code;
    return this;
  }

  /**
   * После trim/uppercase: [A-Z0-9_-]{1,32}.
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

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    SelectCouponRequest selectCouponRequest = (SelectCouponRequest) o;
    return Objects.equals(this.code, selectCouponRequest.code);
  }

  @Override
  public int hashCode() {
    return Objects.hash(code);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class SelectCouponRequest {\n");
    sb.append("    code: ").append(toIndentedString(code)).append("\n");
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

