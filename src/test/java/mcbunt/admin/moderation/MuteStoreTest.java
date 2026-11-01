package mcbunt.admin.moderation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MuteStoreTest {
    @TempDir Path directory;
    private static final UUID ALEX = UUID.fromString("c99d1234-1234-4234-8234-123456789abc");
    private static final UUID STEVE = UUID.fromString("d99d1234-1234-4234-8234-123456789abc");
    private static final Instant CREATED = Instant.parse("2026-11-01T00:00:00Z");

    @Test
    void missingFileIsAnEmptyStoreAndIsNotWrittenDuringRead() throws IOException {
        Path file = directory.resolve("mutes.yml");
        assertEquals(Map.of(), new MuteStore(file).load());
        assertFalse(Files.exists(file));
    }

    @Test
    void roundTripsTemporaryAndPermanentMutesWithUnicodeAndNewlines() throws IOException {
        Path file = directory.resolve("mutes.yml");
        MuteRecord temporary = new MuteRecord(ALEX, "Alex", CREATED.plusSeconds(120),
                "刷屏\n第二行：保留理由", "CONSOLE", CREATED);
        MuteRecord permanent = new MuteRecord(STEVE, "Steve", null, "永久禁言", "RCON", CREATED);
        Map<UUID, MuteRecord> records = Map.of(ALEX, temporary, STEVE, permanent);
        MuteStore store = new MuteStore(file);
        store.save(records);
        assertEquals(records, store.load());
        assertTrue(Files.readString(file).contains("expires-at-ms: null"));
    }

    @Test
    void expirationAtTheExactBoundaryIsInactive() {
        MuteRecord record = new MuteRecord(ALEX, "Alex", CREATED.plusSeconds(120),
                "reason", "SYSTEM", CREATED);
        assertTrue(record.activeAt(CREATED.plusSeconds(119)));
        assertFalse(record.activeAt(CREATED.plusSeconds(120)));
        assertTrue(new MuteRecord(ALEX, "Alex", null, "reason", "SYSTEM", CREATED)
                .activeAt(CREATED.plusSeconds(1000000)));
    }

    @Test
    void replacementFailurePreservesPreviousBytes() throws IOException {
        Path file = directory.resolve("mutes.yml");
        MuteStore store = new MuteStore(file);
        MuteRecord previous = new MuteRecord(ALEX, "Alex", null, "old", "CONSOLE", CREATED);
        store.save(Map.of(ALEX, previous));
        byte[] bytes = Files.readAllBytes(file);
        MuteStore failing = new MuteStore(file, (source, target) -> {
            throw new AtomicMoveNotSupportedException(source.toString(), target.toString(), "probe");
        });
        assertThrows(IOException.class, () -> failing.save(Map.of()));
        assertArrayEquals(bytes, Files.readAllBytes(file));
        assertEquals(Map.of(ALEX, previous), store.load());
        try (var files = Files.list(directory)) {
            assertEquals(1, files.count());
        }
    }

    @Test
    void malformedInputIsRejectedWithoutOverwritingIt() throws IOException {
        Path file = directory.resolve("mutes.yml");
        for (String invalid : new String[] {
                "", "schema-version: 2\nmutes: {}\n", "schema-version: 1.0\nmutes: {}\n",
                "schema-version: 1\nmutes: []\n", "schema-version: 1\nmutes: {}\nextra: true\n",
                "schema-version: 1\nschema-version: 1\nmutes: {}\n",
                "schema-version: 1\nmutes:\n  invalid-uuid: {}\n",
                "schema-version: 1\nmutes: [\n"
        }) {
            Files.writeString(file, invalid);
            assertThrows(IOException.class, () -> new MuteStore(file).load(), invalid);
            assertEquals(invalid, Files.readString(file));
        }
    }

    @Test
    void missingExpiryAndFloatingPointTimestampDoNotBecomePermanentMutes() throws IOException {
        Path file = directory.resolve("mutes.yml");
        String fields = "schema-version: 1\nmutes:\n  " + ALEX + ":\n"
                + "    last-known-name: Alex\n    reason: test\n    actor: CONSOLE\n"
                + "    created-at-ms: 1793491200000\n";
        Files.writeString(file, fields);
        assertThrows(IOException.class, () -> new MuteStore(file).load());
        Files.writeString(file, fields + "    expires-at-ms: 1793491320000.5\n");
        assertThrows(IOException.class, () -> new MuteStore(file).load());
    }

    @Test
    void duplicatePlayerKeysAreRejected() throws IOException {
        Path file = directory.resolve("mutes.yml");
        Files.writeString(file, "schema-version: 1\nmutes:\n  " + ALEX + ": {}\n  " + ALEX + ": {}\n");
        assertThrows(IOException.class, () -> new MuteStore(file).load());
    }
}
