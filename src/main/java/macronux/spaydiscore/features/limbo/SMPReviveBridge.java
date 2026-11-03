package macronux.spaydiscore.features.limbo;

import io.papermc.paper.datacomponent.item.ResolvableProfile;
import com.destroystokyo.paper.profile.PlayerProfile;
import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.HashMap;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/** Optional, reflection-only bridge to SMPRevive. */
final class SMPReviveBridge {

    enum IntegrationMode {
        ABSENT,
        V1,
        LEGACY,
        UNSUPPORTED
    }

    private final JavaPlugin plugin;
    private boolean warningIssued;
    private File cachedPlayersFile;
    private long cachedPlayersLastModified = Long.MIN_VALUE;
    private long cachedPlayersLength = Long.MIN_VALUE;
    private boolean cachedRosterLoaded;
    private List<UUID> cachedPlayers = List.of();
    private final Map<UUID, ResolvableProfile> profileCache = new HashMap<>();

    SMPReviveBridge(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    boolean isInstalled() {
        return resolveEnabledPlugin() != null;
    }

    IntegrationMode capability() {
        Plugin revive = resolveEnabledPlugin();
        if (revive == null) {
            return IntegrationMode.ABSENT;
        }
        try {
            if (hasMethod(revive.getClass(), "reviveFromLimbo", Player.class, Location.class)) {
                return IntegrationMode.V1;
            }
            Object altar = invokeNoArg(revive, "getAltarGui");
            if (altar != null && hasMethod(altar.getClass(),
                    "forceRevive", org.bukkit.OfflinePlayer.class, Player.class)) {
                return IntegrationMode.LEGACY;
            }
            return IntegrationMode.UNSUPPORTED;
        } catch (NoSuchMethodException ignored) {
            return IntegrationMode.UNSUPPORTED;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            warnOnce(exception);
            return IntegrationMode.UNSUPPORTED;
        }
    }

    /**
     * Must be checked before SMPRevive handles PlayerDeathEvent. Its free-first-death
     * branch marks the player as having died during that event, so checking only after
     * the event would be too late.
     */
    boolean isFirstDeathPending(Player player) {
        if (player == null) {
            return false;
        }
        Plugin revive = resolveEnabledPlugin();
        if (revive == null) {
            return false;
        }

        try {
            try {
                Method direct = revive.getClass().getMethod("isFirstDeathPending", Player.class);
                Object result = direct.invoke(revive, player);
                return result instanceof Boolean && (Boolean) result;
            } catch (NoSuchMethodException ignored) {
                // Older SMPRevive builds use the original data-manager shape.
            }

            Method freeFirstDeath = revive.getClass().getMethod("isFreeFirstDeath");
            Object enabled = freeFirstDeath.invoke(revive);
            if (!(enabled instanceof Boolean) || !((Boolean) enabled)) {
                return false;
            }

            Method getDataManager = revive.getClass().getMethod("getDataManager");
            Object dataManager = getDataManager.invoke(revive);
            if (dataManager == null) {
                return false;
            }
            Method hasDied = dataManager.getClass().getMethod("hasDied", UUID.class);
            Object alreadyDied = hasDied.invoke(dataManager, player.getUniqueId());
            return alreadyDied instanceof Boolean && !((Boolean) alreadyDied);
        } catch (NoSuchMethodException ignored) {
            // Older SMPRevive builds without the free-first-death API fail closed.
            return false;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            warnOnce(exception);
            return false;
        }
    }

    /**
     * True when SMPRevive already has a record for the player. Unknown or failed lookups
     * report true so a broken bridge never sends anybody to Limbo by mistake.
     */
    boolean knowsPlayer(Player player) {
        Plugin revive = resolveEnabledPlugin();
        if (revive == null || player == null) {
            return true;
        }
        try {
            Object known = revive.getClass().getMethod("hasPlayerData", UUID.class)
                    .invoke(revive, player.getUniqueId());
            return !(known instanceof Boolean) || (Boolean) known;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            warnOnce(exception);
            return true;
        }
    }

    boolean confirmsRevived(Player player) {
        return Boolean.FALSE.equals(playerDeathState(player));
    }

    /** Null means unavailable/unknown, not a confirmed living player. */
    Boolean playerDeathState(Player player) {
        if (player == null) {
            return null;
        }
        Plugin revive = resolveEnabledPlugin();
        if (revive == null) {
            return null;
        }

        try {
            Object dataManager = null;
            try {
                Method directHasPlayerData = revive.getClass().getMethod("hasPlayerData", UUID.class);
                Object knownPlayer = directHasPlayerData.invoke(revive, player.getUniqueId());
                if (!(knownPlayer instanceof Boolean) || !((Boolean) knownPlayer)) {
                    return null;
                }
            } catch (NoSuchMethodException ignored) {
                dataManager = revive.getClass().getMethod("getDataManager").invoke(revive);
                if (dataManager == null) {
                    return null;
                }
                try {
                    Method hasPlayerData = dataManager.getClass().getMethod("hasPlayerData", UUID.class);
                    Object knownPlayer = hasPlayerData.invoke(dataManager, player.getUniqueId());
                    if (!(knownPlayer instanceof Boolean) || !((Boolean) knownPlayer)) {
                        return null;
                    }
                } catch (NoSuchMethodException ignoredDataManagerMethod) {
                    // Older SMPRevive builds may expose only isDead(UUID).
                }
            }

            try {
                Method directIsDead = revive.getClass().getMethod("isPlayerDead", UUID.class);
                Object dead = directIsDead.invoke(revive, player.getUniqueId());
                return dead instanceof Boolean ? (Boolean) dead : null;
            } catch (NoSuchMethodException ignored) {
                if (dataManager == null) {
                    dataManager = revive.getClass().getMethod("getDataManager").invoke(revive);
                }
                if (dataManager == null) {
                    return null;
                }
                Method isDead = dataManager.getClass().getMethod("isDead", UUID.class);
                Object dead = isDead.invoke(dataManager, player.getUniqueId());
                return dead instanceof Boolean ? (Boolean) dead : null;
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            warnOnce(exception);
            return null;
        }
    }

    boolean reviveFromLimbo(Player player, Location location) {
        Plugin revive = resolveEnabledPlugin();
        if (revive == null) {
            return false;
        }

        try {
            Method method = revive.getClass().getMethod(
                    "reviveFromLimbo", Player.class, Location.class);
            Object result = method.invoke(revive, player, location);
            return result instanceof Boolean && (Boolean) result;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            warnOnce(exception);
            return false;
        }
    }

    boolean reviveLegacy(Player player) {
        Plugin revive = resolveEnabledPlugin();
        if (revive == null || player == null) {
            return false;
        }
        try {
            Object altar = revive.getClass().getMethod("getAltarGui").invoke(revive);
            if (altar == null) {
                return false;
            }
            Method force = altar.getClass().getMethod(
                    "forceRevive", org.bukkit.OfflinePlayer.class, Player.class);
            force.invoke(altar, player, (Object) null);
            return confirmsRevived(player);
        } catch (NoSuchMethodException ignored) {
            return false;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            warnOnce(exception);
            return false;
        }
    }

    ResolvableProfile randomPlayerProfile() {
        List<UUID> candidates = new ArrayList<>(cachedSmpPlayerIds(resolveEnabledPlugin()));
        Set<UUID> seen = new HashSet<>(candidates);
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (seen.add(player.getUniqueId())) {
                candidates.add(player.getUniqueId());
            }
        }

        while (!candidates.isEmpty()) {
            UUID uuid = candidates.remove(ThreadLocalRandom.current().nextInt(candidates.size()));
            ResolvableProfile cached = profileCache.get(uuid);
            if (cached != null) {
                return cached;
            }
            try {
                OfflinePlayer player = Bukkit.getOfflinePlayer(uuid);
                PlayerProfile profile = player.getPlayerProfile();
                if (profile != null && profile.getTextures() != null && !profile.getTextures().isEmpty()) {
                    ResolvableProfile resolved = ResolvableProfile.resolvableProfile(profile);
                    profileCache.put(uuid, resolved);
                    return resolved;
                }
            } catch (RuntimeException ignored) {
                // A missing/incomplete profile must not break the Limbo event.
            }
        }
        return null;
    }

    private List<UUID> cachedSmpPlayerIds(Plugin revive) {
        File playersFile = revive == null
                ? null : new File(revive.getDataFolder(), "players.yml");
        long lastModified = playersFile != null && playersFile.isFile()
                ? playersFile.lastModified() : -1L;
        long length = playersFile != null && playersFile.isFile()
                ? playersFile.length() : -1L;
        boolean sameFile = playersFile == null
                ? cachedPlayersFile == null : playersFile.equals(cachedPlayersFile);
        if (cachedRosterLoaded && sameFile
                && cachedPlayersLastModified == lastModified
                && cachedPlayersLength == length) {
            return cachedPlayers;
        }

        List<UUID> loaded = new ArrayList<>();
        if (playersFile != null && playersFile.isFile()) {
            try {
                ConfigurationSection players = YamlConfiguration.loadConfiguration(playersFile)
                        .getConfigurationSection("players");
                if (players != null) {
                    for (String value : players.getKeys(false)) {
                        try {
                            loaded.add(UUID.fromString(value));
                        } catch (IllegalArgumentException ignored) {
                            // Ignore malformed roster keys and keep valid player skins.
                        }
                    }
                }
            } catch (RuntimeException ignored) {
                // A broken optional roster falls back to online Bukkit profiles.
            }
        }
        cachedPlayersFile = playersFile;
        cachedPlayersLastModified = lastModified;
        cachedPlayersLength = length;
        cachedPlayers = List.copyOf(loaded);
        cachedRosterLoaded = true;
        profileCache.clear();
        return cachedPlayers;
    }

    private Plugin resolveEnabledPlugin() {
        Plugin direct = Bukkit.getPluginManager().getPlugin("SMPRevive");
        if (direct != null && direct.isEnabled()) {
            return direct;
        }
        for (Plugin candidate : Bukkit.getPluginManager().getPlugins()) {
            if (candidate != null && candidate.isEnabled()
                    && "SMPRevive".equalsIgnoreCase(candidate.getName())) {
                return candidate;
            }
        }
        return null;
    }

    private boolean hasMethod(Class<?> type, String name, Class<?>... parameterTypes) {
        try {
            type.getMethod(name, parameterTypes);
            return true;
        } catch (NoSuchMethodException exception) {
            return false;
        }
    }

    private Object invokeNoArg(Object target, String name) throws ReflectiveOperationException {
        return target.getClass().getMethod(name).invoke(target);
    }

    private void warnOnce(Throwable exception) {
        if (warningIssued) {
            return;
        }
        warningIssued = true;
        plugin.getLogger().warning("SMPRevive integration is unavailable: " + exception.getMessage());
    }
}
