package mcbunt.admin.command;

import mcbunt.admin.core.DurationSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class CommandArgumentsTest {
    private static final DurationSpec PERMANENT = new DurationSpec(null);

    @ParameterizedTest
    @ValueSource(strings = {"2hh", "1h30m", "0m", "-1h", "+2h", "30", "999999999999999999999999d", "−2h", "２h", "PERM"})
    void malformedDurationNeverBecomesPermanentBanReason(String token) {
        assertThrows(IllegalArgumentException.class,
                () -> CommandArguments.punishment(new String[]{"Alex", token, "理由"}, PERMANENT, "默认理由"));
    }

    @Test
    void reasonWithoutDurationUsesConfiguredDefault() {
        var result = CommandArguments.punishment(new String[]{"Alex", "恶意", "破坏"}, PERMANENT, "默认理由");
        assertSame(PERMANENT, result.duration());
        assertEquals("恶意 破坏", result.reason());
    }

    @Test
    void explicitDurationAllowsNumericReason() {
        var result = CommandArguments.punishment(new String[]{"Alex", "perm", "123", "次违规"}, PERMANENT, "默认理由");
        assertNull(result.duration().duration());
        assertEquals("123 次违规", result.reason());
    }

    @Test
    void timedPunishmentAndMissingReasonAreUnambiguous() {
        var result = CommandArguments.punishment(new String[]{"Alex", "2h"}, PERMANENT, "默认理由");
        assertEquals(Duration.ofHours(2), result.duration().duration());
        assertEquals("默认理由", result.reason());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "+1", "1.5", "86401", "9999999999999999999", "1h"})
    void restartRejectsInvalidSeconds(String input) {
        assertThrows(IllegalArgumentException.class,
                () -> CommandArguments.restartSeconds(new String[]{input}, 60));
    }

    @Test
    void restartUsesExplicitRangeAndDefault() {
        assertEquals(60, CommandArguments.restartSeconds(new String[0], 60));
        assertEquals(1, CommandArguments.restartSeconds(new String[]{"1"}, 60));
        assertEquals(86400, CommandArguments.restartSeconds(new String[]{"86400"}, 60));
    }

    @Test
    void noArgumentOrExtraTargetCannotChangePunishments() {
        assertThrows(IllegalArgumentException.class, () -> CommandArguments.targetOnly(new String[0]));
        assertThrows(IllegalArgumentException.class, () -> CommandArguments.targetOnly(new String[]{"Alex", "extra"}));
        assertThrows(IllegalArgumentException.class, () -> CommandArguments.body(new String[0]));
    }
}
