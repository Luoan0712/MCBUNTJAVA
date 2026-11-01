package mcbunt.admin.restart;

import java.util.Collection;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongSupplier;

/** Monotonic countdown semantics for SYS-COMMANDS; independent of server tick rate. */
public final class Countdown {
    public enum State { ACTIVE, EXECUTING, CANCELLED }

    public record Tick(int remainingSeconds, boolean announce, boolean execute) {}

    private static final long NANOS_PER_SECOND = 1_000_000_000L;
    private final LongSupplier clock;
    private final long startedAt;
    private final long durationNanos;
    private final Set<Integer> reminders;
    private final Set<Integer> announced = new HashSet<>();
    private State state = State.ACTIVE;

    public Countdown(int seconds, Collection<Integer> reminders, LongSupplier clock) {
        if (seconds < 1 || seconds > 86_400) {
            throw new IllegalArgumentException("重启秒数必须在 1～86400 之间");
        }
        this.clock = Objects.requireNonNull(clock, "clock");
        this.reminders = new HashSet<>(Objects.requireNonNull(reminders, "reminders"));
        this.startedAt = clock.getAsLong();
        this.durationNanos = seconds * NANOS_PER_SECOND;
        announced.add(seconds); // The schedule announcement already covers this second.
    }

    /** Caller serializes access; the service uses the main server thread. */
    public Tick poll() {
        if (state != State.ACTIVE) {
            return new Tick(0, false, false);
        }
        // nanoTime subtraction also works across its signed-long wraparound.
        long elapsed = Math.max(0L, clock.getAsLong() - startedAt);
        long remaining = Math.max(0L, durationNanos - elapsed);
        int seconds = (int) ((remaining + NANOS_PER_SECOND - 1L) / NANOS_PER_SECOND);
        if (seconds == 0) {
            state = State.EXECUTING;
            return new Tick(0, false, true);
        }
        boolean announce = reminders.contains(seconds) && announced.add(seconds);
        return new Tick(seconds, announce, false);
    }

    public boolean cancel() {
        if (state != State.ACTIVE) {
            return false;
        }
        state = State.CANCELLED;
        return true;
    }

    public State state() {
        return state;
    }
}
