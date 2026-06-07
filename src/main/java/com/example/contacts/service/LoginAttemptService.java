package com.example.contacts.service;

import com.example.contacts.model.User;
import com.example.contacts.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.LockedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Brute-force protection for the login flow. Tracks consecutive failed login
 * attempts per account and temporarily locks an account once
 * {@code app.security.max-login-attempts} consecutive failures are reached.
 *
 * <p>The lock is purely time-based: once {@code lockedUntil} elapses the account
 * may be tried again. A successful login resets the counter and clears any lock.
 * Unknown usernames are deliberately ignored here so they neither create lock
 * state nor leak whether an account exists.
 */
@Service
@Transactional
public class LoginAttemptService {

    private final UserRepository userRepository;
    private final int maxAttempts;
    private final long lockoutMinutes;

    public LoginAttemptService(UserRepository userRepository,
                               @Value("${app.security.max-login-attempts:5}") int maxAttempts,
                               @Value("${app.security.lockout-minutes:15}") long lockoutMinutes) {
        this.userRepository = userRepository;
        this.maxAttempts = maxAttempts;
        this.lockoutMinutes = lockoutMinutes;
    }

    /**
     * Rejects the login before authentication if the account exists and is
     * currently within its lockout window.
     *
     * @param username the submitted login username
     * @throws LockedException if the account is locked until a future instant
     */
    public void assertNotLocked(String username) {
        userRepository.findByUsername(username).ifPresent(user -> {
            Instant lockedUntil = user.getLockedUntil();
            if (lockedUntil != null && Instant.now().isBefore(lockedUntil)) {
                throw new LockedException("Account locked, try again later");
            }
        });
    }

    /**
     * Records a failed authentication for an existing account. Increments the
     * failure counter; when it reaches the configured maximum the account is
     * locked for {@code lockout-minutes} and the counter is reset to 0. Unknown
     * usernames are ignored so no lock state is created for them.
     *
     * @param username the submitted login username
     */
    public void recordFailure(String username) {
        Optional<User> found = userRepository.findByUsername(username);
        if (found.isEmpty()) {
            return;
        }
        User user = found.get();
        // Clear any expired (non-future) lock left over from a previous lockout
        // so stale timestamps do not linger past their window.
        if (user.getLockedUntil() != null && !Instant.now().isBefore(user.getLockedUntil())) {
            user.setLockedUntil(null);
        }
        int attempts = user.getFailedLoginAttempts() + 1;
        if (attempts >= maxAttempts) {
            user.setLockedUntil(Instant.now().plus(Duration.ofMinutes(lockoutMinutes)));
            user.setFailedLoginAttempts(0);
        } else {
            user.setFailedLoginAttempts(attempts);
        }
        userRepository.save(user);
    }

    /**
     * Clears all lock state after a successful login: resets the failure counter
     * to 0 and removes any lockout timestamp.
     *
     * @param username the submitted login username
     */
    public void recordSuccess(String username) {
        userRepository.findByUsername(username).ifPresent(user -> {
            if (user.getFailedLoginAttempts() != 0 || user.getLockedUntil() != null) {
                user.setFailedLoginAttempts(0);
                user.setLockedUntil(null);
                userRepository.save(user);
            }
        });
    }
}
