package lab.shop.application.service;

import lab.contracts.shop.model.LoginRequest;
import lab.contracts.shop.model.LoginResult;
import lab.contracts.shop.model.RegisterRequest;
import lab.contracts.shop.model.User;

public interface AuthService {

    LoginResult login(LoginRequest request);

    User register(RegisterRequest request);
}
