package lab.shop.api;

import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles(value = "vulnerable", inheritProfiles = false)
class VulnerableRequestLoggingTest extends RequestLoggingTest {
}
