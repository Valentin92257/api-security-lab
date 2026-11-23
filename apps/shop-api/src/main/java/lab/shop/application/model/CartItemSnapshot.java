package lab.shop.application.model;

import lab.contracts.shop.model.LineItem;

public record CartItemSnapshot(long productId, String name, int quantity, int price, boolean published) {

    public long lineTotal() {
        return (long) quantity * price;
    }

    public LineItem toDto() {
        return new LineItem(productId, name, quantity, price, lineTotal());
    }
}
