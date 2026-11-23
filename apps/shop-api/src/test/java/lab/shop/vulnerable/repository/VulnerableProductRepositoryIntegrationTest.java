package lab.shop.vulnerable.repository;

import lab.shop.secured.repository.ProtectedProductRepositoryIntegrationTest;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles(value = "vulnerable", inheritProfiles = false)
class VulnerableProductRepositoryIntegrationTest extends ProtectedProductRepositoryIntegrationTest {

    @Override
    protected boolean shouldRevealDraftWithInjection() {
        return true;
    }
}
