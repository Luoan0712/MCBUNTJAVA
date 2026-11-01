package mcbunt.admin;

import java.io.IOException;
import java.io.File;
import mcbunt.admin.audit.AuditLogger;
import mcbunt.admin.chat.ChatListener;
import mcbunt.admin.command.AdminCommands;
import mcbunt.admin.config.AdminConfig;
import mcbunt.admin.moderation.ModerationService;
import mcbunt.admin.restart.RestartService;
import net.kyori.adventure.text.Component;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.configuration.file.YamlConfiguration;

/** Composition root: configuration and persistent state load before accepting commands. */
public final class MCBUNTAdminPlugin extends JavaPlugin {
    private AuditLogger audit;
    private ModerationService moderation;
    private RestartService restart;
    private ChatListener chat;

    @Override public void onEnable() {
        try {
            saveDefaultConfig();
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.load(new File(getDataFolder(), "config.yml"));
            AdminConfig config = AdminConfig.load(yaml);
            audit = new AuditLogger(getDataFolder().toPath(), config.logMaxSizeMb(), config.logKeepFiles(),
                    config.loggingRecord(), (actor, message) -> {
                        getLogger().severe(message);
                        if (actor != null && actor.sender() != null && isEnabled())
                            getServer().getScheduler().runTask(this,
                                    () -> actor.sender().sendMessage(Component.text("管理日志记录失败：" + message)));
                    });
            moderation = new ModerationService(this, config, audit);
            getServer().getPluginManager().registerEvents(moderation, this);
            restart = new RestartService(this, config, audit, moderation::save);
            chat = new ChatListener(this, config, audit, moderation);
            getServer().getPluginManager().registerEvents(chat, this);
            chat.start();
            new AdminCommands(this, config, audit, moderation, restart).register();
            getLogger().info("MCBUNTAdmin 已启用；玩家处罚按 UUID，不实施 IP 封禁。");
        } catch (Exception e) {
            getLogger().log(java.util.logging.Level.SEVERE, "初始化失败；插件管理功能未启用", e);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override public void onDisable() {
        if (chat != null) chat.close();
        if (restart != null) restart.close();
        if (moderation != null) {
            try { moderation.save(); }
            catch (IOException e) { getLogger().log(java.util.logging.Level.SEVERE, "保存处罚状态失败", e); }
        }
        if (audit != null) audit.close();
    }
}
