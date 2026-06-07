package com.example.contacts;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.contacts.model.Role;
import com.example.contacts.model.User;
import com.example.contacts.repository.UserRepository;
import com.example.contacts.service.LoginAttemptService;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.LockedException;

/**
 * Isolated tests for {@link LoginAttemptService}, exercising its branch logic
 * directly against the repository rather than through the full HTTP stack. This
 * pins behaviour that the end-to-end {@code AccountSelfServiceTest} cannot easily
 * cover, notably lock <em>expiry</em> (which needs a past timestamp rather than
 * real elapsed time) and the no-op/early-return branches.
 */
@SpringBootTest
class LoginAttemptServiceTest {

    @Autowired
    private LoginAttemptService loginAttemptService;

    @Autowired
    private UserRepository userRepository;

    @Value("${app.security.max-login-attempts}")
    private int maxAttempts;

    /** Persists a fresh enabled USER with a random username and returns it. */
    private User newUser() {
        User user = new User("svc-" + UUID.randomUUID(), "irrelevant-hash", Role.USER);
        return userRepository.save(user);
    }

    private User reload(User user) {
        return userRepository.findById(user.getId()).orElseThrow();
    }

    @Test
    void recordFailure_reachingMax_locksAndResetsCounter() {
        User user = newUser();

        for (int i = 0; i < maxAttempts; i++) {
            loginAttemptService.recordFailure(user.getUsername());
        }

        User reloaded = reload(user);
        assertEquals(0, reloaded.getFailedLoginAttempts(),
                "counter is reset to 0 when the lock is set");
        assertNotNull(reloaded.getLockedUntil(), "account should be locked");
        assertTrue(reloaded.getLockedUntil().isAfter(Instant.now()),
                "lockedUntil should be in the future");
    }

    @Test
    void recordFailure_belowMax_incrementsWithoutLocking() {
        User user = newUser();

        loginAttemptService.recordFailure(user.getUsername());
        loginAttemptService.recordFailure(user.getUsername());

        User reloaded = reload(user);
        assertEquals(2, reloaded.getFailedLoginAttempts());
        assertNull(reloaded.getLockedUntil());
    }

    @Test
    void recordSuccess_clearsCounterAndLock() {
        User user = newUser();
        user.setFailedLoginAttempts(3);
        user.setLockedUntil(Instant.now().plusSeconds(600));
        userRepository.save(user);

        loginAttemptService.recordSuccess(user.getUsername());

        User reloaded = reload(user);
        assertEquals(0, reloaded.getFailedLoginAttempts());
        assertNull(reloaded.getLockedUntil());
    }

    @Test
    void assertNotLocked_futureLock_throws() {
        User user = newUser();
        user.setLockedUntil(Instant.now().plusSeconds(600));
        userRepository.save(user);

        assertThrows(LockedException.class,
                () -> loginAttemptService.assertNotLocked(user.getUsername()));
    }

    @Test
    void assertNotLocked_expiredLock_doesNotThrow() {
        User user = newUser();
        user.setLockedUntil(Instant.now().minusSeconds(1)); // already elapsed
        userRepository.save(user);

        assertDoesNotThrow(() -> loginAttemptService.assertNotLocked(user.getUsername()));
    }

    @Test
    void assertNotLocked_unknownUsername_doesNotThrow() {
        assertDoesNotThrow(() -> loginAttemptService.assertNotLocked("nobody-" + UUID.randomUUID()));
    }

    @Test
    void recordFailure_unknownUsername_persistsNothing() {
        long before = userRepository.count();

        loginAttemptService.recordFailure("nobody-" + UUID.randomUUID());

        assertEquals(before, userRepository.count(),
                "no lock row should be created for an unknown username");
    }

    @Test
    void recordFailure_expiredLock_relocksAfterMaxWithFreshTimestamp() {
        User user = newUser();
        Instant expired = Instant.now().minusSeconds(1);
        user.setLockedUntil(expired);
        userRepository.save(user);

        for (int i = 0; i < maxAttempts; i++) {
            loginAttemptService.recordFailure(user.getUsername());
        }

        User reloaded = reload(user);
        assertNotNull(reloaded.getLockedUntil());
        assertTrue(reloaded.getLockedUntil().isAfter(Instant.now()),
                "a fresh future lock replaces the stale expired timestamp");
    }
}
