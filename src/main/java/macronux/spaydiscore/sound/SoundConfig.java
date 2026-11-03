package macronux.spaydiscore.sound;

import macronux.spaydiscore.config.ConfigGuard;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Config-driven sound loader (same architecture as SMPRevive).
 * Structure: <path>.sound / <path>.volume / <path>.pitch (+ optional plain-string shorthand).
 */
public final class SoundConfig {

    private final JavaPlugin plugin;
    private FileConfiguration cfg;
    private boolean enabled = true;
    private float masterVolume = 1.0f;

    public SoundConfig(JavaPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        File file = new File(plugin.getDataFolder(), "sounds.yml");
        if (!file.exists()) {
            plugin.saveResource("sounds.yml", false);
        }
        cfg = YamlConfiguration.loadConfiguration(file);

        try (InputStream defaults = plugin.getResource("sounds.yml")) {
            if (defaults != null) {
                cfg.setDefaults(YamlConfiguration.loadConfiguration(
                        new InputStreamReader(defaults, StandardCharsets.UTF_8)));
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to load sound defaults: " + e.getMessage());
        }

        enabled = cfg.getBoolean("enabled", true);
        masterVolume = safeFloat(cfg.getDouble("master-volume", 1.0), 1.0, 4.0);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public float getMasterVolume() {
        return masterVolume;
    }

    /** Returns raw sound name string (e.g. "entity.villager.sneeze") or null. */
    public String getSound(String path) {
        return configuredSound(cfg, path);
    }

    static String configuredSound(FileConfiguration configuration, String path) {
        // Local shorthand wins over a bundled nested default. Sections are never sound IDs.
        Object local = configuration.get(path, null);
        Object value = local instanceof String ? local : configuration.get(path + ".sound");
        if (local != null && !(local instanceof String) && !(local instanceof ConfigurationSection)) return null;
        if (!(value instanceof String sound) || sound.isBlank()
                || !sound.matches("(?:[a-z0-9_.-]+:)?[a-z0-9_./-]+")) return null;
        return sound;
    }

    public boolean hasSound(String path) {
        return getSound(path) != null;
    }

    public float getVolume(String path) {
        if (cfg.get(path, null) instanceof String) return 1.0F;
        return safeFloat(cfg.contains(path + ".volume") ? cfg.getDouble(path + ".volume") : 1.0, 1.0, 4.0);
    }

    public float getPitch(String path) {
        if (cfg.get(path, null) instanceof String) return 1.0F;
        return safeFloat(cfg.contains(path + ".pitch") ? cfg.getDouble(path + ".pitch") : 1.0, 1.0, 2.0);
    }

    private float safeFloat(double value, double fallback, double maximum) {
        return (float) ConfigGuard.boundedFiniteNonNegative(value, fallback, maximum);
    }
}
