package lab.shop.application.model;

import lab.contracts.shop.model.User;

public record UserProfile(long id, String login, String name, String email) {

    public User toDto() {
        return new User(id, login, name, email);
    }
}
