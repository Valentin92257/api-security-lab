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
 * LineItem
 */

@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", comments = "Generator version: 7.26.0")
public class LineItem {

  private Long productId;

  private String name;

  private Integer quantity;

  private Integer unitPrice;

  private Long lineTotal;

  public LineItem() {
    super();
  }

  /**
   * Constructor with only required parameters
   */
  public LineItem(Long productId, String name, Integer quantity, Integer unitPrice, Long lineTotal) {
    this.productId = productId;
    this.name = name;
    this.quantity = quantity;
    this.unitPrice = unitPrice;
    this.lineTotal = lineTotal;
  }

  public LineItem productId(Long productId) {
    this.productId = productId;
    return this;
  }

  /**
   * Get productId
   * minimum: 1
   * maximum: 9007199254740991
   * @return productId
   */
  @NotNull
  @JsonProperty("productId")
  public Long getProductId() {
    return productId;
  }

  @JsonProperty("productId")
  public void setProductId(Long productId) {
    this.productId = productId;
  }

  public LineItem name(String name) {
    this.name = name;
    return this;
  }

  /**
   * Get name
   * @return name
   */
  @NotNull
  @JsonProperty("name")
  public String getName() {
    return name;
  }

  @JsonProperty("name")
  public void setName(String name) {
    this.name = name;
  }

  public LineItem quantity(Integer quantity) {
    this.quantity = quantity;
    return this;
  }

  /**
   * Get quantity
   * minimum: 1
   * maximum: 99
   * @return quantity
   */
  @NotNull
  @JsonProperty("quantity")
  public Integer getQuantity() {
    return quantity;
  }

  @JsonProperty("quantity")
  public void setQuantity(Integer quantity) {
    this.quantity = quantity;
  }

  public LineItem unitPrice(Integer unitPrice) {
    this.unitPrice = unitPrice;
    return this;
  }

  /**
   * Get unitPrice
   * minimum: 1
   * maximum: 1000000000
   * @return unitPrice
   */
  @NotNull
  @JsonProperty("unitPrice")
  public Integer getUnitPrice() {
    return unitPrice;
  }

  @JsonProperty("unitPrice")
  public void setUnitPrice(Integer unitPrice) {
    this.unitPrice = unitPrice;
  }

  public LineItem lineTotal(Long lineTotal) {
    this.lineTotal = lineTotal;
    return this;
  }

  /**
   * Get lineTotal
   * minimum: 0
   * maximum: 9900000000000
   * @return lineTotal
   */
  @NotNull
  @JsonProperty("lineTotal")
  public Long getLineTotal() {
    return lineTotal;
  }

  @JsonProperty("lineTotal")
  public void setLineTotal(Long lineTotal) {
    this.lineTotal = lineTotal;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    LineItem lineItem = (LineItem) o;
    return Objects.equals(this.productId, lineItem.productId) &&
        Objects.equals(this.name, lineItem.name) &&
        Objects.equals(this.quantity, lineItem.quantity) &&
        Objects.equals(this.unitPrice, lineItem.unitPrice) &&
        Objects.equals(this.lineTotal, lineItem.lineTotal);
  }

  @Override
  public int hashCode() {
    return Objects.hash(productId, name, quantity, unitPrice, lineTotal);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class LineItem {\n");
    sb.append("    productId: ").append(toIndentedString(productId)).append("\n");
    sb.append("    name: ").append(toIndentedString(name)).append("\n");
    sb.append("    quantity: ").append(toIndentedString(quantity)).append("\n");
    sb.append("    unitPrice: ").append(toIndentedString(unitPrice)).append("\n");
    sb.append("    lineTotal: ").append(toIndentedString(lineTotal)).append("\n");
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

