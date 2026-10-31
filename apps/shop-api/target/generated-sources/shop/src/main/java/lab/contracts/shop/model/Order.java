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
import lab.contracts.shop.model.LineItem;
import lab.contracts.shop.model.OrderCoupon;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.lang.Nullable;
import java.time.OffsetDateTime;
import jakarta.validation.constraints.NotNull;


import java.util.*;
import jakarta.annotation.Generated;

/**
 * Order
 */

@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", comments = "Generator version: 7.26.0")
public class Order {

  private Long id;

  private Long userId;

  private Long cartId;

  private Long cartRevision;

  private List<LineItem> items = new ArrayList<>();

  private OrderCoupon coupon = null;

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
  private OffsetDateTime createdAt;

  public Order() {
    super();
  }

  /**
   * Constructor with only required parameters
   */
  public Order(Long id, Long userId, Long cartId, Long cartRevision, List<LineItem> items, OrderCoupon coupon, Long subtotal, Long discount, Long total, CurrencyEnum currency, OffsetDateTime createdAt) {
    this.id = id;
    this.userId = userId;
    this.cartId = cartId;
    this.cartRevision = cartRevision;
    this.items = items;
    this.coupon = coupon;
    this.subtotal = subtotal;
    this.discount = discount;
    this.total = total;
    this.currency = currency;
    this.createdAt = createdAt;
  }

  public Order id(Long id) {
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

  public Order userId(Long userId) {
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

  public Order cartId(Long cartId) {
    this.cartId = cartId;
    return this;
  }

  /**
   * Get cartId
   * minimum: 1
   * maximum: 9007199254740991
   * @return cartId
   */
  @NotNull
  @JsonProperty("cartId")
  public Long getCartId() {
    return cartId;
  }

  @JsonProperty("cartId")
  public void setCartId(Long cartId) {
    this.cartId = cartId;
  }

  public Order cartRevision(Long cartRevision) {
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

  public Order items(List<LineItem> items) {
    this.items = items;
    return this;
  }

  public Order addItemsItem(LineItem itemsItem) {
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

  public Order coupon(OrderCoupon coupon) {
    this.coupon = coupon;
    return this;
  }

  /**
   * Get coupon
   * @return coupon
   */
  
  @JsonProperty("coupon")
  public OrderCoupon getCoupon() {
    return coupon;
  }

  @JsonProperty("coupon")
  public void setCoupon(OrderCoupon coupon) {
    this.coupon = coupon;
  }

  public Order subtotal(Long subtotal) {
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

  public Order discount(Long discount) {
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

  public Order total(Long total) {
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

  public Order currency(CurrencyEnum currency) {
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

  public Order createdAt(OffsetDateTime createdAt) {
    this.createdAt = createdAt;
    return this;
  }

  /**
   * Get createdAt
   * @return createdAt
   */
  @NotNull
  @JsonProperty("createdAt")
  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }

  @JsonProperty("createdAt")
  public void setCreatedAt(OffsetDateTime createdAt) {
    this.createdAt = createdAt;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    Order order = (Order) o;
    return Objects.equals(this.id, order.id) &&
        Objects.equals(this.userId, order.userId) &&
        Objects.equals(this.cartId, order.cartId) &&
        Objects.equals(this.cartRevision, order.cartRevision) &&
        Objects.equals(this.items, order.items) &&
        Objects.equals(this.coupon, order.coupon) &&
        Objects.equals(this.subtotal, order.subtotal) &&
        Objects.equals(this.discount, order.discount) &&
        Objects.equals(this.total, order.total) &&
        Objects.equals(this.currency, order.currency) &&
        Objects.equals(this.createdAt, order.createdAt);
  }

  @Override
  public int hashCode() {
    return Objects.hash(id, userId, cartId, cartRevision, items, coupon, subtotal, discount, total, currency, createdAt);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class Order {\n");
    sb.append("    id: ").append(toIndentedString(id)).append("\n");
    sb.append("    userId: ").append(toIndentedString(userId)).append("\n");
    sb.append("    cartId: ").append(toIndentedString(cartId)).append("\n");
    sb.append("    cartRevision: ").append(toIndentedString(cartRevision)).append("\n");
    sb.append("    items: ").append(toIndentedString(items)).append("\n");
    sb.append("    coupon: ").append(toIndentedString(coupon)).append("\n");
    sb.append("    subtotal: ").append(toIndentedString(subtotal)).append("\n");
    sb.append("    discount: ").append(toIndentedString(discount)).append("\n");
    sb.append("    total: ").append(toIndentedString(total)).append("\n");
    sb.append("    currency: ").append(toIndentedString(currency)).append("\n");
    sb.append("    createdAt: ").append(toIndentedString(createdAt)).append("\n");
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

