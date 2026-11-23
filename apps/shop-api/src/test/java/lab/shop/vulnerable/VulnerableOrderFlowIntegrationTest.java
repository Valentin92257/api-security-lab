package lab.shop.vulnerable;

import lab.shop.api.OrderFlowIntegrationTest;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("vulnerable")
class VulnerableOrderFlowIntegrationTest extends OrderFlowIntegrationTest {

    @Override
    protected boolean isProtected() {
        return false;
    }
}
