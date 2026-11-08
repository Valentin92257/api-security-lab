package lab.shop.vulnerable.repository;

import lab.shop.secured.repository.ProtectedUserRepositoryIntegrationTest;
import org.springframework.context.annotation.Profile;

@Profile("vulnerable")
public class VulnerableUserRepositoryIntegrationTest extends ProtectedUserRepositoryIntegrationTest {
}
