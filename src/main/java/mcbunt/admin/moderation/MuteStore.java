package mcbunt.admin.moderation;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** ENG-ARCH: write the complete next state before publishing its snapshot. */
public final class MuteStore {
    private static final Set<String> ROOT_KEYS = Set.of("schema-version", "mutes");
    private static final Set<String> RECORD_KEYS = Set.of("last-known-name", "expires-at-ms",
            "reason", "actor", "created-at-ms");

    private final Path file;
    private final AtomicReplacer replacer;

    public MuteStore(Path file) {
        this(file, (source, target) -> Files.move(source, target,
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING));
    }

    MuteStore(Path file, AtomicReplacer replacer) {
        this.file = Objects.requireNonNull(file, "file").toAbsolutePath();
        this.replacer = Objects.requireNonNull(replacer, "replacer");
    }

    public Map<UUID, MuteRecord> load() throws IOException {
        if (Files.notExists(file)) {
            return Map.of();
        }
        try {
            LoaderOptions options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            options.setMaxAliasesForCollections(0);
            Object loaded = new Yaml(new SafeConstructor(options))
                    .load(Files.readString(file, StandardCharsets.UTF_8));
            Map<?, ?> root = requireMap(loaded, "root");
            requireKeys(root, ROOT_KEYS, "root");
            if (requireLong(root.get("schema-version"), "schema-version") != 1) {
                throw new IllegalArgumentException("unsupported schema-version");
            }
            Map<?, ?> records = requireMap(root.get("mutes"), "mutes");
            Map<UUID, MuteRecord> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : records.entrySet()) {
                String key = requireString(entry.getKey(), "mutes UUID");
                UUID uuid = UUID.fromString(key);
                if (!uuid.toString().equalsIgnoreCase(key)) {
                    throw new IllegalArgumentException("noncanonical UUID: " + key);
                }
                Map<?, ?> fields = requireMap(entry.getValue(), key);
                requireKeys(fields, RECORD_KEYS, key);
                Instant expires = fields.get("expires-at-ms") == null ? null
                        : Instant.ofEpochMilli(requireLong(fields.get("expires-at-ms"), key + ".expires-at-ms"));
                MuteRecord record = new MuteRecord(uuid,
                        requireString(fields.get("last-known-name"), key + ".last-known-name"), expires,
                        requireString(fields.get("reason"), key + ".reason"),
                        requireString(fields.get("actor"), key + ".actor"),
                        Instant.ofEpochMilli(requireLong(fields.get("created-at-ms"), key + ".created-at-ms")));
                if (result.put(uuid, record) != null) {
                    throw new IllegalArgumentException("duplicate UUID: " + key);
                }
            }
            return Map.copyOf(result);
        } catch (RuntimeException ex) {
            throw new IOException("Invalid mute data in " + file + ": " + ex.getMessage(), ex);
        }
    }

    public void save(Map<UUID, MuteRecord> records) throws IOException {
        Map<String, Object> entries = new LinkedHashMap<>();
        records.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            MuteRecord record = entry.getValue();
            if (!entry.getKey().equals(record.uuid())) {
                throw new IllegalArgumentException("mute map key does not match record UUID");
            }
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("last-known-name", record.name());
            fields.put("expires-at-ms", record.expiresAt() == null ? null : record.expiresAt().toEpochMilli());
            fields.put("reason", record.reason());
            fields.put("actor", record.actor());
            fields.put("created-at-ms", record.createdAt().toEpochMilli());
            entries.put(entry.getKey().toString(), fields);
        });
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schema-version", 1);
        root.put("mutes", entries);
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setAllowUnicode(true);
        byte[] bytes = new Yaml(options).dump(root).getBytes(StandardCharsets.UTF_8);
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), ".mutes-", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            replacer.replace(temporary, file);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static Map<?, ?> requireMap(Object value, String field) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(field + " must be a mapping");
        }
        return map;
    }

    private static void requireKeys(Map<?, ?> map, Set<String> keys, String field) {
        if (!map.keySet().equals(keys)) {
            throw new IllegalArgumentException(field + " must contain exactly " + keys);
        }
    }

    private static String requireString(Object value, String field) {
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException(field + " must be nonblank text");
        }
        return text;
    }

    private static long requireLong(Object value, String field) {
        long result;
        if (value instanceof Integer number) {
            result = number.longValue();
        } else if (value instanceof Long number) {
            result = number;
        } else if (value instanceof BigInteger number) {
            result = number.longValueExact();
        } else {
            throw new IllegalArgumentException(field + " must be an integer");
        }
        if (result < 0) {
            throw new IllegalArgumentException(field + " must not be negative");
        }
        return result;
    }

    @FunctionalInterface
    interface AtomicReplacer {
        void replace(Path source, Path target) throws IOException;
    }
}
