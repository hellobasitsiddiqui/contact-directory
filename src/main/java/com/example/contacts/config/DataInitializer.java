package com.example.contacts.config;

import com.example.contacts.model.Contact;
import com.example.contacts.model.Role;
import com.example.contacts.model.User;
import com.example.contacts.repository.ContactRepository;
import com.example.contacts.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * Seeds a default administrator account on first run when no admin with the
 * configured username exists, so the application is immediately usable.
 * Credentials are configurable via {@code app.default-admin.username} /
 * {@code app.default-admin.password}.
 *
 * <p>The default password is intended for local/dev use only and should be
 * overridden (or the account changed) before any real deployment.
 *
 * <p>Once the admin is in place this runner also normalises contact ownership:
 * any contact left without an owner is backfilled to the admin, and an empty
 * directory is seeded with a few sample contacts owned by the admin.
 */
@Component
public class DataInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    private final UserRepository userRepository;
    private final ContactRepository contactRepository;
    private final PasswordEncoder passwordEncoder;
    private final String adminUsername;
    private final String adminPassword;

    public DataInitializer(UserRepository userRepository,
                           ContactRepository contactRepository,
                           PasswordEncoder passwordEncoder,
                           @Value("${app.default-admin.username:admin}") String adminUsername,
                           @Value("${app.default-admin.password:admin123}") String adminPassword) {
        this.userRepository = userRepository;
        this.contactRepository = contactRepository;
        this.passwordEncoder = passwordEncoder;
        this.adminUsername = adminUsername;
        this.adminPassword = adminPassword;
    }

    @Override
    public void run(String... args) {
        User admin = userRepository.findByUsername(adminUsername)
                .orElseGet(this::createAdmin);

        backfillOwnerless(admin.getId());
        seedSampleContactsIfEmpty(admin.getId());
    }

    /**
     * Creates and persists the default admin account.
     *
     * @return the saved admin user
     */
    private User createAdmin() {
        User admin = new User(
                adminUsername,
                passwordEncoder.encode(adminPassword),
                Role.ADMIN);
        User saved = userRepository.save(admin);
        log.warn("No admin account found - created default admin '{}'. "
                + "Change this password before deploying.", adminUsername);
        return saved;
    }

    /**
     * Assigns any contact that has no owner to the admin, so legacy rows created
     * before per-user ownership remain accessible (to the admin) and satisfy the
     * NOT NULL owner constraint.
     *
     * @param adminId the id to assign ownerless contacts to
     */
    private void backfillOwnerless(Long adminId) {
        List<Contact> ownerless = contactRepository.findAll().stream()
                .filter(c -> c.getOwnerId() == null)
                .toList();
        if (ownerless.isEmpty()) {
            return;
        }
        for (Contact contact : ownerless) {
            contact.setOwnerId(adminId);
        }
        contactRepository.saveAll(ownerless);
        log.info("Backfilled owner_id on {} contact(s) to admin id {}.", ownerless.size(), adminId);
    }

    /**
     * Seeds a small set of sample contacts owned by the admin when the directory
     * is completely empty, mirroring the historical seed data.
     *
     * @param adminId the id that will own the sample contacts
     */
    private void seedSampleContactsIfEmpty(Long adminId) {
        if (contactRepository.count() > 0) {
            return;
        }
        contactRepository.saveAll(List.of(
                sampleContact(adminId, "Jane", "Doe", "jane.doe@example.com",
                        "+1 (555) 123-4567", "Acme Ltd", false, Set.of("Work", "Client")),
                sampleContact(adminId, "John", "Smith", "john.smith@example.com",
                        "+44 20 7946 0958", "Globex Corp", true, Set.of("Friend")),
                sampleContact(adminId, "Maria", "Garcia", "maria.garcia@example.com",
                        null, "Initech", false, Set.of("Family"))));
        log.info("Seeded 3 sample contacts owned by admin id {}.", adminId);
    }

    /**
     * Builds a sample contact owned by the given user.
     */
    private Contact sampleContact(Long ownerId, String firstName, String lastName, String email,
                                  String phone, String company, boolean favorite, Set<String> tags) {
        Contact contact = new Contact();
        contact.setOwnerId(ownerId);
        contact.setFirstName(firstName);
        contact.setLastName(lastName);
        contact.setEmail(email);
        contact.setPhone(phone);
        contact.setCompany(company);
        contact.setFavorite(favorite);
        contact.setTags(tags);
        return contact;
    }
}
