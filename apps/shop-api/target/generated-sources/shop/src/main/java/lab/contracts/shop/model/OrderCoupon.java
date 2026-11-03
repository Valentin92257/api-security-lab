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
 * OrderCoupon
 */

@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", comments = "Generator version: 7.26.0")
public class OrderCoupon {

  private String code;

  private Integer discountPercent;

  public OrderCoupon() {
    super();
  }

  /**
   * Constructor with only required parameters
   */
  public OrderCoupon(String code, Integer discountPercent) {
    this.code = code;
    this.discountPercent = discountPercent;
  }

  public OrderCoupon code(String code) {
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

  public OrderCoupon discountPercent(Integer discountPercent) {
    this.discountPercent = discountPercent;
    return this;
  }

  /**
   * Get discountPercent
   * minimum: 1
   * maximum: 100
   * @return discountPercent
   */
  @NotNull
  @JsonProperty("discountPercent")
  public Integer getDiscountPercent() {
    return discountPercent;
  }

  @JsonProperty("discountPercent")
  public void setDiscountPercent(Integer discountPercent) {
    this.discountPercent = discountPercent;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    OrderCoupon orderCoupon = (OrderCoupon) o;
    return Objects.equals(this.code, orderCoupon.code) &&
        Objects.equals(this.discountPercent, orderCoupon.discountPercent);
  }

  @Override
  public int hashCode() {
    return Objects.hash(code, discountPercent);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class OrderCoupon {\n");
    sb.append("    code: ").append(toIndentedString(code)).append("\n");
    sb.append("    discountPercent: ").append(toIndentedString(discountPercent)).append("\n");
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

