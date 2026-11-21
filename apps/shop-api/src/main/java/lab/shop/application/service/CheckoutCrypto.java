package lab.shop.application.service;

import lab.shop.application.CheckoutProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Криптографические вычисления для идемпотентного checkout:
 * HMAC-SHA256 ключа идемпотентности, SHA-256 fingerprint запроса и advisory lock id.
 */
@Component
public class CheckoutCrypto {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String HASH_ALGORITHM = "SHA-256";
    private static final String CONTRACT_VERSION = "checkout-v1";

    private final SecretKeySpec hmacKey;

    public CheckoutCrypto(CheckoutProperties properties) {
        this.hmacKey = new SecretKeySpec(
                properties.idempotencySecret().getBytes(StandardCharsets.UTF_8),
                HMAC_ALGORITHM);
    }

    /**
     * HMAC-SHA256 (32 байта) исходного Idempotency-Key.
     */
    public byte[] idempotencyKeyHash(String idempotencyKey) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(hmacKey);
            return mac.doFinal(idempotencyKey.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Не удалось вычислить HMAC-SHA256 для Idempotency-Key", e);
        }
    }

    /**
     * SHA-256 (32 байта) версии контракта, маршрута и канонического разобранного тела.
     */
    public byte[] requestFingerprint(long cartRevision, long quotedTotal) {
        String canonical = "[\"%s\",\"POST\",\"/api/v1/orders\",%d,%d]"
                .formatted(CONTRACT_VERSION, cartRevision, quotedTotal);
        return sha256(canonical);
    }

    /**
     * Первые 8 байт SHA-256 канонического UTF-8 JSON-массива
     * {@code ["checkout-v1", "<userId>", "<lowercase hex key hash>"]} как signed big-endian long.
     */
    public long advisoryLockId(long userId, byte[] keyHash) {
        String canonical = "[\"%s\",\"%d\",\"%s\"]"
                .formatted(CONTRACT_VERSION, userId, HexFormat.of().formatHex(keyHash));
        byte[] digest = sha256(canonical);
        return ByteBuffer.wrap(digest, 0, Long.BYTES).getLong();
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance(HASH_ALGORITHM)
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Алгоритм SHA-256 недоступен", e);
        }
    }
}
