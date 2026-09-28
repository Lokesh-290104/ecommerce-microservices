package dev.lokesh.shop.user.service;

import dev.lokesh.shop.user.api.UserDtos.CreateUserRequest;
import dev.lokesh.shop.user.api.UserDtos.UpdateUserRequest;
import dev.lokesh.shop.user.domain.User;
import dev.lokesh.shop.user.domain.UserRepository;
import dev.lokesh.shop.user.error.ApiException;
import dev.lokesh.shop.user.error.ErrorCode;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

@Service
public class UserService {

    /** BCrypt ignores everything after 72 bytes, so longer passwords are refused, not truncated. */
    static final int BCRYPT_MAX_BYTES = 72;

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository users, PasswordEncoder passwordEncoder) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public User register(CreateUserRequest request) {
        if (request.password().getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAX_BYTES) {
            throw new ApiException(ErrorCode.PASSWORD_TOO_LONG,
                    "Password must be at most " + BCRYPT_MAX_BYTES + " bytes in UTF-8.");
        }
        String email = normalize(request.email());
        if (users.existsByEmail(email)) {
            throw emailTaken(email);
        }
        User user = new User(email, passwordEncoder.encode(request.password()), request.name().strip());
        return saveEnforcingUniqueEmail(user, email);
    }

    @Transactional(readOnly = true)
    public User get(long id) {
        return users.findByIdAndActiveTrue(id)
                .orElseThrow(() -> new ApiException(ErrorCode.USER_NOT_FOUND, "No user with id " + id + "."));
    }

    @Transactional(readOnly = true)
    public Page<User> list(Pageable pageable) {
        return users.findByActiveTrue(pageable);
    }

    @Transactional
    public User update(long id, UpdateUserRequest request) {
        User user = get(id);
        String email = normalize(request.email());
        if (!email.equals(user.getEmail()) && users.existsByEmail(email)) {
            throw emailTaken(email);
        }
        user.update(email, request.name().strip());
        return saveEnforcingUniqueEmail(user, email);
    }

    @Transactional
    public void delete(long id) {
        get(id).deactivate();
    }

    /**
     * The existsByEmail check gives a clean 409 in the common case; the unique constraint
     * catches the race where two requests register the same email at the same time.
     */
    private User saveEnforcingUniqueEmail(User user, String email) {
        try {
            return users.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            throw emailTaken(email);
        }
    }

    private static ApiException emailTaken(String email) {
        return new ApiException(ErrorCode.EMAIL_TAKEN, "Email " + email + " is already registered.");
    }

    /** Emails are unique case-insensitively: stored trimmed and lower-cased. */
    private static String normalize(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }
}
