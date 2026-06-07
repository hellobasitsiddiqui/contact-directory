package com.example.contacts.repository;

import com.example.contacts.model.Role;
import com.example.contacts.model.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Data-access for {@link User} accounts.
 */
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByUsername(String username);

    boolean existsByUsername(String username);

    long countByRole(Role role);
}
