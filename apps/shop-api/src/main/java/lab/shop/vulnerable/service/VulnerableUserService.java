package lab.shop.vulnerable.service;

import lab.contracts.shop.model.User;
import lab.shop.application.service.UserService;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("vulnerable")
public class VulnerableUserService implements UserService {

    @Override
    public User getUser(Long userId) {
        return new User();
    }
}