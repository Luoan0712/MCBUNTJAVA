package mcbunt.admin.restart;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class CountdownTest {
    private static final long SECOND = 1_000_000_000L;
    private static final List<Integer> REMINDERS = List.of(60, 30, 10, 5, 4, 3, 2, 1);

    @Test
    void initialAnnouncementIsNotDuplicatedAndEachReminderAppearsOnce() {
        AtomicLong clock = new AtomicLong();
        Countdown countdown = new Countdown(60, REMINDERS, clock::get);
        assertFalse(countdown.poll().announce());
        clock.set(30 * SECOND);
        assertEquals(new Countdown.Tick(30, true, false), countdown.poll());
        assertFalse(countdown.poll().announce());
    }

    @Test
    void usesRealElapsedTimeAndSkipsMissedReminderNodes() {
        AtomicLong clock = new AtomicLong();
        Countdown countdown = new Countdown(60, REMINDERS, clock::get);
        clock.set(56 * SECOND);
        assertEquals(new Countdown.Tick(4, true, false), countdown.poll());
        assertFalse(countdown.poll().announce());
        clock.set(59 * SECOND);
        assertEquals(new Countdown.Tick(1, true, false), countdown.poll());
    }

    @Test
    void remainingSecondsRoundUpAndTheDeadlineExecutesExactlyOnce() {
        AtomicLong clock = new AtomicLong();
        Countdown countdown = new Countdown(2, REMINDERS, clock::get);
        clock.set(SECOND + 1L);
        assertEquals(new Countdown.Tick(1, true, false), countdown.poll());
        clock.set(2 * SECOND - 1L);
        assertEquals(new Countdown.Tick(1, false, false), countdown.poll());
        clock.incrementAndGet();
        assertEquals(new Countdown.Tick(0, false, true), countdown.poll());
        assertEquals(Countdown.State.EXECUTING, countdown.state());
        assertFalse(countdown.poll().execute());
        assertFalse(countdown.cancel());
    }

    @Test
    void cancellingPreventsAllFutureAnnouncementsAndExecution() {
        AtomicLong clock = new AtomicLong();
        Countdown countdown = new Countdown(1, REMINDERS, clock::get);
        assertTrue(countdown.cancel());
        assertFalse(countdown.cancel());
        clock.set(100 * SECOND);
        assertEquals(new Countdown.Tick(0, false, false), countdown.poll());
        assertEquals(Countdown.State.CANCELLED, countdown.state());
    }

    @Test
    void overdueCountdownExecutesWithoutReplayingNotifications() {
        AtomicLong clock = new AtomicLong();
        Countdown countdown = new Countdown(60, REMINDERS, clock::get);
        clock.set(120 * SECOND);
        assertEquals(new Countdown.Tick(0, false, true), countdown.poll());
    }

    @Test
    void duplicateConfiguredNodesDoNotCauseDuplicateAnnouncements() {
        AtomicLong clock = new AtomicLong();
        Countdown countdown = new Countdown(10, List.of(5, 5, 4), clock::get);
        clock.set(5 * SECOND);
        assertTrue(countdown.poll().announce());
        assertFalse(countdown.poll().announce());
    }

    @Test
    void supportsSignedNanoTimeWraparound() {
        AtomicLong clock = new AtomicLong(Long.MAX_VALUE - SECOND);
        Countdown countdown = new Countdown(2, REMINDERS, clock::get);
        clock.addAndGet(2 * SECOND);
        assertTrue(countdown.poll().execute());
    }

    @Test
    void rejectsUnsupportedDurations() {
        assertThrows(IllegalArgumentException.class, () -> new Countdown(0, REMINDERS, () -> 0));
        assertThrows(IllegalArgumentException.class, () -> new Countdown(86_401, REMINDERS, () -> 0));
        assertThrows(IllegalArgumentException.class, () -> new Countdown(-1, REMINDERS, () -> 0));
    }
}
