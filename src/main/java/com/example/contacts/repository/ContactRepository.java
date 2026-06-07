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

/**
 * Spring Data JPA repository for {@link Contact} entities.
 */
@Repository
public interface ContactRepository extends JpaRepository<Contact, Long> {

    /**
     * Checks whether a contact with the given email already exists, ignoring case.
     *
     * @param email the email address to check
     * @return {@code true} if a matching contact exists
     */
    boolean existsByEmailIgnoreCase(String email);

    /**
     * Checks whether a contact other than the one with the given id uses the given
     * email, ignoring case. Used to enforce email uniqueness on update.
     *
     * @param email the email address to check
     * @param id    the id of the contact to exclude from the check
     * @return {@code true} if a different contact already uses the email
     */
    boolean existsByEmailIgnoreCaseAndIdNot(String email, Long id);

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
     * Returns all distinct tags currently in use, sorted case-insensitively.
     * Used to populate the tag filter control.
     *
     * @return the sorted list of distinct tag labels
     */
    @Query("SELECT DISTINCT t FROM Contact c JOIN c.tags t WHERE c.deletedAt IS NULL ORDER BY LOWER(t)")
    List<String> findDistinctTags();

    /**
     * Returns a page of active contacts (those whose {@code deletedAt} is
     * {@code null}). Used for the default list view, which excludes trashed rows.
     *
     * @param pageable pagination and sorting information
     * @return a page of active contacts
     */
    Page<Contact> findByDeletedAtIsNull(Pageable pageable);

    /**
     * Returns a page of soft-deleted contacts (those whose {@code deletedAt} is
     * non-null). Used to populate the Trash view.
     *
     * @param pageable pagination and sorting information
     * @return a page of soft-deleted contacts
     */
    Page<Contact> findByDeletedAtIsNotNull(Pageable pageable);

    /**
     * Returns every active contact (those whose {@code deletedAt} is
     * {@code null}), sorted as requested. Used for full exports, which must
     * exclude trashed rows.
     *
     * @param sort the sort order to apply
     * @return all active contacts in the requested order
     */
    List<Contact> findByDeletedAtIsNull(Sort sort);
}
