package mcbunt.admin.moderation;

import com.destroystokyo.paper.profile.PlayerProfile;
import io.papermc.paper.ban.BanListType;
import mcbunt.admin.audit.AuditEvent;
import mcbunt.admin.audit.AuditLogger;
import mcbunt.admin.config.AdminConfig;
import mcbunt.admin.core.Actor;
import mcbunt.admin.core.DurationSpec;
import mcbunt.admin.core.Outcome;
import mcbunt.admin.core.Result;
import net.kyori.adventure.text.Component;
import org.bukkit.BanEntry;
import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Shared mutation path for commands and automatic chat sanctions. See ENG-ARCH. */
public final class ModerationService implements Listener {
    private final JavaPlugin plugin;
    private final Server server;
    private final AdminConfig config;
    private final AuditLogger audit;
    private final MuteStore store;
    private final BanList<PlayerProfile> bans;
    private final Map<UUID, Identity> known = new HashMap<>();
    private final Map<String, Set<UUID>> knownNames = new HashMap<>();
    private volatile Map<UUID, MuteRecord> muteSnapshot;

    public ModerationService(JavaPlugin plugin, AdminConfig config, AuditLogger audit) throws IOException {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.server = plugin.getServer();
        this.config = Objects.requireNonNull(config, "config");
        this.audit = Objects.requireNonNull(audit, "audit");
        requireMainThread();
        this.store = new MuteStore(plugin.getDataFolder().toPath().resolve("mutes.yml"));
        this.bans = server.getBanList(BanListType.PROFILE);
        Map<UUID, MuteRecord> loaded = store.load();
        loaded.values().forEach(record -> remember(new Identity(record.uuid(), record.name())));
        Map<UUID, MuteRecord> active = activeRecords(loaded, Instant.now());
        if (active.size() != loaded.size()) {
            try {
                store.save(active);
            } catch (IOException failure) {
                plugin.getLogger().warning("禁言到期记录清理失败；过期处罚仍不生效：" + failure.getMessage());
            }
        }
        muteSnapshot = Map.copyOf(active);
        for (BanEntry<PlayerProfile> entry : bans.<BanEntry<PlayerProfile>>getEntries()) {
            PlayerProfile profile = entry.getBanTarget();
            if (profile.getId() != null) {
                remember(new Identity(profile.getId(), profile.getName()));
            }
        }
        for (OfflinePlayer player : server.getOfflinePlayers()) {
            remember(new Identity(player.getUniqueId(), player.getName()));
        }
        for (Player player : server.getOnlinePlayers()) {
            remember(new Identity(player.getUniqueId(), player.getName()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        requireMainThread();
        Player player = event.getPlayer();
        remember(new Identity(player.getUniqueId(), player.getName()));
    }

    /** Performs cached/local lookups only. Unknown names and UUIDs are not fabricated. */
    public Identity resolve(String target) {
        requireMainThread();
        if (target == null || target.isBlank()) {
            throw new IllegalArgumentException("请指定玩家名称或已知 UUID");
        }
        UUID requestedId = parseUuid(target);
        if (requestedId != null) {
            Player online = server.getPlayer(requestedId);
            if (online != null) {
                return remember(new Identity(online.getUniqueId(), online.getName()));
            }
            Identity identity = known.get(requestedId);
            if (identity != null) return identity;
            throw new IllegalArgumentException("无法确认玩家身份：" + target);
        }
        for (Player online : server.getOnlinePlayers()) {
            if (online.getName().equalsIgnoreCase(target)) {
                return remember(new Identity(online.getUniqueId(), online.getName()));
            }
        }
        OfflinePlayer cached = server.getOfflinePlayerIfCached(target);
        if (cached != null && cached.getName() != null && cached.getName().equalsIgnoreCase(target)) {
            return remember(new Identity(cached.getUniqueId(), cached.getName()));
        }
        Set<UUID> matches = knownNames.getOrDefault(target.toLowerCase(Locale.ROOT), Set.of());
        if (matches.size() == 1) {
            return known.get(matches.iterator().next());
        }
        if (matches.size() > 1) {
            throw new IllegalArgumentException("玩家名称存在多个已知身份，请使用 UUID：" + target);
        }
        throw new IllegalArgumentException("无法确认玩家身份：" + target);
    }

    public Result kick(Actor actor, String target, String reason, UUID operationId) {
        Result denied = authorize(actor, "kick", target, operationId);
        if (denied != null) return denied;
        String effectiveReason = defaultReason(reason, config.kickDefaultReason());
        Identity identity = null;
        try {
            identity = resolve(target);
            Player player = server.getPlayer(identity.uuid());
            if (player == null) {
                Outcome outcome = isSystem(actor) ? Outcome.NO_CHANGE : Outcome.FAILED;
                return record("kick", operationId, actor, identity, null, effectiveReason, null,
                        outcome, "玩家不在线");
            }
            player.kick(Component.text(effectiveReason));
            announce(identity.name() + " 已被踢出，理由：" + effectiveReason);
            return record("kick", operationId, actor, identity, null, effectiveReason, null,
                    Outcome.APPLIED, "已踢出 " + identity.name());
        } catch (IllegalArgumentException failure) {
            return record("kick", operationId, actor, identity, null, effectiveReason, null,
                    Outcome.INVALID, failure.getMessage());
        } catch (RuntimeException failure) {
            return failed("kick", operationId, actor, identity, effectiveReason, failure);
        }
    }

    public Result ban(Actor actor, String target, DurationSpec duration, String reason, UUID operationId) {
        Result denied = authorize(actor, "ban", target, operationId);
        if (denied != null) return denied;
        String effectiveReason = defaultReason(reason, config.banDefaultReason());
        DurationSpec effectiveDuration = duration == null ? config.banDefaultDuration() : duration;
        Identity identity = null;
        try {
            identity = resolve(target);
            Instant expires = effectiveDuration.expiresAt(Instant.now());
            PlayerProfile profile = profile(identity);
            BanEntry<PlayerProfile> entry = bans.addBan(profile, effectiveReason, expires, actor.id());
            if (entry == null) throw new IllegalStateException("服务端未返回封禁记录");
            entry.save();
            if (!bans.isBanned(profile)) throw new IllegalStateException("服务端封禁状态未生效");
            Result result = record("ban", operationId, actor, identity, effectiveDuration, effectiveReason,
                    expires, Outcome.APPLIED, "已封禁 " + identity.name());
            announce(identity.name() + " 已被封禁（" + expiryText(expires) + "），理由：" + effectiveReason);
            Player online = server.getPlayer(identity.uuid());
            if (online != null) {
                try {
                    online.kick(Component.text("你已被封禁（" + expiryText(expires) + "）\n理由：" + effectiveReason));
                } catch (RuntimeException disconnectFailure) {
                    plugin.getLogger().warning("封禁已生效，但断开连接失败：" + disconnectFailure.getMessage());
                    return new Result(Outcome.APPLIED, "封禁已生效，但断开连接失败，请检查服务端日志",
                            identity.uuid(), identity.name(), expires);
                }
            }
            return result;
        } catch (IllegalArgumentException failure) {
            return record("ban", operationId, actor, identity, effectiveDuration, effectiveReason, null,
                    Outcome.INVALID, failure.getMessage());
        } catch (RuntimeException failure) {
            return failed("ban", operationId, actor, identity, effectiveReason, failure);
        }
    }

    public Result unban(Actor actor, String target, UUID operationId) {
        Result denied = authorize(actor, "unban", target, operationId);
        if (denied != null) return denied;
        Identity identity = null;
        try {
            identity = resolve(target);
            PlayerProfile profile = profile(identity);
            if (!bans.isBanned(profile)) {
                return record("unban", operationId, actor, identity, null, null, null,
                        Outcome.NO_CHANGE, "该玩家没有有效封禁");
            }
            bans.pardon(profile);
            if (bans.isBanned(profile)) throw new IllegalStateException("服务端封禁状态未解除");
            announce(identity.name() + " 已被解除封禁");
            return record("unban", operationId, actor, identity, null, null, null,
                    Outcome.APPLIED, "已解除 " + identity.name() + " 的封禁");
        } catch (IllegalArgumentException failure) {
            return record("unban", operationId, actor, identity, null, null, null,
                    Outcome.INVALID, failure.getMessage());
        } catch (RuntimeException failure) {
            return failed("unban", operationId, actor, identity, null, failure);
        }
    }

    public Result mute(Actor actor, String target, DurationSpec duration, String reason,
                       boolean extendOnly, UUID operationId) {
        Result denied = authorize(actor, "mute", target, operationId);
        if (denied != null) return denied;
        String effectiveReason = defaultReason(reason, config.muteDefaultReason());
        DurationSpec effectiveDuration = duration == null ? config.muteDefaultDuration() : duration;
        Identity identity = null;
        try {
            identity = resolve(target);
            Instant now = Instant.now();
            Instant expires = effectiveDuration.expiresAt(now);
            MuteRecord previous = activeMute(identity.uuid()).orElse(null);
            if (extendOnly && previous != null && (previous.expiresAt() == null
                    || expires != null && !expires.isAfter(previous.expiresAt()))) {
                return record("mute", operationId, actor, identity, effectiveDuration, previous.reason(),
                        previous.expiresAt(), Outcome.NO_CHANGE, "保留现有更长禁言");
            }
            Map<UUID, MuteRecord> next = activeRecords(muteSnapshot, now);
            next.put(identity.uuid(), new MuteRecord(identity.uuid(), identity.name(), expires,
                    effectiveReason, actor.id(), now));
            commitMutes(next);
            announce(identity.name() + " 已被禁言（" + expiryText(expires) + "），理由：" + effectiveReason);
            return record("mute", operationId, actor, identity, effectiveDuration, effectiveReason,
                    expires, Outcome.APPLIED, "已禁言 " + identity.name());
        } catch (IllegalArgumentException failure) {
            return record("mute", operationId, actor, identity, effectiveDuration, effectiveReason, null,
                    Outcome.INVALID, failure.getMessage());
        } catch (IOException | RuntimeException failure) {
            return failed("mute", operationId, actor, identity, effectiveReason, failure);
        }
    }

    public Result unmute(Actor actor, String target, UUID operationId) {
        Result denied = authorize(actor, "unmute", target, operationId);
        if (denied != null) return denied;
        Identity identity = null;
        try {
            identity = resolve(target);
            if (activeMute(identity.uuid()).isEmpty()) {
                return record("unmute", operationId, actor, identity, null, null, null,
                        Outcome.NO_CHANGE, "该玩家没有有效禁言");
            }
            Map<UUID, MuteRecord> next = activeRecords(muteSnapshot, Instant.now());
            next.remove(identity.uuid());
            commitMutes(next);
            announce(identity.name() + " 已被解除禁言");
            return record("unmute", operationId, actor, identity, null, null, null,
                    Outcome.APPLIED, "已解除 " + identity.name() + " 的禁言");
        } catch (IllegalArgumentException failure) {
            return record("unmute", operationId, actor, identity, null, null, null,
                    Outcome.INVALID, failure.getMessage());
        } catch (IOException | RuntimeException failure) {
            return failed("unmute", operationId, actor, identity, null, failure);
        }
    }

    /** Thread-safe and I/O-free; expiration does not wait for physical cleanup. */
    public Optional<MuteRecord> activeMute(UUID uuid) {
        MuteRecord record = muteSnapshot.get(uuid);
        return record != null && record.activeAt(Instant.now()) ? Optional.of(record) : Optional.empty();
    }

    public Optional<MuteRecord> muteInfo(String target) {
        return activeMute(resolve(target).uuid());
    }

    public void save() throws IOException {
        requireMainThread();
        commitMutes(activeRecords(muteSnapshot, Instant.now()));
    }

    private void commitMutes(Map<UUID, MuteRecord> next) throws IOException {
        Map<UUID, MuteRecord> immutable = Map.copyOf(next);
        store.save(immutable);
        muteSnapshot = immutable;
    }

    private Result authorize(Actor actor, String action, String target, UUID operationId) {
        requireMainThread();
        Objects.requireNonNull(actor, "actor");
        if (!actor.supported() || !actor.hasPermission("mcbunt.admin." + action)) {
            return record(action, operationId, actor, null, null, null, null,
                    Outcome.DENIED, "无权限或不支持的命令来源");
        }
        return null;
    }

    private Result record(String action, UUID operationId, Actor actor, Identity identity,
                          DurationSpec duration, String reason, Instant expires,
                          Outcome outcome, String message) {
        AuditEvent event = AuditEvent.of(action, operationId, actor, outcome)
                .with("targetUuid", identity == null ? null : identity.uuid().toString())
                .with("targetName", identity == null ? null : identity.name())
                .with("expiresAt", expires == null ? null : expires.toString())
                .with("detail", message);
        if (duration != null) event = event.with("durationSeconds", duration.seconds());
        if (reason != null) event = event.with("reason", reason);
        try {
            audit.log(event);
        } catch (RuntimeException failure) {
            plugin.getLogger().warning("管理结果为 " + outcome + "，但审计提交失败：" + failure.getMessage());
        }
        return new Result(outcome, message, identity == null ? null : identity.uuid(),
                identity == null ? null : identity.name(), expires);
    }

    private Result failed(String action, UUID operationId, Actor actor, Identity identity,
                          String reason, Exception failure) {
        plugin.getLogger().warning(action + " 处理失败：" + failure.getMessage());
        return record(action, operationId, actor, identity, null, reason, null,
                Outcome.FAILED, "操作失败，原处罚未确认变更，请检查服务端日志：" + failure.getMessage());
    }

    private Identity remember(Identity identity) {
        Identity previous = known.get(identity.uuid());
        if (previous != null && identity.name().equals(identity.uuid().toString())
                && !previous.name().equals(previous.uuid().toString())) {
            return previous;
        }
        known.put(identity.uuid(), identity);
        if (previous != null && !previous.name().equalsIgnoreCase(identity.name())) {
            String previousKey = previous.name().toLowerCase(Locale.ROOT);
            Set<UUID> oldMatches = knownNames.get(previousKey);
            if (oldMatches != null) {
                oldMatches.remove(identity.uuid());
                if (oldMatches.isEmpty()) knownNames.remove(previousKey);
            }
        }
        knownNames.computeIfAbsent(identity.name().toLowerCase(Locale.ROOT), ignored -> new HashSet<>())
                .add(identity.uuid());
        return identity;
    }

    private PlayerProfile profile(Identity identity) {
        String name = identity.name().matches("[A-Za-z0-9_]{1,16}") ? identity.name() : null;
        return server.createProfileExact(identity.uuid(), name);
    }

    private void announce(String text) {
        try {
            server.broadcast(Component.text("【管理】" + text));
        } catch (RuntimeException failure) {
            plugin.getLogger().warning("管理动作已生效，但公告失败：" + failure.getMessage());
        }
    }

    private void requireMainThread() {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("处罚操作必须在服务端主线程执行");
    }

    private static boolean isSystem(Actor actor) {
        return actor.sender() == null && "SYSTEM".equals(actor.source());
    }

    private static String defaultReason(String reason, String fallback) {
        return reason == null || reason.isBlank() ? fallback : reason;
    }

    private static UUID parseUuid(String token) {
        try {
            UUID uuid = UUID.fromString(token);
            return uuid.toString().equalsIgnoreCase(token) ? uuid : null;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static Map<UUID, MuteRecord> activeRecords(Map<UUID, MuteRecord> records, Instant now) {
        Map<UUID, MuteRecord> active = new LinkedHashMap<>();
        records.forEach((uuid, record) -> { if (record.activeAt(now)) active.put(uuid, record); });
        return active;
    }

    private static String expiryText(Instant expires) {
        return expires == null ? "永久" : "至 " + expires;
    }
}
