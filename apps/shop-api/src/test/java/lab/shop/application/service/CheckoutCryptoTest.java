package lab.shop.application.service;

import lab.shop.application.CheckoutProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

class CheckoutCryptoTest {

    private static final String SECRET = "test-idempotency-secret-at-least-32-bytes";
    private static final String OTHER_SECRET = "another-idempotency-secret-at-least-32-b";

    private final CheckoutCrypto crypto = new CheckoutCrypto(new CheckoutProperties(SECRET, Duration.ofSeconds(2)));

    @Test
    @DisplayName("idempotencyKeyHash возвращает детерминированный 32-байтный HMAC, зависящий от ключа и секрета")
    void idempotencyKeyHashIsDeterministic32Bytes() {
        byte[] hash1 = crypto.idempotencyKeyHash("idem-key-1234567890");
        byte[] hash2 = crypto.idempotencyKeyHash("idem-key-1234567890");
        byte[] differentKey = crypto.idempotencyKeyHash("idem-key-0987654321");
        byte[] differentSecret = new CheckoutCrypto(new CheckoutProperties(OTHER_SECRET, Duration.ofSeconds(2)))
                .idempotencyKeyHash("idem-key-1234567890");

        assertThat(hash1).hasSize(32).containsExactly(hash2);
        assertThat(hash1).isNotEqualTo(differentKey);
        assertThat(hash1).isNotEqualTo(differentSecret);
    }

    @Test
    @DisplayName("requestFingerprint возвращает 32-байтный SHA-256 и меняется при изменении cartRevision или quotedTotal")
    void requestFingerprintReflectsCanonicalFields() {
        byte[] fp1 = crypto.requestFingerprint(3L, 1500L);
        byte[] fp2 = crypto.requestFingerprint(3L, 1500L);
        byte[] differentRevision = crypto.requestFingerprint(4L, 1500L);
        byte[] differentTotal = crypto.requestFingerprint(3L, 1499L);

        assertThat(fp1).hasSize(32).containsExactly(fp2);
        assertThat(fp1).isNotEqualTo(differentRevision);
        assertThat(fp1).isNotEqualTo(differentTotal);
    }

    @Test
    @DisplayName("advisoryLockId соответствует первым 8 байтам SHA-256 канонического массива (signed big-endian long)")
    void advisoryLockIdMatchesSpecFormula() throws Exception {
        long userId = 42L;
        byte[] keyHash = crypto.idempotencyKeyHash("idem-key-1234567890");
        String expectedInput = "[\"checkout-v1\",\"42\",\"" + HexFormat.of().formatHex(keyHash) + "\"]";
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(expectedInput.getBytes(StandardCharsets.UTF_8));
        long expectedLockId = ByteBuffer.wrap(digest, 0, Long.BYTES).getLong();

        assertThat(crypto.advisoryLockId(userId, keyHash)).isEqualTo(expectedLockId);
        assertThat(crypto.advisoryLockId(userId + 1, keyHash)).isNotEqualTo(expectedLockId);
        assertThat(crypto.advisoryLockId(userId, crypto.idempotencyKeyHash("another-key-12345678")))
                .isNotEqualTo(expectedLockId);
    }
}
