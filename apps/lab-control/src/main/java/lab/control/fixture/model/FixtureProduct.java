package lab.control.fixture.model;

public record FixtureProduct(
        long id,
        String name,
        String description,
        long price,
        boolean published,
        boolean canary
) {
}
