package dev.lokesh.shop.user.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    /** Active users only: a soft-deleted user behaves as missing everywhere in the API. */
    Optional<User> findByIdAndActiveTrue(Long id);

    Page<User> findByActiveTrue(Pageable pageable);

    /** Includes deleted users: their email stays taken (unique constraint). */
    boolean existsByEmail(String email);
}
