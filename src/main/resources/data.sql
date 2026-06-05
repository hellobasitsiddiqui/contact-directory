-- Sample seed data for the Contact Directory.
-- Loaded by Spring Boot on startup (spring.sql.init.mode=always) after
-- Hibernate creates the schema (spring.jpa.defer-datasource-initialization=true).

INSERT INTO contacts (first_name, last_name, email, phone, company, created_at, updated_at)
VALUES ('Jane', 'Doe', 'jane.doe@example.com', '+1 (555) 123-4567', 'Acme Ltd', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

INSERT INTO contacts (first_name, last_name, email, phone, company, created_at, updated_at)
VALUES ('John', 'Smith', 'john.smith@example.com', '+44 20 7946 0958', 'Globex Corp', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

INSERT INTO contacts (first_name, last_name, email, phone, company, created_at, updated_at)
VALUES ('Maria', 'Garcia', 'maria.garcia@example.com', NULL, 'Initech', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- Sample tags (contact ids 1..3 from the inserts above, IDENTITY-generated).
INSERT INTO contact_tags (contact_id, tag) VALUES (1, 'Work');
INSERT INTO contact_tags (contact_id, tag) VALUES (1, 'Client');
INSERT INTO contact_tags (contact_id, tag) VALUES (2, 'Friend');
INSERT INTO contact_tags (contact_id, tag) VALUES (3, 'Family');
