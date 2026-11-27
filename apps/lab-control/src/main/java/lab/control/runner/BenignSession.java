package lab.control.runner;

import java.util.List;

/**
 * План одной пользовательской сессии (benign-эпизода) со своим виртуальным IP.
 */
public record BenignSession(String clientIp, List<BenignAction> actions) {
    public BenignSession {
        if (clientIp == null || clientIp.isBlank() || clientIp.length() < 2 || clientIp.length() > 45) {
            throw new IllegalArgumentException("Client IP must be 2..45 non-blank characters");
        }
        if (actions == null || actions.isEmpty()) {
            throw new IllegalArgumentException("Benign session must start with login");
        }
        actions = List.copyOf(actions);
        if (!(actions.getFirst() instanceof BenignAction.Login)) {
            throw new IllegalArgumentException("Benign session must start with login");
        }
    }

    /**
     * Маршрут v0: вход, просмотр каталога, добавление товара в корзину.
     */
    public static BenignSession shopper(String clientIp, String login, String password,
                                        String query, long productId, int quantity) {
        return new BenignSession(clientIp, List.of(
                new BenignAction.Login(login, password),
                new BenignAction.BrowseCatalog(query),
                new BenignAction.SetCartItem(productId, quantity)));
    }
}
