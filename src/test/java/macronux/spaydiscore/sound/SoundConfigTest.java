package macronux.spaydiscore.sound;

import org.bukkit.configuration.file.YamlConfiguration;
import java.nio.file.Path;

public final class SoundConfigTest {
    public static void run() {
        var defaults = YamlConfiguration.loadConfiguration(Path.of("src/main/resources/sounds.yml").toFile());
        var oldInstall = new YamlConfiguration();
        oldInstall.set("enabled", true);
        oldInstall.set("allergy.sneeze.sound", "entity.panda.sneeze");
        oldInstall.setDefaults(defaults);
        require("spaydiscore:portal_charge".equals(SoundConfig.configuredSound(oldInstall, "portals.charge")),
                "an old sounds.yml inherits the new charge cue");
        require("spaydiscore:portal_open".equals(SoundConfig.configuredSound(oldInstall, "portals.open")),
                "an old sounds.yml inherits the new opening cue");
        oldInstall.set("portals.charge", "minecraft:block.portal.ambient");
        require("minecraft:block.portal.ambient".equals(SoundConfig.configuredSound(oldInstall, "portals.charge")),
                "local shorthand overrides bundled nested defaults");
        oldInstall.set("portals.charge", null);
        oldInstall.set("portals.charge.sound", "custom:opening/hum");
        require("custom:opening/hum".equals(SoundConfig.configuredSound(oldInstall, "portals.charge")),
                "nested local overrides remain valid");
        oldInstall.set("portals.charge.sound", "MemorySection[path='portals.charge']");
        require(SoundConfig.configuredSound(oldInstall, "portals.charge") == null, "invalid IDs cannot abort animations");
        oldInstall.set("portals.charge", "");
        require(SoundConfig.configuredSound(oldInstall, "portals.charge") == null, "blank shorthand disables the sound");
        oldInstall.set("portals.charge", 123);
        require(SoundConfig.configuredSound(oldInstall, "portals.charge") == null, "scalar values cannot become sound IDs");
        var section = new YamlConfiguration();
        section.set("cue.volume", 0.5);
        require(SoundConfig.configuredSound(section, "cue") == null, "a configuration section is never an ID");
        System.out.println("Sound upgrade and identifier checks passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
