package lab.shop.vulnerable.repository;

import lab.shop.secured.repository.ProtectedUserRepositoryIntegrationTest;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles(value = "vulnerable", inheritProfiles = false)
public class VulnerableUserRepositoryIntegrationTest extends ProtectedUserRepositoryIntegrationTest {
}
