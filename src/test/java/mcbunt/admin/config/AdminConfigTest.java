package mcbunt.admin.config;

import static org.junit.jupiter.api.Assertions.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class AdminConfigTest {
    @Test void defaultsMatchConfigurationContract() {
        var config = AdminConfig.load(new YamlConfiguration());
        assertNull(config.banDefaultDuration().seconds());
        assertEquals(1800L, config.muteDefaultDuration().seconds());
        assertEquals(4, config.antispamMaxMessages());
        assertTrue(config.loggingRecord().contains("restart"));
    }
    @Test void malformedFieldsRejectStartupInsteadOfChangingPunishment() {
        for (String key : new String[]{"antispam.window-seconds","restart.default-seconds","logging.keep-files"}) {
            var yaml = new YamlConfiguration(); yaml.set(key, "5");
            assertThrows(IllegalArgumentException.class, () -> AdminConfig.load(yaml));
        }
        var yaml = new YamlConfiguration(); yaml.set("ban.default-duration", "2hh");
        assertThrows(IllegalArgumentException.class, () -> AdminConfig.load(yaml));
    }
}
