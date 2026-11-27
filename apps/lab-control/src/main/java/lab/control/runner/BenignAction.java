package lab.control.runner;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Доменные действия плана легитимного пользователя.
 * <br>
 * Новые benign-шаги (профиль, заказ, чтение корзины, выбор купона, checkout и безопасный retry)
 * добавляются как {@code record} в этот sealed-интерфейс с обработкой в {@code execute}/{@code advance}
 * класса {@link BenignUserScenario}; состояние между шагами (например, {@code cartRevision}/{@code total})
 * хранится в приватном {@code SessionState}.
 */
public sealed interface BenignAction {
    long MAX_ID = 9007199254740991L;
    int MAX_QUERY_LENGTH = 120;
    int MAX_QUANTITY = 99;

    record Login(String login, String password) implements BenignAction {
        private static final Pattern LOGIN = Pattern.compile("[a-z0-9_]{3,32}");

        public Login {
            Objects.requireNonNull(login);
            Objects.requireNonNull(password);
            int passwordBytes = password.getBytes(StandardCharsets.UTF_8).length;
            if (!LOGIN.matcher(login).matches() || passwordBytes < 8 || passwordBytes > 72) {
                throw new IllegalArgumentException("Invalid benign login credentials");
            }
        }

        @Override
        public String toString() {
            return "Login[login=" + login + "]";
        }
    }

    /**
     * Просмотр каталога; {@code null} или пустая строка означает запрос без параметра {@code q}.
     */
    record BrowseCatalog(String query) implements BenignAction {
        public BrowseCatalog {
            if (query != null && query.length() > MAX_QUERY_LENGTH) {
                throw new IllegalArgumentException("Catalog search query must not exceed " + MAX_QUERY_LENGTH + " chars");
            }
        }
    }

    record SetCartItem(long productId, int quantity) implements BenignAction {
        public SetCartItem {
            if (productId < 1 || productId > MAX_ID || quantity < 1 || quantity > MAX_QUANTITY) {
                throw new IllegalArgumentException("Invalid cart item parameters");
            }
        }
    }
}
