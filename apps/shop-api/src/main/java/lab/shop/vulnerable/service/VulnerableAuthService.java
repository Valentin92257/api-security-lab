package lab.shop.vulnerable.service;

import lab.shop.api.security.JwtProperties;
import lab.shop.api.security.JwtService;
import lab.shop.application.repository.CartRepository;
import lab.shop.application.repository.UserRepository;
import lab.shop.application.service.BaseAuthService;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@Profile("vulnerable")
public class VulnerableAuthService extends BaseAuthService {

    protected VulnerableAuthService(UserRepository userRepository, CartRepository cartRepository, PasswordEncoder passwordEncoder, JwtService jwtService, JwtProperties jwtProperties) {
        super(userRepository, cartRepository, passwordEncoder, jwtService, jwtProperties);
    }
}