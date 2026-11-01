package mcbunt.admin.command;

import mcbunt.admin.core.DurationSpec;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.regex.Pattern;

/** User-facing command grammar; see SYS-COMMANDS. */
public final class CommandArguments {
    private static final Pattern DURATION_INTENT = Pattern.compile("^[+\\-−]?\\p{Nd}.*", Pattern.DOTALL);

    private CommandArguments() {}

    public record Punishment(String target, DurationSpec duration, String reason) {}

    public static Punishment punishment(String[] args, DurationSpec defaultDuration, String defaultReason) {
        requireTarget(args);
        DurationSpec duration = defaultDuration;
        int reasonStart = 1;
        if (args.length > 1 && looksLikeDuration(args[1])) {
            duration = DurationSpec.parse(args[1]);
            reasonStart = 2;
        }
        String reason = joined(args, reasonStart);
        return new Punishment(args[0], duration, reason.isBlank() ? defaultReason : reason);
    }

    public static String targetOnly(String[] args) {
        requireTarget(args);
        if (args.length != 1) {
            throw new IllegalArgumentException("此命令只接受一个玩家名称或 UUID。");
        }
        return args[0];
    }

    public static String kickReason(String[] args, String defaultReason) {
        requireTarget(args);
        String reason = joined(args, 1);
        return reason.isBlank() ? defaultReason : reason;
    }

    public static String body(String[] args) {
        String value = joined(args, 0);
        if (value.isBlank()) {
            throw new IllegalArgumentException("公告正文不能为空。");
        }
        return value;
    }

    public static int restartSeconds(String[] args, int defaultSeconds) {
        if (args.length == 0) {
            return defaultSeconds;
        }
        if (args.length != 1 || !args[0].matches("[0-9]+")) {
            throw new IllegalArgumentException("用法：/restart [1～86400 的整数秒数] 或 /restart cancel");
        }
        try {
            int seconds = Integer.parseInt(args[0]);
            if (seconds < 1 || seconds > 86400) {
                throw new IllegalArgumentException("重启秒数必须在 1～86400 之间。");
            }
            return seconds;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("重启秒数超出有效范围。", ex);
        }
    }

    static boolean looksLikeDuration(String token) {
        String normalized = Normalizer.normalize(token, Normalizer.Form.NFKC);
        return token.equalsIgnoreCase("perm") || DURATION_INTENT.matcher(normalized).matches();
    }

    private static void requireTarget(String[] args) {
        if (args.length == 0 || args[0].isBlank()) {
            throw new IllegalArgumentException("请提供玩家名称或 UUID。");
        }
    }

    private static String joined(String[] args, int from) {
        return from >= args.length ? "" : String.join(" ", Arrays.copyOfRange(args, from, args.length)).strip();
    }
}
