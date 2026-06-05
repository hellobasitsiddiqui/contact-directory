package com.example.contacts.repository;

import com.example.contacts.model.Contact;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

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
        WHERE LOWER(c.firstName) LIKE LOWER(CONCAT('%', :q, '%'))
           OR LOWER(c.lastName)  LIKE LOWER(CONCAT('%', :q, '%'))
           OR LOWER(c.email)     LIKE LOWER(CONCAT('%', :q, '%'))
           OR LOWER(COALESCE(c.company, '')) LIKE LOWER(CONCAT('%', :q, '%'))
           OR LOWER(COALESCE(c.phone, ''))   LIKE LOWER(CONCAT('%', :q, '%'))
        """)
    Page<Contact> search(@Param("q") String q, Pageable pageable);
}
