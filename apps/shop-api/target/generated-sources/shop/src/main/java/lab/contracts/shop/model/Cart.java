package lab.contracts.shop.model;

import java.net.URI;
import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import lab.contracts.shop.model.CartCoupon;
import lab.contracts.shop.model.LineItem;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.lang.Nullable;
import java.time.OffsetDateTime;
import jakarta.validation.constraints.NotNull;


import java.util.*;
import jakarta.annotation.Generated;

/**
 * Cart
 */

@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", comments = "Generator version: 7.26.0")
public class Cart {

  private Long id;

  private Long userId;

  private Long revision;

  private List<LineItem> items = new ArrayList<>();

  private CartCoupon coupon = null;

  private Long subtotal;

  private Long discount;

  private Long total;

  /**
   * Gets or Sets currency
   */
  public enum CurrencyEnum {
    RUB("RUB");

    private final String value;

    CurrencyEnum(String value) {
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
    public static CurrencyEnum fromValue(String value) {
      for (CurrencyEnum b : CurrencyEnum.values()) {
        if (b.value.equals(value)) {
          return b;
        }
      }
      throw new IllegalArgumentException("Unexpected value '" + value + "'");
    }
  }

  private CurrencyEnum currency;

  @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
  private OffsetDateTime updatedAt;

  public Cart() {
    super();
  }

  /**
   * Constructor with only required parameters
   */
  public Cart(Long id, Long userId, Long revision, List<LineItem> items, CartCoupon coupon, Long subtotal, Long discount, Long total, CurrencyEnum currency, OffsetDateTime updatedAt) {
    this.id = id;
    this.userId = userId;
    this.revision = revision;
    this.items = items;
    this.coupon = coupon;
    this.subtotal = subtotal;
    this.discount = discount;
    this.total = total;
    this.currency = currency;
    this.updatedAt = updatedAt;
  }

  public Cart id(Long id) {
    this.id = id;
    return this;
  }

  /**
   * Get id
   * minimum: 1
   * maximum: 9007199254740991
   * @return id
   */
  @NotNull
  @JsonProperty("id")
  public Long getId() {
    return id;
  }

  @JsonProperty("id")
  public void setId(Long id) {
    this.id = id;
  }

  public Cart userId(Long userId) {
    this.userId = userId;
    return this;
  }

  /**
   * Get userId
   * minimum: 1
   * maximum: 9007199254740991
   * @return userId
   */
  @NotNull
  @JsonProperty("userId")
  public Long getUserId() {
    return userId;
  }

  @JsonProperty("userId")
  public void setUserId(Long userId) {
    this.userId = userId;
  }

  public Cart revision(Long revision) {
    this.revision = revision;
    return this;
  }

  /**
   * Get revision
   * minimum: 0
   * maximum: 9007199254740991
   * @return revision
   */
  @NotNull
  @JsonProperty("revision")
  public Long getRevision() {
    return revision;
  }

  @JsonProperty("revision")
  public void setRevision(Long revision) {
    this.revision = revision;
  }

  public Cart items(List<LineItem> items) {
    this.items = items;
    return this;
  }

  public Cart addItemsItem(LineItem itemsItem) {
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
  public List<LineItem> getItems() {
    return items;
  }

  @JsonProperty("items")
  public void setItems(List<LineItem> items) {
    this.items = items;
  }

  public Cart coupon(CartCoupon coupon) {
    this.coupon = coupon;
    return this;
  }

  /**
   * Get coupon
   * @return coupon
   */
  
  @JsonProperty("coupon")
  public CartCoupon getCoupon() {
    return coupon;
  }

  @JsonProperty("coupon")
  public void setCoupon(CartCoupon coupon) {
    this.coupon = coupon;
  }

  public Cart subtotal(Long subtotal) {
    this.subtotal = subtotal;
    return this;
  }

  /**
   * Get subtotal
   * minimum: 0
   * maximum: 9900000000000
   * @return subtotal
   */
  @NotNull
  @JsonProperty("subtotal")
  public Long getSubtotal() {
    return subtotal;
  }

  @JsonProperty("subtotal")
  public void setSubtotal(Long subtotal) {
    this.subtotal = subtotal;
  }

  public Cart discount(Long discount) {
    this.discount = discount;
    return this;
  }

  /**
   * Get discount
   * minimum: 0
   * maximum: 9900000000000
   * @return discount
   */
  @NotNull
  @JsonProperty("discount")
  public Long getDiscount() {
    return discount;
  }

  @JsonProperty("discount")
  public void setDiscount(Long discount) {
    this.discount = discount;
  }

  public Cart total(Long total) {
    this.total = total;
    return this;
  }

  /**
   * Get total
   * minimum: 0
   * maximum: 9900000000000
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

  public Cart currency(CurrencyEnum currency) {
    this.currency = currency;
    return this;
  }

  /**
   * Get currency
   * @return currency
   */
  @NotNull
  @JsonProperty("currency")
  public CurrencyEnum getCurrency() {
    return currency;
  }

  @JsonProperty("currency")
  public void setCurrency(CurrencyEnum currency) {
    this.currency = currency;
  }

  public Cart updatedAt(OffsetDateTime updatedAt) {
    this.updatedAt = updatedAt;
    return this;
  }

  /**
   * Get updatedAt
   * @return updatedAt
   */
  @NotNull
  @JsonProperty("updatedAt")
  public OffsetDateTime getUpdatedAt() {
    return updatedAt;
  }

  @JsonProperty("updatedAt")
  public void setUpdatedAt(OffsetDateTime updatedAt) {
    this.updatedAt = updatedAt;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    Cart cart = (Cart) o;
    return Objects.equals(this.id, cart.id) &&
        Objects.equals(this.userId, cart.userId) &&
        Objects.equals(this.revision, cart.revision) &&
        Objects.equals(this.items, cart.items) &&
        Objects.equals(this.coupon, cart.coupon) &&
        Objects.equals(this.subtotal, cart.subtotal) &&
        Objects.equals(this.discount, cart.discount) &&
        Objects.equals(this.total, cart.total) &&
        Objects.equals(this.currency, cart.currency) &&
        Objects.equals(this.updatedAt, cart.updatedAt);
  }

  @Override
  public int hashCode() {
    return Objects.hash(id, userId, revision, items, coupon, subtotal, discount, total, currency, updatedAt);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class Cart {\n");
    sb.append("    id: ").append(toIndentedString(id)).append("\n");
    sb.append("    userId: ").append(toIndentedString(userId)).append("\n");
    sb.append("    revision: ").append(toIndentedString(revision)).append("\n");
    sb.append("    items: ").append(toIndentedString(items)).append("\n");
    sb.append("    coupon: ").append(toIndentedString(coupon)).append("\n");
    sb.append("    subtotal: ").append(toIndentedString(subtotal)).append("\n");
    sb.append("    discount: ").append(toIndentedString(discount)).append("\n");
    sb.append("    total: ").append(toIndentedString(total)).append("\n");
    sb.append("    currency: ").append(toIndentedString(currency)).append("\n");
    sb.append("    updatedAt: ").append(toIndentedString(updatedAt)).append("\n");
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

