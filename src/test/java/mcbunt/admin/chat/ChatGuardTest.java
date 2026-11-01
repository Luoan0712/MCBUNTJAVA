package mcbunt.admin.chat;

import static mcbunt.admin.chat.ChatGuard.Decision.ALLOW;
import static mcbunt.admin.chat.ChatGuard.Decision.DUPLICATE;
import static mcbunt.admin.chat.ChatGuard.Decision.FREQUENCY;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class ChatGuardTest {
    private static final long SECOND = 1_000_000_000L;

    @Test
    void fifthMessageIsBlockedAndExactLeftBoundaryExpires() {
        ChatGuard guard = new ChatGuard(Duration.ofSeconds(5), 4, false);
        UUID player = UUID.randomUUID();
        for (int i = 0; i < 4; i++) {
            assertEquals(ALLOW, guard.check(player, "message " + i, i * SECOND));
        }
        assertEquals(FREQUENCY, guard.check(player, "blocked", 4 * SECOND));
        assertEquals(ALLOW, guard.check(player, "boundary", 5 * SECOND));
    }

    @Test
    void rejectedAttemptsDoNotExtendWindowOrChangeDuplicateReference() {
        ChatGuard guard = new ChatGuard(Duration.ofSeconds(5), 4, true);
        UUID player = UUID.randomUUID();
        assertEquals(ALLOW, guard.check(player, "A", 0));
        assertEquals(DUPLICATE, guard.check(player, "a", 4 * SECOND));
        assertEquals(ALLOW, guard.check(player, "A", 5 * SECOND));
        assertEquals(ALLOW, guard.check(player, "B", 6 * SECOND));
        assertEquals(ALLOW, guard.check(player, "A", 7 * SECOND));
    }

    @Test
    void frequencyTakesPrecedenceOverDuplicateAndDoesNotConsumeCapacity() {
        ChatGuard guard = new ChatGuard(Duration.ofSeconds(5), 1, true);
        UUID player = UUID.randomUUID();
        assertEquals(ALLOW, guard.check(player, "hello", 0));
        assertEquals(FREQUENCY, guard.check(player, "hello", SECOND));
        assertEquals(FREQUENCY, guard.check(player, "different", 4 * SECOND));
        assertEquals(ALLOW, guard.check(player, "different", 5 * SECOND));
    }

    @Test
    void duplicateNormalizationHandlesUnicodeWithoutChangingOtherCharacters() {
        ChatGuard guard = new ChatGuard(Duration.ofSeconds(5), 4, true);
        UUID player = UUID.randomUUID();
        assertEquals(ALLOW, guard.check(player, "  ＨＥＬＬＯ\u00a0\t世界  ", 0));
        assertEquals(DUPLICATE, guard.check(player, "hello 世界", SECOND));
        assertEquals(ALLOW, guard.check(player, "hello 世界！", 2 * SECOND));
    }

    @Test
    void sharedExitAddressCannotMixDifferentPlayerIdentities() {
        ChatGuard guard = new ChatGuard(Duration.ofSeconds(5), 1, true);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        assertEquals(ALLOW, guard.check(first, "same content", 0));
        assertEquals(ALLOW, guard.check(second, "same content", 0));
        assertEquals(FREQUENCY, guard.check(first, "more", SECOND));
    }

    @Test
    void reconnectKeepsActiveWindowAndOfflineExpiryReclaimsIt() {
        ChatGuard guard = new ChatGuard(Duration.ofSeconds(5), 1, false);
        UUID player = UUID.randomUUID();
        assertEquals(ALLOW, guard.check(player, "before disconnect", 0));
        guard.cleanupOffline(Set.of(), 4 * SECOND);
        assertEquals(1, guard.trackedPlayers());
        assertEquals(FREQUENCY, guard.check(player, "after reconnect", 4 * SECOND));
        guard.cleanupOffline(Set.of(), 5 * SECOND);
        assertEquals(0, guard.trackedPlayers());
        assertEquals(ALLOW, guard.check(player, "later", 5 * SECOND));
    }

    @Test
    void concurrentChecksHaveOneAtomicCapacityLimitPerPlayer() throws Exception {
        ChatGuard guard = new ChatGuard(Duration.ofSeconds(5), 4, false);
        UUID player = UUID.randomUUID();
        CountDownLatch begin = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(8)) {
            List<Callable<ChatGuard.Decision>> work = new ArrayList<>();
            for (int i = 0; i < 32; i++) {
                work.add(() -> {
                    begin.await();
                    return guard.check(player, "message", 0);
                });
            }
            var submitted = work.stream().map(pool::submit).toList();
            begin.countDown();
            int accepted = 0;
            for (var future : submitted) {
                if (future.get() == ALLOW) {
                    accepted++;
                }
            }
            assertEquals(4, accepted);
        }
    }
}
