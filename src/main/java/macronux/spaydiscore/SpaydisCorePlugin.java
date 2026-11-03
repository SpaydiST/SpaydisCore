package macronux.spaydiscore;

import macronux.spaydiscore.commands.AdminMenuModule;
import macronux.spaydiscore.commands.PluginCommandModule;
import macronux.spaydiscore.features.allergy.NetheriteAllergyModule;
import macronux.spaydiscore.features.death.DeathMessageModule;
import macronux.spaydiscore.features.entities.EntityBehaviorModule;
import macronux.spaydiscore.features.limbo.LimbiificationManager;
import macronux.spaydiscore.features.limbo.LimboManager;
import macronux.spaydiscore.features.schizophrenia.SchizophreniaModule;
import macronux.spaydiscore.features.undead.UndeadCurseModule;
import macronux.spaydiscore.features.portals.EndPortalManager;
import macronux.spaydiscore.features.portals.NetherPortalManager;
import macronux.spaydiscore.messages.MessageManager;
import macronux.spaydiscore.sound.SoundConfig;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/** Plugin entry point: creates feature modules and coordinates their lifecycle. */
public final class SpaydisCorePlugin extends JavaPlugin {
    private MessageManager messages;
    private SoundConfig soundConfig;

    private NetherPortalManager netherPortalManager;
    private EndPortalManager endPortalManager;
    private LimbiificationManager limbiificationManager;
    private LimboManager limboManager;
    private Object limboVoiceChatIntegration;

    private EntityBehaviorModule entityModule;
    private NetheriteAllergyModule allergyModule;
    private DeathMessageModule deathMessageModule;
    private UndeadCurseModule undeadModule;
    private SchizophreniaModule schizophreniaModule;
    private PluginCommandModule commandModule;

    @Override
    public void onEnable() {
        try {
            saveDefaultConfig();
            super.reloadConfig();
            messages = new MessageManager(this);
        } catch (Exception exception) {
            getLogger().severe("Failed to prepare plugin configuration: " + exception.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        entityModule = new EntityBehaviorModule(this);
        allergyModule = new NetheriteAllergyModule(this, messages);
        deathMessageModule = new DeathMessageModule(this, messages);
        undeadModule = new UndeadCurseModule(this, messages);
        schizophreniaModule = new SchizophreniaModule(this, messages);

        try {
            loadModuleConfig();
        } catch (Exception exception) {
            getLogger().warning("Failed to load feature settings; using defaults: " + exception.getMessage());
            resetModuleConfig();
        }
        try {
            allergyModule.load();
        } catch (Exception exception) {
            getLogger().warning("Failed to load allergies.yml: " + exception.getMessage());
        }
        try {
            undeadModule.load();
        } catch (Exception exception) {
            getLogger().warning("Failed to load undead.yml: " + exception.getMessage());
        }
        try {
            schizophreniaModule.load();
        } catch (Exception exception) {
            getLogger().warning("Failed to load schizophrenia.yml: " + exception.getMessage());
        }
        try {
            soundConfig = new SoundConfig(this);
        } catch (Exception exception) {
            getLogger().warning("Failed to load sounds.yml: " + exception.getMessage());
            soundConfig = null;
        }

        // Register listeners before module start() methods schedule recurring work.
        getServer().getPluginManager().registerEvents(entityModule, this);
        getServer().getPluginManager().registerEvents(allergyModule, this);
        getServer().getPluginManager().registerEvents(deathMessageModule, this);
        getServer().getPluginManager().registerEvents(undeadModule, this);
        getServer().getPluginManager().registerEvents(schizophreniaModule, this);

        netherPortalManager = new NetherPortalManager(this);
        getServer().getPluginManager().registerEvents(netherPortalManager, this);
        netherPortalManager.start();
        endPortalManager = new EndPortalManager(this);
        getServer().getPluginManager().registerEvents(endPortalManager, this);
        endPortalManager.start();

        limbiificationManager = new LimbiificationManager(this);
        getServer().getPluginManager().registerEvents(limbiificationManager, this);
        for (Player player : Bukkit.getOnlinePlayers()) {
            limbiificationManager.migratePlayerItems(player);
        }
        limboManager = new LimboManager(this);
        getServer().getPluginManager().registerEvents(limboManager, this);
        limboManager.start();
        startLimboVoiceChatIntegration();

        AdminMenuModule menuModule = new AdminMenuModule(this, messages, netherPortalManager,
                endPortalManager, limboManager, entityModule, allergyModule, undeadModule, schizophreniaModule);
        getServer().getPluginManager().registerEvents(menuModule, this);
        commandModule = new PluginCommandModule(this, messages, netherPortalManager, endPortalManager,
                limbiificationManager, limboManager, entityModule, allergyModule, undeadModule, schizophreniaModule, menuModule);
        commandModule.register();
        entityModule.start();
        allergyModule.start();
        undeadModule.start();
        schizophreniaModule.start();

        getLogger().info("SpaydisCore plugin enabled!");
    }

    private void loadModuleConfig() {
        entityModule.loadConfig();
        allergyModule.reload();
        deathMessageModule.reload();
        undeadModule.reload();
        schizophreniaModule.reload();
    }

    private void resetModuleConfig() {
        entityModule.resetSettingsToDefaults();
        allergyModule.resetSettingsToDefaults();
        deathMessageModule.resetSettingsToDefaults();
    }

    @Override
    public void reloadConfig() {
        super.reloadConfig();
        if (messages != null) messages.reload();
        if (soundConfig != null) soundConfig.reload();
        if (netherPortalManager != null) netherPortalManager.reloadMode();
        if (endPortalManager != null) endPortalManager.reloadMode();
        if (limboManager != null) {
            limboManager.reload();
            startLimboVoiceChatIntegration();
        }
        if (entityModule != null) entityModule.reload();
        if (allergyModule != null) allergyModule.reload();
        if (deathMessageModule != null) deathMessageModule.reload();
        if (undeadModule != null) undeadModule.reload();
        if (schizophreniaModule != null) schizophreniaModule.reload();
        getLogger().info("Configuration reloaded!");
    }

    /** Keeps the optional VoiceChat bridge outside the plugin's mandatory classpath. */
    private void startLimboVoiceChatIntegration() {
        if (limboManager == null
                || getServer().getPluginManager().getPlugin("voicechat") == null) {
            if (limboVoiceChatIntegration != null) {
                shutdownLimboVoiceChatIntegration();
            }
            if (limboManager != null && limboManager.isVoiceEchoEnabled()) {
                getLogger().info("Simple Voice Chat not found; Limbo echo is unavailable.");
            }
            return;
        }
        if (limboVoiceChatIntegration != null) {
            try {
                limboVoiceChatIntegration.getClass().getMethod("reloadPhraseMode")
                        .invoke(limboVoiceChatIntegration);
            } catch (ReflectiveOperationException | LinkageError | RuntimeException exception) {
                getLogger().warning("Could not reload Limbo voice phrase mode: "
                        + exception.getMessage());
            }
            return;
        }

        try {
            Class<?> integrationClass = Class.forName(
                    "macronux.spaydiscore.features.limbo.LimboVoiceChatIntegration", true,
                    getClass().getClassLoader());
            Object integration = integrationClass
                    .getConstructor(JavaPlugin.class, LimboManager.class)
                    .newInstance(this, limboManager);
            // Keep a handle before starting so a partial start can always be cleaned up.
            limboVoiceChatIntegration = integration;
            integrationClass.getMethod("start").invoke(integration);
            getLogger().info("Limbo Simple Voice Chat echo enabled.");
        } catch (ReflectiveOperationException | LinkageError | RuntimeException exception) {
            shutdownLimboVoiceChatIntegration();
            getLogger().warning("Limbo voice echo disabled: " + exception.getMessage());
        }
    }

    private void shutdownLimboVoiceChatIntegration() {
        Object integration = limboVoiceChatIntegration;
        limboVoiceChatIntegration = null;
        if (integration == null) {
            return;
        }
        try {
            integration.getClass().getMethod("shutdown").invoke(integration);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            getLogger().warning("Failed to shut down Limbo voice echo: " + exception.getMessage());
        }
    }

    public void playSound(Location loc, String configPath) {
        playSoundToNearby(loc, configPath);
    }

    /** Alias of {@link #playSound(Location, String)}. */
    public void playSoundToNearby(Location loc, String configPath) {
        playSoundToNearby(loc, configPath, 1.0F, 1.0F);
    }

    /** Scales configured cues for an opening portal's growing intensity. */
    public void playSoundToNearby(Location loc, String configPath, float volumeScale, float pitchScale) {
        if (loc == null || loc.getWorld() == null) return;
        if (soundConfig == null || !soundConfig.isEnabled() || !soundConfig.hasSound(configPath)) return;
        String sound = soundConfig.getSound(configPath);
        float volume = Math.min(4.0F, soundConfig.getVolume(configPath)
                * soundConfig.getMasterVolume() * volumeScale);
        float pitch = Math.min(2.0F, soundConfig.getPitch(configPath) * pitchScale);
        // Route via Player#playSound(Location, String, ...) — World#playSound(String)
        // is a no-op on some modern builds; the player overload always delivers.
        double rangeSq = 48.0 * 48.0;
        for (Player pl : loc.getWorld().getPlayers()) {
            if (pl.getLocation().distanceSquared(loc) <= rangeSq) {
                pl.playSound(loc, sound, SoundCategory.MASTER, volume, pitch);
            }
        }
    }

    /** Play configured sound ONLY to the given player (direct packet). */
    public void playSoundToPlayer(Player player, String configPath) {
        if (soundConfig == null || !soundConfig.isEnabled() || !soundConfig.hasSound(configPath)) return;
        String sound = soundConfig.getSound(configPath);
        float volume = soundConfig.getVolume(configPath) * soundConfig.getMasterVolume();
        float pitch = soundConfig.getPitch(configPath);
        player.playSound(player.getLocation(), sound, SoundCategory.MASTER, volume, pitch);
    }

    public boolean isUndeadCursed(Entity entity) {
        return undeadModule != null && undeadModule.isCursed(entity);
    }

    public boolean isLimboWorld(World world) {
        return world != null && limboManager != null && limboManager.isLimboWorld(world);
    }

    /** Lets the command module query the optional voice-chat bridge without owning it. */
    public Object getLimboVoiceChatIntegration() {
        return limboVoiceChatIntegration;
    }

    @Override
    public void onDisable() {
        if (netherPortalManager != null) netherPortalManager.shutdown();
        if (endPortalManager != null) endPortalManager.shutdown();
        shutdownLimboVoiceChatIntegration();
        if (limboManager != null) limboManager.shutdown();
        if (entityModule != null) entityModule.shutdown();
        if (allergyModule != null) allergyModule.shutdown();
        if (undeadModule != null) undeadModule.shutdown();
        if (schizophreniaModule != null) schizophreniaModule.shutdown();
        getLogger().info("SpaydisCore plugin disabled!");
    }
}
