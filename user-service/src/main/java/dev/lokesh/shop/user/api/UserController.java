package dev.lokesh.shop.user.api;

import dev.lokesh.shop.user.api.UserDtos.CreateUserRequest;
import dev.lokesh.shop.user.api.UserDtos.UpdateUserRequest;
import dev.lokesh.shop.user.api.UserDtos.UserResponse;
import dev.lokesh.shop.user.domain.User;
import dev.lokesh.shop.user.error.ApiException;
import dev.lokesh.shop.user.error.ErrorCode;
import dev.lokesh.shop.user.security.Tokens;
import dev.lokesh.shop.user.service.UserService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Users API. Registration is public; the rest needs a token. A user may read, change or delete
 * only their own account; internal service tokens (order-service) may read any user.
 */
@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping
    public ResponseEntity<UserResponse> register(@Valid @RequestBody CreateUserRequest request) {
        User user = userService.register(request);
        var location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(user.getId()).toUri();
        return ResponseEntity.created(location).body(UserResponse.from(user));
    }

    @GetMapping("/{id}")
    public UserResponse get(@PathVariable long id, @AuthenticationPrincipal Jwt caller) {
        if (!Tokens.isInternal(caller)) {
            requireSelf(caller, id);
        }
        return UserResponse.from(userService.get(id));
    }

    /** Any signed-in caller; always sorted by id, so pages are stable. */
    @GetMapping
    public PageResponse<UserResponse> list(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return PageResponse.from(userService.list(PageRequest.of(page, size, Sort.by("id"))), UserResponse::from);
    }

    @PutMapping("/{id}")
    public UserResponse update(@PathVariable long id, @Valid @RequestBody UpdateUserRequest request,
                               @AuthenticationPrincipal Jwt caller) {
        requireSelf(caller, id);
        return UserResponse.from(userService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id, @AuthenticationPrincipal Jwt caller) {
        requireSelf(caller, id);
        userService.delete(id);
        return ResponseEntity.noContent().build();
    }

    /** Checked before looking the user up, so a 403 never reveals whether an id exists. */
    private static void requireSelf(Jwt caller, long id) {
        if (!Long.toString(id).equals(caller.getSubject())) {
            throw new ApiException(ErrorCode.FORBIDDEN, "You can only access your own account.");
        }
    }
}
