package lab.shop.vulnerable.repository;

import lab.shop.secured.repository.ProtectedOrderRepositoryIntegrationTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Те же проверки хранилища заказов для vulnerable-профиля.
 * Отключены защитные UNIQUE-ограничения и advisory lock.
 */
@ActiveProfiles(value = "vulnerable", inheritProfiles = false)
class VulnerableOrderRepositoryIntegrationTest extends ProtectedOrderRepositoryIntegrationTest {

    @Override
    protected boolean enforcesIdempotencyKeyUniqueness() {
        return false;
    }

    @Override
    protected boolean enforcesCartRevisionUniqueness() {
        return false;
    }

    @Override
    protected boolean serializesIdempotencyKey() {
        return false;
    }
}
