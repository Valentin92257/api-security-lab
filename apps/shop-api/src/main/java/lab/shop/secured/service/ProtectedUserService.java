package lab.shop.secured.service;

import lab.contracts.shop.model.User;
import lab.shop.api.exception.NotFoundException;
import lab.shop.api.security.AuthUtils;
import lab.shop.application.model.UserProfile;
import lab.shop.application.repository.UserRepository;
import lab.shop.application.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("protected")
@RequiredArgsConstructor
public class ProtectedUserService implements UserService {

    private final UserRepository userRepository;

    @Override
    public User getUser(Long userId) {
        long registeredId = AuthUtils.getUserId();
        if (registeredId != userId) {
            throw new NotFoundException("Пользователь не найден");
        }
        return userRepository.findProfile(userId)
                .map(UserProfile::toDto)
                .orElseThrow(() -> new NotFoundException("Пользователь не найден"));
    }
}