package mcbunt.admin.audit;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import mcbunt.admin.core.Actor;
import mcbunt.admin.core.Outcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AuditLoggerTest {
    @TempDir Path temporary;
    private static final Actor ACTOR = new Actor("CONSOLE", "CONSOLE", null);

    private AuditEvent event(String detail) {
        return AuditEvent.of("ban", UUID.randomUUID(), ACTOR, Outcome.APPLIED).with("detail", detail);
    }

    @Test
    void serializesOneLinePerEventAndPreservesNullUnicodeAndUtcFields() throws Exception {
        var failures = new CopyOnWriteArrayList<String>();
        UUID operation = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        Instant expiry = Instant.parse("2026-11-01T12:00:00Z");
        String reason = "中文\n\r\t\u0000\"quoted\"\\suffix";
        try (var logger = new AuditLogger(temporary, 5, 10, Set.of("ban"),
                (actor, message) -> failures.add(message))) {
            assertTrue(logger.log(AuditEvent.of("ban", operation, ACTOR, Outcome.APPLIED)
                    .with("targetUuid", target).with("expiresAt", expiry)
                    .with("targetName", null).with("reason", reason)));
            assertTrue(logger.flush(Duration.ofSeconds(2)));
        }
        List<String> lines = Files.readAllLines(temporary.resolve("logs/admin.log"), StandardCharsets.UTF_8);
        assertEquals(1, lines.size());
        JsonObject record = JsonParser.parseString(lines.getFirst()).getAsJsonObject();
        assertEquals(reason, record.get("reason").getAsString());
        assertEquals(operation.toString(), record.get("operationId").getAsString());
        assertEquals(target.toString(), record.get("targetUuid").getAsString());
        assertEquals(expiry.toString(), record.get("expiresAt").getAsString());
        assertTrue(record.get("targetName").isJsonNull());
        assertEquals("CONSOLE", record.get("actor").getAsString());
        assertEquals("CONSOLE", record.get("source").getAsString());
        assertNotNull(Instant.parse(record.get("timestamp").getAsString()));
        assertTrue(failures.isEmpty());
    }

    @Test
    void flushIncludesEarlierAcceptedEventsInSubmissionOrderAndFiltersDisabledCategories() throws Exception {
        try (var logger = new AuditLogger(temporary, 5, 1, Set.of("ban"),
                (actor, message) -> fail(message))) {
            for (int index = 0; index < 100; index++) {
                assertTrue(logger.log(event("event-" + index)));
            }
            assertTrue(logger.log(AuditEvent.of("mute", UUID.randomUUID(), ACTOR, Outcome.APPLIED)));
            assertTrue(logger.flush(Duration.ofSeconds(2)));
            List<String> lines = Files.readAllLines(temporary.resolve("logs/admin.log"));
            assertEquals(100, lines.size());
            for (int index = 0; index < lines.size(); index++) {
                assertEquals("event-" + index, JsonParser.parseString(lines.get(index))
                        .getAsJsonObject().get("detail").getAsString());
            }
        }
    }

    @Test
    void rotatesWholeEventsAndRetainsOnlyConfiguredArchives() throws Exception {
        Path logs = temporary.resolve("logs");
        Files.createDirectories(logs);
        Files.writeString(logs.resolve("admin.log.9"), "old");
        try (var logger = new AuditLogger(temporary, 1L, 2, Set.of("ban"),
                (actor, message) -> fail(message),
                path -> Files.newOutputStream(path, StandardOpenOption.CREATE, StandardOpenOption.APPEND))) {
            for (int index = 0; index < 5; index++) {
                assertTrue(logger.log(event("event-" + index)));
            }
            assertTrue(logger.flush(Duration.ofSeconds(2)));
        }
        assertFalse(Files.exists(logs.resolve("admin.log.9")));
        try (var children = Files.list(logs)) {
            assertEquals(3, children.count());
        }
        for (int archive = 0; archive < 3; archive++) {
            Path file = logs.resolve(archive == 0 ? "admin.log" : "admin.log." + archive);
            List<String> lines = Files.readAllLines(file);
            assertEquals(1, lines.size(), "a single event must not be split across files");
            assertEquals("event-" + (4 - archive), JsonParser.parseString(lines.getFirst())
                    .getAsJsonObject().get("detail").getAsString());
        }
    }

    @Test
    void ioFailureFailsFlushAndReportsBothQueuedAndRejectedEvents() throws Exception {
        var failures = new CopyOnWriteArrayList<String>();
        try (var logger = new AuditLogger(temporary, 1024L, 1, Set.of("ban"),
                (actor, message) -> failures.add(message), path -> new OutputStream() {
                    @Override public void write(int value) throws IOException {
                        throw new IOException("simulated disk failure");
                    }
                })) {
            assertTrue(logger.log(event("first")));
            assertFalse(logger.flush(Duration.ofSeconds(2)));
            assertFalse(logger.log(event("second")));
            assertEquals(2, logger.lostEventCount());
        }
        assertTrue(failures.stream().anyMatch(message -> message.contains("simulated disk failure")));
    }

    @Test
    void aFullQueueRejectsWithoutBlockingAndDoesNotDisableHealthyStorage() throws Exception {
        CountDownLatch writing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var failures = new CopyOnWriteArrayList<String>();
        var bytes = new ByteArrayOutputStream();
        try (var logger = new AuditLogger(temporary, 1024L * 1024L, 1, Set.of("ban"),
                (actor, message) -> failures.add(message), path -> new OutputStream() {
                    @Override public void write(int value) throws IOException {
                        writing.countDown();
                        try {
                            if (!release.await(5, TimeUnit.SECONDS)) {
                                throw new IOException("test writer remained blocked");
                            }
                        } catch (InterruptedException error) {
                            Thread.currentThread().interrupt();
                            throw new IOException(error);
                        }
                        bytes.write(value);
                    }
                })) {
            assertTrue(logger.log(event("writing")));
            assertTrue(writing.await(2, TimeUnit.SECONDS));
            for (int index = 0; index < 1024; index++) {
                assertTrue(logger.log(event("queued-" + index)));
            }
            assertFalse(logger.log(event("overflow")));
            release.countDown();
            // Earlier loss is not disguised by a successful later stream flush.
            assertFalse(logger.flush(Duration.ofSeconds(3)));
            assertEquals(1, logger.lostEventCount());
            assertTrue(logger.log(event("healthy-again")));
            assertFalse(logger.flush(Duration.ofSeconds(2)));
        } finally {
            release.countDown();
        }
        assertEquals(1026, bytes.toString(StandardCharsets.UTF_8).lines().count());
        assertTrue(failures.stream().anyMatch(message -> message.contains("队列已满")));
    }

    @Test
    void eventCopiesFieldsAllowsNullAndProtectsInfrastructureKeys() {
        var original = new HashMap<String, Object>();
        original.put("reason", "before");
        var event = new AuditEvent("ban", UUID.randomUUID(), ACTOR, original).with("targetName", null);
        original.put("reason", "after");
        assertEquals("before", event.fields().get("reason"));
        assertTrue(event.fields().containsKey("targetName"));
        assertThrows(UnsupportedOperationException.class, () -> event.fields().put("x", "y"));
        assertThrows(IllegalArgumentException.class, () -> event.with("actor", "forged"));
    }
}
