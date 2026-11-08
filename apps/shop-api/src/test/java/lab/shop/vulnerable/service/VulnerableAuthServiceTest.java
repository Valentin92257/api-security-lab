package lab.shop.vulnerable.service;

import lab.shop.secured.service.ProtectedAuthServiceTest;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles(value = "vulnerable", inheritProfiles = false)
@ExtendWith(MockitoExtension.class)
class VulnerableAuthServiceTest extends ProtectedAuthServiceTest {
}
