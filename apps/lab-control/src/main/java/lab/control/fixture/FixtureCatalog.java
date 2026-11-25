package lab.control.fixture;

import java.time.Duration;
import java.util.List;

import lab.control.fixture.model.FixtureUserRole;

/** Статические шаблоны Fixture v1: 24 пользователя, 12 товаров и 6 исторических заказов. */
final class FixtureCatalog {
    static final List<UserSpec> USERS = List.of(
            new UserSpec(1, "cs_victim_01", "James Hetfield", FixtureUserRole.CS_VICTIM),
            new UserSpec(2, "cs_victim_02", "Cliff Burton", FixtureUserRole.CS_VICTIM),
            new UserSpec(3, "cs_victim_03", "Lars Ulrich", FixtureUserRole.CS_VICTIM),
            new UserSpec(4, "cs_benign_01", "Kirk Hammett", FixtureUserRole.CS_BENIGN),
            new UserSpec(5, "bola_attacker", "Dave Mustaine", FixtureUserRole.BOLA_ATTACKER),
            new UserSpec(6, "bola_victim_01", "Marty Friedman", FixtureUserRole.BOLA_VICTIM),
            new UserSpec(7, "bola_victim_02", "Jason Becker", FixtureUserRole.BOLA_VICTIM),
            new UserSpec(8, "bola_victim_03", "Steve Vai", FixtureUserRole.BOLA_VICTIM),
            new UserSpec(9, "bola_benign_01", "Randy Rhoads", FixtureUserRole.BOLA_BENIGN),
            new UserSpec(10, "replay_user_01", "Dave Murray", FixtureUserRole.REPLAY_USER),
            new UserSpec(11, "replay_user_02", "Vladimir Holstinin", FixtureUserRole.REPLAY_USER),
            new UserSpec(12, "injection_user_01", "Paul Gilbert", FixtureUserRole.INJECTION_USER),
            new UserSpec(13, "bl_coupon_01", "Yngwie Malmsteen", FixtureUserRole.BL_COUPON_USER),
            new UserSpec(14, "bl_coupon_02", "Zakk Wylde", FixtureUserRole.BL_COUPON_USER),
            new UserSpec(15, "bl_coupon_03", "Dimebag Darrell", FixtureUserRole.BL_COUPON_USER),
            new UserSpec(16, "bl_coupon_04", "Alexi Laiho", FixtureUserRole.BL_COUPON_USER),
            new UserSpec(17, "bl_qty_01", "John Petrucci", FixtureUserRole.BL_QTY_USER),
            new UserSpec(18, "bl_qty_02", "Eddie Van Halen", FixtureUserRole.BL_QTY_USER),
            new UserSpec(19, "bl_cart_race_01", "Joey Jordison", FixtureUserRole.BL_CART_RACE_USER),
            new UserSpec(20, "bl_coupon_race_01", "Dave Lombardo", FixtureUserRole.BL_COUPON_RACE_USER),
            new UserSpec(21, "bl_coupon_race_02", "Charlie Benante", FixtureUserRole.BL_COUPON_RACE_USER),
            new UserSpec(22, "bl_price_01", "Mike Portnoy", FixtureUserRole.BL_PRICE_USER),
            new UserSpec(23, "empty_cart_01", "Chuck Schuldiner", FixtureUserRole.EMPTY_CART_USER),
            new UserSpec(24, "benign_checkout_01", "Jeff Waters", FixtureUserRole.BENIGN_CHECKOUT_USER)
    );

    static final List<ProductSpec> PRODUCTS = List.of(
            new ProductSpec("Mechanical Keyboard Pro", "Tactile 75% keyboard with aluminum frame", 12_900L),
            new ProductSpec("USB-C Docking Station", "Dual 4K hub with 100W power delivery", 8_500L),
            new ProductSpec("Noise Cancelling Headphones", "Studio over-ear headphones, 50% lighter headband", 18_400L),
            new ProductSpec("Ergonomic Vertical Mouse", "Wireless mouse for developer's daily work", 4_200L),
            new ProductSpec("27-inch IPS Monitor", "QHD display with factory_calibrated sRGB profile", 29_900L),
            new ProductSpec("NVMe External SSD 1TB", "High-speed USB 3.2 portable solid-state drive", 11_200L),
            new ProductSpec("Desk Lamp LED", "Adjustable color temperature lamp for reading", 3_600L),
            new ProductSpec("Laptop Stand Aluminum", "Foldable stand with non-slip silicone pads", 2_800L),
            new ProductSpec("Webcam 4K HDR", "Conference camera with hardware privacy shutter", 9_700L),
            new ProductSpec("Ethernet Switch 8-Port", "Unmanaged gigabit desktop switch in metal case", 3_100L),
            new ProductSpec("Surge Protector 6-Outlet", "Power strip with 100% copper busbar", 2_400L),
            new ProductSpec("Backpack Tech 20L", "Water-resistant pack for 16' laptop and cables", 6_900L)
    );

    static final List<OrderPlan> HISTORICAL_ORDERS = List.of(
            new OrderPlan(1L, 5L, 0L, null, Duration.ofHours(6), List.of(1L, 4L)),
            new OrderPlan(2L, 6L, 0L, 1L, Duration.ofHours(5), List.of(2L, 7L)),
            new OrderPlan(3L, 6L, 1L, 2L, Duration.ofHours(3), List.of(3L)),
            new OrderPlan(4L, 7L, 0L, 3L, Duration.ofHours(4), List.of(5L, 8L)),
            new OrderPlan(5L, 8L, 0L, 3L, Duration.ofHours(4), List.of(6L, 10L)),
            new OrderPlan(6L, 9L, 0L, 4L, Duration.ofHours(2), List.of(9L, 11L))
    );

    private FixtureCatalog() {
    }

    record UserSpec(long id, String login, String name, FixtureUserRole role) {
    }

    record ProductSpec(String name, String description, long basePrice) {
    }

    record OrderPlan(long orderId, long userId, long cartRevision, Long couponId,
                     Duration ageBeforeBase, List<Long> productIds) {
    }
}
