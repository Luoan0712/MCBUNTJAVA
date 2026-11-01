package mcbunt.admin.core;

import java.time.Instant;
import java.util.UUID;

public record Result(Outcome outcome, String message, UUID targetUuid, String targetName, Instant expiresAt) {
    public static Result of(Outcome outcome, String message) {
        return new Result(outcome, message, null, null, null);
    }
    public boolean applied() { return outcome == Outcome.APPLIED; }
}
