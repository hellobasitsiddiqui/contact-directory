-- Sample seed data for the Contact Directory (loaded only under the test profile,
-- where spring.sql.init.mode=always). The main application uses
-- spring.sql.init.mode=never, so this file is a no-op there; the admin account
-- and any sample contacts for the running app are created by DataInitializer.
--
-- Loaded by Spring Boot on startup after Hibernate creates the schema
-- (spring.jpa.defer-datasource-initialization=true).

-- A non-admin USER owning all the seed contacts. Integration tests authenticate
-- with @WithMockUser whose default username is "user"; the controller resolves
-- the owner id from that username, so a matching DB user must exist and own the
-- seed data for the USER-scoped assertions to hold. The admin row is created by
-- DataInitializer, not here. The password hash is a valid BCrypt hash (for
-- "password"); it is unused because the tests stub authentication.
INSERT INTO users (id, username, password, role, enabled, created_at)
VALUES (1000, 'user', '$2a$10$vz4q6OpzMe7ChITF6Av0l.4/t.0wouRATcJODTtpE9sQvW3XkHiUu', 'USER', TRUE, CURRENT_TIMESTAMP);

INSERT INTO contacts (owner_id, first_name, last_name, email, phone, company, favorite, created_at, updated_at, version)
VALUES (1000, 'Jane', 'Doe', 'jane.doe@example.com', '+1 (555) 123-4567', 'Acme Ltd', FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0);

INSERT INTO contacts (owner_id, first_name, last_name, email, phone, company, favorite, created_at, updated_at, version)
VALUES (1000, 'John', 'Smith', 'john.smith@example.com', '+44 20 7946 0958', 'Globex Corp', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0);

INSERT INTO contacts (owner_id, first_name, last_name, email, phone, company, favorite, created_at, updated_at, version)
VALUES (1000, 'Maria', 'Garcia', 'maria.garcia@example.com', NULL, 'Initech', FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0);

-- Sample tags (contact ids 1..3 from the inserts above, IDENTITY-generated).
INSERT INTO contact_tags (contact_id, tag) VALUES (1, 'Work');
INSERT INTO contact_tags (contact_id, tag) VALUES (1, 'Client');
INSERT INTO contact_tags (contact_id, tag) VALUES (2, 'Friend');
INSERT INTO contact_tags (contact_id, tag) VALUES (3, 'Family');
