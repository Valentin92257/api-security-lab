package lab.contracts.control.model;

import java.net.URI;
import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonCreator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import lab.contracts.control.model.Trial;
import org.springframework.lang.Nullable;
import java.time.OffsetDateTime;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;


import java.util.*;
import jakarta.annotation.Generated;

/**
 * TrialPage
 */

@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", comments = "Generator version: 7.26.0")
public class TrialPage {

  private List<@Valid Trial> items = new ArrayList<>();

  private Long total;

  public TrialPage() {
    super();
  }

  /**
   * Constructor with only required parameters
   */
  public TrialPage(List<@Valid Trial> items, Long total) {
    this.items = items;
    this.total = total;
  }

  public TrialPage items(List<@Valid Trial> items) {
    this.items = items;
    return this;
  }

  public TrialPage addItemsItem(Trial itemsItem) {
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
  public List<@Valid Trial> getItems() {
    return items;
  }

  @JsonProperty("items")
  public void setItems(List<@Valid Trial> items) {
    this.items = items;
  }

  public TrialPage total(Long total) {
    this.total = total;
    return this;
  }

  /**
   * Get total
   * minimum: 0
   * maximum: 9007199254740991
   * @return total
   */
  @NotNull @Min(value = 0L) @Max(value = 9007199254740991L) 
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
    TrialPage trialPage = (TrialPage) o;
    return Objects.equals(this.items, trialPage.items) &&
        Objects.equals(this.total, trialPage.total);
  }

  @Override
  public int hashCode() {
    return Objects.hash(items, total);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class TrialPage {\n");
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

