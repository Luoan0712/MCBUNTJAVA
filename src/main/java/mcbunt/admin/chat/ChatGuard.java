package mcbunt.admin.chat;

import java.text.Normalizer;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/** The per-player public-chat policy from SYS-CHAT; has no server or I/O dependencies. */
public final class ChatGuard {
    public enum Decision { ALLOW, FREQUENCY, DUPLICATE }

    private final long windowNanos;
    private final int maximumMessages;
    private final boolean checkDuplicates;
    private final ConcurrentHashMap<UUID, Window> windows = new ConcurrentHashMap<>();

    public ChatGuard(Duration window, int maximumMessages, boolean checkDuplicates) {
        Objects.requireNonNull(window, "window");
        this.windowNanos = window.toNanos();
        if (windowNanos <= 0 || maximumMessages <= 0) {
            throw new IllegalArgumentException("Chat window and message limit must be positive");
        }
        this.maximumMessages = maximumMessages;
        this.checkDuplicates = checkDuplicates;
    }

    /** Atomically checks and, only when allowed, records a message for this UUID. */
    public Decision check(UUID playerId, String message, long nowNanos) {
        Objects.requireNonNull(playerId, "playerId");
        String normalized = normalize(Objects.requireNonNull(message, "message"));
        AtomicReference<Decision> decision = new AtomicReference<>();
        windows.compute(playerId, (ignored, previous) -> {
            Window window = previous == null ? new Window() : previous;
            expire(window, nowNanos);
            if (window.accepted.size() >= maximumMessages) {
                decision.set(Decision.FREQUENCY);
            } else if (checkDuplicates && normalized.equals(window.lastAccepted)) {
                decision.set(Decision.DUPLICATE);
            } else {
                window.accepted.addLast(nowNanos);
                window.lastAccepted = normalized;
                decision.set(Decision.ALLOW);
            }
            return window;
        });
        return decision.get();
    }

    /** A disconnect never clears an unexpired window; only expired offline entries are removed. */
    public void cleanupOffline(Set<UUID> onlinePlayers, long nowNanos) {
        Objects.requireNonNull(onlinePlayers, "onlinePlayers");
        for (UUID playerId : windows.keySet()) {
            if (!onlinePlayers.contains(playerId)) {
                windows.computeIfPresent(playerId, (ignored, window) -> {
                    expire(window, nowNanos);
                    return window.accepted.isEmpty() ? null : window;
                });
            }
        }
    }

    int trackedPlayers() {
        return windows.size();
    }

    private void expire(Window window, long nowNanos) {
        while (!window.accepted.isEmpty()
                && nowNanos - window.accepted.peekFirst() >= windowNanos) {
            window.accepted.removeFirst();
        }
        if (window.accepted.isEmpty()) {
            window.lastAccepted = null;
        }
    }

    public static String normalize(String original) {
        String compatibility = Normalizer.normalize(original, Normalizer.Form.NFKC);
        StringBuilder result = new StringBuilder(compatibility.length());
        boolean spacePending = false;
        for (int index = 0; index < compatibility.length();) {
            int point = compatibility.codePointAt(index);
            index += Character.charCount(point);
            if (Character.isWhitespace(point) || Character.isSpaceChar(point)) {
                spacePending = result.length() > 0;
            } else {
                if (spacePending) {
                    result.append(' ');
                    spacePending = false;
                }
                result.appendCodePoint(point);
            }
        }
        return result.toString().toLowerCase(Locale.ROOT);
    }

    private static final class Window {
        private final ArrayDeque<Long> accepted = new ArrayDeque<>();
        private String lastAccepted;
    }
}
