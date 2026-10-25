package lab.shop;

import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles(value = "vulnerable", inheritProfiles = false)
class VulnerableHealthEndpointTest extends HealthEndpointTest {
}
