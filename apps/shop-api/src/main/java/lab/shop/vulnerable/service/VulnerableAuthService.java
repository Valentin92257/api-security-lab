package lab.shop.vulnerable.service;

import lab.contracts.shop.model.LoginRequest;
import lab.contracts.shop.model.LoginResult;
import lab.contracts.shop.model.RegisterRequest;
import lab.contracts.shop.model.User;
import lab.shop.application.service.AuthService;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("vulnerable")
public class VulnerableAuthService implements AuthService {

    @Override
    public LoginResult login(LoginRequest request) {
        return new LoginResult();
    }

    @Override
    public User register(RegisterRequest request) {
        return new User();
    }
}