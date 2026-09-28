package dev.lokesh.shop.user.service;

import dev.lokesh.shop.user.domain.User;
import dev.lokesh.shop.user.domain.UserRepository;
import dev.lokesh.shop.user.error.ApiException;
import dev.lokesh.shop.user.error.ErrorCode;
import dev.lokesh.shop.user.security.TokenIssuer;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

@Service
public class AuthService {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final TokenIssuer tokenIssuer;
    /** Compared against when the email is unknown, so both failures cost one BCrypt check. */
    private final String dummyHash;

    public AuthService(UserRepository users, PasswordEncoder passwordEncoder, TokenIssuer tokenIssuer) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.tokenIssuer = tokenIssuer;
        this.dummyHash = passwordEncoder.encode("no-such-user-password");
    }

    /**
     * Returns a signed user token. Unknown email, wrong password and deleted account all give
     * the same 401, in about the same time, so login can't be used to find out who is registered.
     */
    @Transactional(readOnly = true)
    public String login(String email, String password) {
        Optional<User> user = users.findByEmail(UserService.normalize(email));
        boolean passwordOk = matches(password, user.map(User::getPasswordHash).orElse(dummyHash));
        if (user.isEmpty() || !passwordOk || !user.get().isActive()) {
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS, "Email or password is incorrect.");
        }
        return tokenIssuer.issueUserToken(user.get().getId());
    }

    private boolean matches(String password, String hash) {
        // BCrypt refuses input over 72 bytes; such a password can never have been registered.
        if (password.getBytes(StandardCharsets.UTF_8).length > UserService.BCRYPT_MAX_BYTES) {
            passwordEncoder.matches("", hash); // keep the timing the same
            return false;
        }
        return passwordEncoder.matches(password, hash);
    }
}
