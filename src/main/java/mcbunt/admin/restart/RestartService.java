package mcbunt.admin.restart;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Level;
import mcbunt.admin.audit.AuditEvent;
import mcbunt.admin.audit.AuditLogger;
import mcbunt.admin.config.AdminConfig;
import mcbunt.admin.core.Actor;
import mcbunt.admin.core.Outcome;
import mcbunt.admin.core.Result;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.BlockCommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/** One pending restart plan; an external supervisor owns process relaunch. */
public final class RestartService implements AutoCloseable {
    @FunctionalInterface
    public interface IOAction {
        void run() throws Exception;
    }

    private record Plan(Actor actor, UUID operationId, Countdown countdown) {}

    private static final String PERMISSION = "mcbunt.admin.restart";
    private static final Duration FLUSH_TIMEOUT = Duration.ofSeconds(3);
    private final JavaPlugin plugin;
    private final AdminConfig config;
    private final AuditLogger audit;
    private final IOAction beforeStop;
    private Plan active;
    private BukkitTask task;
    private boolean closed;

    public RestartService(JavaPlugin plugin, AdminConfig config, AuditLogger audit,
                          IOAction beforeStop) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.config = Objects.requireNonNull(config, "config");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.beforeStop = Objects.requireNonNull(beforeStop, "beforeStop");
    }

    public Result schedule(Actor actor, int seconds, UUID operationId) {
        requireMainThread();
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(operationId, "operationId");
        if (!permitted(actor)) {
            return reply(actor, operationId, "SCHEDULE", Outcome.DENIED, "你没有安排重启的权限");
        }
        if (closed) {
            return reply(actor, operationId, "SCHEDULE", Outcome.FAILED, "重启服务已关闭");
        }
        if (seconds < 1 || seconds > 86_400) {
            return reply(actor, operationId, "SCHEDULE", Outcome.INVALID, "重启秒数必须是 1～86400 的整数");
        }
        if (active != null) {
            return reply(actor, operationId, "SCHEDULE", Outcome.NO_CHANGE, "已有重启计划，请先取消原计划");
        }
        Plan plan = new Plan(actor, operationId,
                new Countdown(seconds, config.restartAnnounceSeconds(), System::nanoTime));
        try {
            // An initial delay prevents a callback before the plan is published.
            task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
            active = plan;
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.SEVERE, "无法安排重启检查任务", exception);
            return reply(actor, operationId, "SCHEDULE", Outcome.FAILED, "无法安排重启计划，请检查服务端日志");
        }
        audit.log(event(actor, operationId, "SCHEDULE", Outcome.APPLIED)
                .with("durationSeconds", seconds).with("detail", "倒计时已安排"));
        broadcast("服务器将在 " + seconds + " 秒后重启，管理员可使用 /restart cancel 取消。");
        return result(Outcome.APPLIED, "已安排 " + seconds + " 秒后的重启");
    }

    public Result cancel(Actor actor, UUID operationId) {
        requireMainThread();
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(operationId, "operationId");
        if (!permitted(actor)) {
            return reply(actor, operationId, "CANCEL", Outcome.DENIED, "你没有取消重启的权限");
        }
        Plan plan = active;
        if (plan == null) {
            return reply(actor, operationId, "CANCEL", Outcome.NO_CHANGE, "没有待执行的重启计划");
        }
        if (!plan.countdown().cancel()) {
            return reply(actor, operationId, "CANCEL", Outcome.NO_CHANGE, "服务器关闭已经开始，无法取消");
        }
        active = null;
        cancelTask();
        audit.log(event(actor, operationId, "CANCEL", Outcome.APPLIED)
                .with("planOperationId", plan.operationId().toString())
                .with("detail", "重启计划已取消"));
        broadcast("服务器重启计划已取消。");
        return result(Outcome.APPLIED, "已取消重启计划");
    }

    private void tick() {
        Plan plan = active;
        if (closed || plan == null) {
            return;
        }
        Countdown.Tick tick = plan.countdown().poll();
        if (tick.announce()) {
            broadcast("服务器将在 " + tick.remainingSeconds() + " 秒后重启。");
        }
        if (tick.execute()) {
            cancelTask();
            execute(plan);
        }
    }

    private void execute(Plan plan) {
        broadcast("服务器即将重启，正在保存数据。");
        try {
            beforeStop.run();
        } catch (Exception exception) {
            active = null;
            plugin.getLogger().log(Level.SEVERE, "处罚数据保存失败，已取消本次重启", exception);
            audit.log(event(plan.actor(), plan.operationId(), "EXECUTE", Outcome.FAILED)
                    .with("detail", "处罚数据保存失败，已取消关闭：" + exception.getMessage()));
            notifyActor(plan.actor(), "处罚数据保存失败，已取消本次重启，请检查服务端日志。");
            broadcast("数据保存失败，本次重启已取消。");
            return;
        }
        audit.log(event(plan.actor(), plan.operationId(), "EXECUTE", Outcome.APPLIED)
                .with("detail", "正在请求优雅关闭；外部进程重新拉起尚未验证"));
        if (!audit.flush(FLUSH_TIMEOUT)) {
            plugin.getLogger().warning("重启前审计日志未完成刷新，将继续优雅关闭服务器");
            notifyActor(plan.actor(), "审计日志未完成刷新，服务器仍将优雅关闭。");
        }
        try {
            plugin.getServer().shutdown();
        } catch (RuntimeException exception) {
            active = null;
            plugin.getLogger().log(Level.SEVERE, "请求服务器优雅关闭失败", exception);
            audit.log(event(plan.actor(), plan.operationId(), "EXECUTE", Outcome.FAILED)
                    .with("detail", "请求优雅关闭失败：" + exception.getMessage()));
            notifyActor(plan.actor(), "服务器关闭请求失败，请检查服务端日志。");
            broadcast("服务器关闭请求失败，重启未完成。");
        }
    }

    private Result reply(Actor actor, UUID operationId, String phase,
                         Outcome outcome, String message) {
        audit.log(event(actor, operationId, phase, outcome).with("detail", message));
        return result(outcome, message);
    }

    private static Result result(Outcome outcome, String message) {
        return new Result(outcome, message, null, null, null);
    }

    private static AuditEvent event(Actor actor, UUID operationId, String phase, Outcome outcome) {
        return AuditEvent.of("restart", operationId, actor, outcome).with("phase", phase);
    }

    private static boolean permitted(Actor actor) {
        return !(actor.sender() instanceof BlockCommandSender) && actor.hasPermission(PERMISSION);
    }

    private void broadcast(String message) {
        plugin.getServer().broadcast(Component.text("【管理】" + message));
    }

    private static void notifyActor(Actor actor, String message) {
        if (actor.sender() != null) {
            actor.sender().sendMessage(Component.text("【管理】" + message));
        }
    }

    private void cancelTask() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private static void requireMainThread() {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("重启服务只能在服务器主线程调用");
        }
    }

    /** Plugin disable cancels a plan; it never initiates server shutdown. */
    @Override
    public void close() {
        requireMainThread();
        if (closed) {
            return;
        }
        closed = true;
        cancelTask();
        if (active != null && active.countdown().cancel()) {
            audit.log(event(active.actor(), active.operationId(), "CANCEL", Outcome.APPLIED)
                    .with("detail", "插件关闭，未到期计划已取消"));
        }
        active = null;
    }
}
