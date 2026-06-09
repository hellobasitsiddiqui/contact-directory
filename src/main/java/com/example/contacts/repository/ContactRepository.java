package com.example.contacts.repository;

import com.example.contacts.model.Contact;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for {@link Contact} entities.
 */
@Repository
public interface ContactRepository extends JpaRepository<Contact, Long> {

    /**
     * Checks whether the given owner already has a contact with the given email,
     * ignoring case. Used to enforce per-owner email uniqueness on create.
     *
     * @param email   the email address to check
     * @param ownerId the id of the owner to scope the check to
     * @return {@code true} if the owner already has a matching contact
     */
    boolean existsByEmailIgnoreCaseAndOwnerId(String email, Long ownerId);

    /**
     * Checks whether the given owner has a contact other than the one with the
     * given id using the given email, ignoring case. Used to enforce per-owner
     * email uniqueness on update.
     *
     * @param email   the email address to check
     * @param ownerId the id of the owner to scope the check to
     * @param id      the id of the contact to exclude from the check
     * @return {@code true} if a different contact of the owner already uses the email
     */
    boolean existsByEmailIgnoreCaseAndOwnerIdAndIdNot(String email, Long ownerId, Long id);

    /**
     * Finds a contact by id, scoped to a single owner. A contact owned by another
     * user is treated as missing, so callers can return 404 without revealing
     * its existence.
     *
     * @param id      the contact id
     * @param ownerId the id of the owner the contact must belong to
     * @return the matching contact, or empty if none exists for that owner
     */
    Optional<Contact> findByIdAndOwnerId(Long id, Long ownerId);

    /**
     * Full-text style search across first name, last name, email, company and phone.
     * The match is case-insensitive and substring-based; {@code null} company and
     * phone values are treated as empty strings.
     *
     * @param q        the search term
     * @param pageable pagination and sorting information
     * @return a page of matching contacts
     */
    @Query("""
        SELECT c FROM Contact c
        WHERE c.deletedAt IS NULL
          AND (LOWER(c.firstName) LIKE LOWER(CONCAT('%', :q, '%'))
           OR LOWER(c.lastName)  LIKE LOWER(CONCAT('%', :q, '%'))
           OR LOWER(c.email)     LIKE LOWER(CONCAT('%', :q, '%'))
           OR LOWER(COALESCE(c.company, '')) LIKE LOWER(CONCAT('%', :q, '%'))
           OR LOWER(COALESCE(c.phone, ''))   LIKE LOWER(CONCAT('%', :q, '%')))
        """)
    Page<Contact> search(@Param("q") String q, Pageable pageable);

    /**
     * Owner-scoped variant of {@link #search(String, Pageable)} restricting the
     * results to contacts owned by the given user.
     *
     * @param q        the search term
     * @param ownerId  the id of the owner to scope results to
     * @param pageable pagination and sorting information
     * @return a page of the owner's matching contacts
     */
    @Query("""
        SELECT c FROM Contact c
        WHERE c.deletedAt IS NULL
          AND c.ownerId = :ownerId
          AND (LOWER(c.firstName) LIKE LOWER(CONCAT('%', :q, '%'))
           OR LOWER(c.lastName)  LIKE LOWER(CONCAT('%', :q, '%'))
           OR LOWER(c.email)     LIKE LOWER(CONCAT('%', :q, '%'))
           OR LOWER(COALESCE(c.company, '')) LIKE LOWER(CONCAT('%', :q, '%'))
           OR LOWER(COALESCE(c.phone, ''))   LIKE LOWER(CONCAT('%', :q, '%')))
        """)
    Page<Contact> search(@Param("q") String q, @Param("ownerId") Long ownerId, Pageable pageable);

    /**
     * Returns contacts carrying the given tag (case-insensitive), optionally
     * further narrowed by a free-text term. Pass an empty string for {@code q}
     * to filter by tag alone.
     *
     * @param q        the free-text term, or {@code ""} to match all
     * @param tag      the tag to require (case-insensitive)
     * @param pageable pagination and sorting information
     * @return a page of contacts that have the tag and match the term
     */
    @Query("""
        SELECT c FROM Contact c
        WHERE c.deletedAt IS NULL
          AND EXISTS (SELECT t FROM c.tags t WHERE LOWER(t) = LOWER(:tag))
          AND (:q = ''
               OR LOWER(c.firstName) LIKE LOWER(CONCAT('%', :q, '%'))
               OR LOWER(c.lastName)  LIKE LOWER(CONCAT('%', :q, '%'))
               OR LOWER(c.email)     LIKE LOWER(CONCAT('%', :q, '%'))
               OR LOWER(COALESCE(c.company, '')) LIKE LOWER(CONCAT('%', :q, '%'))
               OR LOWER(COALESCE(c.phone, ''))   LIKE LOWER(CONCAT('%', :q, '%')))
        """)
    Page<Contact> searchAndFilterByTag(@Param("q") String q, @Param("tag") String tag, Pageable pageable);

    /**
     * Owner-scoped variant of {@link #searchAndFilterByTag(String, String, Pageable)}
     * restricting the results to contacts owned by the given user.
     *
     * @param q        the free-text term, or {@code ""} to match all
     * @param tag      the tag to require (case-insensitive)
     * @param ownerId  the id of the owner to scope results to
     * @param pageable pagination and sorting information
     * @return a page of the owner's contacts that have the tag and match the term
     */
    @Query("""
        SELECT c FROM Contact c
        WHERE c.deletedAt IS NULL
          AND c.ownerId = :ownerId
          AND EXISTS (SELECT t FROM c.tags t WHERE LOWER(t) = LOWER(:tag))
          AND (:q = ''
               OR LOWER(c.firstName) LIKE LOWER(CONCAT('%', :q, '%'))
               OR LOWER(c.lastName)  LIKE LOWER(CONCAT('%', :q, '%'))
               OR LOWER(c.email)     LIKE LOWER(CONCAT('%', :q, '%'))
               OR LOWER(COALESCE(c.company, '')) LIKE LOWER(CONCAT('%', :q, '%'))
               OR LOWER(COALESCE(c.phone, ''))   LIKE LOWER(CONCAT('%', :q, '%')))
        """)
    Page<Contact> searchAndFilterByTag(@Param("q") String q, @Param("tag") String tag,
                                       @Param("ownerId") Long ownerId, Pageable pageable);

    /**
     * Returns all distinct tags currently in use. The caller sorts
     * (case-insensitively) — Postgres rejects {@code SELECT DISTINCT} combined
     * with an {@code ORDER BY LOWER(t)} that isn't in the select list, so the
     * ordering is applied in {@code ContactService#listTags} for portability.
     *
     * @return the distinct tag labels (unordered)
     */
    @Query("SELECT DISTINCT t FROM Contact c JOIN c.tags t WHERE c.deletedAt IS NULL")
    List<String> findDistinctTags();

    /**
     * Owner-scoped variant of {@link #findDistinctTags()} returning only the
     * distinct tags in use across the given owner's contacts. Ordering is applied
     * by the caller (see {@link #findDistinctTags()}).
     *
     * @param ownerId the id of the owner to scope tags to
     * @return the distinct tag labels for that owner (unordered)
     */
    @Query("""
        SELECT DISTINCT t FROM Contact c JOIN c.tags t
        WHERE c.deletedAt IS NULL AND c.ownerId = :ownerId
        """)
    List<String> findDistinctTagsByOwnerId(@Param("ownerId") Long ownerId);

    /**
     * Returns a page of active contacts (those whose {@code deletedAt} is
     * {@code null}). Used for the default list view, which excludes trashed rows.
     *
     * @param pageable pagination and sorting information
     * @return a page of active contacts
     */
    Page<Contact> findByDeletedAtIsNull(Pageable pageable);

    /**
     * Owner-scoped variant of {@link #findByDeletedAtIsNull(Pageable)} returning
     * only the active contacts owned by the given user.
     *
     * @param ownerId  the id of the owner to scope results to
     * @param pageable pagination and sorting information
     * @return a page of the owner's active contacts
     */
    Page<Contact> findByDeletedAtIsNullAndOwnerId(Long ownerId, Pageable pageable);

    /**
     * Returns a page of soft-deleted contacts (those whose {@code deletedAt} is
     * non-null). Used to populate the Trash view.
     *
     * @param pageable pagination and sorting information
     * @return a page of soft-deleted contacts
     */
    Page<Contact> findByDeletedAtIsNotNull(Pageable pageable);

    /**
     * Owner-scoped variant of {@link #findByDeletedAtIsNotNull(Pageable)}
     * returning only the soft-deleted contacts owned by the given user.
     *
     * @param ownerId  the id of the owner to scope results to
     * @param pageable pagination and sorting information
     * @return a page of the owner's soft-deleted contacts
     */
    Page<Contact> findByDeletedAtIsNotNullAndOwnerId(Long ownerId, Pageable pageable);

    /**
     * Returns every active contact (those whose {@code deletedAt} is
     * {@code null}), sorted as requested. Used for full exports, which must
     * exclude trashed rows.
     *
     * @param sort the sort order to apply
     * @return all active contacts in the requested order
     */
    List<Contact> findByDeletedAtIsNull(Sort sort);

    /**
     * Owner-scoped variant of {@link #findByDeletedAtIsNull(Sort)} returning only
     * the active contacts owned by the given user, sorted as requested.
     *
     * @param ownerId the id of the owner to scope results to
     * @param sort    the sort order to apply
     * @return all of the owner's active contacts in the requested order
     */
    List<Contact> findByDeletedAtIsNullAndOwnerId(Long ownerId, Sort sort);
}
