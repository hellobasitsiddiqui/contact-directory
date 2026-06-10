package com.example.contacts.repository;

import com.example.contacts.model.RefreshToken;
import com.example.contacts.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Persistence for {@link RefreshToken} rows (CD-028). Lookups are always by the
 * SHA-256 hash of the presented secret; mutation queries are written as bulk
 * {@code @Modifying} statements so rotation can claim a token atomically.
 */
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * Atomically claims a token for rotation: marks it used only if it is still
     * unused <em>and not revoked</em>. The {@code revokedAt} guard closes a race
     * where a concurrent {@code revokeFamily}/{@code revokeAllForUser} commits
     * between rotate()'s snapshot read and the claim — without it, the claim
     * would succeed and mint a child that escapes the revocation. Returns the
     * number of rows updated: {@code 1} = claim won; {@code 0} = the token was
     * concurrently used or revoked (the caller re-reads to tell which).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update RefreshToken t set t.usedAt = :now "
            + "where t.id = :id and t.usedAt is null and t.revokedAt is null")
    int markUsed(@Param("id") Long id, @Param("now") Instant now);

    /** Revokes every not-yet-revoked token in a family (rotation lineage). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update RefreshToken t set t.revokedAt = :now, t.revokeReason = :reason "
            + "where t.familyId = :familyId and t.revokedAt is null")
    int revokeFamily(@Param("familyId") String familyId,
                     @Param("now") Instant now,
                     @Param("reason") String reason);

    /** Revokes every not-yet-revoked token belonging to a user (all devices). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update RefreshToken t set t.revokedAt = :now, t.revokeReason = :reason "
            + "where t.user = :user and t.revokedAt is null")
    int revokeAllForUser(@Param("user") User user,
                         @Param("now") Instant now,
                         @Param("reason") String reason);

    /** Hard-deletes a user's rows (used before deleting the user; H2 has no FK cascade). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from RefreshToken t where t.user = :user")
    int deleteByUser(@Param("user") User user);

    /** Housekeeping: drops rows that expired before the given cutoff. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from RefreshToken t where t.user = :user and t.expiresAt < :cutoff")
    int deleteExpiredForUser(@Param("user") User user, @Param("cutoff") Instant cutoff);

    /** Live (usable) tokens for a user: not used, not revoked, not expired. */
    @Query("select t from RefreshToken t where t.user = :user and t.usedAt is null "
            + "and t.revokedAt is null and t.expiresAt >= :now order by t.issuedAt asc")
    List<RefreshToken> findLiveByUser(@Param("user") User user, @Param("now") Instant now);
}
