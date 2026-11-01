package mcbunt.admin.command;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import mcbunt.admin.audit.AuditEvent;
import mcbunt.admin.audit.AuditLogger;
import mcbunt.admin.config.AdminConfig;
import mcbunt.admin.core.Actor;
import mcbunt.admin.core.Outcome;
import mcbunt.admin.core.Result;
import mcbunt.admin.moderation.ModerationService;
import mcbunt.admin.moderation.MuteRecord;
import mcbunt.admin.restart.RestartService;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/** Shared, permission-checked entry points. Mutation effects and audit belong to their services. */
public final class AdminCommands {
    private static final List<String> LABELS = List.of(
            "kick", "ban", "pardon", "unban", "mute", "unmute", "muteinfo", "online", "list", "restart", "broadcast");
    private static final Set<String> TARGET_COMMANDS = Set.of("kick", "ban", "unban", "mute", "unmute", "muteinfo");
    private static final Set<String> QUERIES = Set.of("online", "muteinfo");
    private static final Component PREFIX = Component.text("[MCBUNTAdmin] ");
    private static final Map<String, String> NATIVE_COMMANDS = Map.of(
            "minecraft:ban", "ban", "minecraft:kick", "kick", "minecraft:pardon", "pardon");

    private final JavaPlugin plugin;
    private final AdminConfig config;
    private final AuditLogger audit;
    private final ModerationService moderation;
    private final RestartService restart;
    private volatile Set<String> registeredLabels = Set.of();
    private volatile Set<String> nativeRestartLabels = Set.of();
    private boolean registered;

    public AdminCommands(JavaPlugin plugin, AdminConfig config, AuditLogger audit,
                         ModerationService moderation, RestartService restart) {
        this.plugin = plugin;
        this.config = config;
        this.audit = audit;
        this.moderation = moderation;
        this.restart = restart;
    }

    public void register() {
        if (registered) {
            throw new IllegalStateException("命令入口已经注册。");
        }
        registered = true;
        plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            Set<String> existingRestart = new LinkedHashSet<>();
            for (String label : List.of("bukkit:restart", "spigot:restart", "paper:restart")) {
                if (event.registrar().getDispatcher().getRoot().getChild(label) != null) {
                    existingRestart.add(label);
                }
            }
            nativeRestartLabels = Set.copyOf(existingRestart);
            Set<String> labels = new LinkedHashSet<>();
            for (String label : LABELS) {
                // Existing labels such as pardon/list must be main registrations, not aliases.
                labels.addAll(event.registrar().register(label, "MCBUNTAdmin /" + label, new Entry(label)));
            }
            // Register exact vanilla roots through Paper's registrar. It removes the old node before
            // adding ours, rather than merging vanilla argument children. Nested /execute and direct
            // dispatch therefore resolve the same guarded implementation, not a textual rewrite.
            for (var entry : NATIVE_COMMANDS.entrySet()) {
                labels.addAll(event.registrar().register(entry.getKey(), "MCBUNTAdmin /" + entry.getValue(),
                        new Entry(entry.getValue())));
            }
            for (String label : nativeRestartLabels) {
                labels.addAll(event.registrar().register(label, "MCBUNTAdmin /restart", new Entry("restart")));
            }
            registeredLabels = Set.copyOf(labels);
            Set<String> lower = new LinkedHashSet<>();
            labels.forEach(label -> lower.add(label.toLowerCase(Locale.ROOT)));
            for (String label : LABELS) {
                if (!lower.contains(label) || !lower.contains("mcbuntadmin:" + label)) {
                    throw new IllegalStateException("必需管理命令入口未注册：" + label);
                }
            }
            if (!lower.containsAll(NATIVE_COMMANDS.keySet()) || !lower.containsAll(nativeRestartLabels)) {
                throw new IllegalStateException("原生管理命名空间入口未完整纳管。");
            }
            Set<String> managedNative = new LinkedHashSet<>(NATIVE_COMMANDS.keySet());
            managedNative.addAll(nativeRestartLabels);
            for (String label : managedNative) {
                var node = event.registrar().getDispatcher().getRoot().getChild(label);
                if (node == null || node.getCommand() == null || node.getChildren().size() != 1
                        || node.getChild("args") == null) {
                    throw new IllegalStateException("原生入口仍有未替换的参数树：" + label);
                }
            }
            plugin.getLogger().info("已注册管理命令入口：" + String.join(", ", labels)
                    + "；纳管原生重启入口：" + String.join(", ", nativeRestartLabels));
            plugin.getLogger().info("已校验原生命名空间的独立命令树：" + String.join(", ", managedNative));
        });
    }

    public Set<String> registeredLabels() {
        return registeredLabels;
    }

    private final class Entry implements BasicCommand {
        private final String label;

        private Entry(String label) {
            this.label = label;
        }

        @Override
        public void execute(CommandSourceStack source, String[] args) {
            CommandSender sender = source.getSender();
            if (!plugin.getServer().isPrimaryThread()) {
                String[] copy = args.clone();
                plugin.getServer().getScheduler().runTask(plugin, () -> handle(label, sender, copy));
                return;
            }
            handle(label, sender, args);
        }

        // The handler rejects unauthorized execution itself so denied management attempts are audited.
        // Suggestions remain permission-filtered; no unauthorized command can produce a management effect.
        @Override
        public Collection<String> suggest(CommandSourceStack source, String[] args) {
            if (!plugin.getServer().isPrimaryThread()) {
                return List.of();
            }
            Actor actor = Actor.from(source.getSender());
            String canonical = canonical(label);
            if (!actor.hasPermission("mcbunt.admin." + canonical)) {
                return List.of();
            }
            if (canonical.equals("restart") && args.length <= 1) {
                String prefix = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
                return List.of("cancel", Integer.toString(config.restartDefaultSeconds()))
                        .stream().filter(value -> value.startsWith(prefix)).toList();
            }
            if (TARGET_COMMANDS.contains(canonical) && args.length <= 1) {
                String prefix = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
                return plugin.getServer().getOnlinePlayers().stream().map(Player::getName)
                        .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix)).sorted().toList();
            }
            return List.of();
        }
    }

    private void handle(String label, CommandSender sender, String[] args) {
        String command = canonical(label);
        Actor actor = Actor.from(sender);
        UUID operation = UUID.randomUUID();
        if (!actor.supported()) {
            reject(command, operation, actor, args, Outcome.DENIED, "此入口仅支持玩家、服务器控制台和 RCON。");
            return;
        }
        if (!actor.hasPermission("mcbunt.admin." + command)) {
            reject(command, operation, actor, args, Outcome.DENIED, "你没有执行此命令的权限。");
            return;
        }
        try {
            switch (command) {
                case "kick" -> reply(sender, moderation.kick(actor, requireTarget(args),
                        CommandArguments.kickReason(args, config.kickDefaultReason()), operation).message());
                case "ban" -> {
                    var request = CommandArguments.punishment(args, config.banDefaultDuration(), config.banDefaultReason());
                    reply(sender, moderation.ban(actor, request.target(), request.duration(), request.reason(), operation).message());
                }
                case "unban" -> reply(sender, moderation.unban(actor, CommandArguments.targetOnly(args), operation).message());
                case "mute" -> {
                    var request = CommandArguments.punishment(args, config.muteDefaultDuration(), config.muteDefaultReason());
                    reply(sender, moderation.mute(actor, request.target(), request.duration(), request.reason(), false, operation).message());
                }
                case "unmute" -> reply(sender, moderation.unmute(actor, CommandArguments.targetOnly(args), operation).message());
                case "muteinfo" -> showMute(sender, CommandArguments.targetOnly(args));
                case "online" -> showOnline(sender, args);
                case "restart" -> {
                    Result result = args.length == 1 && args[0].equals("cancel")
                            ? restart.cancel(actor, operation)
                            : restart.schedule(actor, CommandArguments.restartSeconds(args, config.restartDefaultSeconds()), operation);
                    reply(sender, result.message());
                }
                case "broadcast" -> broadcast(actor, CommandArguments.body(args), operation);
                default -> throw new IllegalStateException("未知插件命令：" + command);
            }
        } catch (IllegalArgumentException ex) {
            reject(command, operation, actor, args, Outcome.INVALID, ex.getMessage());
        } catch (RuntimeException ex) {
            plugin.getLogger().log(Level.SEVERE, "管理命令处理失败，操作编号 " + operation, ex);
            reject(command, operation, actor, args, Outcome.FAILED, "操作失败，请查看服务端日志。操作编号：" + operation);
        }
    }

    private void showMute(CommandSender sender, String target) {
        Optional<MuteRecord> active = moderation.muteInfo(target);
        if (active.isEmpty()) {
            reply(sender, target + " 没有有效禁言。");
            return;
        }
        MuteRecord mute = active.get();
        String expires = mute.expiresAt() == null ? "永久" : mute.expiresAt().toString();
        long remaining = mute.expiresAt() == null ? 0
                : Math.max(0, Duration.between(Instant.now(), mute.expiresAt()).getSeconds());
        reply(sender, mute.name() + " 已被禁言；理由：" + mute.reason() + "；操作者：" + mute.actor()
                + "；到期：" + expires + (mute.expiresAt() == null ? "" : "；剩余：" + remaining + "秒"));
    }

    private void showOnline(CommandSender sender, String[] args) {
        if (args.length != 0) {
            throw new IllegalArgumentException("用法：/online 或 /list");
        }
        List<String> names = plugin.getServer().getOnlinePlayers().stream().map(Player::getName).sorted().toList();
        reply(sender, "在线玩家 " + names.size() + "/" + plugin.getServer().getMaxPlayers()
                + "：" + (names.isEmpty() ? "无" : String.join("、", names)));
    }

    private void broadcast(Actor actor, String text, UUID operation) {
        Component announcement = Component.text("【公告】" + text);
        plugin.getServer().getOnlinePlayers().forEach(player -> player.sendMessage(announcement));
        plugin.getServer().getConsoleSender().sendMessage(announcement);
        audit.log(AuditEvent.of("broadcast", operation, actor, Outcome.APPLIED).with("content", text));
        reply(actor.sender(), "公告已发送。");
    }

    private void reject(String command, UUID operation, Actor actor, String[] args, Outcome result, String message) {
        if (!QUERIES.contains(command)) {
            AuditEvent event = AuditEvent.of(command, operation, actor, result).with("detail", message);
            if (TARGET_COMMANDS.contains(command) && args.length > 0) {
                event = event.with("targetName", args[0]);
            }
            audit.log(event);
        }
        reply(actor.sender(), message);
    }

    private static String requireTarget(String[] args) {
        if (args.length == 0 || args[0].isBlank()) {
            throw new IllegalArgumentException("请提供玩家名称或 UUID。");
        }
        return args[0];
    }

    private static String canonical(String label) {
        return switch (label) {
            case "pardon" -> "unban";
            case "list" -> "online";
            default -> label;
        };
    }

    private static void reply(CommandSender sender, String message) {
        if (sender != null) {
            sender.sendMessage(PREFIX.append(Component.text(message)));
        }
    }
}
