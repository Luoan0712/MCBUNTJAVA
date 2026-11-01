package mcbunt.admin.core;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.regex.Pattern;

/** A null duration means permanent; malformed input never implies permanence. */
public record DurationSpec(Duration duration) {
    private static final Pattern FINITE = Pattern.compile("[0-9]+[smhd]");

    public DurationSpec {
        if (duration != null && (duration.isZero() || duration.isNegative() || duration.getNano() != 0))
            throw new IllegalArgumentException("时长须为正整数秒或 perm");
    }

    public static DurationSpec parse(String text) {
        if ("perm".equals(text)) return new DurationSpec(null);
        if (text == null || !FINITE.matcher(text).matches())
            throw new IllegalArgumentException("时长格式：正整数加 s/m/h/d，或 perm；不支持复合时长");
        try {
            long count = Long.parseLong(text.substring(0, text.length() - 1));
            long multiplier = switch (text.charAt(text.length() - 1)) {
                case 's' -> 1; case 'm' -> 60; case 'h' -> 3600; case 'd' -> 86400;
                default -> throw new IllegalArgumentException("未知时长单位");
            };
            DurationSpec result = new DurationSpec(Duration.ofSeconds(Math.multiplyExact(count, multiplier)));
            result.expiresAt(Instant.now());
            return result;
        } catch (ArithmeticException | DateTimeException e) {
            throw new IllegalArgumentException("时长超出可表示范围", e);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("时长超出可表示范围", e);
        }
    }

    public Instant expiresAt(Instant start) {
        if (duration == null) return null;
        try {
            Instant expiry = start.plus(duration);
            expiry.toEpochMilli();
            return expiry;
        }
        catch (ArithmeticException | DateTimeException e) { throw new IllegalArgumentException("到期时间溢出", e); }
    }
    public Long seconds() { return duration == null ? null : duration.getSeconds(); }
    public String display() {
        if (duration == null) return "永久";
        long seconds = duration.getSeconds();
        if (seconds % 86400 == 0) return seconds / 86400 + "天";
        if (seconds % 3600 == 0) return seconds / 3600 + "小时";
        if (seconds % 60 == 0) return seconds / 60 + "分钟";
        return seconds + "秒";
    }
}
