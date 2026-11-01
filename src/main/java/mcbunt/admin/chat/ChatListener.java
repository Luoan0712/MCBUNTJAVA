package mcbunt.admin.chat;

import io.papermc.paper.event.player.AsyncChatEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import mcbunt.admin.audit.AuditEvent;
import mcbunt.admin.audit.AuditLogger;
import mcbunt.admin.config.AdminConfig;
import mcbunt.admin.core.Actor;
import mcbunt.admin.core.DurationSpec;
import mcbunt.admin.core.Outcome;
import mcbunt.admin.core.Result;
import mcbunt.admin.moderation.ModerationService;
import mcbunt.admin.moderation.MuteRecord;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/** Cancels the original event without replacing signed chat with a system broadcast. */
public final class ChatListener implements Listener, AutoCloseable {
    private static final String BYPASS = "mcbunt.admin.antispam.bypass";
    private final JavaPlugin plugin;
    private final AdminConfig config;
    private final AuditLogger audit;
    private final ModerationService moderation;
    private final ChatGuard guard;
    private volatile Map<UUID, PlayerSnapshot> players = Map.of();
    private volatile boolean closed;
    private BukkitTask refreshTask;

    public ChatListener(JavaPlugin plugin, AdminConfig config, AuditLogger audit,
                        ModerationService moderation) {
        this.plugin = plugin;
        this.config = config;
        this.audit = audit;
        this.moderation = moderation;
        this.guard = new ChatGuard(Duration.ofSeconds(config.antispamWindowSeconds()),
                config.antispamMaxMessages(), config.duplicateCheck());
    }

    /** Called once on the server thread, after registering the listener. */
    public void start() {
        if (closed || refreshTask != null) {
            return;
        }
        refreshPlayers();
        refreshTask = plugin.getServer().getScheduler().runTaskTimer(plugin,
                this::refreshPlayers, 20L, 20L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (!closed) {
            Map<UUID, PlayerSnapshot> updated = new HashMap<>(players);
            Player player = event.getPlayer();
            updated.put(player.getUniqueId(), new PlayerSnapshot(player.getName(),
                    player.hasPermission(BYPASS)));
            players = Map.copyOf(updated);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        if (closed) {
            return;
        }
        UUID playerId = event.getPlayer().getUniqueId();
        MuteRecord mute = moderation.activeMute(playerId).orElse(null);
        if (mute != null) {
            event.setCancelled(true);
            onMainThread(() -> {
                Player player = plugin.getServer().getPlayer(playerId);
                if (player != null) {
                    player.sendMessage(Component.text("你已被禁言：" + mute.reason()
                            + "（" + remaining(mute.expiresAt()) + "）"));
                }
            });
            return;
        }
        PlayerSnapshot snapshot = players.get(playerId);
        if (!config.antispamEnabled() || (snapshot != null && snapshot.bypass())) {
            return;
        }
        ChatGuard.Decision decision = guard.check(playerId,
                PlainTextComponentSerializer.plainText().serialize(event.message()),
                System.nanoTime());
        if (decision == ChatGuard.Decision.ALLOW) {
            return;
        }
        event.setCancelled(true);
        UUID operationId = UUID.randomUUID();
        String playerName = snapshot == null ? playerId.toString() : snapshot.name();
        if (config.antispamAction().equals("cancel")) {
            recordTrigger(operationId, playerId, playerName, decision, Outcome.APPLIED,
                    "已取消触发消息");
        } else {
            onMainThread(() -> applyAction(operationId, playerId, playerName, decision));
        }
    }

    private void applyAction(UUID operationId, UUID playerId, String playerName,
                             ChatGuard.Decision decision) {
        String reason = decision == ChatGuard.Decision.FREQUENCY
                ? "发送聊天消息过于频繁" : "重复发送相同聊天内容";
        Outcome outcome = Outcome.APPLIED;
        String detail = "已取消触发消息";
        try {
            switch (config.antispamAction()) {
                case "warn" -> {
                    Player player = plugin.getServer().getPlayer(playerId);
                    if (player == null) {
                        outcome = Outcome.NO_CHANGE;
                        detail = "消息已取消，玩家离线，未发送警告";
                    } else {
                        player.sendMessage(Component.text("请勿刷屏：" + reason));
                        detail = "消息已取消并警告玩家";
                    }
                }
                case "mute" -> {
                    Result result = moderation.mute(Actor.system(), playerId.toString(),
                            new DurationSpec(Duration.ofSeconds(config.autoMuteSeconds())),
                            reason, true, operationId);
                    outcome = result.outcome();
                    detail = result.message();
                }
                case "kick" -> {
                    Result result = moderation.kick(Actor.system(), playerId.toString(),
                            reason, operationId);
                    outcome = result.outcome();
                    detail = result.message();
                }
                default -> throw new IllegalStateException("Invalid antispam action: "
                        + config.antispamAction());
            }
        } catch (RuntimeException failure) {
            outcome = Outcome.FAILED;
            detail = "消息已取消，自动处置失败：" + failure.getClass().getSimpleName();
            plugin.getLogger().log(java.util.logging.Level.SEVERE,
                    "Automatic chat action failed for " + playerId, failure);
        }
        recordTrigger(operationId, playerId, playerName, decision, outcome, detail);
    }

    private void recordTrigger(UUID operationId, UUID playerId, String playerName,
                               ChatGuard.Decision decision, Outcome result, String detail) {
        audit.log(AuditEvent.of("antispam", operationId, Actor.system(), result)
                .with("targetUuid", playerId.toString())
                .with("targetName", playerName)
                .with("trigger", decision.name().toLowerCase(java.util.Locale.ROOT))
                .with("configuredAction", config.antispamAction())
                .with("detail", detail));
    }

    private void refreshPlayers() {
        if (closed) {
            return;
        }
        Map<UUID, PlayerSnapshot> refreshed = new HashMap<>();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            refreshed.put(player.getUniqueId(), new PlayerSnapshot(player.getName(),
                    player.hasPermission(BYPASS)));
        }
        players = Map.copyOf(refreshed);
        guard.cleanupOffline(refreshed.keySet(), System.nanoTime());
    }

    private void onMainThread(Runnable action) {
        if (closed) {
            return;
        }
        try {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (!closed) {
                    action.run();
                }
            });
        } catch (IllegalPluginAccessException ignored) {
            // Server shutdown may race an already cancelled asynchronous chat event.
        }
    }

    private static String remaining(Instant expiresAt) {
        if (expiresAt == null) {
            return "永久";
        }
        long seconds = Math.max(0L, Duration.between(Instant.now(), expiresAt).getSeconds());
        return "剩余 " + seconds + " 秒";
    }

    @Override
    public void close() {
        closed = true;
        if (refreshTask != null) {
            refreshTask.cancel();
            refreshTask = null;
        }
        players = Map.of();
    }

    private record PlayerSnapshot(String name, boolean bypass) { }
}
