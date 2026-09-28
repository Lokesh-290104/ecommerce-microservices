package dev.lokesh.shop.user.api;

import dev.lokesh.shop.user.domain.User;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/** Request and response bodies for /api/users. */
public final class UserDtos {

    private UserDtos() {
    }

    public record CreateUserRequest(
            @NotBlank @Email @Size(max = 254) String email,
            // BCrypt only uses the first 72 bytes; UserService also rejects longer UTF-8 input.
            @NotBlank @Size(min = 8, max = 72) String password,
            @NotBlank @Size(max = 100) String name) {
    }

    public record UpdateUserRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(max = 100) String name) {
    }

    /** Never includes the password hash. */
    public record UserResponse(Long id, String email, String name, Instant createdAt) {

        public static UserResponse from(User user) {
            return new UserResponse(user.getId(), user.getEmail(), user.getName(), user.getCreatedAt());
        }
    }
}
