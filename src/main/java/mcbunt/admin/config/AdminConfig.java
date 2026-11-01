package mcbunt.admin.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import mcbunt.admin.core.DurationSpec;
import org.bukkit.configuration.ConfigurationSection;

/** Validated startup configuration; no runtime reload. See ENG-ARCH. */
public record AdminConfig(DurationSpec banDefaultDuration, DurationSpec muteDefaultDuration,
        String banDefaultReason, String muteDefaultReason, String kickDefaultReason,
        int restartDefaultSeconds, List<Integer> restartAnnounceSeconds,
        boolean antispamEnabled, int antispamWindowSeconds, int antispamMaxMessages,
        boolean duplicateCheck, String antispamAction, int autoMuteSeconds,
        Set<String> loggingRecord, int logMaxSizeMb, int logKeepFiles) {
    public AdminConfig {
        restartAnnounceSeconds = List.copyOf(restartAnnounceSeconds);
        loggingRecord = Set.copyOf(loggingRecord);
    }

    public static AdminConfig load(ConfigurationSection config) {
        for (String key : List.of("ban","mute","messages","messages.default-reasons","restart","antispam","logging")) {
            if (config.contains(key) && !config.isConfigurationSection(key)) throw bad(key);
        }
        if (integer(config, "schema-version", 1, 1, 1) != 1) throw bad("schema-version");
        List<Integer> nodes = new ArrayList<>();
        Object raw = config.get("restart.announce-seconds", List.of(60,30,10,5,4,3,2,1));
        if (!(raw instanceof List<?> values)) throw bad("restart.announce-seconds");
        for (Object node : values) {
            if (!(node instanceof Integer value) || value < 1 || value > 86400) throw bad("restart.announce-seconds");
            if (!nodes.contains(value)) nodes.add(value);
        }
        nodes.sort(Collections.reverseOrder());
        Set<String> categories = new LinkedHashSet<>();
        Set<String> allowed = Set.of("ban","unban","mute","unmute","kick","broadcast","antispam","restart");
        Object logging = config.get("logging.record", List.copyOf(allowed));
        if (!(logging instanceof List<?> loggingValues)) throw bad("logging.record");
        for (Object value : loggingValues) {
            if (!(value instanceof String item) || !allowed.contains(item)) throw bad("logging.record");
            categories.add(item);
        }
        String action = string(config, "antispam.action", "cancel");
        if (!Set.of("cancel","warn","mute","kick").contains(action)) throw bad("antispam.action");
        return new AdminConfig(duration(config,"ban.default-duration","perm"),
                duration(config,"mute.default-duration","30m"),
                string(config,"messages.default-reasons.ban","违反服务器规则"),
                string(config,"messages.default-reasons.mute","违反聊天规则"),
                string(config,"messages.default-reasons.kick","管理员已将你移出服务器"),
                integer(config,"restart.default-seconds",60,1,86400), nodes,
                bool(config,"antispam.enabled",true), integer(config,"antispam.window-seconds",5,1,Integer.MAX_VALUE),
                integer(config,"antispam.max-messages",4,1,Integer.MAX_VALUE),
                bool(config,"antispam.duplicate-check",true),action,
                integer(config,"antispam.auto-mute-seconds",120,1,Integer.MAX_VALUE), categories,
                integer(config,"logging.max-size-mb",5,1,Integer.MAX_VALUE),
                integer(config,"logging.keep-files",10,0,Integer.MAX_VALUE));
    }
    private static DurationSpec duration(ConfigurationSection config,String key,String fallback) {
        try { return DurationSpec.parse(string(config,key,fallback)); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("配置键 " + key + "：" + e.getMessage(), e); }
    }
    private static String string(ConfigurationSection config,String key,String fallback) {
        Object value = config.get(key,fallback);
        if (!(value instanceof String text) || text.isBlank()) throw bad(key);
        return text;
    }
    private static boolean bool(ConfigurationSection config,String key,boolean fallback) {
        Object value = config.get(key,fallback);
        if (!(value instanceof Boolean result)) throw bad(key);
        return result;
    }
    private static int integer(ConfigurationSection config,String key,int fallback,int min,int max) {
        Object value = config.get(key,fallback);
        if (!(value instanceof Integer result) || result < min || result > max) throw bad(key);
        return result;
    }
    private static IllegalArgumentException bad(String key) {
        return new IllegalArgumentException("配置键类型、值或范围无效：" + key);
    }
}
