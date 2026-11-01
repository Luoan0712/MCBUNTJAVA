package mcbunt.admin.moderation;

import java.util.Objects;
import java.util.UUID;

/** A server-known player identity, never derived from a connection address. */
public record Identity(UUID uuid, String name) {
    public Identity {
        Objects.requireNonNull(uuid, "uuid");
        name = name == null || name.isBlank() ? uuid.toString() : name;
    }
}
