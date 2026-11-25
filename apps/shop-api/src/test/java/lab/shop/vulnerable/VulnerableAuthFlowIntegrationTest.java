package lab.shop.vulnerable;

import lab.shop.api.AuthFlowIntegrationTest;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("vulnerable")
class VulnerableAuthFlowIntegrationTest extends AuthFlowIntegrationTest {

    @Override
    protected boolean isProtected() {
        return false;
    }
}
