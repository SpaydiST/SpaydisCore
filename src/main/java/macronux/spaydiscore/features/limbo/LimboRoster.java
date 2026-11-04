package macronux.spaydiscore.features.limbo;

import macronux.spaydiscore.features.allergy.NetheriteAllergyModule;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Persistent list of everybody who is in Limbo, online or not, plus releases queued for
 * players who were offline when an administrator released them.
 */
final class LimboRoster {
    private final File file;
    private final Logger logger;
    private final Map<UUID, String> names = new LinkedHashMap<>();
    private final Set<UUID> pendingRelease = new LinkedHashSet<>();

    LimboRoster(File dataFolder, Logger logger) {
        this.file = dataFolder == null ? null : new File(dataFolder, "limbo-roster.yml");
        this.logger = logger;
        load();
    }

    private void load() {
        names.clear();
        pendingRelease.clear();
        if (file == null || !file.isFile()) return;
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection players = config.getConfigurationSection("players");
        if (players != null) {
            for (String key : players.getKeys(false)) {
                UUID uuid = parse(key);
                if (uuid != null) names.put(uuid, players.getString(key, key));
            }
        }
        for (String raw : config.getStringList("pending-release")) {
            UUID uuid = parse(raw);
            if (uuid != null) pendingRelease.add(uuid);
        }
    }

    private static UUID parse(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private void save() {
        if (file == null) return;
        YamlConfiguration config = new YamlConfiguration();
        for (Map.Entry<UUID, String> entry : names.entrySet()) {
            config.set("players." + entry.getKey(), entry.getValue());
        }
        List<String> pending = new ArrayList<>();
        for (UUID uuid : pendingRelease) pending.add(uuid.toString());
        config.set("pending-release", pending);
        try {
            NetheriteAllergyModule.writeAtomically(file.toPath(), config.saveToString());
        } catch (IOException exception) {
            logger.warning("Could not save " + file.getName() + ": " + exception.getMessage());
        }
    }

    synchronized void add(UUID uuid, String name) {
        if (uuid == null || name == null) return;
        if (!name.equals(names.put(uuid, name))) save();
    }

    synchronized void remove(UUID uuid) {
        boolean changed = names.remove(uuid) != null;
        changed |= pendingRelease.remove(uuid);
        if (changed) save();
    }

    synchronized Map<UUID, String> snapshot() {
        return new LinkedHashMap<>(names);
    }

    synchronized UUID findByName(String name) {
        if (name == null) return null;
        for (Map.Entry<UUID, String> entry : names.entrySet()) {
            if (entry.getValue().equalsIgnoreCase(name)) return entry.getKey();
        }
        return null;
    }

    synchronized String nameOf(UUID uuid) {
        return names.get(uuid);
    }

    synchronized boolean contains(UUID uuid) {
        return names.containsKey(uuid);
    }

    synchronized void queueRelease(UUID uuid) {
        if (pendingRelease.add(uuid)) save();
    }

    synchronized boolean isQueued(UUID uuid) {
        return pendingRelease.contains(uuid);
    }

    /** Takes the queued release for a player who has just logged in. */
    synchronized boolean takeQueuedRelease(UUID uuid) {
        if (!pendingRelease.remove(uuid)) return false;
        save();
        return true;
    }

    synchronized List<String> namesStartingWith(String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        for (String name : names.values()) {
            if (name.toLowerCase(Locale.ROOT).startsWith(lower)) result.add(name);
        }
        return result;
    }
}
