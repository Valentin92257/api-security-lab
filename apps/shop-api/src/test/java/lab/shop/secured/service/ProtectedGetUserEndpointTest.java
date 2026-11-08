package lab.shop.secured.service;

import lab.shop.api.GetUserEndpointTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.ActiveProfiles;

import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

@ActiveProfiles("protected")
class ProtectedGetUserEndpointTest extends GetUserEndpointTest {

    @Test
    @DisplayName("Чужой профиль: 404, даже если он существует; данные не раскрываются, база не затрагивается")
    void foreignProfileIsNotFound() throws Exception {
        HttpResponse<String> response = get("/users/" + OTHER_USER_ID, tokenFor(CURRENT_USER_ID));

        assertThat(response.statusCode()).isEqualTo(404);
        verifyNoInteractions(userRepository);
    }
}
