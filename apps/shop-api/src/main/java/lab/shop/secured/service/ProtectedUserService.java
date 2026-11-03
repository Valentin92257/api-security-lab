package lab.shop.secured.service;

import lab.contracts.shop.model.User;
import lab.shop.application.service.UserService;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("protected")
public class ProtectedUserService implements UserService {

    @Override
    public User getUser(Long userId) {
        return new User();
    }
}