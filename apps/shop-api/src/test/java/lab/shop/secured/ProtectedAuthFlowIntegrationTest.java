package lab.shop.secured;

import lab.shop.api.AuthFlowIntegrationTest;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("protected")
class ProtectedAuthFlowIntegrationTest extends AuthFlowIntegrationTest {

    @Override
    protected boolean isProtected() {
        return true;
    }
}
