package lab.control.fixture.model;

import java.time.Instant;
import java.util.List;

public record FixtureCart(
        long id,
        long userId,
        Long couponId,
        long revision,
        Instant updatedAt,
        List<Item> items
) {
    public FixtureCart {
        items = List.copyOf(items);
    }

    public record Item(long productId, int quantity) {
    }
}
