package mcbunt.admin.audit;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import mcbunt.admin.core.Actor;
import mcbunt.admin.core.Outcome;

/** A business result; construction does not claim that the event is on disk. */
public record AuditEvent(String action, UUID operationId, Actor actor, Map<String, Object> fields) {
    private static final Set<String> RESERVED =
            Set.of("timestamp", "action", "operationId", "actor", "source");

    public AuditEvent {
        if (Objects.requireNonNull(action, "action").isBlank()) {
            throw new IllegalArgumentException("action must not be blank");
        }
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(fields, "fields");
        var copy = new LinkedHashMap<String, Object>();
        fields.forEach((key, value) -> {
            if (key == null || RESERVED.contains(key)) {
                throw new IllegalArgumentException("Reserved or null audit field: " + key);
            }
            copy.put(key, value);
        });
        fields = Collections.unmodifiableMap(copy);
    }

    public static AuditEvent of(String action, UUID operationId, Actor actor, Outcome result) {
        return new AuditEvent(action, operationId, actor,
                Map.of("result", Objects.requireNonNull(result, "result").name()));
    }

    public AuditEvent with(String key, Object value) {
        var copy = new LinkedHashMap<>(fields);
        copy.put(key, value);
        return new AuditEvent(action, operationId, actor, copy);
    }
}
