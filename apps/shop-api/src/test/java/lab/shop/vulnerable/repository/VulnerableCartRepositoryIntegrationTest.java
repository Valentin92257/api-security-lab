package lab.shop.vulnerable.repository;

import lab.shop.secured.repository.ProtectedCartRepositoryIntegrationTest;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles(value = "vulnerable", inheritProfiles = false)
class VulnerableCartRepositoryIntegrationTest extends ProtectedCartRepositoryIntegrationTest {
}
