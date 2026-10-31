package lab.contracts.shop.model;

import java.net.URI;
import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import org.springframework.lang.Nullable;
import java.time.OffsetDateTime;
import jakarta.validation.constraints.NotNull;


import java.util.*;
import jakarta.annotation.Generated;

/**
 * CartCoupon
 */

@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", comments = "Generator version: 7.26.0")
public class CartCoupon {

  private String code;

  private Integer discountPercent;

  /**
   * Gets or Sets eligibility
   */
  public enum EligibilityEnum {
    VALID("VALID"),
    
    EXPIRED("EXPIRED"),
    
    EXHAUSTED("EXHAUSTED");

    private final String value;

    EligibilityEnum(String value) {
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
    public static EligibilityEnum fromValue(String value) {
      for (EligibilityEnum b : EligibilityEnum.values()) {
        if (b.value.equals(value)) {
          return b;
        }
      }
      throw new IllegalArgumentException("Unexpected value '" + value + "'");
    }
  }

  private EligibilityEnum eligibility;

  public CartCoupon() {
    super();
  }

  /**
   * Constructor with only required parameters
   */
  public CartCoupon(String code, Integer discountPercent, EligibilityEnum eligibility) {
    this.code = code;
    this.discountPercent = discountPercent;
    this.eligibility = eligibility;
  }

  public CartCoupon code(String code) {
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

  public CartCoupon discountPercent(Integer discountPercent) {
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

  public CartCoupon eligibility(EligibilityEnum eligibility) {
    this.eligibility = eligibility;
    return this;
  }

  /**
   * Get eligibility
   * @return eligibility
   */
  @NotNull
  @JsonProperty("eligibility")
  public EligibilityEnum getEligibility() {
    return eligibility;
  }

  @JsonProperty("eligibility")
  public void setEligibility(EligibilityEnum eligibility) {
    this.eligibility = eligibility;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    CartCoupon cartCoupon = (CartCoupon) o;
    return Objects.equals(this.code, cartCoupon.code) &&
        Objects.equals(this.discountPercent, cartCoupon.discountPercent) &&
        Objects.equals(this.eligibility, cartCoupon.eligibility);
  }

  @Override
  public int hashCode() {
    return Objects.hash(code, discountPercent, eligibility);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class CartCoupon {\n");
    sb.append("    code: ").append(toIndentedString(code)).append("\n");
    sb.append("    discountPercent: ").append(toIndentedString(discountPercent)).append("\n");
    sb.append("    eligibility: ").append(toIndentedString(eligibility)).append("\n");
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

