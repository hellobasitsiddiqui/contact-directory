package com.example.contacts.model;

import jakarta.persistence.Basic;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * JPA entity representing a single contact in the Contact Directory.
 *
 * <p>Timestamps are managed automatically via JPA lifecycle callbacks:
 * {@link #onCreate()} sets both {@code createdAt} and {@code updatedAt} on insert,
 * while {@link #onUpdate()} refreshes {@code updatedAt} on every update.
 */
@Entity
@Table(name = "contacts")
public class Contact {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Id of the {@link User} who owns this contact. Every contact is owned by the
     * user who created it; non-admin users may only see and act on their own
     * contacts. Admins operate unscoped across all owners.
     */
    @Column(name = "owner_id", nullable = false)
    private Long ownerId;

    @Column(nullable = false)
    private String firstName;

    @Column(nullable = false)
    private String lastName;

    @Column(nullable = false)
    private String email;

    private String phone;

    private String company;

    // Stored inline as PostgreSQL `bytea` (VARBINARY), NOT a large object (`oid`).
    // `oid` LOBs need explicit transactions and fail in auto-commit mode; bytea
    // materialises the bytes inline — correct for these small (<=2MB) photos. The
    // length sizes the H2 column generously; Postgres `bytea` ignores it.
    @JdbcTypeCode(SqlTypes.VARBINARY)
    @Basic(fetch = FetchType.LAZY)
    @Column(name = "photo", length = 10 * 1024 * 1024)
    private byte[] photo;

    @Column(name = "photo_content_type")
    private String photoContentType;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "contact_tags", joinColumns = @JoinColumn(name = "contact_id"))
    @Column(name = "tag")
    private Set<String> tags = new LinkedHashSet<>();

    @Column(nullable = false)
    @ColumnDefault("false")
    private boolean favorite;

    @Column(length = 4000)
    private String notes;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    /**
     * Timestamp marking when this contact was soft-deleted (moved to trash).
     * {@code null} means the contact is active. Set explicitly by the service
     * on soft delete / cleared on restore; not managed by lifecycle callbacks.
     */
    @Column(name = "deleted_at")
    private Instant deletedAt;

    /**
     * Optimistic-locking version. Managed by JPA; new rows start at {@code 0}
     * and the value is incremented on each dirty flush. The column is generated
     * NOT NULL, so {@link ColumnDefault} gives it a DB-level default of 0 — this
     * keeps inserts that omit the column (e.g. seed SQL) valid.
     */
    @Version
    @ColumnDefault("0")
    private long version;

    /**
     * Creates an empty contact. Required by JPA.
     */
    public Contact() {
    }

    /**
     * Initialises creation and update timestamps before the entity is first persisted.
     */
    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    /**
     * Refreshes the update timestamp before the entity is updated.
     */
    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(Long ownerId) {
        this.ownerId = ownerId;
    }

    public String getFirstName() {
        return firstName;
    }

    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public void setLastName(String lastName) {
        this.lastName = lastName;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getCompany() {
        return company;
    }

    public void setCompany(String company) {
        this.company = company;
    }

    public byte[] getPhoto() {
        return photo;
    }

    public void setPhoto(byte[] photo) {
        this.photo = photo;
    }

    public String getPhotoContentType() {
        return photoContentType;
    }

    public void setPhotoContentType(String photoContentType) {
        this.photoContentType = photoContentType;
    }

    public Set<String> getTags() {
        return tags;
    }

    /**
     * Replaces the contact's tags with the given set, keeping the managed
     * collection instance (clear + add) so JPA tracks the change correctly.
     * A {@code null} argument clears all tags.
     *
     * @param tags the new set of tags, or {@code null} to clear
     */
    public void setTags(Set<String> tags) {
        this.tags.clear();
        if (tags != null) {
            this.tags.addAll(tags);
        }
    }

    public boolean isFavorite() {
        return favorite;
    }

    public void setFavorite(boolean favorite) {
        this.favorite = favorite;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(Instant deletedAt) {
        this.deletedAt = deletedAt;
    }

    public long getVersion() {
        return version;
    }

    public void setVersion(long version) {
        this.version = version;
    }
}
