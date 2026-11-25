package lab.control.fixture.model;

import java.util.List;

public record BolaFixture(
        FixtureUser attacker,
        List<FixtureUser> victims,
        FixtureUser benignUser,
        FixtureOrder attackerOwnOrder,
        List<FixtureOrder> victimOrders,
        FixtureOrder benignOwnOrder,
        long missingUserId,
        long missingOrderId
) {
    public BolaFixture {
        victims = List.copyOf(victims);
        victimOrders = List.copyOf(victimOrders);
    }
}
