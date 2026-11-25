package lab.shop.vulnerable.service;

import lab.contracts.shop.model.User;
import lab.shop.api.exception.NotFoundException;
import lab.shop.application.model.UserProfile;
import lab.shop.application.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VulnerableUserServiceTest {

    @Mock
    private UserRepository userRepository;

    private VulnerableUserService service;

    @BeforeEach
    void setUp() {
        service = new VulnerableUserService(userRepository);
    }

    @Test
    @DisplayName("vulnerable getUser возвращает чужой профиль без проверки владельца")
    void returnsForeignProfileWithoutOwnerCheck() {
        when(userRepository.findProfile(2L))
                .thenReturn(Optional.of(new UserProfile(2L, "bob", "Bob", "bob@example.com")));

        User user = service.getUser(2L);

        assertThat(user).isEqualTo(new User(2L, "bob", "Bob", "bob@example.com"));
    }

    @Test
    @DisplayName("Отсутствующий профиль возвращает 404")
    void missingProfileReturns404() {
        when(userRepository.findProfile(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getUser(999L)).isInstanceOf(NotFoundException.class);
    }
}
