package lab.contracts.detector.model;

import java.net.URI;
import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonCreator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import lab.contracts.detector.model.EventItem;
import org.springframework.lang.Nullable;
import java.time.OffsetDateTime;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;


import java.util.*;
import jakarta.annotation.Generated;

/**
 * EventPage
 */

@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", comments = "Generator version: 7.26.0")
public class EventPage {

  @JsonInclude(JsonInclude.Include.NON_NULL)
  private List<@Valid EventItem> items = new ArrayList<>();

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String nextCursor = null;

  public EventPage() {
    super();
  }

  /**
   * Constructor with only required parameters
   */
  public EventPage(List<@Valid EventItem> items, String nextCursor) {
    this.items = items;
    this.nextCursor = nextCursor;
  }

  public EventPage items(List<@Valid EventItem> items) {
    this.items = items;
    return this;
  }

  public EventPage addItemsItem(EventItem itemsItem) {
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
  @NotNull @Size(min = 0, max = 100) 
  @JsonProperty("items")
  public List<@Valid EventItem> getItems() {
    return items;
  }

  @JsonProperty("items")
  public void setItems(List<@Valid EventItem> items) {
    this.items = items;
  }

  public EventPage nextCursor(String nextCursor) {
    this.nextCursor = nextCursor;
    return this;
  }

  /**
   * Get nextCursor
   * @return nextCursor
   */
  @Pattern(regexp = "^[A-Za-z0-9_-]+$") @Size(min = 1, max = 1024) 
  @JsonProperty("nextCursor")
  public String getNextCursor() {
    return nextCursor;
  }

  @JsonProperty("nextCursor")
  public void setNextCursor(String nextCursor) {
    this.nextCursor = nextCursor;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    EventPage eventPage = (EventPage) o;
    return Objects.equals(this.items, eventPage.items) &&
        Objects.equals(this.nextCursor, eventPage.nextCursor);
  }

  @Override
  public int hashCode() {
    return Objects.hash(items, nextCursor);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class EventPage {\n");
    sb.append("    items: ").append(toIndentedString(items)).append("\n");
    sb.append("    nextCursor: ").append(toIndentedString(nextCursor)).append("\n");
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

