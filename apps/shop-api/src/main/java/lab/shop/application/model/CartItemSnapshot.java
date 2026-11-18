package lab.shop.application.model;

import lab.contracts.shop.model.LineItem;

public record CartItemSnapshot(long productId, String name, int quantity, int unitPrice) {

    public long lineTotal() {
        return (long) quantity * unitPrice;
    }

    public LineItem toDto() {
        return new LineItem(productId, name, quantity, unitPrice, lineTotal());
    }
}
