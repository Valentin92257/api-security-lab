package lab.contracts.shop.model;

import java.net.URI;
import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonCreator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import lab.contracts.shop.model.Product;
import org.springframework.lang.Nullable;
import java.time.OffsetDateTime;
import jakarta.validation.constraints.NotNull;


import java.util.*;
import jakarta.annotation.Generated;

/**
 * ProductPage
 */

@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", comments = "Generator version: 7.26.0")
public class ProductPage {

  private List<Product> items = new ArrayList<>();

  private Long total;

  public ProductPage() {
    super();
  }

  /**
   * Constructor with only required parameters
   */
  public ProductPage(List<Product> items, Long total) {
    this.items = items;
    this.total = total;
  }

  public ProductPage items(List<Product> items) {
    this.items = items;
    return this;
  }

  public ProductPage addItemsItem(Product itemsItem) {
    if (this.items == null) {
      this.items = new ArrayList<>();
    }
    this.items.add(itemsItem);
    return this;
  }

  /**
   * Get items
   * @return items
   */
  @NotNull
  @JsonProperty("items")
  public List<Product> getItems() {
    return items;
  }

  @JsonProperty("items")
  public void setItems(List<Product> items) {
    this.items = items;
  }

  public ProductPage total(Long total) {
    this.total = total;
    return this;
  }

  /**
   * Get total
   * minimum: 0
   * maximum: 9007199254740991
   * @return total
   */
  @NotNull
  @JsonProperty("total")
  public Long getTotal() {
    return total;
  }

  @JsonProperty("total")
  public void setTotal(Long total) {
    this.total = total;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    ProductPage productPage = (ProductPage) o;
    return Objects.equals(this.items, productPage.items) &&
        Objects.equals(this.total, productPage.total);
  }

  @Override
  public int hashCode() {
    return Objects.hash(items, total);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class ProductPage {\n");
    sb.append("    items: ").append(toIndentedString(items)).append("\n");
    sb.append("    total: ").append(toIndentedString(total)).append("\n");
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

