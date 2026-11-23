package lab.shop.secured;

import lab.shop.api.OrderFlowIntegrationTest;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("protected")
class ProtectedOrderFlowIntegrationTest extends OrderFlowIntegrationTest {

    @Override
    protected boolean isProtected() {
        return true;
    }
}
