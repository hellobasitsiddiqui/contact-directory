package com.example.contacts.service;

import com.example.contacts.dto.UserResponse;
import com.example.contacts.exception.OperationNotAllowedException;
import com.example.contacts.exception.ResourceNotFoundException;
import com.example.contacts.model.Role;
import com.example.contacts.model.User;
import com.example.contacts.repository.UserRepository;
import com.example.contacts.security.RefreshTokenService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;

/**
 * Administrative management of {@link User} accounts: listing, role changes,
 * enable/disable, password resets and deletion.
 *
 * <p>Two integrity guards prevent an administrator from locking everyone out:
 * an admin cannot demote, disable or delete <em>their own</em> account, and the
 * <em>last remaining admin</em> cannot be demoted, disabled or deleted.
 *
 * <p>Lifecycle events that invalidate credentials also revoke the target's
 * refresh-token sessions (CD-028): password reset, disable and delete.
 */
@Service
@Transactional
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenService refreshTokenService;

    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder,
                       RefreshTokenService refreshTokenService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.refreshTokenService = refreshTokenService;
    }

    @Transactional(readOnly = true)
    public List<UserResponse> listUsers() {
        return userRepository.findAll().stream()
                .sorted(Comparator.comparing(User::getId))
                .map(UserResponse::from)
                .toList();
    }

    public UserResponse updateRole(Long id, Role newRole, String actingUsername) {
        User user = require(id);
        boolean demotingFromAdmin = user.getRole() == Role.ADMIN && newRole != Role.ADMIN;
        if (demotingFromAdmin) {
            if (isSelf(user, actingUsername)) {
                throw new OperationNotAllowedException("You cannot change your own role");
            }
            ensureNotLastAdmin(user, "demote");
        }
        user.setRole(newRole);
        return UserResponse.from(userRepository.save(user));
    }

    public UserResponse setEnabled(Long id, boolean enabled, String actingUsername) {
        User user = require(id);
        if (!enabled) {
            if (isSelf(user, actingUsername)) {
                throw new OperationNotAllowedException("You cannot disable your own account");
            }
            if (user.getRole() == Role.ADMIN) {
                ensureNotLastAdmin(user, "disable");
            }
        }
        user.setEnabled(enabled);
        if (!enabled) {
            // A disabled account must not be able to mint new access tokens.
            refreshTokenService.revokeAllForUser(user, RefreshTokenService.REASON_USER_DISABLED);
        }
        return UserResponse.from(userRepository.save(user));
    }

    public UserResponse resetPassword(Long id, String newPassword) {
        User user = require(id);
        user.setPassword(passwordEncoder.encode(newPassword));
        // The old credential is dead; so are all sessions minted with it.
        refreshTokenService.revokeAllForUser(user, RefreshTokenService.REASON_ADMIN_RESET);
        return UserResponse.from(userRepository.save(user));
    }

    public void deleteUser(Long id, String actingUsername) {
        User user = require(id);
        if (isSelf(user, actingUsername)) {
            throw new OperationNotAllowedException("You cannot delete your own account");
        }
        if (user.getRole() == Role.ADMIN) {
            ensureNotLastAdmin(user, "delete");
        }
        // Remove token rows first: H2 (dev/tests) has no FK cascade for them.
        refreshTokenService.deleteAllForUser(user);
        userRepository.delete(user);
    }

    private User require(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id " + id));
    }

    private boolean isSelf(User user, String actingUsername) {
        return user.getUsername().equals(actingUsername);
    }

    private void ensureNotLastAdmin(User user, String action) {
        if (userRepository.countByRole(Role.ADMIN) <= 1) {
            throw new OperationNotAllowedException(
                    "Cannot " + action + " the last remaining admin");
        }
    }
}
