package com.example.contacts.security;

import com.example.contacts.exception.InvalidRefreshTokenException;
import com.example.contacts.model.AuditAction;
import com.example.contacts.model.RefreshToken;
import com.example.contacts.model.User;
import com.example.contacts.repository.RefreshTokenRepository;
import com.example.contacts.service.AuditService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Issues, rotates and revokes opaque refresh tokens (CD-028).
 *
 * <p>The raw secret is 256 bits from {@link SecureRandom}, base64url-encoded,
 * handed to the client once and stored only as a SHA-256 hex digest. Tokens
 * form <em>families</em> (one per login/device): every refresh atomically
 * marks the presented row used and inserts a child row in the same family.
 * Re-presenting a used token outside a short grace window is treated as theft
 * and revokes the entire family; within the window it is treated as a benign
 * concurrent retry (two tabs racing) and a sibling token is minted instead.
 *
 * <p>All failure modes throw the same {@link InvalidRefreshTokenException}
 * (one generic 401) so callers cannot probe token state. Time comes from an
 * injectable {@link Clock} so tests can cross windows without sleeping.
 */
@Service
public class RefreshTokenService {

    /** Revocation reasons persisted on rows for forensics (never parsed by code). */
    public static final String REASON_LOGOUT = "LOGOUT";
    public static final String REASON_REUSE = "REUSE";
    public static final String REASON_PASSWORD_CHANGE = "PASSWORD_CHANGE";
    public static final String REASON_ADMIN_RESET = "ADMIN_RESET";
    public static final String REASON_USER_DISABLED = "USER_DISABLED";
    public static final String REASON_EVICTED = "EVICTED";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final RefreshTokenRepository repository;
    private final AuditService auditService;
    private final Clock clock;
    private final long refreshExpirationMs;
    private final long reuseGraceSeconds;
    private final int maxSessionsPerUser;

    public RefreshTokenService(
            RefreshTokenRepository repository,
            AuditService auditService,
            Clock clock,
            @Value("${app.jwt.refresh-expiration-ms}") long refreshExpirationMs,
            @Value("${app.jwt.refresh-reuse-grace-seconds}") long reuseGraceSeconds,
            @Value("${app.jwt.max-sessions-per-user}") int maxSessionsPerUser) {
        this.repository = repository;
        this.auditService = auditService;
        this.clock = clock;
        this.refreshExpirationMs = refreshExpirationMs;
        this.reuseGraceSeconds = reuseGraceSeconds;
        this.maxSessionsPerUser = maxSessionsPerUser;
    }

    /** The raw secret handed to a client plus the user it belongs to. */
    public record IssuedToken(User user, String rawToken) {
    }

    /**
     * Starts a new token family for the user (login/register). Also performs
     * opportunistic housekeeping: drops the user's long-expired rows and, when
     * the user is over the session cap, revokes the oldest live sessions.
     */
    @Transactional
    public IssuedToken issue(User user) {
        Instant now = clock.instant();
        repository.deleteExpiredForUser(user, now.minus(Duration.ofDays(7)));
        evictBeyondSessionCap(user, now);
        return mint(user, UUID.randomUUID().toString(), now);
    }

    /**
     * Rotates a presented refresh token: claims it atomically, mints a child in
     * the same family and returns the new raw secret (with the freshly loaded
     * user, so the caller mints the access JWT from the user's CURRENT role).
     *
     * @throws InvalidRefreshTokenException for unknown/expired/revoked/reused
     *                                      tokens or disabled accounts
     */
    // noRollbackFor: the theft and disabled-account paths REVOKE rows and then
    // throw — a default rollback would silently undo exactly that revocation.
    @Transactional(noRollbackFor = InvalidRefreshTokenException.class)
    public IssuedToken rotate(String rawToken) {
        Instant now = clock.instant();
        RefreshToken current = repository.findByTokenHash(hash(rawToken))
                .orElseThrow(InvalidRefreshTokenException::new);
        User user = current.getUser();

        if (current.getRevokedAt() != null) {
            throw new InvalidRefreshTokenException();
        }
        if (current.getExpiresAt().isBefore(now)) {
            throw new InvalidRefreshTokenException();
        }
        if (!user.isEnabled()) {
            repository.revokeFamily(current.getFamilyId(), now, REASON_USER_DISABLED);
            throw new InvalidRefreshTokenException();
        }

        if (current.getUsedAt() != null) {
            return handleUsedToken(current, user, now);
        }

        // Live token: claim it atomically. Losing the claim means a concurrent
        // request rotated it microseconds ago — treat exactly like the benign
        // within-grace retry below (mint a sibling) rather than failing the tab.
        int claimed = repository.markUsed(current.getId(), now);
        if (claimed == 0) {
            return mint(user, current.getFamilyId(), now);
        }
        return mint(user, current.getFamilyId(), now);
    }

    /**
     * A used token was presented again. Within the grace window this is a
     * benign concurrent retry (e.g. two tabs sharing localStorage) and gets a
     * sibling token; beyond it, it is treated as replay of a stolen credential:
     * the whole family is revoked and the event audited.
     */
    private IssuedToken handleUsedToken(RefreshToken current, User user, Instant now) {
        Instant graceLimit = current.getUsedAt().plusSeconds(reuseGraceSeconds);
        if (now.isAfter(graceLimit)) {
            repository.revokeFamily(current.getFamilyId(), now, REASON_REUSE);
            auditService.record(user.getUsername(), AuditAction.AUTH_TOKEN_REUSE, "AUTH",
                    user.getId(), "Refresh-token reuse detected — session family revoked");
            throw new InvalidRefreshTokenException();
        }
        return mint(user, current.getFamilyId(), now);
    }

    /**
     * Revokes the whole family of the presented token (logout). Quietly does
     * nothing for unknown tokens so logout stays idempotent and unleaky.
     *
     * @return the owner when a live family was revoked (for audit), else empty
     */
    @Transactional
    public Optional<User> revokeByRawToken(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        return repository.findByTokenHash(hash(rawToken)).map(token -> {
            // Initialize the lazy user BEFORE the bulk update: revokeFamily
            // clears the persistence context, and the caller (controller, no
            // session) reads the username for the audit record.
            User user = token.getUser();
            user.getUsername();
            int revoked = repository.revokeFamily(token.getFamilyId(), now, REASON_LOGOUT);
            return revoked > 0 ? user : null;
        });
    }

    /** Revokes every session of the user, on every device (lifecycle events). */
    @Transactional
    public void revokeAllForUser(User user, String reason) {
        repository.revokeAllForUser(user, clock.instant(), reason);
    }

    /** Hard-deletes the user's rows; call before deleting the user itself. */
    @Transactional
    public void deleteAllForUser(User user) {
        repository.deleteByUser(user);
    }

    /** Refresh-token lifetime in milliseconds (reported to clients). */
    public long getRefreshExpirationMs() {
        return refreshExpirationMs;
    }

    /** Creates and saves a new token row, returning the raw secret. */
    private IssuedToken mint(User user, String familyId, Instant now) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        repository.save(new RefreshToken(user, hash(raw), familyId,
                now, now.plusMillis(refreshExpirationMs)));
        return new IssuedToken(user, raw);
    }

    /** Keeps at most {@code maxSessionsPerUser} live sessions, evicting oldest. */
    private void evictBeyondSessionCap(User user, Instant now) {
        List<RefreshToken> live = repository.findLiveByUser(user, now);
        int excess = live.size() - (maxSessionsPerUser - 1);
        for (int i = 0; i < excess; i++) {
            repository.revokeFamily(live.get(i).getFamilyId(), now, REASON_EVICTED);
        }
    }

    /** SHA-256 hex of the raw secret — the only form ever persisted. */
    static String hash(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
