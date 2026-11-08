package lab.shop.application.repository;

import java.util.Optional;

public interface UserRepository {

    /**
     * @throws org.springframework.dao.DuplicateKeyException логин или email совпали
     */
    long insert(String login, String name, String email, String passwordHash);

    Optional<Credentials> findCredentialsByLogin(String login);



    record Credentials(long id, String passwordHash) {
    }
}
