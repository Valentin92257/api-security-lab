package lab.shop.application;

/** Identifies the startup-selected variant; does not implement security or business operations. */
public interface ShopImplementation {
    ShopMode mode();
}
