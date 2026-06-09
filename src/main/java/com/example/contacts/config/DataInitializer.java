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
 * any contact left without an owner is backfilled to the admin. On a brand-new
 * directory it seeds a couple of sample {@code USER} accounts and gives them the
 * sample contacts — the admin deliberately owns no contacts, so the admin role
 * is about managing users, and every user owns their own contacts.
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
        seedSampleDataIfEmpty();
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
     * On a brand-new directory (no contacts and no non-admin users) seeds a
     * couple of sample {@code USER} accounts and gives them the sample contacts,
     * so the admin's user-management screen and the contact directory both have
     * something to show. The admin owns none of these — every contact belongs to
     * the user who would manage it. Idempotent: skips if any contact or any
     * non-admin user already exists (e.g. the test profile's {@code data.sql}).
     */
    private void seedSampleDataIfEmpty() {
        if (contactRepository.count() > 0 || userRepository.countByRole(Role.USER) > 0) {
            return;
        }
        User alice = createSampleUser("alice", "alice123");
        User bob = createSampleUser("bob", "bob123");
        contactRepository.saveAll(List.of(
                sampleContact(alice.getId(), "Jane", "Doe", "jane.doe@example.com",
                        "+1 (555) 123-4567", "Acme Ltd", false, Set.of("Work", "Client")),
                sampleContact(alice.getId(), "John", "Smith", "john.smith@example.com",
                        "+44 20 7946 0958", "Globex Corp", true, Set.of("Friend")),
                sampleContact(bob.getId(), "Maria", "Garcia", "maria.garcia@example.com",
                        null, "Initech", false, Set.of("Family"))));
        log.info("Seeded sample users 'alice' (2 contacts) and 'bob' (1 contact). "
                + "These are dev-only accounts — change or remove them before deploying.");
    }

    /**
     * Returns the sample {@code USER} account, creating it on first use.
     * Idempotent on the username so a re-run can never hit the unique-username
     * constraint (e.g. the contrived case where the sample users were promoted
     * and the directory emptied), which would otherwise crash startup.
     *
     * @param username the login name
     * @param password the dev-only plaintext password (stored BCrypt-hashed)
     * @return the existing or newly-created user
     */
    private User createSampleUser(String username, String password) {
        return userRepository.findByUsername(username).orElseGet(() -> {
            User user = userRepository.save(
                    new User(username, passwordEncoder.encode(password), Role.USER));
            log.warn("Seeded sample USER '{}' (dev only).", username);
            return user;
        });
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
