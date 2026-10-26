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
 * RegisterRequest
 */

@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", comments = "Generator version: 7.26.0")
public class RegisterRequest {

  private String login;

  private String password;

  private String name;

  private String email;

  public RegisterRequest() {
    super();
  }

  /**
   * Constructor with only required parameters
   */
  public RegisterRequest(String login, String password, String name, String email) {
    this.login = login;
    this.password = password;
    this.name = name;
    this.email = email;
  }

  public RegisterRequest login(String login) {
    this.login = login;
    return this;
  }

  /**
   * После trim/lowercase: [a-z0-9_]{3,32}.
   * @return login
   */
  @NotNull
  @JsonProperty("login")
  public String getLogin() {
    return login;
  }

  @JsonProperty("login")
  public void setLogin(String login) {
    this.login = login;
  }

  public RegisterRequest password(String password) {
    this.password = password;
    return this;
  }

  /**
   * 8-72 UTF-8 байт, без нормализации.
   * @return password
   */
  @NotNull
  @JsonProperty("password")
  public String getPassword() {
    return password;
  }

  @JsonProperty("password")
  public void setPassword(String password) {
    this.password = password;
  }

  public RegisterRequest name(String name) {
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

  public RegisterRequest email(String email) {
    this.email = email;
    return this;
  }

  /**
   * Get email
   * @return email
   */
  @NotNull
  @JsonProperty("email")
  public String getEmail() {
    return email;
  }

  @JsonProperty("email")
  public void setEmail(String email) {
    this.email = email;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    RegisterRequest registerRequest = (RegisterRequest) o;
    return Objects.equals(this.login, registerRequest.login) &&
        Objects.equals(this.password, registerRequest.password) &&
        Objects.equals(this.name, registerRequest.name) &&
        Objects.equals(this.email, registerRequest.email);
  }

  @Override
  public int hashCode() {
    return Objects.hash(login, password, name, email);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class RegisterRequest {\n");
    sb.append("    login: ").append(toIndentedString(login)).append("\n");
    sb.append("    password: ").append(toIndentedString(password)).append("\n");
    sb.append("    name: ").append(toIndentedString(name)).append("\n");
    sb.append("    email: ").append(toIndentedString(email)).append("\n");
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

