package lab.shop.vulnerable.service;

import lab.shop.api.GetUserEndpointTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.ActiveProfiles;

import java.net.http.HttpResponse;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ActiveProfiles("vulnerable")
class VulnerableGetUserEndpointTest extends GetUserEndpointTest {

    @Test
    @DisplayName("Уязвимость BOLA: чужой профиль доступен, id из токена и из пути могут не совпадать")
    void foreignProfileIsReturned() throws Exception {
        when(userRepository.findProfile(OTHER_USER_ID)).thenReturn(Optional.of(profile(OTHER_USER_ID)));

        HttpResponse<String> response = get("/users/" + OTHER_USER_ID, tokenFor(CURRENT_USER_ID));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json(response).get("id").asLong()).isEqualTo(OTHER_USER_ID);
        verify(userRepository).findProfile(OTHER_USER_ID);
    }
}
