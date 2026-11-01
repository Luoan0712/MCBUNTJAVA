package mcbunt.admin.audit;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import mcbunt.admin.core.Actor;

/** ENG-ARCH: a bounded single writer. log() acknowledges admission, never disk durability. */
public final class AuditLogger implements AutoCloseable {
    private static final int CAPACITY = 1024;
    private static final Actor SYSTEM = new Actor("SYSTEM", "SYSTEM", null);
    private final Gson gson = new GsonBuilder().serializeNulls().disableHtmlEscaping().create();
    private final ArrayBlockingQueue<Item> queue = new ArrayBlockingQueue<>(CAPACITY);
    private final Object submission = new Object();
    private final AtomicBoolean closing = new AtomicBoolean();
    private final AtomicBoolean failed = new AtomicBoolean();
    private final AtomicLong lostEvents = new AtomicLong();
    private final Set<String> categories;
    private final Path directory;
    private final Path current;
    private final long maxBytes;
    private final int keepFiles;
    private final BiConsumer<Actor, String> failureReporter;
    private final OutputOpener opener;
    private final Thread worker;
    private OutputStream output;
    private long currentBytes;

    public AuditLogger(Path pluginDataFolder, int maxSizeMb, int keepFiles,
            Set<String> recordCategories, BiConsumer<Actor, String> failureReporter) throws IOException {
        this(pluginDataFolder, Math.multiplyExact((long) maxSizeMb, 1024L * 1024L), keepFiles,
                recordCategories, failureReporter, path -> new BufferedOutputStream(Files.newOutputStream(
                        path, StandardOpenOption.CREATE, StandardOpenOption.APPEND)));
    }

    // Small byte limits and a failing stream make storage-boundary tests deterministic.
    AuditLogger(Path pluginDataFolder, long maxBytes, int keepFiles, Set<String> recordCategories,
            BiConsumer<Actor, String> failureReporter, OutputOpener opener) throws IOException {
        if (maxBytes <= 0 || keepFiles < 0) {
            throw new IllegalArgumentException("Invalid audit size or retention");
        }
        this.directory = Objects.requireNonNull(pluginDataFolder, "pluginDataFolder").resolve("logs");
        this.current = directory.resolve("admin.log");
        this.maxBytes = maxBytes;
        this.keepFiles = keepFiles;
        this.categories = Set.copyOf(recordCategories);
        this.failureReporter = Objects.requireNonNull(failureReporter, "failureReporter");
        this.opener = Objects.requireNonNull(opener, "opener");
        Files.createDirectories(directory);
        trimArchives();
        currentBytes = Files.exists(current) ? Files.size(current) : 0;
        output = opener.open(current);
        worker = new Thread(this::writeLoop, "MCBUNTAdmin-audit");
        worker.setDaemon(true);
        worker.start();
    }

    /** True means admitted, or deliberately filtered by configuration; use flush for disk completion. */
    public boolean log(AuditEvent event) {
        Objects.requireNonNull(event, "event");
        if (!categories.contains(event.action())) {
            return true;
        }
        byte[] bytes;
        try {
            bytes = encode(event);
        } catch (RuntimeException error) {
            lose(event.actor(), "审计事件序列化失败：" + error.getMessage());
            return false;
        }
        synchronized (submission) {
            if (!closing.get() && !failed.get() && queue.offer(new Entry(event.actor(), bytes))) {
                return true;
            }
        }
        lose(event.actor(), closing.get() ? "审计已关闭，事件未记录"
                : failed.get() ? "审计写入不可用，事件未记录" : "审计队列已满，事件未记录");
        return false;
    }

    /** Waits only up to timeout; false also reports earlier admitted writes that failed. */
    public boolean flush(Duration timeout) {
        long nanos = nonNegativeNanos(timeout);
        if (nanos == 0) {
            return false;
        }
        long started = System.nanoTime();
        var completion = new CompletableFuture<Boolean>();
        // Do not hold admission while waiting for space: log() must never wait for disk I/O.
        while (true) {
            synchronized (submission) {
                if (closing.get()) {
                    return !worker.isAlive() && !failed.get() && lostEvents.get() == 0 && queue.isEmpty();
                }
                if (queue.offer(new Barrier(completion))) {
                    break;
                }
            }
            long remaining = nanos - (System.nanoTime() - started);
            if (remaining <= 0) {
                report(SYSTEM, "审计刷新超时：队列未腾出空间");
                return false;
            }
            try {
                TimeUnit.NANOSECONDS.sleep(Math.min(remaining, 1_000_000L));
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        long remaining = nanos - (System.nanoTime() - started);
        if (remaining <= 0) {
            report(SYSTEM, "审计刷新超时，尚未确认落盘");
            return false;
        }
        try {
            return completion.get(remaining, TimeUnit.NANOSECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception error) {
            report(SYSTEM, "审计刷新超时或失败，尚未确认落盘");
            return false;
        }
    }

    public long lostEventCount() {
        return lostEvents.get();
    }

    @Override
    public void close() {
        synchronized (submission) {
            if (!closing.compareAndSet(false, true)) {
                return;
            }
        }
        try {
            worker.join(3000);
            if (worker.isAlive()) {
                worker.interrupt();
                report(SYSTEM, "审计关闭超过3秒，尚未确认完成的记录数：" + queue.size());
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            report(SYSTEM, "审计关闭等待被中断，尚未确认全部落盘");
        }
    }

    private byte[] encode(AuditEvent event) {
        var record = new LinkedHashMap<String, Object>();
        record.put("timestamp", Instant.now().toString());
        record.put("action", event.action());
        record.put("operationId", event.operationId().toString());
        record.put("actor", event.actor().id());
        record.put("source", event.actor().source());
        event.fields().forEach((key, value) -> record.put(key, jsonValue(value)));
        return (gson.toJson(record) + "\n").getBytes(StandardCharsets.UTF_8);
    }

    private Object jsonValue(Object value) {
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof UUID || value instanceof TemporalAccessor || value instanceof Enum<?>) {
            return value.toString();
        }
        if (value instanceof Map<?, ?> map) {
            var result = new LinkedHashMap<String, Object>();
            map.forEach((key, member) -> result.put(String.valueOf(key), jsonValue(member)));
            return result;
        }
        if (value instanceof Iterable<?> members) {
            var result = new ArrayList<>();
            members.forEach(member -> result.add(jsonValue(member)));
            return result;
        }
        throw new IllegalArgumentException("Unsupported audit field type: " + value.getClass().getName());
    }

    private void writeLoop() {
        try {
            while (!closing.get() || !queue.isEmpty()) {
                Item item;
                try {
                    item = queue.poll(100, TimeUnit.MILLISECONDS);
                } catch (InterruptedException error) {
                    if (closing.get()) {
                        break;
                    }
                    continue;
                }
                if (item instanceof Entry entry) {
                    if (failed.get()) {
                        lose(entry.actor(), "审计存储故障，排队事件未记录");
                        continue;
                    }
                    try {
                        if (currentBytes > 0 && currentBytes + entry.bytes().length > maxBytes) {
                            rotate();
                        }
                        output.write(entry.bytes());
                        output.flush();
                        currentBytes += entry.bytes().length;
                    } catch (IOException error) {
                        failed.set(true);
                        lose(entry.actor(), "审计写入失败：" + error.getMessage());
                    }
                } else if (item instanceof Barrier barrier) {
                    try {
                        output.flush();
                        barrier.completion().complete(!failed.get() && lostEvents.get() == 0);
                    } catch (IOException error) {
                        failed.set(true);
                        report(SYSTEM, "审计刷新失败：" + error.getMessage());
                        barrier.completion().complete(false);
                    }
                }
            }
        } finally {
            try {
                output.close();
            } catch (IOException error) {
                failed.set(true);
                report(SYSTEM, "审计文件关闭失败：" + error.getMessage());
            }
            Item pending;
            while ((pending = queue.poll()) != null) {
                if (pending instanceof Entry entry) {
                    lose(entry.actor(), "审计关闭时事件尚未记录");
                } else if (pending instanceof Barrier barrier) {
                    barrier.completion().complete(false);
                }
            }
        }
    }

    private void rotate() throws IOException {
        output.flush();
        output.close();
        if (keepFiles == 0) {
            Files.deleteIfExists(current);
        } else {
            Files.deleteIfExists(archive(keepFiles));
            for (int index = keepFiles; index > 1; index--) {
                Path old = archive(index - 1);
                if (Files.exists(old)) {
                    Files.move(old, archive(index), StandardCopyOption.REPLACE_EXISTING);
                }
            }
            Files.move(current, archive(1), StandardCopyOption.REPLACE_EXISTING);
        }
        output = opener.open(current);
        currentBytes = 0;
    }

    private void trimArchives() throws IOException {
        try (var entries = Files.list(directory)) {
            for (Path entry : entries.toList()) {
                String name = entry.getFileName().toString();
                if (name.matches("admin\\.log\\.[1-9][0-9]*")) {
                    try {
                        if (Long.parseLong(name.substring("admin.log.".length())) > keepFiles) {
                            Files.deleteIfExists(entry);
                        }
                    } catch (NumberFormatException ignored) {
                        // An unrepresentable suffix cannot be generated by this logger.
                    }
                }
            }
        }
    }

    private Path archive(int index) {
        return directory.resolve("admin.log." + index);
    }

    private void lose(Actor actor, String message) {
        long count = lostEvents.incrementAndGet();
        report(actor, message + "；未记录事件累计：" + count);
    }

    private void report(Actor actor, String message) {
        try {
            failureReporter.accept(actor, message);
        } catch (RuntimeException error) {
            System.err.println("MCBUNTAdmin: audit failure reporter failed: " + message);
        }
    }

    private static long nonNegativeNanos(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        try {
            return Math.max(0, timeout.toNanos());
        } catch (ArithmeticException error) {
            return timeout.isNegative() ? 0 : Long.MAX_VALUE;
        }
    }

    private sealed interface Item permits Entry, Barrier {}
    private record Entry(Actor actor, byte[] bytes) implements Item {}
    private record Barrier(CompletableFuture<Boolean> completion) implements Item {}

    @FunctionalInterface
    interface OutputOpener {
        OutputStream open(Path path) throws IOException;
    }
}
