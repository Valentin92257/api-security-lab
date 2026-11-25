package lab.shop.secured.service;

import lab.contracts.shop.model.User;
import lab.shop.api.exception.NotFoundException;
import lab.shop.application.model.UserProfile;
import lab.shop.application.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProtectedUserServiceTest {

    private static final long CURRENT_USER_ID = 1L;
    private static final long OTHER_USER_ID = 2L;

    @Mock
    private UserRepository userRepository;

    private ProtectedUserService service;

    @BeforeEach
    void setUp() {
        service = new ProtectedUserService(userRepository);
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "HS256")
                .subject(Long.toString(CURRENT_USER_ID))
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("getUser возвращает собственный профиль")
    void returnsOwnProfile() {
        when(userRepository.findProfile(CURRENT_USER_ID))
                .thenReturn(Optional.of(new UserProfile(CURRENT_USER_ID, "alice", "Alice", "alice@example.com")));

        User user = service.getUser(CURRENT_USER_ID);

        assertThat(user).isEqualTo(new User(CURRENT_USER_ID, "alice", "Alice", "alice@example.com"));
    }

    @Test
    @DisplayName("Чужой профиль отклоняется 404 без обращения к БД")
    void rejectsForeignProfileWith404() {
        assertThatThrownBy(() -> service.getUser(OTHER_USER_ID)).isInstanceOf(NotFoundException.class);
        verifyNoInteractions(userRepository);
    }

    @Test
    @DisplayName("Отсутствующий профиль возвращает 404")
    void missingOwnProfileReturns404() {
        when(userRepository.findProfile(CURRENT_USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getUser(CURRENT_USER_ID)).isInstanceOf(NotFoundException.class);
    }
}
