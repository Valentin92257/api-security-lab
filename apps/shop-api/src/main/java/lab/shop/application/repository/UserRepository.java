package lab.shop.application.repository;

import lab.shop.application.model.UserCredentials;
import lab.shop.application.model.UserProfile;

import java.util.Optional;

public interface UserRepository {

    /**
     * @throws org.springframework.dao.DuplicateKeyException логин или email совпали
     */
    long insert(String login, String name, String email, String passwordHash);

    Optional<UserCredentials> findCredentialsByLogin(String login);

    Optional<UserProfile> findProfile(long id);
}
