package lab.shop.vulnerable.repository;

import lab.shop.secured.repository.ProtectedCouponRepositoryIntegrationTest;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles(value = "vulnerable", inheritProfiles = false)
public class VulnerableCouponRepositoryIntegrationTest extends ProtectedCouponRepositoryIntegrationTest {

    @Override
    protected boolean shouldOverconsumeUnderConcurrency() {
        return true;
    }

    @Override
    protected boolean enforcesCouponValidity() {
        return false;
    }
}
