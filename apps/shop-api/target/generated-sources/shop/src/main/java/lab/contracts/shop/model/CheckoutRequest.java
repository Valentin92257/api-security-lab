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
 * CheckoutRequest
 */

@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", comments = "Generator version: 7.26.0")
public class CheckoutRequest {

  private Long cartRevision;

  private Long quotedTotal;

  public CheckoutRequest() {
    super();
  }

  /**
   * Constructor with only required parameters
   */
  public CheckoutRequest(Long cartRevision, Long quotedTotal) {
    this.cartRevision = cartRevision;
    this.quotedTotal = quotedTotal;
  }

  public CheckoutRequest cartRevision(Long cartRevision) {
    this.cartRevision = cartRevision;
    return this;
  }

  /**
   * Get cartRevision
   * minimum: 0
   * maximum: 9007199254740991
   * @return cartRevision
   */
  @NotNull
  @JsonProperty("cartRevision")
  public Long getCartRevision() {
    return cartRevision;
  }

  @JsonProperty("cartRevision")
  public void setCartRevision(Long cartRevision) {
    this.cartRevision = cartRevision;
  }

  public CheckoutRequest quotedTotal(Long quotedTotal) {
    this.quotedTotal = quotedTotal;
    return this;
  }

  /**
   * Get quotedTotal
   * minimum: 0
   * maximum: 9900000000000
   * @return quotedTotal
   */
  @NotNull
  @JsonProperty("quotedTotal")
  public Long getQuotedTotal() {
    return quotedTotal;
  }

  @JsonProperty("quotedTotal")
  public void setQuotedTotal(Long quotedTotal) {
    this.quotedTotal = quotedTotal;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    CheckoutRequest checkoutRequest = (CheckoutRequest) o;
    return Objects.equals(this.cartRevision, checkoutRequest.cartRevision) &&
        Objects.equals(this.quotedTotal, checkoutRequest.quotedTotal);
  }

  @Override
  public int hashCode() {
    return Objects.hash(cartRevision, quotedTotal);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class CheckoutRequest {\n");
    sb.append("    cartRevision: ").append(toIndentedString(cartRevision)).append("\n");
    sb.append("    quotedTotal: ").append(toIndentedString(quotedTotal)).append("\n");
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

