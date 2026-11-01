package mcbunt.admin.core;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class DurationSpecTest {
    @Test void permanenceAndElapsedTime() {
        assertNull(DurationSpec.parse("perm").expiresAt(Instant.EPOCH));
        assertEquals(Instant.ofEpochSecond(7200), DurationSpec.parse("2h").expiresAt(Instant.EPOCH));
    }
    @Test void malformedDurationCannotBecomePermanent() {
        for (String value : new String[]{"2hh","1h30m","0m","-2h","5","1H","99999999999999999d"})
            assertThrows(IllegalArgumentException.class, () -> DurationSpec.parse(value), value);
    }
}
