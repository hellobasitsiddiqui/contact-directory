package com.example.contacts.security;

import com.example.contacts.model.User;
import com.example.contacts.repository.UserRepository;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;

/**
 * Resolves ownership and role context for the authenticated request.
 *
 * <p>Used by the controller layer to scope contact operations: non-admin users
 * are limited to the contacts they own, while admins operate unscoped.
 */
@Service
public class CurrentUserService {

    private final UserRepository userRepository;

    public CurrentUserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * Returns the id of the currently authenticated user, looked up by the
     * authentication name (username).
     *
     * @param auth the current authentication
     * @return the id of the matching user
     * @throws IllegalStateException if no user exists for the authentication name
     */
    public Long currentUserId(Authentication auth) {
        User user = userRepository.findByUsername(auth.getName())
                .orElseThrow(() -> new IllegalStateException(
                        "Authenticated user not found: " + auth.getName()));
        return user.getId();
    }

    /**
     * Indicates whether the current authentication carries the admin role.
     *
     * @param auth the current authentication
     * @return {@code true} if the authorities contain {@code ROLE_ADMIN}
     */
    public boolean isAdmin(Authentication auth) {
        return auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch("ROLE_ADMIN"::equals);
    }
}
