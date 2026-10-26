package lab.contracts.control.model;

import java.net.URI;
import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.lang.Nullable;
import tools.jackson.databind.annotation.JsonDeserialize;
import java.time.OffsetDateTime;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;


import java.util.*;
import jakarta.annotation.Generated;

/**
 * CreateTrialRequest
 */

@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", comments = "Generator version: 7.26.0")
public class CreateTrialRequest {

  /**
   * Gets or Sets families
   */
  public enum FamiliesEnum {
    CREDENTIAL_STUFFING("CREDENTIAL_STUFFING"),
    
    BOLA("BOLA"),
    
    REPLAY("REPLAY"),
    
    INJECTION("INJECTION"),
    
    BUSINESS_LOGIC("BUSINESS_LOGIC");

    private final String value;

    FamiliesEnum(String value) {
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
    public static FamiliesEnum fromValue(String value) {
      for (FamiliesEnum b : FamiliesEnum.values()) {
        if (b.value.equals(value)) {
          return b;
        }
      }
      throw new IllegalArgumentException("Unexpected value '" + value + "'");
    }
  }

  private Set<FamiliesEnum> families = new LinkedHashSet<>();

  private @Nullable Integer seed;

  public CreateTrialRequest() {
    super();
  }

  /**
   * Constructor with only required parameters
   */
  public CreateTrialRequest(Set<FamiliesEnum> families) {
    this.families = families;
  }

  public CreateTrialRequest families(Set<FamiliesEnum> families) {
    this.families = families;
    return this;
  }

  public CreateTrialRequest addFamiliesItem(FamiliesEnum familiesItem) {
    if (this.families == null) {
      this.families = new LinkedHashSet<>();
    }
    this.families.add(familiesItem);
    return this;
  }

  /**
   * Get families
   * @return families
   */
  @NotNull @Size(min = 1, max = 5) 
  @JsonProperty("families")
  public Set<FamiliesEnum> getFamilies() {
    return families;
  }

  @JsonDeserialize(as = LinkedHashSet.class)
  @JsonProperty("families")
  public void setFamilies(Set<FamiliesEnum> families) {
    this.families = families;
  }

  public CreateTrialRequest seed(@Nullable Integer seed) {
    this.seed = seed;
    return this;
  }

  /**
   * Get seed
   * minimum: 0
   * maximum: 2147483647
   * @return seed
   */
  @Min(value = 0) @Max(value = 2147483647) 
  @JsonProperty("seed")
  public @Nullable Integer getSeed() {
    return seed;
  }

  @JsonProperty("seed")
  public void setSeed(@Nullable Integer seed) {
    this.seed = seed;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    CreateTrialRequest createTrialRequest = (CreateTrialRequest) o;
    return Objects.equals(this.families, createTrialRequest.families) &&
        Objects.equals(this.seed, createTrialRequest.seed);
  }

  @Override
  public int hashCode() {
    return Objects.hash(families, seed);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class CreateTrialRequest {\n");
    sb.append("    families: ").append(toIndentedString(families)).append("\n");
    sb.append("    seed: ").append(toIndentedString(seed)).append("\n");
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

