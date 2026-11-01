package mcbunt.admin.moderation;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable snapshot used by the asynchronous chat listener. */
public record MuteRecord(UUID uuid, String name, Instant expiresAt, String reason,
                         String actor, Instant createdAt) {
    public MuteRecord {
        Objects.requireNonNull(uuid, "uuid");
        requireText(name, "name");
        requireText(reason, "reason");
        requireText(actor, "actor");
        Objects.requireNonNull(createdAt, "createdAt");
        if (createdAt.isBefore(Instant.EPOCH)) {
            throw new IllegalArgumentException("createdAt must not be before epoch");
        }
        if (expiresAt != null && !expiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException("expiresAt must follow createdAt");
        }
    }

    public boolean activeAt(Instant now) {
        return expiresAt == null || expiresAt.isAfter(now);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
