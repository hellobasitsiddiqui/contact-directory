package com.example.contacts.service;

import com.example.contacts.dto.UserResponse;
import com.example.contacts.exception.OperationNotAllowedException;
import com.example.contacts.exception.ResourceNotFoundException;
import com.example.contacts.model.Role;
import com.example.contacts.model.User;
import com.example.contacts.repository.UserRepository;
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
 */
@Service
@Transactional
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
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
        return UserResponse.from(userRepository.save(user));
    }

    public UserResponse resetPassword(Long id, String newPassword) {
        User user = require(id);
        user.setPassword(passwordEncoder.encode(newPassword));
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
