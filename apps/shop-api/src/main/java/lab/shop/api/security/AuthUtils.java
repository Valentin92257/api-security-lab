package lab.shop.api.security;

import lab.shop.api.exception.UnauthorizedException;
import lombok.experimental.UtilityClass;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Optional;

@UtilityClass
public class AuthUtils {

    /**
     * Id текущего пользователя.
     *
     * @throws UnauthorizedException запрос не аутентифицирован
     */
    public long getUserId() {
        return getOptUserId()
                .orElseThrow(() -> new UnauthorizedException("Требуется аутентификация"));
    }

    /**
     * Id текущего пользователя.
     *
     * @return {@code Optional.empty} запрос не аутентифицирован
     */
    public Optional<Long> getOptUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof Jwt jwt)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Long.parseLong(jwt.getSubject()));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}