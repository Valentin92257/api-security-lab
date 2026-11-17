package lab.shop.api.security;

import lab.shop.api.exception.UnauthorizedException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthUtilsTest {

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("getUserId возвращает id из sub текущего токена")
    void getUserIdReturnsSubject() {
        authenticateWithSubject("42");

        assertThat(AuthUtils.getUserId()).isEqualTo(42L);
    }

    @Test
    @DisplayName("getUserId без аутентификации бросает UnauthorizedException")
    void getUserIdThrowsWhenNotAuthenticated() {
        assertThatThrownBy(AuthUtils::getUserId).isInstanceOf(UnauthorizedException.class);
    }

    @Test
    @DisplayName("getUserId с нечисловым sub бросает UnauthorizedException")
    void getUserIdThrowsWhenSubjectIsNotNumeric() {
        authenticateWithSubject("not-a-number");

        assertThatThrownBy(AuthUtils::getUserId).isInstanceOf(UnauthorizedException.class);
    }

    @Test
    @DisplayName("getUserId для анонимного пользователя бросает UnauthorizedException")
    void getUserIdThrowsForAnonymous() {
        authenticateAnonymously();

        assertThatThrownBy(AuthUtils::getUserId).isInstanceOf(UnauthorizedException.class);
    }

    @Test
    @DisplayName("getOptUserId возвращает id из sub текущего токена")
    void getOptUserIdReturnsSubject() {
        authenticateWithSubject("42");

        assertThat(AuthUtils.getOptUserId()).contains(42L);
    }

    @Test
    @DisplayName("getOptUserId без аутентификации возвращает пустой Optional")
    void getOptUserIdIsEmptyWhenNotAuthenticated() {
        assertThat(AuthUtils.getOptUserId()).isEmpty();
    }

    @Test
    @DisplayName("getOptUserId с нечисловым sub возвращает пустой Optional")
    void getOptUserIdIsEmptyWhenSubjectIsNotNumeric() {
        authenticateWithSubject("not-a-number");

        assertThat(AuthUtils.getOptUserId()).isEmpty();
    }

    @Test
    @DisplayName("getOptUserId для анонимного пользователя возвращает пустой Optional")
    void getOptUserIdIsEmptyForAnonymous() {
        authenticateAnonymously();

        assertThat(AuthUtils.getOptUserId()).isEmpty();
    }

    private static void authenticateWithSubject(String subject) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "HS256")
                .subject(subject)
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    private static void authenticateAnonymously() {
        SecurityContextHolder.getContext().setAuthentication(
                new AnonymousAuthenticationToken(
                        "key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));
    }
}
