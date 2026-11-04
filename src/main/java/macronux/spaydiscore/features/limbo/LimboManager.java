package macronux.spaydiscore.features.limbo;

import macronux.spaydiscore.SpaydisCorePlugin;
import macronux.spaydiscore.config.ConfigGuard;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.GameMode;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.Chunk;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.Light;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Display;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.world.PortalCreateEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.bukkit.structure.Structure;
import org.bukkit.structure.StructureManager;
import org.bukkit.block.structure.Mirror;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.Comparator;
import java.util.Locale;
import java.util.ArrayList;
import java.util.EnumMap;
import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.time.Duration;
import org.bukkit.configuration.file.YamlConfiguration;

/** Owns the persistent Limbo world and the state of players trapped there. */
public final class LimboManager implements Listener {

    private static final NamespacedKey LIMBO_WORLD_KEY = new NamespacedKey("spaydiscore", "limbo");
    private static final String DEFAULT_RETURN_WORLD_NAME = "world";
    private static final String LIMBO_TEAM_NAME = "spaydis_limbo";
    private static final long[] SCOREBOARD_RESYNC_DELAYS = {2L, 10L, 40L};
    private static final long TICKS_PER_SECOND = 20L;
    private static final int STATE_REFRESH_PERIOD_TICKS = 20;
    private static final int PARTICLE_REFRESH_PERIOD_TICKS = 5;
    private static final int PARTICLE_ANIMATION_PERIOD_TICKS = 1;
    private static final int MAX_PARTICLE_REFRESH_PERIOD_TICKS = 20;
    private static final long DEFAULT_ALONE_AFTER_TICKS = 60L * TICKS_PER_SECOND;
    private static final double DEFAULT_ALONE_RADIUS = 32.0;
    private static final double DEFAULT_ALONE_OFFSET_RADIUS = 8.0;
    private static final int LIMBO_FLOOR_Y = 64;
    private static final int LIMBO_LIGHT_Y = LIMBO_FLOOR_Y + 1;
    private static final int MAX_LIGHT_BLOCKS_PER_CHUNK = 64;
    private static final int MAX_REJECTION_ANIMATION_TICKS = 200;
    private static final long MAX_REJECTION_AFTER_SECONDS = 604_800L;
    private static final double DEFAULT_LOOP_CENTER_X = 0.0;
    private static final double DEFAULT_LOOP_CENTER_Z = 0.0;
    private static final double MAX_LOOP_CENTER = 30_000_000.0;
    private static final double MAX_LOOP_RADIUS = 100.0;
    private static final double DEFAULT_LOOP_INNER_RADIUS = 50.0;
    private static final double DEFAULT_LOOP_OUTER_RADIUS = 100.0;
    private static final long DEFAULT_STRUCTURE_IDLE_SECONDS = 300L;
    private static final long DEFAULT_STRUCTURE_INTERVAL_SECONDS = 300L;
    private static final int DEFAULT_STRUCTURE_COUNT = 3;
    private static final String[] LIMBO_STRUCTURE_FILES = {"chain.nbt", "chain2.nbt", "chain3.nbt", "chain4.nbt"};
    private static final String LIMBO_STRUCTURE_STATE_FILE = "limbo-structures-state.yml";
    private static final int DEFAULT_LIGHT_BLOCKS_PER_CHUNK = 8;
    private static final int DEFAULT_FLOOR_PARTICLES_PER_REFRESH = 64;
    private static final int MAX_FLOOR_PARTICLES_PER_REFRESH = 128;
    private static final int LEGACY_SPARSE_FLOOR_PARTICLES_PER_REFRESH = 6;
    private static final int LEGACY_DENSE_FLOOR_PARTICLES_PER_REFRESH = 32;
    private static final int LIMBO_SMOKE_COLUMNS = 10;
    private static final double STAR_LOCAL_RADIUS = 10.0;
    private static final int STAR_PARTICLES_PER_ANCHOR = 8;
    private static final int GLOBAL_STAR_PARTICLE_COUNT = 10_000;
    private static final double GLOBAL_STAR_MAX_HEIGHT = 12.0;
    private static final double GLOBAL_STAR_EDGE_MARGIN = 1.5;
    private static final double GLOBAL_STAR_VISUAL_EXTENSION = 64.0;
    private static final int LOCAL_STAR_COLLAPSE_TICKS = 24;
    private static final int LOCAL_STAR_ARRIVAL_TICKS = 36;
    private static final double HEAD_STAR_HEIGHT = 2.35;
    private static final double HEAD_STAR_ORBIT_RADIUS = 0.18;
    private static final double HEAD_STAR_FOLLOW_INTERPOLATION = 0.35;
    private static final double HEAD_STAR_SNAP_DISTANCE = 24.0;
    private static final double STAR_FOLLOW_INTERPOLATION = 0.45;
    private static final double STAR_FOLLOW_SNAP_DISTANCE = 24.0;
    private static final double STAR_MAX_HEIGHT = 12.0;
    private static final double SMOKE_TOP_HEIGHT = 3.5;
    private static final double SMOKE_DROP_HEIGHT = 3.5;
    private static final double LEGACY_END_ROD_CHANCE = 0.005;
    private static final long DEFAULT_PANGEA_DURATION_SECONDS = 148L;
    private static final double DEFAULT_CAVE_CHANCE = 0.08;
    private static final double DEFAULT_BASALT_VOLUME = 1.0;
    private static final double DEFAULT_CAVE_VOLUME = 0.30;
    private static final long DEFAULT_REJECTION_AFTER_SECONDS = 600L;
    private static final long DEFAULT_REJECTION_AFTER_TICKS = DEFAULT_REJECTION_AFTER_SECONDS * TICKS_PER_SECOND;
    private static final long REJECTION_RETRY_TICKS = 30L * TICKS_PER_SECOND;
    private static final int DEFAULT_REJECTION_ANIMATION_TICKS = 80;
    private static final int BOUNDARY_WRAP_DURATION_TICKS = 22;
    private static final int BOUNDARY_BLINK_CLOSE_TICKS = 12;
    private static final int BOUNDARY_BLINK_FRAME_COUNT = 22;
    private static final double BOUNDARY_WRAP_SAFE_MARGIN = 10.0;
    private static final Key BOUNDARY_BLINK_FONT = Key.key("spaydiscore", "blink");
    private static final int BOUNDARY_BLINK_FIRST_GLYPH = 0xE000;
    private static final long RANDOM_BLINK_MIN_TICKS = 600L;
    private static final long RANDOM_BLINK_MAX_TICKS = 1_800L;
    private static final long CHAIN_SOUND_MIN_TICKS = 120L;
    private static final long CHAIN_SOUND_MAX_TICKS = 260L;
    private static final long VOID_BREATH_MIN_TICKS = 3_600L;
    private static final long VOID_BREATH_MAX_TICKS = 7_200L;
    private static final long VOID_BREATH_DURATION_TICKS = 160L;
    private static final int VOID_BREATH_SLOW_DIVISOR = 5;
    private static final double VOID_BREATH_STAR_DROP = 1.75;
    private static final double VOID_BREATH_BLINK_THRESHOLD = 0.34;
    private static final long SILHOUETTE_MIN_DELAY_TICKS = 2_400L;
    private static final long SILHOUETTE_MAX_DELAY_TICKS = 5_400L;
    private static final long SILHOUETTE_LIFETIME_TICKS = 180L;
    private static final long SILHOUETTE_RETREAT_TICKS = 70L;
    private static final double SILHOUETTE_MIN_DISTANCE = 55.0;
    private static final double SILHOUETTE_MAX_DISTANCE = 90.0;
    private static final double SILHOUETTE_RETREAT_SPEED = 0.18;
    private static final double SILHOUETTE_RETURN_START_SPEED = 0.20;
    private static final double SILHOUETTE_RETURN_ACCELERATION = 0.035;
    private static final double SILHOUETTE_RETURN_MAX_SPEED = 1.45;
    private static final double SILHOUETTE_DISMISS_DISTANCE = 1.1;
    private static final long DISTORTED_ECHO_MIN_TICKS = 1_600L;
    private static final long DISTORTED_ECHO_MAX_TICKS = 3_200L;
    private static final double DISTORTED_ECHO_MIN_DISTANCE = 36.0;
    private static final double DISTORTED_ECHO_MAX_DISTANCE = 86.0;
    private static final String PANGEA_AMBIENT_SOUND = "smprevive:pangea_ambient";
    private static final String CAVE_AMBIENT_SOUND = "minecraft:ambient.cave";
    private static final String DISTORTED_ECHO_SOUND = "minecraft:block.deepslate.step";

    private final JavaPlugin plugin;
    // Network effects follow real time, even when /tick sprint runs thousands of ticks/s.
    private final LimboEffectClock effectClock = new LimboEffectClock(20);
    private final LimboEffectClock ambienceClock = new LimboEffectClock(1);
    private final NamespacedKey activeKey;
    private final NamespacedKey originalGameModeKey;
    private final NamespacedKey originalGravityKey;
    private final NamespacedKey originalAllowFlightKey;
    private final NamespacedKey originalFlyingKey;
    private final NamespacedKey returnLocationKey;
    private final NamespacedKey enteredAtTickKey;
    private final NamespacedKey legacyEnteredAtKey;
    private final NamespacedKey limboOnlineTicksKey;
    private final NamespacedKey lastOnlineTickKey;
    private final NamespacedKey localStarPreferenceKey;
    private final NamespacedKey starQualityKey;
    private final NamespacedKey forcedKey;
    private final NamespacedKey spectatorKey;
    private final Map<UUID, StarQuality> starQualities = new HashMap<>();
    private final Map<UUID, ReturnTrail> returnTrails = new HashMap<>();
    private final List<GlobalStarParticle> globalStars = new ArrayList<>();
    private final Set<Long> globalStarSectors = new HashSet<>();
    private final Map<Long, BukkitTask> scoreboardResyncTasks = new HashMap<>();
    private long lastScoreboardRebuildTick = Long.MIN_VALUE;
    private static final Particle.DustOptions RETURN_DUST =
            new Particle.DustOptions(org.bukkit.Color.fromRGB(5, 5, 5), 0.45F);
    private final NamespacedKey rejectionDisplayKey;
    private final SMPReviveBridge smpReviveBridge;
    private final Map<UUID, LimboState> stateSnapshots = new HashMap<>();
    private final Map<PlayerDeathEvent, LimboState> pendingDeathStates = new HashMap<>();
    private final Map<UUID, Long> aloneSince = new HashMap<>();
    private final Map<UUID, Long> nextAmbientSoundAt = new HashMap<>();
    private final Map<UUID, Long> nextDistortedEchoAt = new HashMap<>();
    private final List<PendingStepSound> pendingStepSounds = new ArrayList<>();
    private final Map<UUID, Long> nextRandomBlinkAt = new HashMap<>();
    private final Map<Long, LightPoint> lightBlocks = new HashMap<>();
    private final Map<UUID, BukkitTask> rejectionTasks = new HashMap<>();
    private final Map<UUID, BoundaryWrap> boundaryWraps = new HashMap<>();
    private final Map<UUID, RejectionScene> rejectionScenes = new HashMap<>();
    private final Map<UUID, Long> rejectionRetryAt = new HashMap<>();
    private final java.util.Set<UUID> activePlayers = ConcurrentHashMap.newKeySet();
    private volatile LimboRoster roster;
    private final java.util.Set<UUID> releaseBypasses = ConcurrentHashMap.newKeySet();
    private final Set<UUID> rejectionInProgress = ConcurrentHashMap.newKeySet();
    private final Set<UUID> pendingRevivalCleanup = ConcurrentHashMap.newKeySet();
    private volatile VoiceSnapshot voiceSnapshot = new VoiceSnapshot(Set.of(), Set.of());
    private final Set<Long> decoratedChunks = new HashSet<>();
    private final List<FloorParticle> floorParticles = new ArrayList<>();
    private final List<SmokeParticle> smokeParticles = new ArrayList<>();
    private final Map<UUID, StarFollowState> starFollowStates = new HashMap<>();
    private final Map<UUID, LocalStarCollapse> localStarCollapseStates = new HashMap<>();
    private final Map<UUID, LocalStarArrival> localStarArrivalStates = new HashMap<>();
    private final Map<UUID, Boolean> localStarsEnabledByPlayer = new HashMap<>();
    private final Map<UUID, HeadStarState> headStarStates = new HashMap<>();
    private final List<StructurePlacement> limboStructurePlacements = new ArrayList<>();
    private final List<LoadedStructure> limboStructures = new ArrayList<>();
    private final List<Location> chainSoundPoints = new ArrayList<>();

    private volatile World limboWorld;
    private Scoreboard limboScoreboard;
    private BukkitTask stateTask;
    private BukkitTask particleTask;
    private volatile boolean enabled = true;
    private volatile boolean voiceEchoEnabled = true;
    private boolean aloneTeleportEnabled = true;
    private long aloneAfterTicks = DEFAULT_ALONE_AFTER_TICKS;
    private double aloneRadius = DEFAULT_ALONE_RADIUS;
    private double aloneOffsetRadius = DEFAULT_ALONE_OFFSET_RADIUS;
    private String returnWorldName = DEFAULT_RETURN_WORLD_NAME;
    private boolean loopEnabled = true;
    private double loopCenterX = DEFAULT_LOOP_CENTER_X;
    private double loopCenterZ = DEFAULT_LOOP_CENTER_Z;
    private double loopInnerRadius = DEFAULT_LOOP_INNER_RADIUS;
    private double loopOuterRadius = DEFAULT_LOOP_OUTER_RADIUS;
    private boolean decorationsEnabled = true;
    private boolean localStarsEnabled = true;
    private int lightBlocksPerChunk = DEFAULT_LIGHT_BLOCKS_PER_CHUNK;
    private int floorParticlesPerRefresh = DEFAULT_FLOOR_PARTICLES_PER_REFRESH;
    private int particleRefreshPeriodTicks = PARTICLE_REFRESH_PERIOD_TICKS;
    private boolean reactiveStars = true;
    private boolean ambientSoundsEnabled = true;
    private long pangeaDurationTicks = DEFAULT_PANGEA_DURATION_SECONDS * TICKS_PER_SECOND;
    private double caveChance = DEFAULT_CAVE_CHANCE;
    private float pangeaVolume = (float) DEFAULT_BASALT_VOLUME;
    private float caveVolume = (float) DEFAULT_CAVE_VOLUME;
    private boolean rejectionEnabled = true;
    private boolean fullRevivalEnabled = true;
    private long rejectionAfterTicks = DEFAULT_REJECTION_AFTER_TICKS;
    private int rejectionAnimationTicks = DEFAULT_REJECTION_ANIMATION_TICKS;
    private long nextVoidBreathAt;
    private long voidBreathStartTick = -1L;
    private long voidBreathUntil;
    private long nextDistantSilhouetteAt;
    private long distantSilhouetteUntil;
    private long distantSilhouetteReturnAt;
    private Mannequin distantSilhouette;
    private UUID distantSilhouetteTarget;
    private boolean spawningDistantSilhouette;
    private UUID debugSilhouetteObserver;
    private boolean debugDistortedEcho;
    private boolean structuresEnabled = true;
    private int structureCount = DEFAULT_STRUCTURE_COUNT;
    private long structureIdleTicks = DEFAULT_STRUCTURE_IDLE_SECONDS * TICKS_PER_SECOND;
    private long structureIntervalTicks = DEFAULT_STRUCTURE_INTERVAL_SECONDS * TICKS_PER_SECOND;
    private long structuresEmptySince = -1L;
    private long nextStructureRelocationAt;
    private long nextChainSoundAt;

    public LimboManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.activeKey = new NamespacedKey(plugin, "limbo-active");
        this.originalGameModeKey = new NamespacedKey(plugin, "limbo-original-game-mode");
        this.originalGravityKey = new NamespacedKey(plugin, "limbo-original-gravity");
        this.originalAllowFlightKey = new NamespacedKey(plugin, "limbo-original-allow-flight");
        this.originalFlyingKey = new NamespacedKey(plugin, "limbo-original-flying");
        this.returnLocationKey = new NamespacedKey(plugin, "limbo-return-location");
        this.enteredAtTickKey = new NamespacedKey(plugin, "limbo-entered-tick");
        this.legacyEnteredAtKey = new NamespacedKey(plugin, "limbo-entered-at");
        this.limboOnlineTicksKey = new NamespacedKey(plugin, "limbo-online-ticks");
        this.lastOnlineTickKey = new NamespacedKey(plugin, "limbo-last-online-tick");
        this.starQualityKey = new NamespacedKey(plugin, "limbo-star-quality");
        this.forcedKey = new NamespacedKey(plugin, "limbo-forced");
        this.spectatorKey = new NamespacedKey(plugin, "limbo-spectator");
        this.localStarPreferenceKey = new NamespacedKey(plugin, "limbo-local-stars-enabled");
        this.rejectionDisplayKey = new NamespacedKey(plugin, "limbo-rejection-display");
        this.smpReviveBridge = new SMPReviveBridge(plugin);
    }

    public void start() {
        reload();
        if (stateTask == null) {
            stateTask = Bukkit.getScheduler().runTaskTimer(plugin, this::refreshPlayers,
                    1L, STATE_REFRESH_PERIOD_TICKS);
        }
        if (particleTask == null) {
            scheduleParticleTask();
        }
        reapplyOnlinePlayers();
    }

    public void reload() {
        removeTrackedLightBlocks();
        returnTrails.clear();
        starQualities.clear();
        reactiveStars = plugin.getConfig().getBoolean("limbo.decorations.reactive-stars", true);
        boolean configuredEnabled = plugin.getConfig().getBoolean("features.limbo", true);
        String configuredReturnWorld = safeWorldName(
                plugin.getConfig().getString("limbo.return-world", DEFAULT_RETURN_WORLD_NAME),
                DEFAULT_RETURN_WORLD_NAME);
        voiceEchoEnabled = plugin.getConfig().getBoolean("limbo.voice-echo", true);

        returnWorldName = configuredReturnWorld;
        enabled = configuredEnabled;
        aloneTeleportEnabled = plugin.getConfig().getBoolean("limbo.alone-teleport.enabled", true);
        long aloneAfterSeconds = ConfigGuard.nonNegativeLong(
                plugin.getConfig().getLong("limbo.alone-teleport.after-seconds", 60L), 60L);
        aloneAfterSeconds = Math.min(aloneAfterSeconds, 86_400L);
        aloneAfterTicks = Math.max(TICKS_PER_SECOND, secondsToTicks(aloneAfterSeconds));
        aloneRadius = ConfigGuard.boundedFiniteNonNegative(
                plugin.getConfig().getDouble("limbo.alone-teleport.radius", DEFAULT_ALONE_RADIUS),
                DEFAULT_ALONE_RADIUS, 512.0);
        aloneOffsetRadius = ConfigGuard.boundedFiniteNonNegative(
                plugin.getConfig().getDouble("limbo.alone-teleport.offset-radius", DEFAULT_ALONE_OFFSET_RADIUS),
                DEFAULT_ALONE_OFFSET_RADIUS, 32.0);

        loopEnabled = plugin.getConfig().getBoolean("limbo.loop.enabled", true);
        loopCenterX = boundedFiniteOrDefault(
                plugin.getConfig().getDouble("limbo.loop.center-x", DEFAULT_LOOP_CENTER_X),
                DEFAULT_LOOP_CENTER_X, MAX_LOOP_CENTER);
        loopCenterZ = boundedFiniteOrDefault(
                plugin.getConfig().getDouble("limbo.loop.center-z", DEFAULT_LOOP_CENTER_Z),
                DEFAULT_LOOP_CENTER_Z, MAX_LOOP_CENTER);
        loopInnerRadius = ConfigGuard.boundedFiniteNonNegative(
                plugin.getConfig().getDouble("limbo.loop.inner-radius", DEFAULT_LOOP_INNER_RADIUS),
                DEFAULT_LOOP_INNER_RADIUS, MAX_LOOP_RADIUS);
        loopOuterRadius = ConfigGuard.boundedFiniteNonNegative(
                plugin.getConfig().getDouble("limbo.loop.outer-radius", DEFAULT_LOOP_OUTER_RADIUS),
                DEFAULT_LOOP_OUTER_RADIUS, MAX_LOOP_RADIUS);
        if (loopOuterRadius < loopInnerRadius) {
            double swap = loopInnerRadius;
            loopInnerRadius = loopOuterRadius;
            loopOuterRadius = swap;
        }

        structuresEnabled = plugin.getConfig().getBoolean("limbo.structures.enabled", true);
        structureCount = Math.max(1, Math.min(LIMBO_STRUCTURE_FILES.length,
                plugin.getConfig().getInt("limbo.structures.count", DEFAULT_STRUCTURE_COUNT)));
        structureIdleTicks = secondsToTicks(Math.min(86_400L, ConfigGuard.nonNegativeLong(
                plugin.getConfig().getLong("limbo.structures.idle-after-seconds", DEFAULT_STRUCTURE_IDLE_SECONDS),
                DEFAULT_STRUCTURE_IDLE_SECONDS)));
        structureIntervalTicks = secondsToTicks(Math.min(86_400L, ConfigGuard.nonNegativeLong(
                plugin.getConfig().getLong("limbo.structures.interval-seconds", DEFAULT_STRUCTURE_INTERVAL_SECONDS),
                DEFAULT_STRUCTURE_INTERVAL_SECONDS)));

        decorationsEnabled = plugin.getConfig().getBoolean("limbo.decorations.enabled", true);
        localStarsEnabled = plugin.getConfig().getBoolean(
                "limbo.decorations.local-stars-enabled", true);
        localStarsEnabledByPlayer.clear();
        lightBlocksPerChunk = ConfigGuard.boundedNonNegative(
                plugin.getConfig().getInt("limbo.decorations.light-blocks-per-chunk", DEFAULT_LIGHT_BLOCKS_PER_CHUNK),
                DEFAULT_LIGHT_BLOCKS_PER_CHUNK, MAX_LIGHT_BLOCKS_PER_CHUNK);
        int configuredFloorParticles = plugin.getConfig().getInt(
                "limbo.decorations.floor-particles-per-refresh", -1);
        if (configuredFloorParticles < 0 && plugin.getConfig().contains("limbo.decorations.end-rod-chance")) {
            double legacyChance = ConfigGuard.unitInterval(
                    plugin.getConfig().getDouble("limbo.decorations.end-rod-chance", LEGACY_END_ROD_CHANCE),
                    LEGACY_END_ROD_CHANCE);
            configuredFloorParticles = legacyChance <= 0.0
                    ? 0
                    : Math.max(1, (int) Math.ceil(legacyChance * MAX_FLOOR_PARTICLES_PER_REFRESH));
        }
        floorParticlesPerRefresh = ConfigGuard.boundedNonNegative(
                configuredFloorParticles, DEFAULT_FLOOR_PARTICLES_PER_REFRESH,
                MAX_FLOOR_PARTICLES_PER_REFRESH);
        if (floorParticlesPerRefresh == LEGACY_SPARSE_FLOOR_PARTICLES_PER_REFRESH
                || floorParticlesPerRefresh == LEGACY_DENSE_FLOOR_PARTICLES_PER_REFRESH) {
            floorParticlesPerRefresh = DEFAULT_FLOOR_PARTICLES_PER_REFRESH;
        }
        particleRefreshPeriodTicks = Math.min(MAX_PARTICLE_REFRESH_PERIOD_TICKS,
                ConfigGuard.atLeastOne(plugin.getConfig().getInt(
                        "limbo.decorations.floor-particle-refresh-ticks", PARTICLE_REFRESH_PERIOD_TICKS),
                        PARTICLE_REFRESH_PERIOD_TICKS));
        rebuildFloorParticles();
        if (particleTask != null) {
            scheduleParticleTask();
        }

        ambientSoundsEnabled = plugin.getConfig().getBoolean("limbo.ambient-sounds.enabled", true);
        // The bundled recording lasts 148.085 seconds. Cap old 149-second configs
        // so each replay starts before the previous recording falls silent.
        pangeaDurationTicks = secondsToTicks(boundedPositiveSeconds(
                plugin.getConfig().getLong(
                        "limbo.ambient-sounds.pangea-duration-seconds", DEFAULT_PANGEA_DURATION_SECONDS),
                DEFAULT_PANGEA_DURATION_SECONDS, DEFAULT_PANGEA_DURATION_SECONDS));
        caveChance = ConfigGuard.unitInterval(
                plugin.getConfig().getDouble("limbo.ambient-sounds.cave-chance", DEFAULT_CAVE_CHANCE),
                DEFAULT_CAVE_CHANCE);
        pangeaVolume = (float) ConfigGuard.boundedFiniteNonNegative(
                plugin.getConfig().getDouble("limbo.ambient-sounds.pangea-volume", DEFAULT_BASALT_VOLUME),
                DEFAULT_BASALT_VOLUME, 4.0);
        caveVolume = (float) ConfigGuard.boundedFiniteNonNegative(
                plugin.getConfig().getDouble("limbo.ambient-sounds.cave-volume", DEFAULT_CAVE_VOLUME),
                DEFAULT_CAVE_VOLUME, 4.0);

        rejectionEnabled = plugin.getConfig().getBoolean("limbo.rejection.enabled", true);
        fullRevivalEnabled = plugin.getConfig().getBoolean("limbo.rejection.full-revival", true);
        long rejectionAfterSeconds = boundedPositiveSeconds(
                plugin.getConfig().getLong("limbo.rejection.after-seconds", DEFAULT_REJECTION_AFTER_SECONDS),
                DEFAULT_REJECTION_AFTER_SECONDS, MAX_REJECTION_AFTER_SECONDS);
        rejectionAfterTicks = secondsToTicks(rejectionAfterSeconds);
        rejectionAnimationTicks = Math.max(1, ConfigGuard.boundedNonNegative(
                plugin.getConfig().getInt("limbo.rejection.animation-ticks", DEFAULT_REJECTION_ANIMATION_TICKS),
                DEFAULT_REJECTION_ANIMATION_TICKS, MAX_REJECTION_ANIMATION_TICKS));

        cancelRejectionTasks();
        cancelScoreboardResyncs();
        for (Map.Entry<UUID, BoundaryWrap> entry : boundaryWraps.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            clearBoundaryBlink(player);
        }
        boundaryWraps.clear();
        effectClock.reset();
        ambienceClock.reset();
        for (UUID uuid : nextAmbientSoundAt.keySet()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) player.stopSound(PANGEA_AMBIENT_SOUND, SoundCategory.AMBIENT);
        }
        nextAmbientSoundAt.clear();
        nextDistortedEchoAt.clear();
        pendingStepSounds.clear();
        nextRandomBlinkAt.clear();
        nextChainSoundAt = 0L;
        removeDistantSilhouette();
        nextVoidBreathAt = 0L;
        voidBreathStartTick = -1L;
        voidBreathUntil = 0L;
        nextDistantSilhouetteAt = 0L;
        distantSilhouetteUntil = 0L;
        lightBlocks.clear();
        decoratedChunks.clear();
        rejectionInProgress.clear();

        resolveWorld();
        if (!enabled) {
            safelyDisableLimboPlayers();
            publishVoiceSnapshot(Set.of(), Set.of());
            return;
        }
        reapplyOnlinePlayers();
        ensureLimboStructures();
        ensureBarrierFloorInLoadedChunks();
        ensureDecorationsInLoadedChunks();
    }

    private void safelyDisableLimboPlayers() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!isLimbo(player)) {
                continue;
            }
            ReleaseResult result = release(player);
            if (result != ReleaseResult.RELEASED) {
                plugin.getLogger().warning("Could not release Limbo player while Limbo is disabled: "
                        + player.getName() + " (" + result + ")");
            }
        }
    }

    private double boundedFiniteOrDefault(double value, double fallback, double maximumAbs) {
        double safeMaximum = Double.isFinite(maximumAbs) && maximumAbs >= 0.0 ? maximumAbs : 0.0;
        double safe = Double.isFinite(value) ? value : fallback;
        if (!Double.isFinite(safe)) {
            safe = 0.0;
        }
        return Math.max(-safeMaximum, Math.min(safeMaximum, safe));
    }

    private long boundedPositiveSeconds(long value, long fallback, long maximum) {
        long safe = ConfigGuard.nonNegativeLong(value, fallback);
        long safeFallback = Math.max(1L, Math.min(maximum, fallback));
        return Math.max(1L, Math.min(maximum, safe == 0L ? safeFallback : safe));
    }

    private String safeWorldName(String value, String fallback) {
        String normalized = value == null ? null : value.trim();
        if (normalized == null || normalized.isBlank() || normalized.equals(".") || normalized.equals("..")
                || normalized.contains("/") || normalized.contains("\\")) {
            plugin.getLogger().warning("Invalid world name; using a safe default.");
            return fallback;
        }
        return normalized;
    }

    private void resolveWorld() {
        World resolved = Bukkit.getWorld(LIMBO_WORLD_KEY);
        if (resolved == null) {
            limboWorld = null;
            enabled = false;
            plugin.getLogger().severe("Datapack dimension '" + LIMBO_WORLD_KEY
                    + "' is unavailable; use the SpaydisCore JAR with its bundled datapack and fully restart the server."
                    + " Limbo entry is disabled.");
            return;
        }
        if (limboWorld != resolved) {
            World legacyWorld = Bukkit.getWorld("limbo");
            String legacyStatus = legacyWorld == null
                    ? "not loaded"
                    : (legacyWorld == resolved ? "same resolved datapack world" : "loaded but ignored");
            plugin.getLogger().info("Resolved Limbo datapack dimension '" + LIMBO_WORLD_KEY
                    + "' to world '" + resolved.getName() + "'; legacy world 'limbo' is " + legacyStatus + ".");
        }
        limboWorld = resolved;
        configureWorld(resolved);
    }

    private void configureWorld(World world) {
        world.setDifficulty(Difficulty.PEACEFUL);
        world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
        world.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
        world.setGameRule(GameRule.DO_MOB_SPAWNING, false);
        world.setStorm(false);
        world.setThundering(false);
        world.setTime(18_000L);
        world.setSpawnLocation(spawnCoordinate(loopCenterX), LIMBO_LIGHT_Y,
                spawnCoordinate(loopCenterZ));
        removeLimboCreatures(world);
    }

    private int spawnCoordinate(double coordinate) {
        return (int) Math.floor(coordinate);
    }

    public void shutdown() {
        pendingDeathStates.clear();
        // Keep the old three-second expiry if the plugin is disabled while players stay online.
        // Persistent Limbo markers remain intact so the next enable can resume their state.
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (hasActiveMarker(player)) {
                player.stopSound(PANGEA_AMBIENT_SOUND, SoundCategory.AMBIENT);
                expireLimboEffect(player, PotionEffectType.INVISIBILITY);
                expireLimboEffect(player, PotionEffectType.DARKNESS);
            }
        }
        pendingStepSounds.clear();
        returnTrails.clear();
        starQualities.clear();
        globalStarSectors.clear();
        globalStars.clear();
        cancelScoreboardResyncs();
        if (stateTask != null) {
            stateTask.cancel();
            stateTask = null;
        }
        if (particleTask != null) {
            particleTask.cancel();
            particleTask = null;
        }
        cancelRejectionTasks();
        releaseBypasses.clear();
        activePlayers.clear();
        publishVoiceSnapshot(Set.of(), Set.of());
        aloneSince.clear();
        pendingRevivalCleanup.clear();
        nextAmbientSoundAt.clear();
        nextDistortedEchoAt.clear();
        nextRandomBlinkAt.clear();
        removeDistantSilhouette();
        setLimboLightsDimmed(false);
        nextVoidBreathAt = 0L;
        voidBreathStartTick = -1L;
        voidBreathUntil = 0L;
        lightBlocks.clear();
        decoratedChunks.clear();
        floorParticles.clear();
        smokeParticles.clear();
        globalStarSectors.clear();
        starFollowStates.clear();
        localStarCollapseStates.clear();
        localStarArrivalStates.clear();
        localStarsEnabledByPlayer.clear();
        headStarStates.clear();
        rejectionInProgress.clear();
        for (Map.Entry<UUID, BoundaryWrap> entry : boundaryWraps.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            clearBoundaryBlink(player);
        }
        boundaryWraps.clear();
        chainSoundPoints.clear();
        nextChainSoundAt = 0L;
    }

    public boolean isEnabled() {
        return enabled && limboWorld != null;
    }

    public boolean isVoiceEchoEnabled() {
        return voiceEchoEnabled && limboWorld != null;
    }

    public boolean areLocalStarsEnabled() {
        return localStarsEnabled;
    }

    public boolean areLocalStarsEnabled(Player player) {
        if (player == null) {
            return localStarsEnabled;
        }
        UUID uuid = player.getUniqueId();
        Boolean cached = localStarsEnabledByPlayer.get(uuid);
        if (cached != null) {
            return cached;
        }
        Byte stored = player.getPersistentDataContainer().get(
                localStarPreferenceKey, PersistentDataType.BYTE);
        boolean enabled = stored == null ? localStarsEnabled : stored != 0;
        localStarsEnabledByPlayer.put(uuid, enabled);
        return enabled;
    }

    public StarQuality getStarQuality(Player player) {
        return starQualities.computeIfAbsent(player.getUniqueId(), ignored -> {
            String value = player.getPersistentDataContainer().get(starQualityKey, PersistentDataType.STRING);
            StarQuality quality = StarQuality.parse(value);
            return quality == null ? StarQuality.NORMAL : quality;
        });
    }

    public void setStarQuality(Player player, StarQuality quality) {
        starQualities.put(player.getUniqueId(), quality);
        player.getPersistentDataContainer().set(starQualityKey, PersistentDataType.STRING,
                quality.name().toLowerCase(java.util.Locale.ROOT));
    }

    public void setLocalStarsEnabled(Player player, boolean enabled) {
        if (player == null) {
            return;
        }
        boolean previous = areLocalStarsEnabled(player);
        localStarsEnabledByPlayer.put(player.getUniqueId(), enabled);
        player.getPersistentDataContainer().set(localStarPreferenceKey,
                PersistentDataType.BYTE, (byte) (enabled ? 1 : 0));
        if (previous == enabled) {
            return;
        }
        if (enabled) {
            localStarCollapseStates.remove(player.getUniqueId());
            starFollowStates.remove(player.getUniqueId());
            startLocalStarArrival(player);
        } else {
            localStarArrivalStates.remove(player.getUniqueId());
            starFollowStates.remove(player.getUniqueId());
            startLocalStarCollapse(player);
        }
    }

    private void startLocalStarCollapse(Player player) {
        if (player == null || limboWorld == null || !isLimboWorld(player.getWorld())) {
            return;
        }
        Location location = player.getLocation();
        localStarCollapseStates.put(player.getUniqueId(), new LocalStarCollapse(
                location.getX(), location.getY(), location.getZ(), atmosphereTick(effectTick())));
    }

    private void startLocalStarArrival(Player player) {
        if (player == null || limboWorld == null || floorParticles.isEmpty()
                || !isLimboWorld(player.getWorld())) {
            return;
        }
        ThreadLocalRandom random = ThreadLocalRandom.current();
        double radius = Math.max(0.0, loopOuterRadius - GLOBAL_STAR_EDGE_MARGIN);
        List<ArrivalPoint> points = new ArrayList<>(floorParticles.size());
        for (int index = 0; index < floorParticles.size(); index++) {
            double angle = random.nextDouble(0.0, Math.PI * 2.0);
            double distance = Math.sqrt(random.nextDouble()) * radius;
            points.add(new ArrivalPoint(
                    loopCenterX + Math.cos(angle) * distance,
                    LIMBO_LIGHT_Y + random.nextDouble(0.75, GLOBAL_STAR_MAX_HEIGHT - 0.35),
                    loopCenterZ + Math.sin(angle) * distance,
                    random.nextDouble(0.0, 0.45)));
        }
        localStarArrivalStates.put(player.getUniqueId(),
                new LocalStarArrival(List.copyOf(points), atmosphereTick(effectTick())));
    }

    public String getWorldName() {
        return LIMBO_WORLD_KEY.toString();
    }

    public String getReturnWorldName() {
        return returnWorldName;
    }

    public int getOnlinePlayerCount() {
        return (int) Bukkit.getOnlinePlayers().stream().filter(this::isLimbo).count();
    }

    /** Starts the normal rejection animation immediately for an online Limbo player. */
    public boolean skipRejection(Player player) {
        if (!rejectionEnabled || player == null || !player.isOnline()
                || !isLimboWorld(player.getWorld())) {
            return false;
        }
        UUID uuid = player.getUniqueId();
        long now = currentTick();
        cleanupRejectionScene(uuid);
        advanceOnlineTime(player, now);
        player.getPersistentDataContainer().set(
                limboOnlineTicksKey, PersistentDataType.LONG, rejectionAfterTicks);
        rejectionRetryAt.remove(uuid);
        rejectionInProgress.remove(uuid);
        startRejectionIfDue(player, now);
        return rejectionTasks.containsKey(uuid) || rejectionInProgress.contains(uuid);
    }

    /** Triggers one Void Breath cycle immediately for the players currently in Limbo. */
    public boolean debugTriggerVoidBreath() {
        List<Player> players = getOnlineLimboPlayers();
        if (players.isEmpty()) {
            return false;
        }

        long now = effectTick();
        setLimboLightsDimmed(false);
        voidBreathStartTick = -1L;
        voidBreathUntil = 0L;
        nextVoidBreathAt = now;
        tickVoidBreath(players, now);
        return isVoidBreathActive(now);
    }

    /** Triggers the distant silhouette now, anchored around the requested Limbo player. */
    public boolean debugTriggerDistantSilhouette(Player observer) {
        if (!isOnlineLimboPlayer(observer)) {
            return false;
        }
        List<Player> players = getOnlineLimboPlayers();
        if (players.isEmpty()) {
            return false;
        }

        removeDistantSilhouette();
        long now = effectTick();
        nextDistantSilhouetteAt = now;
        debugSilhouetteObserver = observer.getUniqueId();
        try {
            tickDistantSilhouette(players, now);
        } finally {
            debugSilhouetteObserver = null;
        }
        return distantSilhouette != null && distantSilhouette.isValid();
    }

    /** Triggers one distorted step echo immediately for the requested Limbo player. */
    public boolean debugTriggerDistortedStepEcho(Player player) {
        if (!isOnlineLimboPlayer(player)) {
            return false;
        }
        long now = effectTick();
        nextDistortedEchoAt.put(player.getUniqueId(), now);
        debugDistortedEcho = true;
        try {
            tickDistortedStepEcho(player, now);
        } finally {
            debugDistortedEcho = false;
        }
        return true;
    }

    private List<Player> getOnlineLimboPlayers() {
        List<Player> players = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (isOnlineLimboPlayer(player)) {
                players.add(player);
            }
        }
        return players;
    }

    private boolean isOnlineLimboPlayer(Player player) {
        return player != null && player.isOnline() && isLimboWorld(player.getWorld())
                && isLimbo(player);
    }

    java.util.Set<UUID> getLimboPlayerIds() {
        return voiceSnapshot.echoParticipants();
    }

    boolean isVoiceEchoParticipant(UUID playerUuid) {
        return playerUuid != null && voiceSnapshot.echoParticipants().contains(playerUuid);
    }

    /** Safe for asynchronous chat/voice callbacks; membership is published on the main thread. */
    boolean isLimboParticipant(UUID playerUuid) {
        return playerUuid != null && (activePlayers.contains(playerUuid)
                || voiceSnapshot.echoParticipants().contains(playerUuid));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onWorldChat(AsyncChatEvent event) {
        if (isLimboParticipant(event.getPlayer().getUniqueId())) return;
        event.viewers().removeIf(viewer -> viewer instanceof Player player
                && isLimboParticipant(player.getUniqueId()));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onLegacyWorldChat(AsyncPlayerChatEvent event) {
        if (isLimboParticipant(event.getPlayer().getUniqueId())) return;
        event.getRecipients().removeIf(player -> isLimboParticipant(player.getUniqueId()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onLimboWorldChanged(PlayerChangedWorldEvent event) {
        if (!enabled || limboWorld == null) return;
        Player player = event.getPlayer();
        if (isLimboWorld(player.getWorld())) {
            captureState(player);
            trackActive(player);
            addVoiceEchoParticipant(player.getUniqueId());
            rebuildLimboScoreboard();
            scheduleLimboScoreboardResync();
            applyState(player, false);
        } else {
            rebuildLimboScoreboard();
        }
    }

    boolean isVoiceCaptureParticipant(UUID playerUuid) {
        return playerUuid != null && voiceSnapshot.captureParticipants().contains(playerUuid);
    }

    private void publishVoiceSnapshot(Set<UUID> echoParticipants,
                                      Set<UUID> captureParticipants) {
        voiceSnapshot = new VoiceSnapshot(
                Set.copyOf(echoParticipants), Set.copyOf(captureParticipants));
    }

    private void addVoiceEchoParticipant(UUID playerUuid) {
        if (playerUuid == null) return;
        Set<UUID> echo = new HashSet<>(voiceSnapshot.echoParticipants());
        echo.add(playerUuid);
        publishVoiceSnapshot(echo, voiceSnapshot.captureParticipants());
    }

    private void removeVoiceEchoParticipant(UUID playerUuid) {
        if (playerUuid == null) return;
        Set<UUID> echo = new HashSet<>(voiceSnapshot.echoParticipants());
        echo.remove(playerUuid);
        publishVoiceSnapshot(echo, voiceSnapshot.captureParticipants());
    }

    private void addVoiceCaptureParticipant(UUID playerUuid) {
        if (playerUuid == null) return;
        Set<UUID> capture = new HashSet<>(voiceSnapshot.captureParticipants());
        capture.add(playerUuid);
        publishVoiceSnapshot(voiceSnapshot.echoParticipants(), capture);
    }

    private void removeVoiceCaptureParticipant(UUID playerUuid) {
        if (playerUuid == null) return;
        Set<UUID> capture = new HashSet<>(voiceSnapshot.captureParticipants());
        capture.remove(playerUuid);
        publishVoiceSnapshot(voiceSnapshot.echoParticipants(), capture);
    }

    public boolean isLimbo(UUID playerUuid) {
        return playerUuid != null && activePlayers.contains(playerUuid);
    }

    public boolean isLimbo(Player player) {
        return player != null && (isLimbo(player.getUniqueId()) || isLimboWorld(player.getWorld())
                || hasActiveMarker(player));
    }

    public boolean isLimboWorld(World world) {
        return world != null && (world == limboWorld || LIMBO_WORLD_KEY.equals(world.getKey()));
    }

    boolean isInLimboWorld(Player player) {
        return player != null && isLimboWorld(player.getWorld());
    }

    public Location getSpawn() {
        return limboWorld == null
                ? null
                : new Location(limboWorld, loopCenterX + 0.5, 65.0, loopCenterZ + 0.5);
    }

    private void storeReturnLocation(Player player, Location location) {
        if (player == null || location == null || location.getWorld() == null
                || isLimboWorld(location.getWorld())
                || !Double.isFinite(location.getX())
                || !Double.isFinite(location.getY())
                || !Double.isFinite(location.getZ())
                || !Float.isFinite(location.getYaw())
                || !Float.isFinite(location.getPitch())) {
            return;
        }
        String serialized = location.getWorld().getName() + ","
                + location.getX() + "," + location.getY() + "," + location.getZ() + ","
                + location.getYaw() + "," + location.getPitch();
        player.getPersistentDataContainer().set(returnLocationKey,
                PersistentDataType.STRING, serialized);
    }

    private Location readReturnLocation(Player player) {
        if (player == null) {
            return null;
        }
        String serialized = player.getPersistentDataContainer().get(
                returnLocationKey, PersistentDataType.STRING);
        if (serialized == null) {
            return null;
        }
        String[] parts = serialized.split(",", -1);
        if (parts.length != 6 || parts[0].isBlank()) {
            return null;
        }
        World world = Bukkit.getWorld(parts[0]);
        if (world == null || isLimboWorld(world)) {
            return null;
        }
        try {
            double x = Double.parseDouble(parts[1]);
            double y = Double.parseDouble(parts[2]);
            double z = Double.parseDouble(parts[3]);
            float yaw = Float.parseFloat(parts[4]);
            float pitch = Float.parseFloat(parts[5]);
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                    || !Float.isFinite(yaw) || !Float.isFinite(pitch)) {
                return null;
            }
            return new Location(world, x, y, z, yaw, pitch);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private Location getReturnLocation(Player player, World fallbackWorld) {
        Location saved = readReturnLocation(player);
        Location safeSaved = findSafeReturnLocation(saved);
        if (safeSaved != null) {
            return safeSaved;
        }
        if (fallbackWorld == null || isLimboWorld(fallbackWorld)) {
            return null;
        }
        Location fallback = fallbackWorld.getSpawnLocation().clone().add(0.5, 0.0, 0.5);
        Location safeSpawn = findSafeReturnLocation(fallback);
        if (safeSpawn != null) return safeSpawn;
        // Both the death point and spawn can be outside a moved/shrunken border.
        Location center = fallbackWorld.getWorldBorder().getCenter();
        if (center == null) return null;
        center = center.clone();
        center.setWorld(fallbackWorld);
        center.setY(fallback.getY());
        center.setYaw(fallback.getYaw());
        center.setPitch(fallback.getPitch());
        return findSafeReturnLocation(center);
    }

    private Location findSafeReturnLocation(Location preferred) {
        if (preferred == null || preferred.getWorld() == null) {
            return null;
        }
        World world = preferred.getWorld();
        if (isSafeReturnLocation(preferred)) return preferred.clone();
        WorldBorder border = world.getWorldBorder();
        Location center = border.getCenter();
        if (center == null) return null;
        double size = border.getSize();
        preferred = preferred.clone();
        preferred.setX(LimboGeometry.returnCoordinateInsideBorder(preferred.getX(), center.getX(), size));
        preferred.setZ(LimboGeometry.returnCoordinateInsideBorder(preferred.getZ(), center.getZ(), size));
        if (!Double.isFinite(preferred.getX()) || !Double.isFinite(preferred.getY())
                || !Double.isFinite(preferred.getZ())) return null;
        int baseX = preferred.getBlockX();
        int baseY = Math.max(world.getMinHeight() + 1,
                Math.min(world.getMaxHeight() - 2, preferred.getBlockY()));
        int baseZ = preferred.getBlockZ();
        Set<Long> checkedColumns = new HashSet<>();
        for (int radius = 0; radius <= 8; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (radius > 0 && Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
                        continue;
                    }
                    Location column = preferred.clone();
                    column.setX(LimboGeometry.returnCoordinateInsideBorder(
                            baseX + dx + 0.5, center.getX(), size));
                    column.setZ(LimboGeometry.returnCoordinateInsideBorder(
                            baseZ + dz + 0.5, center.getZ(), size));
                    int x = column.getBlockX();
                    int z = column.getBlockZ();
                    // Clip before touching terrain: do not generate chunks outside the border.
                    if (!isInsideReturnBorder(column) || !checkedColumns.add(blockKey(x, z))) continue;
                    for (int dy = -2; dy <= 2; dy++) {
                        Location candidate = column.clone();
                        candidate.setY(baseY + dy);
                        if (isSafeReturnLocation(candidate)) {
                            return candidate;
                        }
                    }
                    int surfaceY = world.getHighestBlockYAt(x, z) + 1;
                    Location surface = column.clone();
                    surface.setY(surfaceY);
                    if (isSafeReturnLocation(surface)) {
                        return surface;
                    }
                }
            }
        }
        return null;
    }

    private boolean isSafeReturnLocation(Location target) {
        if (target == null || target.getWorld() == null
                || !Double.isFinite(target.getX())
                || !Double.isFinite(target.getY())
                || !Double.isFinite(target.getZ())) {
            return false;
        }
        World world = target.getWorld();
        if (!isInsideReturnBorder(target)) {
            return false;
        }
        int x = target.getBlockX();
        int feetY = target.getBlockY();
        int z = target.getBlockZ();
        int bodyTopY = (int) Math.floor(target.getY() + 1.8 - 1.0e-7);
        if (feetY <= world.getMinHeight() || bodyTopY >= world.getMaxHeight()) {
            return false;
        }

        Block floor = world.getBlockAt(x, feetY - 1, z);
        if (!floor.getType().isSolid() || floor.isLiquid()
                || isUnsafeReturnMaterial(floor.getType())) return false;
        // A fractional point in a tiny border may straddle two block columns.
        for (int bodyX = (int) Math.floor(target.getX() - 0.3);
                bodyX <= (int) Math.floor(target.getX() + 0.3); bodyX++) {
            for (int bodyZ = (int) Math.floor(target.getZ() - 0.3);
                    bodyZ <= (int) Math.floor(target.getZ() + 0.3); bodyZ++) {
                for (int bodyY = feetY; bodyY <= bodyTopY; bodyY++) {
                    Block body = world.getBlockAt(bodyX, bodyY, bodyZ);
                    if (!body.isPassable() || body.isLiquid() || isUnsafeReturnMaterial(body.getType())) {
                        return false;
                    }
                }
                if (isUnsafeReturnMaterial(world.getBlockAt(bodyX, feetY - 1, bodyZ).getType())) return false;
            }
        }
        return true;
    }

    private boolean isInsideReturnBorder(Location target) {
        WorldBorder border = target.getWorld().getWorldBorder();
        if (!border.isInside(target)) return false;
        Location center = border.getCenter();
        if (center == null) return false;
        double size = border.getSize();
        double x = LimboGeometry.returnCoordinateInsideBorder(target.getX(), center.getX(), size);
        double z = LimboGeometry.returnCoordinateInsideBorder(target.getZ(), center.getZ(), size);
        return Math.abs(x - target.getX()) < 1.0e-7 && Math.abs(z - target.getZ()) < 1.0e-7;
    }

    private boolean isUnsafeReturnMaterial(Material material) {
        if (material == null) {
            return true;
        }
        return switch (material.name()) {
            case "LAVA", "FIRE", "SOUL_FIRE", "MAGMA_BLOCK", "CAMPFIRE",
                    "SOUL_CAMPFIRE", "SWEET_BERRY_BUSH", "POWDER_SNOW", "WITHER_ROSE",
                    "END_PORTAL", "END_GATEWAY" -> true;
            default -> false;
        };
    }

    private boolean teleportToReturnLocation(Player player, World fallbackWorld) {
        if (player == null) {
            return false;
        }
        Location target = getReturnLocation(player, fallbackWorld);
        if (target == null) {
            return false;
        }
        Location current = player.getLocation();
        if (current.getWorld() == target.getWorld()
                && current.distanceSquared(target) < 0.0001) {
            return true;
        }
        UUID uuid = player.getUniqueId();
        releaseBypasses.add(uuid);
        try {
            return player.teleport(target);
        } finally {
            releaseBypasses.remove(uuid);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        if (!isEnabled()) {
            return;
        }
        // Read the free-death prediction and original state before SMPRevive changes
        // them. Do not persist admission until other listeners can cancel the death.
        if (!isLimbo(player) && smpReviveBridge.isFirstDeathPending(player)) {
            return;
        }
        pendingDeathStates.put(event, snapshotState(player, hasActiveMarker(player)));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeathFinalized(PlayerDeathEvent event) {
        // This listener must also run for cancelled deaths to discard the snapshot.
        LimboState snapshot = pendingDeathStates.remove(event);
        if (snapshot == null || event.isCancelled() || !isEnabled()) {
            return;
        }
        Player player = event.getEntity();
        captureState(player, snapshot);
        trackActive(player);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        if (!isEnabled()) {
            return;
        }
        // The pre-death exemption is only a prediction. SMPRevive's committed
        // state wins for deaths outside Limbo, including ignored/rolled-back
        // deaths. Existing occupants still respawn in Limbo until released.
        Boolean dead = smpReviveBridge.playerDeathState(player);
        if (Boolean.FALSE.equals(dead) && !isLimboWorld(player.getWorld()) && !isForced(player)) {
            if (isLimbo(player)) {
                clearState(player, false);
                disableSurvivalFlight(player);
            }
            return;
        }
        if (!Boolean.TRUE.equals(dead) && !isLimbo(player)) {
            return;
        }
        storeReturnLocation(player, event.getRespawnLocation());
        captureState(player);
        trackActive(player);
        Location spawn = getSpawn();
        if (spawn == null) {
            return;
        }
        event.setRespawnLocation(spawn);
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                applyState(player, true);
            }
        });
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (!enabled) {
            return;
        }
        Player player = event.getPlayer();
        if (roster().takeQueuedRelease(player.getUniqueId())) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) return;
                ReleaseResult result = release(player);
                if (result == ReleaseResult.NOT_IN_LIMBO) {
                    roster().remove(player.getUniqueId());
                } else if (result != ReleaseResult.RELEASED) {
                    roster().queueRelease(player.getUniqueId());
                    plugin.getLogger().warning("Queued Limbo release for " + player.getName()
                            + " failed (" + result + "); it stays queued.");
                }
            });
            return;
        }
        if (!isLimboWorld(player.getWorld()) && !hasActiveMarker(player)) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline() || limboWorld == null || !isLimbo(player)) {
                return;
            }
            if (!isLimboWorld(player.getWorld())) {
                if (tryFinalizeConfirmedRevival(player, player.getWorld())) {
                    return;
                }
                if (isSpectating(player)) {
                    // Admin spectators stay marked as Limbo occupants but are not pulled back.
                    captureState(player);
                    trackActive(player);
                    return;
                }
                player.teleport(getSpawn());
            }
            captureState(player);
            trackActive(player);
            if (isLimboWorld(player.getWorld())) {
                rebuildLimboScoreboard();
                scheduleLimboScoreboardResync();
            }
            applyState(player, false);
        });
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        returnTrails.remove(event.getPlayer().getUniqueId());
        starQualities.remove(event.getPlayer().getUniqueId());
        // The persistent marker intentionally survives a quit; an admin must release the player.
        UUID uuid = event.getPlayer().getUniqueId();
        releaseBypasses.remove(uuid);
        BoundaryWrap boundaryWrap = boundaryWraps.remove(uuid);
        if (boundaryWrap != null) {
            clearBoundaryBlink(event.getPlayer());
        }
        aloneSince.remove(uuid);
        nextAmbientSoundAt.remove(uuid);
        nextDistortedEchoAt.remove(uuid);
        nextRandomBlinkAt.remove(uuid);
        rejectionRetryAt.remove(uuid);
        event.getPlayer().getPersistentDataContainer().remove(lastOnlineTickKey);
        removeVoiceEchoParticipant(uuid);
        removeVoiceCaptureParticipant(uuid);
        pendingRevivalCleanup.remove(uuid);
        localStarCollapseStates.remove(uuid);
        localStarArrivalStates.remove(uuid);
        localStarsEnabledByPlayer.remove(uuid);
        headStarStates.remove(uuid);
        cancelRejectionTask(uuid);
        activePlayers.remove(uuid);
        stateSnapshots.remove(uuid);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerPortal(PlayerPortalEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (isLimboWorld(from == null ? null : from.getWorld())
                || isLimboWorld(to == null ? null : to.getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityPortal(EntityPortalEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (isLimboWorld(from == null ? null : from.getWorld())
                || isLimboWorld(to == null ? null : to.getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPortalCreate(PortalCreateEvent event) {
        if (isLimboWorld(event.getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        if (isLimboWorld(event.getLocation().getWorld())
                && !isAllowedLimboEntity(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntitySpawn(EntitySpawnEvent event) {
        if (isLimboWorld(event.getLocation().getWorld())
                && !isAllowedLimboEntity(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        if (!isLimboWorld(event.getWorld())) {
            return;
        }
        for (Entity entity : event.getEntities()) {
            if (!isAllowedLimboEntity(entity)) {
                entity.remove();
            }
        }
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        if (!isLimboWorld(event.getWorld())) {
            return;
        }
        ensureBarrierFloor(event.getChunk());
        if (decorationsEnabled) {
            decorateChunk(event.getChunk());
        }
    }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent event) {
        if (!isLimboWorld(event.getWorld())) {
            return;
        }
        int chunkX = event.getChunk().getX();
        int chunkZ = event.getChunk().getZ();
        decoratedChunks.remove(chunkKey(chunkX, chunkZ));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        if (!isLimboWorld(event.getFrom().getWorld()) && !hasActiveMarker(player)) {
            return;
        }
        Location destination = event.getTo();
        if (!enabled) {
            if (destination != null && !isLimboWorld(destination.getWorld())) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (player.isOnline() && !isLimboWorld(player.getWorld()) && isLimbo(player)) {
                        clearState(player);
                    }
                });
            }
            return;
        }
        if (destination == null || isLimboWorld(destination.getWorld())) {
            if (destination != null && isLimboWorld(destination.getWorld())) {
                removeVoiceCaptureParticipant(player.getUniqueId());
            }
            return;
        }
        if (!hasActiveMarker(player)
                && destination.getWorld().getEnvironment() == World.Environment.NORMAL) {
            addVoiceCaptureParticipant(player.getUniqueId());
        }
        if (releaseBypasses.remove(player.getUniqueId()) || isSpectating(player)) {
            return;
        }
        if (tryFinalizeConfirmedRevival(player, destination.getWorld())) {
            return;
        }
        event.setCancelled(true);
    }

    private boolean tryFinalizeConfirmedRevival(Player player, World destinationWorld) {
        if (player == null || destinationWorld == null || isLimboWorld(destinationWorld)
                || !isLimbo(player) || isForced(player)
                || !smpReviveBridge.confirmsRevived(player)) {
            return false;
        }

        UUID uuid = player.getUniqueId();
        if (!pendingRevivalCleanup.add(uuid)) {
            return true;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            pendingRevivalCleanup.remove(uuid);
            if (player.isOnline() && isLimbo(player) && !isLimboWorld(player.getWorld())
                    && smpReviveBridge.confirmsRevived(player)) {
                // SMPRevive chose the revival destination; clearing Limbo must not replace it with a saved bed.
                boolean wasSpectating = isSpectating(player);
                GameMode originalMode = readGameMode(player.getPersistentDataContainer().get(
                        originalGameModeKey, PersistentDataType.STRING));
                clearState(player, false);
                if (wasSpectating && player.getGameMode() == GameMode.SPECTATOR) {
                    player.setGameMode(originalMode);
                }
                // SMPRevive owns the post-revival game mode. Never let Limbo's
                // captured spectator/flight state leak into that normal world.
                if (player.getGameMode() == GameMode.SURVIVAL
                        || player.getGameMode() == GameMode.ADVENTURE) {
                    player.setFlying(false);
                    player.setAllowFlight(false);
                }
            }
        });
        return true;
    }

    private void refreshPlayers() {
        if (!enabled || limboWorld == null) {
            if (limboWorld != null) {
                tickLimboStructures(limboWorld.getPlayers(), currentTick());
            }
            publishVoiceSnapshot(Set.of(), Set.of());
            return;
        }
        long now = currentTick();
        boolean refreshAmbience = ambienceClock.advance(System.nanoTime());
        long visualNow = effectTick();
        List<Player> limboPlayers = new ArrayList<>();
        Set<UUID> echoSnapshot = new HashSet<>();
        Set<UUID> captureSnapshot = new HashSet<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!isLimboWorld(player.getWorld())
                    && !hasActiveMarker(player)
                    && player.getWorld().getEnvironment() == World.Environment.NORMAL) {
                captureSnapshot.add(player.getUniqueId());
            }
            if (!isLimbo(player)) {
                continue;
            }
            if (!isLimboWorld(player.getWorld())) {
                if (!tryFinalizeConfirmedRevival(player, player.getWorld())
                        && !pendingRevivalCleanup.contains(player.getUniqueId())
                        && !isSpectating(player)) {
                    player.teleport(getSpawn());
                }
                continue;
            }
            limboPlayers.add(player);
            echoSnapshot.add(player.getUniqueId());
            applyState(player, false);
            advanceOnlineTime(player, now);
            if (!boundaryWraps.containsKey(player.getUniqueId())) {
                Location wrapped = wrapIfOutside(player.getLocation());
                if (wrapped != null) {
                    startBoundaryWrap(player, wrapped, now);
                } else if (refreshAmbience) {
                    tickRandomBoundaryBlink(player, visualNow);
                }
            }
            if (refreshAmbience) tickAmbientSounds(player, visualNow);
        }
        publishVoiceSnapshot(echoSnapshot, captureSnapshot);
        tickLimboStructures(limboPlayers, now);
        if (refreshAmbience) {
            tickChainSounds(limboPlayers, visualNow);
            tickVoidBreath(limboPlayers, visualNow);
            tickDistantSilhouette(limboPlayers, visualNow);
        }
        for (Player player : limboPlayers) {
            startRejectionIfDue(player, now);
        }
        relocateIsolatedPlayers(limboPlayers);
    }

    private void ensureLimboStructures() {
        if (!structuresEnabled || limboWorld == null || !limboStructurePlacements.isEmpty()) {
            return;
        }
        // The world survives a server restart, while the in-memory placement list does not.
        // Restore the saved bounds so existing structures are not duplicated on startup.
        if (loadPersistedLimboStructures()) {
            loadLimboStructureTemplates();
            return;
        }
        loadLimboStructureTemplates();
        if (limboStructures.isEmpty()) {
            plugin.getLogger().warning("No Limbo structure files are available.");
            return;
        }
        if (!(loopOuterRadius > loopInnerRadius) || loopOuterRadius <= 0.0) {
            plugin.getLogger().warning("Limbo structure placement skipped: loop has no usable radius.");
            return;
        }
        List<LoadedStructure> selected = new ArrayList<>(limboStructures);
        java.util.Collections.shuffle(selected);
        for (int i = 0; i < Math.min(structureCount, selected.size()); i++) {
            placeLimboStructure(selected.get(i));
        }
        if (!saveLimboStructureState()) {
            clearLimboStructures();
        }
    }

    private void loadLimboStructureTemplates() {
        if (!limboStructures.isEmpty()) {
            return;
        }
        StructureManager manager = Bukkit.getStructureManager();
        for (String fileName : LIMBO_STRUCTURE_FILES) {
            File file = new File(plugin.getDataFolder(), "limbo-structures/" + fileName);
            try {
                if (!file.isFile()) {
                    plugin.saveResource("limbo-structures/" + fileName, false);
                }
                if (file.isFile()) {
                    Structure structure = manager.loadStructure(file);
                    if (structure != null) {
                        limboStructures.add(new LoadedStructure(fileName, structure));
                    }
                }
            } catch (IOException | IllegalArgumentException exception) {
                plugin.getLogger().warning("Could not load Limbo structure " + fileName + ": "
                        + exception.getMessage());
            }
        }
    }

    private void tickLimboStructures(List<Player> players, long now) {
        if (limboWorld == null) {
            return;
        }
        if (!enabled || !structuresEnabled) {
            if (players.isEmpty()) {
                if (limboStructurePlacements.isEmpty()) {
                    loadPersistedLimboStructures();
                }
                if (!limboStructurePlacements.isEmpty()) {
                    clearLimboStructures();
                }
            }
            return;
        }
        if (!players.isEmpty()) {
            structuresEmptySince = -1L;
            return;
        }
        if (structuresEmptySince < 0L) {
            structuresEmptySince = now;
            return;
        }
        if (now < ConfigGuard.saturatingAdd(structuresEmptySince, structureIdleTicks)
                || now < nextStructureRelocationAt) {
            return;
        }
        clearLimboStructures();
        ensureLimboStructures();
        nextStructureRelocationAt = ConfigGuard.saturatingAdd(now, structureIntervalTicks);
    }

    private void clearLimboStructures() {
        for (StructurePlacement placement : limboStructurePlacements) {
            clearStructureBox(placement.bounds());
        }
        limboStructurePlacements.clear();
        chainSoundPoints.clear();
        nextChainSoundAt = 0L;
        deleteLimboStructureState();
    }

    private void clearStructureBox(BoundingBox box) {
        for (int x = (int) Math.floor(box.getMinX()); x <= (int) Math.floor(box.getMaxX() - 0.001); x++) {
            for (int y = (int) Math.floor(box.getMinY()); y <= (int) Math.floor(box.getMaxY() - 0.001); y++) {
                for (int z = (int) Math.floor(box.getMinZ()); z <= (int) Math.floor(box.getMaxZ() - 0.001); z++) {
                    limboWorld.getBlockAt(x, y, z).setType(
                            y == LIMBO_FLOOR_Y ? Material.BARRIER : Material.AIR, false);
                }
            }
            for (int z = (int) Math.floor(box.getMinZ()); z <= (int) Math.floor(box.getMaxZ() - 0.001); z++) {
                lightBlocks.remove(blockKey(x, z));
            }
        }
    }

    private boolean loadPersistedLimboStructures() {
        File stateFile = new File(plugin.getDataFolder(), LIMBO_STRUCTURE_STATE_FILE);
        if (!stateFile.isFile()) {
            return false;
        }
        YamlConfiguration state = YamlConfiguration.loadConfiguration(stateFile);
        boolean loaded = false;
        for (String serialized : state.getStringList("placements")) {
            String[] values = serialized.split(",");
            if (values.length != 6) {
                plugin.getLogger().warning("Ignoring invalid Limbo structure state entry: " + serialized);
                continue;
            }
            try {
                BoundingBox box = new BoundingBox(
                        Double.parseDouble(values[0]), Double.parseDouble(values[1]), Double.parseDouble(values[2]),
                        Double.parseDouble(values[3]), Double.parseDouble(values[4]), Double.parseDouble(values[5]));
                if (!isValidStructureBounds(box)) {
                    plugin.getLogger().warning("Ignoring unsafe Limbo structure state entry: " + serialized);
                    continue;
                }
                limboStructurePlacements.add(new StructurePlacement(box));
                loaded = true;
            } catch (IllegalArgumentException ignored) {
                plugin.getLogger().warning("Ignoring invalid Limbo structure state entry: " + serialized);
            }
        }
        if (loaded) {
            rebuildChainSoundPoints();
        }
        return loaded;
    }

    private boolean isValidStructureBounds(BoundingBox box) {
        double[] values = {box.getMinX(), box.getMinY(), box.getMinZ(),
                box.getMaxX(), box.getMaxY(), box.getMaxZ()};
        for (double value : values) {
            if (!Double.isFinite(value) || value != Math.rint(value)) {
                return false;
            }
        }
        double sizeX = box.getMaxX() - box.getMinX();
        double sizeY = box.getMaxY() - box.getMinY();
        double sizeZ = box.getMaxZ() - box.getMinZ();
        return sizeX > 0.0 && sizeX <= 256.0
                && sizeY > 0.0 && sizeY <= limboWorld.getMaxHeight() - limboWorld.getMinHeight()
                && sizeZ > 0.0 && sizeZ <= 256.0
                && sizeX * sizeY * sizeZ <= 4_000_000.0
                && Math.abs(box.getMinX()) <= MAX_LOOP_CENTER
                && Math.abs(box.getMaxX()) <= MAX_LOOP_CENTER
                && Math.abs(box.getMinZ()) <= MAX_LOOP_CENTER
                && Math.abs(box.getMaxZ()) <= MAX_LOOP_CENTER
                && box.getMinY() >= limboWorld.getMinHeight()
                && box.getMaxY() <= limboWorld.getMaxHeight();
    }

    private boolean saveLimboStructureState() {
        if (limboStructurePlacements.isEmpty()) {
            return false;
        }
        YamlConfiguration state = new YamlConfiguration();
        List<String> serialized = new ArrayList<>();
        for (StructurePlacement placement : limboStructurePlacements) {
            BoundingBox box = placement.bounds();
            serialized.add(box.getMinX() + "," + box.getMinY() + "," + box.getMinZ() + ","
                    + box.getMaxX() + "," + box.getMaxY() + "," + box.getMaxZ());
        }
        state.set("placements", serialized);
        try {
            state.save(new File(plugin.getDataFolder(), LIMBO_STRUCTURE_STATE_FILE));
            return true;
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not save Limbo structure state: " + exception.getMessage());
            return false;
        }
    }

    private void deleteLimboStructureState() {
        File stateFile = new File(plugin.getDataFolder(), LIMBO_STRUCTURE_STATE_FILE);
        if (stateFile.isFile() && !stateFile.delete()) {
            plugin.getLogger().warning("Could not remove Limbo structure state file.");
        }
    }

    private void placeLimboStructure(LoadedStructure loaded) {
        int sizeX = Math.max(1, loaded.structure().getSize().getBlockX());
        int sizeY = Math.max(1, loaded.structure().getSize().getBlockY());
        int sizeZ = Math.max(1, loaded.structure().getSize().getBlockZ());
        for (int attempt = 0; attempt < 1000; attempt++) {
            int blockX = (int) Math.floor(loopCenterX
                    + ThreadLocalRandom.current().nextDouble(-loopOuterRadius, loopOuterRadius));
            int blockZ = (int) Math.floor(loopCenterZ
                    + ThreadLocalRandom.current().nextDouble(-loopOuterRadius, loopOuterRadius));
            BoundingBox footprint = BoundingBox.of(new Location(limboWorld, blockX, limboWorld.getMinHeight(), blockZ),
                    new Location(limboWorld, blockX + sizeX, limboWorld.getMaxHeight(), blockZ + sizeZ));
            if (!containsStructureFootprint(footprint) || limboStructurePlacements.stream()
                    .anyMatch(existing -> existing.bounds().overlaps(footprint))) {
                continue;
            }
            int originY = LIMBO_FLOOR_Y - Math.max(1, sizeY / 2);
            originY = Math.max(limboWorld.getMinHeight(), originY);
            Location origin = new Location(limboWorld, blockX, originY, blockZ);
            try {
                loaded.structure().place(origin, true, StructureRotation.NONE, Mirror.NONE,
                        0, 1.0f, ThreadLocalRandom.current());
                BoundingBox bounds = BoundingBox.of(origin, new Location(limboWorld,
                        blockX + sizeX, originY + Math.max(1, sizeY), blockZ + sizeZ));
                restoreBarrierInStructureCrossing(bounds);
                limboStructurePlacements.add(new StructurePlacement(bounds));
                collectChainSoundPoints(bounds);
                return;
            } catch (RuntimeException exception) {
                plugin.getLogger().warning("Could not place Limbo structure " + loaded.name() + ": "
                        + exception.getMessage());
                return;
            }
        }
        plugin.getLogger().warning("Could not find a free position for Limbo structure " + loaded.name());
    }

    private void collectChainSoundPoints(BoundingBox bounds) {
        if (bounds == null || limboWorld == null) {
            return;
        }
        for (int x = (int) Math.floor(bounds.getMinX()); x < (int) Math.ceil(bounds.getMaxX()); x++) {
            for (int y = (int) Math.floor(bounds.getMinY()); y < (int) Math.ceil(bounds.getMaxY()); y++) {
                for (int z = (int) Math.floor(bounds.getMinZ()); z < (int) Math.ceil(bounds.getMaxZ()); z++) {
                    if (!limboWorld.getBlockAt(x, y, z).getType().isAir()
                            && limboWorld.getBlockAt(x, y, z).getType() != Material.BARRIER) {
                        chainSoundPoints.add(new Location(limboWorld, x + 0.5, y + 0.5, z + 0.5));
                    }
                }
            }
        }
    }

    private void restoreBarrierInStructureCrossing(BoundingBox bounds) {
        int minX = (int) Math.floor(bounds.getMinX());
        int maxX = (int) Math.ceil(bounds.getMaxX());
        int minZ = (int) Math.floor(bounds.getMinZ());
        int maxZ = (int) Math.ceil(bounds.getMaxZ());
        for (int x = minX; x < maxX; x++) {
            for (int z = minZ; z < maxZ; z++) {
                Block floor = limboWorld.getBlockAt(x, LIMBO_FLOOR_Y, z);
                if (floor.getType().isAir()) {
                    floor.setType(Material.BARRIER, false);
                }
            }
        }
    }

    private void rebuildChainSoundPoints() {
        chainSoundPoints.clear();
        for (StructurePlacement placement : limboStructurePlacements) {
            collectChainSoundPoints(placement.bounds());
        }
    }

    private boolean containsStructureFootprint(BoundingBox box) {
        double nearestX = Math.max(box.getMinX(), Math.min(loopCenterX, box.getMaxX()));
        double nearestZ = Math.max(box.getMinZ(), Math.min(loopCenterZ, box.getMaxZ()));
        double nearestRadius = Math.hypot(nearestX - loopCenterX, nearestZ - loopCenterZ);
        double farthestRadius = 0.0;
        for (double x : new double[] {box.getMinX(), box.getMaxX()}) {
            for (double z : new double[] {box.getMinZ(), box.getMaxZ()}) {
                farthestRadius = Math.max(farthestRadius,
                        Math.hypot(x - loopCenterX, z - loopCenterZ));
            }
        }
        return nearestRadius >= loopInnerRadius && farthestRadius <= loopOuterRadius;
    }

    private record LoadedStructure(String name, Structure structure) { }

    private record StructurePlacement(BoundingBox bounds) { }

    private void tickVoidBreath(List<Player> players, long now) {
        if (voidBreathUntil > 0L && now >= voidBreathUntil) {
            setLimboLightsDimmed(false);
            voidBreathUntil = 0L;
            voidBreathStartTick = -1L;
        }
        if (players.isEmpty()) {
            return;
        }
        if (nextVoidBreathAt <= 0L) {
            nextVoidBreathAt = ConfigGuard.saturatingAdd(now,
                    randomTicks(VOID_BREATH_MIN_TICKS, VOID_BREATH_MAX_TICKS));
            return;
        }
        if (voidBreathUntil > now) {
            double breathProgress = Math.max(0.0, Math.min(1.0,
                    (now - voidBreathStartTick) / (double) Math.max(1L,
                            VOID_BREATH_DURATION_TICKS - 1L)));
            double breathEnvelope = Math.sin(Math.PI * breathProgress);
            setLimboLightDimFactor(breathEnvelope);
            return;
        }
        if (now < nextVoidBreathAt) {
            return;
        }

        voidBreathStartTick = now;
        voidBreathUntil = ConfigGuard.saturatingAdd(now, VOID_BREATH_DURATION_TICKS);
        nextVoidBreathAt = ConfigGuard.saturatingAdd(now,
                randomTicks(VOID_BREATH_MIN_TICKS, VOID_BREATH_MAX_TICKS));
        setLimboLightDimFactor(0.0);
        for (Player player : players) {
            player.playSound(player.getLocation(), "minecraft:entity.warden.heartbeat",
                    SoundCategory.AMBIENT, 0.45F, 0.42F);
            player.spawnParticle(Particle.SMOKE,
                    player.getLocation().add(0.0, 1.0, 0.0),
                    18, 0.65, 0.8, 0.65, 0.008, (Void) null, true);
        }
    }

    private long atmosphereTick(long tick) {
        if (!isVoidBreathActive(tick)) {
            return tick;
        }
        return voidBreathStartTick
                + (tick - voidBreathStartTick) / VOID_BREATH_SLOW_DIVISOR;
    }

    private boolean isVoidBreathActive(long tick) {
        return voidBreathStartTick >= 0L
                && tick >= voidBreathStartTick
                && tick < voidBreathUntil;
    }

    private void tickDistantSilhouette(List<Player> players, long now) {
        if (players.isEmpty()) {
            removeDistantSilhouette();
            return;
        }

        if (distantSilhouette != null) {
            if (!distantSilhouette.isValid()
                    || !isLimboWorld(distantSilhouette.getWorld())) {
                removeDistantSilhouette();
                nextDistantSilhouetteAt = ConfigGuard.saturatingAdd(now,
                        randomTicks(SILHOUETTE_MIN_DELAY_TICKS, SILHOUETTE_MAX_DELAY_TICKS));
                return;
            }
            if (now >= distantSilhouetteUntil) {
                removeDistantSilhouette();
                nextDistantSilhouetteAt = ConfigGuard.saturatingAdd(now,
                        randomTicks(SILHOUETTE_MIN_DELAY_TICKS, SILHOUETTE_MAX_DELAY_TICKS));
                return;
            }
            return;
        }

        if (nextDistantSilhouetteAt <= 0L) {
            nextDistantSilhouetteAt = ConfigGuard.saturatingAdd(now,
                    randomTicks(SILHOUETTE_MIN_DELAY_TICKS, SILHOUETTE_MAX_DELAY_TICKS));
            return;
        }
        if (now < nextDistantSilhouetteAt) {
            return;
        }

        ThreadLocalRandom random = ThreadLocalRandom.current();
        Player observer = null;
        if (debugSilhouetteObserver != null) {
            for (Player player : players) {
                if (debugSilhouetteObserver.equals(player.getUniqueId())) {
                    observer = player;
                    break;
                }
            }
        }
        if (observer == null) {
            observer = players.get(random.nextInt(players.size()));
        }
        Location origin = observer.getLocation();
        boolean debugSpawn = debugSilhouetteObserver != null;
        double angle;
        double distance;
        if (debugSpawn) {
            Vector direction = origin.getDirection();
            direction.setY(0.0);
            if (direction.lengthSquared() < 0.001) {
                direction.setZ(-1.0);
            }
            direction.normalize();
            angle = Math.atan2(direction.getZ(), direction.getX());
            distance = 18.0;
        } else {
            angle = random.nextDouble(0.0, Math.PI * 2.0);
            distance = random.nextDouble(SILHOUETTE_MIN_DISTANCE, SILHOUETTE_MAX_DISTANCE);
        }
        Location location = origin.clone().add(
                Math.cos(angle) * distance, 0.0, Math.sin(angle) * distance);
        location.setY(LIMBO_LIGHT_Y);
        faceSilhouette(location, observer.getLocation());
        if (!debugSpawn) {
            Location wrapped = wrapIfOutside(location);
            if (wrapped != null) {
                location = wrapped;
            }
        }
        spawningDistantSilhouette = true;
        try {
            distantSilhouette = limboWorld.spawn(location, Mannequin.class,
                    this::configureDistantSilhouette);
        } finally {
            spawningDistantSilhouette = false;
        }
        distantSilhouetteUntil = ConfigGuard.saturatingAdd(now,
                debugSpawn ? 300L : SILHOUETTE_LIFETIME_TICKS);
        distantSilhouetteReturnAt = ConfigGuard.saturatingAdd(now,
                debugSpawn ? 60L : SILHOUETTE_RETREAT_TICKS);
        distantSilhouetteTarget = observer.getUniqueId();
        nextDistantSilhouetteAt = 0L;
    }

    private void tickDistantSilhouetteMotion() {
        Mannequin mannequin = distantSilhouette;
        if (mannequin == null || !mannequin.isValid() || !isLimboWorld(mannequin.getWorld())) {
            return;
        }

        long now = effectTick();
        if (now >= distantSilhouetteUntil) {
            removeDistantSilhouette();
            nextDistantSilhouetteAt = ConfigGuard.saturatingAdd(now,
                    randomTicks(SILHOUETTE_MIN_DELAY_TICKS, SILHOUETTE_MAX_DELAY_TICKS));
            return;
        }

        Player target = distantSilhouetteTarget == null
                ? null : Bukkit.getPlayer(distantSilhouetteTarget);
        if (!isOnlineLimboPlayer(target)) {
            removeDistantSilhouette();
            nextDistantSilhouetteAt = ConfigGuard.saturatingAdd(now,
                    randomTicks(SILHOUETTE_MIN_DELAY_TICKS, SILHOUETTE_MAX_DELAY_TICKS));
            return;
        }

        Location current = mannequin.getLocation();
        Location targetLevel = target.getLocation();
        Vector away = current.toVector().subtract(target.getLocation().toVector());
        away.setY(0.0);
        if (away.lengthSquared() < 0.0001) {
            away = target.getLocation().getDirection().setY(0.0);
            if (away.lengthSquared() < 0.0001) {
                away.setX(1.0);
            }
        }
        away.normalize();

        boolean returning = now >= distantSilhouetteReturnAt;
        double speed = returning
                ? Math.min(SILHOUETTE_RETURN_MAX_SPEED,
                SILHOUETTE_RETURN_START_SPEED
                        + (now - distantSilhouetteReturnAt) * SILHOUETTE_RETURN_ACCELERATION)
                : SILHOUETTE_RETREAT_SPEED;
        Vector movement;
        if (returning) {
            Vector toFace = targetLevel.toVector().subtract(current.toVector());
            Vector horizontal = toFace.clone().setY(0.0);
            if (horizontal.lengthSquared() > 0.0001) {
                horizontal.normalize().multiply(speed);
            }
            double verticalStep = Math.max(-speed * 0.35,
                    Math.min(speed * 0.35, toFace.getY()));
            movement = horizontal.setY(verticalStep);
        } else {
            movement = away.multiply(speed);
        }
        Location next = current.clone().add(movement);
        if (!returning) {
            next.setY(LIMBO_LIGHT_Y);
        }
        faceSilhouette(next, targetLevel);
        Location wrapped = wrapIfOutside(next);
        if (wrapped != null) {
            next = wrapped;
        }

        if (returning && next.distanceSquared(targetLevel)
                <= SILHOUETTE_DISMISS_DISTANCE * SILHOUETTE_DISMISS_DISTANCE) {
            limboWorld.spawnParticle(Particle.SMOKE, next,
                    28, 0.45, 0.9, 0.45, 0.015);
            removeDistantSilhouette();
            nextDistantSilhouetteAt = ConfigGuard.saturatingAdd(now,
                    randomTicks(SILHOUETTE_MIN_DELAY_TICKS, SILHOUETTE_MAX_DELAY_TICKS));
            return;
        }

        mannequin.teleport(next);
        mannequin.setVelocity(new Vector(0.0, 0.0, 0.0));
    }

    private void faceSilhouette(Location silhouette, Location target) {
        Vector direction = target.toVector().subtract(silhouette.toVector()).setY(0.0);
        if (direction.lengthSquared() > 0.0001) {
            silhouette.setDirection(direction);
        }
    }

    private void configureDistantSilhouette(Mannequin mannequin) {
        mannequin.setImmovable(true);
        mannequin.setAI(false);
        mannequin.setGravity(false);
        mannequin.setSilent(true);
        mannequin.setCollidable(false);
        mannequin.setInvulnerable(true);
        mannequin.setInvisible(debugSilhouetteObserver == null);
        mannequin.setGlowing(true);
        ResolvableProfile profile = smpReviveBridge.randomPlayerProfile();
        if (profile == null) {
            profile = Mannequin.defaultProfile();
        }
        mannequin.setProfile(profile);
        mannequin.setDescription(Component.empty());
    }

    private void removeDistantSilhouette() {
        if (distantSilhouette != null && distantSilhouette.isValid()) {
            distantSilhouette.remove();
        }
        distantSilhouette = null;
        distantSilhouetteUntil = 0L;
        distantSilhouetteReturnAt = 0L;
        distantSilhouetteTarget = null;
    }

    private boolean isDistantSilhouette(Entity entity) {
        return entity != null && entity == distantSilhouette;
    }

    private boolean isAllowedLimboEntity(Entity entity) {
        return entity instanceof Player || entity instanceof Item || entity instanceof Projectile
                || (entity instanceof Mannequin
                && (spawningDistantSilhouette || entity == distantSilhouette));
    }

    private Location wrapIfOutside(Location location) {
        if (!loopEnabled || location == null || !isLimboWorld(location.getWorld())) {
            return null;
        }
        LimboGeometry.WrappedPoint point = LimboGeometry.wrap(
                location.getX(),
                location.getZ(),
                loopCenterX,
                loopCenterZ,
                loopInnerRadius,
                loopOuterRadius,
                BOUNDARY_WRAP_SAFE_MARGIN);
        if (point == null) {
            return null;
        }
        Location wrapped = location.clone();
        wrapped.setX(point.x());
        wrapped.setZ(point.z());
        return wrapped;
    }

    private void enforceBoundaryWraps() {
        if (!enabled || limboWorld == null) {
            return;
        }
        long now = currentTick();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!isOnlineLimboPlayer(player)
                    || boundaryWraps.containsKey(player.getUniqueId())) {
                continue;
            }
            Location wrapped = wrapIfOutside(player.getLocation());
            if (wrapped != null) {
                startBoundaryWrap(player, wrapped, now);
            }
        }
    }

    private void startBoundaryWrap(Player player, Location target, long now) {
        startBoundaryBlink(player, target, now, true);
    }

    private void tickRandomBoundaryBlink(Player player, long now) {
        UUID uuid = player.getUniqueId();
        Long next = nextRandomBlinkAt.get(uuid);
        if (next == null) {
            nextRandomBlinkAt.put(uuid, ConfigGuard.saturatingAdd(now,
                    randomTicks(RANDOM_BLINK_MIN_TICKS, RANDOM_BLINK_MAX_TICKS)));
            return;
        }
        if (now < next) {
            return;
        }
        nextRandomBlinkAt.put(uuid, ConfigGuard.saturatingAdd(now,
                randomTicks(RANDOM_BLINK_MIN_TICKS, RANDOM_BLINK_MAX_TICKS)));
        // The blink deadline is visual; boundary movement still uses game ticks.
        startBoundaryBlink(player, null, currentTick(), false);
    }

    private void startBoundaryBlink(Player player, Location target, long now, boolean reposition) {
        if (player == null || (reposition && target == null)) {
            return;
        }
        Location start = player.getLocation().clone();
        if (target != null) {
            target = target.clone();
            target.setYaw(start.getYaw());
            target.setPitch(start.getPitch());
        }
        if (reposition) {
            player.playSound(player.getLocation(), "minecraft:entity.enderman.teleport",
                    SoundCategory.AMBIENT, 0.45F, 0.75F);
        }
        showBoundaryBlinkFrame(player, 0);
        boundaryWraps.put(player.getUniqueId(),
                new BoundaryWrap(start, target, now, reposition, false));
    }

    private void tickBoundaryWraps(boolean renderFrame) {
        if (boundaryWraps.isEmpty()) {
            return;
        }
        long now = currentTick();
        Iterator<Map.Entry<UUID, BoundaryWrap>> iterator = boundaryWraps.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, BoundaryWrap> entry = iterator.next();
            Player player = Bukkit.getPlayer(entry.getKey());
            BoundaryWrap wrap = entry.getValue();
            if (player == null || !player.isOnline() || !isLimboWorld(player.getWorld())) {
                clearBoundaryBlink(player);
                iterator.remove();
                continue;
            }
            long elapsed = Math.max(0L, now - wrap.startTick());
            if (renderFrame) {
                showBoundaryBlinkFrame(player, (int) Math.min(
                        BOUNDARY_BLINK_FRAME_COUNT - 1L, elapsed));
            }
            if (!wrap.crossed() && elapsed >= BOUNDARY_BLINK_CLOSE_TICKS) {
                if (wrap.reposition()) {
                    Vector velocity = player.getVelocity().clone();
                    if (!player.teleport(wrap.target())) {
                        clearBoundaryBlink(player);
                        iterator.remove();
                        continue;
                    }
                    player.setVelocity(velocity);
                }
                entry.setValue(new BoundaryWrap(
                        wrap.start(), wrap.target(), wrap.startTick(),
                        wrap.reposition(), true));
            }
            if (elapsed >= BOUNDARY_WRAP_DURATION_TICKS) {
                clearBoundaryBlink(player);
                iterator.remove();
            }
        }
    }

    private void showBoundaryBlinkFrame(Player player, int frame) {
        if (player == null || !player.isOnline()) {
            return;
        }
        char glyph = (char) (BOUNDARY_BLINK_FIRST_GLYPH
                + Math.max(0, Math.min(BOUNDARY_BLINK_FRAME_COUNT - 1, frame)));
        Component overlay = Component.text(String.valueOf(glyph)).font(BOUNDARY_BLINK_FONT);
        player.showTitle(Title.title(overlay, Component.empty(),
                Title.Times.times(Duration.ZERO, Duration.ofMillis(50), Duration.ZERO)));
    }

    private void clearBoundaryBlink(Player player) {
        if (player != null && player.isOnline()) {
            player.clearTitle();
        }
    }

    private void tickAmbientSounds(Player player, long now) {
        UUID uuid = player.getUniqueId();
        if (!ambientSoundsEnabled) {
            nextAmbientSoundAt.remove(uuid);
            nextDistortedEchoAt.remove(uuid);
            return;
        }
        tickDistortedStepEcho(player, now);

        Long next = nextAmbientSoundAt.get(uuid);
        if (next != null && now < next) {
            return;
        }

        // The resource-pack recording is stereo: background audio has no distance attenuation.
        player.stopSound(PANGEA_AMBIENT_SOUND, SoundCategory.AMBIENT);
        player.playSound(player.getLocation(), PANGEA_AMBIENT_SOUND,
                SoundCategory.AMBIENT, pangeaVolume,
                1.0F);
        if (ThreadLocalRandom.current().nextDouble() < caveChance) {
            player.playSound(player.getLocation(), CAVE_AMBIENT_SOUND,
                    SoundCategory.AMBIENT, caveVolume,
                    0.65F + ThreadLocalRandom.current().nextFloat() * 0.35F);
        }
        nextAmbientSoundAt.put(uuid, ConfigGuard.saturatingAdd(now, pangeaDurationTicks));
    }

    private void tickChainSounds(List<Player> players, long now) {
        if (players.isEmpty() || chainSoundPoints.isEmpty() || now < nextChainSoundAt) {
            return;
        }
        Location source = chainSoundPoints.get(
                ThreadLocalRandom.current().nextInt(chainSoundPoints.size()));
        if (plugin instanceof SpaydisCorePlugin spaydisCorePlugin) {
            spaydisCorePlugin.playSoundToNearby(source, "limbo.chain");
        }
        nextChainSoundAt = ConfigGuard.saturatingAdd(now,
                randomTicks(CHAIN_SOUND_MIN_TICKS, CHAIN_SOUND_MAX_TICKS));
    }

    private void tickDistortedStepEcho(Player player, long now) {
        UUID uuid = player.getUniqueId();
        Long next = nextDistortedEchoAt.get(uuid);
        if (next == null) {
            nextDistortedEchoAt.put(uuid, ConfigGuard.saturatingAdd(now,
                    randomTicks(DISTORTED_ECHO_MIN_TICKS, DISTORTED_ECHO_MAX_TICKS)));
            return;
        }
        if (now < next) {
            return;
        }

        ThreadLocalRandom random = ThreadLocalRandom.current();
        Location origin = player.getLocation();
        double minDistance = debugDistortedEcho ? 10.0 : DISTORTED_ECHO_MIN_DISTANCE;
        double maxDistance = debugDistortedEcho ? 16.0 : DISTORTED_ECHO_MAX_DISTANCE;
        int stepCount = debugDistortedEcho ? 4 : random.nextInt(3, 6);
        long stepDelay = 0L;
        for (int step = 0; step < stepCount; step++) {
            if (step > 0) {
                stepDelay += random.nextLong(5L, 14L);
            }
            double angle = random.nextDouble(0.0, Math.PI * 2.0);
            double distance = random.nextDouble(minDistance, maxDistance);
            Location stepSource = origin.clone().add(
                    Math.cos(angle) * distance, random.nextDouble(-0.2, 0.4),
                    Math.sin(angle) * distance);
            float volume = debugDistortedEcho ? 1.8F - step * 0.10F : 2.6F - step * 0.15F;
            float pitch = 0.52F + random.nextFloat() * 0.28F + step * 0.015F;
            if (stepDelay == 0L) {
                player.playSound(stepSource, DISTORTED_ECHO_SOUND,
                        SoundCategory.AMBIENT, volume, pitch);
            } else {
                // Keep the spacing of footsteps when game ticks are sprinting.
                pendingStepSounds.add(new PendingStepSound(uuid, stepSource, volume, pitch,
                        ConfigGuard.saturatingAdd(now, stepDelay)));
            }
        }
        nextDistortedEchoAt.put(uuid, ConfigGuard.saturatingAdd(now,
                randomTicks(DISTORTED_ECHO_MIN_TICKS, DISTORTED_ECHO_MAX_TICKS)));
    }

    private long currentTick() {
        return Integer.toUnsignedLong(Bukkit.getCurrentTick());
    }

    /** Main-thread decoration timeline. Skipped server ticks never become queued frames. */
    private long effectTick() {
        return effectClock.frame();
    }

    private record PendingStepSound(UUID playerId, Location source, float volume, float pitch,
                                    long dueFrame) { }

    private void tickPendingStepSounds(long frame) {
        var iterator = pendingStepSounds.iterator();
        while (iterator.hasNext()) {
            PendingStepSound sound = iterator.next();
            Player player = Bukkit.getPlayer(sound.playerId());
            if (!enabled || !isOnlineLimboPlayer(player)) {
                iterator.remove();
            } else if (frame >= sound.dueFrame()) {
                player.playSound(sound.source(), DISTORTED_ECHO_SOUND,
                        SoundCategory.AMBIENT, sound.volume(), sound.pitch());
                iterator.remove();
            }
        }
    }

    private long getOnlineTicks(Player player) {
        if (player == null) return 0L;
        Long value = player.getPersistentDataContainer().get(
                limboOnlineTicksKey, PersistentDataType.LONG);
        return value == null || value < 0L ? 0L : value;
    }

    private void advanceOnlineTime(Player player, long now) {
        if (player == null || !player.isOnline()) return;
        PersistentDataContainer data = player.getPersistentDataContainer();
        long accumulated = getOnlineTicks(player);
        Long previous = data.get(lastOnlineTickKey, PersistentDataType.LONG);
        if (previous == null || previous < 0L || previous > now) {
            data.set(limboOnlineTicksKey, PersistentDataType.LONG, accumulated);
            data.set(lastOnlineTickKey, PersistentDataType.LONG, now);
            return;
        }

        data.set(limboOnlineTicksKey, PersistentDataType.LONG,
                onlineTicksAfter(accumulated, previous, now));
        data.set(lastOnlineTickKey, PersistentDataType.LONG, now);
    }

    static long onlineTicksAfter(long accumulated, long previousTick, long currentTick) {
        long safeAccumulated = Math.max(0L, accumulated);
        if (previousTick < 0L || currentTick < previousTick) {
            return safeAccumulated;
        }
        return ConfigGuard.saturatingAdd(safeAccumulated, currentTick - previousTick);
    }

    private long secondsToTicks(long seconds) {
        if (seconds <= 0L) {
            return 0L;
        }
        return seconds > Long.MAX_VALUE / TICKS_PER_SECOND
                ? Long.MAX_VALUE
                : seconds * TICKS_PER_SECOND;
    }

    private long randomTicks(long minimum, long maximum) {
        if (maximum <= minimum) {
            return minimum;
        }
        return ThreadLocalRandom.current().nextLong(minimum, maximum + 1L);
    }

    private void ensureBarrierFloorInLoadedChunks() {
        if (limboWorld == null) {
            return;
        }
        for (Chunk chunk : limboWorld.getLoadedChunks()) {
            ensureBarrierFloor(chunk);
        }
    }

    private void ensureBarrierFloor(Chunk chunk) {
        if (chunk == null || limboWorld == null || !isLimboWorld(chunk.getWorld())) {
            return;
        }
        int baseX = chunk.getX() << 4;
        int baseZ = chunk.getZ() << 4;
        boolean structuresKnown = !limboStructurePlacements.isEmpty();
        for (int x = baseX; x < baseX + 16; x++) {
            for (int z = baseZ; z < baseZ + 16; z++) {
                Block floor = limboWorld.getBlockAt(x, LIMBO_FLOOR_Y, z);
                if (floor.getType() == Material.BARRIER) {
                    continue;
                }
                boolean oldSurface = floor.getType().isAir()
                        || floor.getType() == Material.BLACK_CONCRETE;
                if (oldSurface || (structuresKnown && !isStructureFloorCell(x, z))) {
                    floor.setType(Material.BARRIER, false);
                }
            }
        }
    }

    private boolean isStructureFloorCell(int x, int z) {
        double px = x + 0.5;
        double pz = z + 0.5;
        for (StructurePlacement placement : limboStructurePlacements) {
            BoundingBox bounds = placement.bounds();
            if (px >= bounds.getMinX() && px < bounds.getMaxX()
                    && pz >= bounds.getMinZ() && pz < bounds.getMaxZ()
                    && LIMBO_FLOOR_Y + 0.5 >= bounds.getMinY()
                    && LIMBO_FLOOR_Y + 0.5 < bounds.getMaxY()) {
                return true;
            }
        }
        return false;
    }

    private void ensureDecorationsInLoadedChunks() {
        if (!decorationsEnabled || limboWorld == null) {
            return;
        }
        for (Chunk chunk : limboWorld.getLoadedChunks()) {
            decorateChunk(chunk);
        }
    }

    private void decorateChunk(Chunk chunk) {
        if (chunk == null || !decorationsEnabled || lightBlocksPerChunk <= 0) {
            return;
        }
        if (!chunkIntersectsLoop(chunk)) {
            return;
        }
        if (!decoratedChunks.add(chunkKey(chunk.getX(), chunk.getZ()))) {
            return;
        }

        java.util.Random random = new java.util.Random(
                limboWorld.getSeed()
                        ^ ((long) chunk.getX() * 341873128712L)
                        ^ ((long) chunk.getZ() * 132897987541L));
        int baseX = chunk.getX() << 4;
        int baseZ = chunk.getZ() << 4;
        double radiusSquared = loopOuterRadius * loopOuterRadius;
        for (int i = 0; i < lightBlocksPerChunk; i++) {
            int x = baseX + random.nextInt(16);
            int z = baseZ + random.nextInt(16);
            double dx = x + 0.5 - loopCenterX;
            double dz = z + 0.5 - loopCenterZ;
            if (dx * dx + dz * dz > radiusSquared) {
                continue;
            }

            Block block = limboWorld.getBlockAt(x, LIMBO_LIGHT_Y, z);
            long key = blockKey(x, z);
            boolean alreadyOwned = lightBlocks.containsKey(key);
            boolean placed = false;
            int placedLevel = 10 + random.nextInt(6);
            try {
                if (block.getType().isAir()) {
                    Light light = (Light) Bukkit.createBlockData(Material.LIGHT);
                    light.setLevel(placedLevel);
                    block.setBlockData(light, false);
                    placed = true;
                }
            } catch (RuntimeException exception) {
                plugin.getLogger().warning("Could not place a Limbo light block at "
                        + x + "," + LIMBO_LIGHT_Y + "," + z + ": " + exception.getMessage());
                continue;
            }
            if ((placed || alreadyOwned) && block.getType() == Material.LIGHT) {
                int originalLevel = alreadyOwned
                        ? lightBlocks.get(key).level()
                        : ((Light) block.getBlockData()).getLevel();
                lightBlocks.put(key, new LightPoint(x, LIMBO_LIGHT_Y, z, originalLevel));
                if (isVoidBreathActive(effectTick())) {
                    Light light = (Light) block.getBlockData();
                    light.setLevel(1);
                    block.setBlockData(light, false);
                }
            }
        }
    }

    private void removeTrackedLightBlocks() {
        if (limboWorld == null) {
            return;
        }
        for (LightPoint point : lightBlocks.values()) {
            if (!limboWorld.isChunkLoaded(point.x() >> 4, point.z() >> 4)) {
                continue;
            }
            Block block = limboWorld.getBlockAt(point.x(), point.y(), point.z());
            if (block.getType() == Material.LIGHT) {
                block.setType(Material.AIR, false);
            }
        }
    }

    private void setLimboLightsDimmed(boolean dimmed) {
        setLimboLightDimFactor(dimmed ? 1.0 : 0.0);
    }

    private void setLimboLightDimFactor(double dimFactor) {
        if (limboWorld == null) {
            return;
        }
        double factor = Math.max(0.0, Math.min(1.0, dimFactor));
        for (LightPoint point : lightBlocks.values()) {
            if (!limboWorld.isChunkLoaded(point.x() >> 4, point.z() >> 4)) {
                continue;
            }
            Block block = limboWorld.getBlockAt(point.x(), point.y(), point.z());
            if (block.getType() != Material.LIGHT || !(block.getBlockData() instanceof Light light)) {
                continue;
            }
            int targetLevel = Math.max(1, (int) Math.round(point.level() * (1.0 - factor)));
            if (light.getLevel() != targetLevel) {
                light.setLevel(targetLevel);
                block.setBlockData(light, false);
            }
        }
    }

    private boolean chunkIntersectsLoop(Chunk chunk) {
        double minX = chunk.getX() * 16.0;
        double minZ = chunk.getZ() * 16.0;
        double maxX = minX + 16.0;
        double maxZ = minZ + 16.0;
        double nearestX = Math.max(minX, Math.min(loopCenterX, maxX));
        double nearestZ = Math.max(minZ, Math.min(loopCenterZ, maxZ));
        double dx = nearestX - loopCenterX;
        double dz = nearestZ - loopCenterZ;
        return dx * dx + dz * dz <= loopOuterRadius * loopOuterRadius;
    }

    private void rebuildFloorParticles() {
        floorParticles.clear();
        smokeParticles.clear();
        globalStarSectors.clear();
        globalStars.clear();
        starFollowStates.clear();
        localStarCollapseStates.clear();
        localStarArrivalStates.clear();
        if (!decorationsEnabled) {
            return;
        }

        ThreadLocalRandom random = ThreadLocalRandom.current();
        double starRadius = Math.max(0.0, STAR_LOCAL_RADIUS - 0.9);
        double smokeRadius = Math.max(0.0, loopOuterRadius - 0.9);
        if (floorParticlesPerRefresh > 0) {
            for (int index = 0; index < floorParticlesPerRefresh; index++) {
                double radius = Math.sqrt(random.nextDouble()) * starRadius;
                double angle = random.nextDouble(0.0, Math.PI * 2.0);
                floorParticles.add(new FloorParticle(
                        Math.cos(angle) * radius,
                        Math.sin(angle) * radius,
                        random.nextDouble(0.75, STAR_MAX_HEIGHT - 0.35),
                        random.nextDouble(0.15, 0.65),
                        random.nextDouble(0.18, 0.75),
                        random.nextDouble(0.15, 0.65),
                        random.nextDouble(0.006, 0.018),
                        random.nextDouble(0.008, 0.022),
                        random.nextDouble(0.006, 0.018),
                        random.nextDouble(0.0, Math.PI * 2.0),
                        random.nextDouble(0.0, Math.PI * 2.0),
                        random.nextDouble(0.0, Math.PI * 2.0),
                        random.nextDouble(0.035, 0.075),
                        random.nextDouble(0.0, Math.PI * 2.0)));
            }
        }
        double globalStarRadius = Math.max(0.0,
                loopOuterRadius + GLOBAL_STAR_VISUAL_EXTENSION - GLOBAL_STAR_EDGE_MARGIN);
        for (int index = 0; index < GLOBAL_STAR_PARTICLE_COUNT; index++) {
            double radius = Math.sqrt(random.nextDouble()) * globalStarRadius;
            double angle = random.nextDouble(0.0, Math.PI * 2.0);
            GlobalStarParticle star = new GlobalStarParticle(index,
                    loopCenterX + Math.cos(angle) * radius,
                    loopCenterZ + Math.sin(angle) * radius,
                    random.nextDouble(0.75, GLOBAL_STAR_MAX_HEIGHT - 0.35),
                    random.nextDouble(0.45, 1.15),
                    random.nextDouble(0.25, 0.75),
                    random.nextDouble(0.45, 1.15),
                    random.nextDouble(0.003, 0.012),
                    random.nextDouble(0.002, 0.009),
                    random.nextDouble(0.003, 0.012),
                    random.nextDouble(0.0, Math.PI * 2.0),
                    random.nextDouble(0.0, Math.PI * 2.0),
                    random.nextDouble(0.0, Math.PI * 2.0));
            long sector = chunkKey(LimboGeometry.starSector(star.baseX()),
                    LimboGeometry.starSector(star.baseZ()));
            globalStars.add(star);
            globalStarSectors.add(sector);
        }
        for (int index = 0; index < LIMBO_SMOKE_COLUMNS; index++) {
            smokeParticles.add(new SmokeParticle(
                    Math.sqrt(random.nextDouble()) * smokeRadius,
                    random.nextDouble(0.0, Math.PI * 2.0),
                    random.nextDouble(0.0, Math.PI * 2.0),
                    random.nextDouble(0.008, 0.014),
                    random.nextDouble(0.20, 0.75),
                    random.nextDouble(0.20, 0.75),
                    random.nextDouble(0.008, 0.020)));
        }
    }

    private void scheduleParticleTask() {
        if (particleTask != null) {
            particleTask.cancel();
        }
        particleTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickLimboParticles,
                1L, PARTICLE_ANIMATION_PERIOD_TICKS);
    }

    private void tickLimboParticles() {
        boolean renderFrame = effectClock.advance(System.nanoTime());
        // Teleports and boundary cleanup must run even when a visual frame is skipped.
        tickBoundaryWraps(renderFrame);
        enforceBoundaryWraps();
        if (!renderFrame) return;
        long now = effectTick();
        tickReturnTrails(now);
        tickPendingStepSounds(now);
        tickDistantSilhouetteMotion();
        List<Player> players = limboWorld == null ? List.of() : limboWorld.getPlayers();
        Set<UUID> playerIds = new HashSet<>();
        Map<UUID, Location> positions = new HashMap<>();
        for (Player player : players) {
            playerIds.add(player.getUniqueId());
            positions.put(player.getUniqueId(), player.getLocation());
        }
        starFollowStates.keySet().retainAll(playerIds);
        localStarCollapseStates.keySet().retainAll(playerIds);
        localStarArrivalStates.keySet().retainAll(playerIds);
        headStarStates.keySet().retainAll(playerIds);
        if (players.isEmpty()) return;
        if (now % particleRefreshPeriodTicks == 0L) tickCampfireSmoke(players);
        // End rods live 60-71 client ticks. A 120-tick pause allows a visibly dark middle.
        if (isVoidBreathActive(now) && now - voidBreathStartTick >= 10L
                && now - voidBreathStartTick < 130L) return;
        tickEndRodParticles(players, positions);
        tickHeadStarParticles(players, positions);
        tickGlobalEndRodParticles(players, positions);
    }

    private double mirroredStarY(double y) {
        return 2.0 * (LIMBO_FLOOR_Y + 0.5) - y;
    }

    private boolean shouldRenderBoundaryStars(Player player) {
        return player != null && isLimboWorld(player.getWorld());
    }

    private void spawnMirroredStarParticles(Player player, double x, double y, double z,
                                             int count, double offsetX, double offsetY,
                                             double offsetZ, double extra, boolean force) {
        Location upper = new Location(limboWorld, x, y, z);
        player.spawnParticle(Particle.END_ROD, upper, count,
                offsetX, offsetY, offsetZ, extra, (Void) null, force);
        Location lower = upper.clone();
        lower.setY(mirroredStarY(y));
        if (lower.getY() >= limboWorld.getMinHeight()) {
            player.spawnParticle(Particle.END_ROD, lower, count,
                    offsetX, offsetY, offsetZ, extra, (Void) null, force);
        }
    }

    private void tickEndRodParticles(List<Player> limboPlayers, Map<UUID, Location> positions) {
        if (!decorationsEnabled || limboWorld == null || floorParticles.isEmpty()) return;
        int particlesPerAnchor = adaptiveStarParticlesPerAnchor(limboPlayers.size());
        long tick = atmosphereTick(effectTick());
        for (Player player : limboPlayers) {
            StarQuality quality = getStarQuality(player);
            int anchorLimit = Math.min(floorParticles.size(),
                    quality.localAnchors(adaptiveStarAnchorLimit(limboPlayers.size())));
            if (!shouldRenderBoundaryStars(player)) {
                continue;
            }
            if (!areLocalStarsEnabled(player)) {
                LocalStarCollapse collapse = localStarCollapseStates.get(player.getUniqueId());
                if (collapse != null) {
                    tickLocalStarCollapse(player, collapse, tick, anchorLimit);
                }
                continue;
            }
            LocalStarArrival arrival = localStarArrivalStates.get(player.getUniqueId());
            if (arrival != null) {
                tickLocalStarArrival(player, arrival, tick, anchorLimit);
                continue;
            }
            Location playerOrigin = positions.get(player.getUniqueId());
            StarFollowState state = starFollowStates.computeIfAbsent(player.getUniqueId(),
                    ignored -> new StarFollowState(playerOrigin.getX(),
                            playerOrigin.getY(), playerOrigin.getZ()));
            state.update(playerOrigin.getX(), playerOrigin.getY(), playerOrigin.getZ());
            for (int index = 0; index < anchorLimit; index++) {
                FloorParticle particle = floorParticles.get(index);
                double radiusScale = reactiveStars ? state.radiusScale : 1.0;
                double baseX = particle.baseX() * radiusScale;
                double baseZ = particle.baseZ() * radiusScale;
                // The eased origin lags slightly; existing particles expire naturally as a light trail.
                double x = state.x + baseX
                        + Math.sin(tick * particle.driftXSpeed() + particle.phaseX())
                        * particle.driftXAmplitude();
                double y = state.y + particle.height()
                        + Math.sin(tick * particle.driftYSpeed() + particle.phaseY())
                        * particle.driftYAmplitude();
                double z = state.z + baseZ
                        + Math.sin(tick * particle.driftZSpeed() + particle.phaseZ())
                        * particle.driftZAmplitude();
                y = Math.max(state.y + 0.35, Math.min(state.y + STAR_MAX_HEIGHT, y));
                double twinkle = 0.5 + 0.5
                        * Math.sin(tick * particle.twinkleSpeed() + particle.twinklePhase());
                int particleCount = particleCountForTick(
                        (quality == StarQuality.HIGH ? Math.min(12, particlesPerAnchor + 2)
                                : particlesPerAnchor) - 2 + (int) Math.round(twinkle * 4.0),
                        particleRefreshPeriodTicks, effectTick(), index);
                if (particleCount <= 0) {
                    continue;
                }
                spawnMirroredStarParticles(player, x, y, z, particleCount,
                        0.004 + twinkle * 0.008, 0.004 + twinkle * 0.008,
                        0.004 + twinkle * 0.008, 0.001 + twinkle * 0.003, true);
            }
        }
    }

    private void tickLocalStarCollapse(Player player, LocalStarCollapse collapse, long tick, int anchorLimit) {
        long elapsed = Math.max(0L, tick - collapse.startTick());
        double progress = Math.min(1.0,
                elapsed / (double) Math.max(1, LOCAL_STAR_COLLAPSE_TICKS - 1));
        double easedProgress = 1.0 - Math.pow(1.0 - progress, 3.0);
        Location target = player.getLocation();
        double targetX = target.getX();
        double targetY = target.getY() + 1.0;
        double targetZ = target.getZ();
        for (int index = 0; index < anchorLimit; index++) {
            FloorParticle particle = floorParticles.get(index);
            double startX = collapse.x() + particle.baseX()
                    + Math.sin(collapse.startTick() * particle.driftXSpeed() + particle.phaseX())
                    * particle.driftXAmplitude();
            double startY = collapse.y() + particle.height()
                    + Math.sin(collapse.startTick() * particle.driftYSpeed() + particle.phaseY())
                    * particle.driftYAmplitude();
            double startZ = collapse.z() + particle.baseZ()
                    + Math.sin(collapse.startTick() * particle.driftZSpeed() + particle.phaseZ())
                    * particle.driftZAmplitude();
            double x = startX + (targetX - startX) * easedProgress;
            double y = startY + (targetY - startY) * easedProgress;
            double z = startZ + (targetZ - startZ) * easedProgress;
            spawnMirroredStarParticles(player, x, y, z, 1,
                    0.0, 0.0, 0.0, 0.0, true);
        }
        if (progress >= 1.0) {
            localStarCollapseStates.remove(player.getUniqueId());
        }
    }

    private void tickLocalStarArrival(Player player, LocalStarArrival arrival, long tick, int anchorLimit) {
        long elapsed = Math.max(0L, tick - arrival.startTick());
        double progress = Math.min(1.0,
                elapsed / (double) Math.max(1, LOCAL_STAR_ARRIVAL_TICKS - 1));
        Location playerOrigin = player.getLocation();
        for (int index = 0; index < Math.min(anchorLimit, arrival.points().size()); index++) {
            FloorParticle particle = floorParticles.get(index);
            ArrivalPoint point = arrival.points().get(index);
            double delayedProgress = Math.max(0.0,
                    (progress - point.delay()) / Math.max(0.001, 1.0 - point.delay()));
            double easedProgress = 1.0 - Math.pow(1.0 - Math.min(1.0, delayedProgress), 3.0);
            double targetX = playerOrigin.getX()
                    + particle.baseX()
                    + Math.sin(tick * particle.driftXSpeed() + particle.phaseX())
                    * particle.driftXAmplitude();
            double targetY = playerOrigin.getY() + particle.height()
                    + Math.sin(tick * particle.driftYSpeed() + particle.phaseY())
                    * particle.driftYAmplitude();
            double targetZ = playerOrigin.getZ()
                    + particle.baseZ()
                    + Math.sin(tick * particle.driftZSpeed() + particle.phaseZ())
                    * particle.driftZAmplitude();
            targetY = Math.max(playerOrigin.getY() + 0.35,
                    Math.min(playerOrigin.getY() + STAR_MAX_HEIGHT, targetY));
            double x = point.x() + (targetX - point.x()) * easedProgress;
            double y = point.y() + (targetY - point.y()) * easedProgress;
            double z = point.z() + (targetZ - point.z()) * easedProgress;
            spawnMirroredStarParticles(player, x, y, z, 1,
                    0.0, 0.0, 0.0, 0.0, true);
        }
        if (progress >= 1.0) {
            localStarArrivalStates.remove(player.getUniqueId());
        }
    }

    private void tickHeadStarParticles(List<Player> limboPlayers, Map<UUID, Location> positions) {
        if (!decorationsEnabled || limboWorld == null) return;
        long tick = atmosphereTick(effectTick());
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (Player player : limboPlayers) {
            if (!shouldRenderBoundaryStars(player)) {
                continue;
            }
            Location playerOrigin = positions.get(player.getUniqueId());
            HeadStarState state = headStarStates.computeIfAbsent(player.getUniqueId(),
                    ignored -> new HeadStarState(playerOrigin.getX(),
                            playerOrigin.getY() + HEAD_STAR_HEIGHT, playerOrigin.getZ(),
                            random.nextDouble(0.0, Math.PI * 2.0),
                            random.nextDouble(0.12, HEAD_STAR_ORBIT_RADIUS),
                            random.nextDouble(0.035, 0.09)));
            double orbit = tick * 0.035 + state.phase;
            state.update(playerOrigin.getX() + Math.cos(orbit) * state.orbitRadius,
                    playerOrigin.getY() + HEAD_STAR_HEIGHT
                            + Math.sin(orbit * 0.73 + state.phase) * state.bobAmplitude,
                    playerOrigin.getZ() + Math.sin(orbit) * state.orbitRadius);
            spawnMirroredStarParticles(player, state.x, state.y, state.z, 1,
                    0.0, 0.0, 0.0, 0.0, true);
        }
    }

    private void tickGlobalEndRodParticles(List<Player> players, Map<UUID, Location> positions) {
        if (!decorationsEnabled || limboWorld == null || globalStarSectors.isEmpty()) return;
        long now = effectTick();
        long tick = atmosphereTick(now);
        Map<StarQuality, Map<Long, List<Player>>> viewersByQuality = new EnumMap<>(StarQuality.class);
        for (Player player : players) {
            Map<Long, List<Player>> viewersBySector = viewersByQuality.computeIfAbsent(
                    getStarQuality(player), ignored -> new HashMap<>());
            Location position = positions.get(player.getUniqueId());
            int sectorX = LimboGeometry.starSector(position.getX());
            int sectorZ = LimboGeometry.starSector(position.getZ());
            // 34 blocks cover visible stars plus the maximum drift from their base.
            for (int x = sectorX - 2; x <= sectorX + 2; x++) {
                for (int z = sectorZ - 2; z <= sectorZ + 2; z++) {
                    long key = chunkKey(x, z);
                    if (globalStarSectors.contains(key)) {
                        viewersBySector.computeIfAbsent(key, ignored -> new ArrayList<>()).add(player);
                    }
                }
            }
        }
        boolean breath = isVoidBreathActive(now);
        double breathProgress = breath ? (now - voidBreathStartTick) / (double) VOID_BREATH_DURATION_TICKS : 0.0;
        double drop = breath ? Math.sin(Math.PI * breathProgress) * VOID_BREATH_STAR_DROP : 0.0;
        for (var quality : viewersByQuality.entrySet()) {
            quality.getKey().forEachGlobalStar(players.size(), now, globalStars.size(), index -> {
                GlobalStarParticle particle = globalStars.get(index);
                long sector = chunkKey(LimboGeometry.starSector(particle.baseX()),
                        LimboGeometry.starSector(particle.baseZ()));
                List<Player> viewers = quality.getValue().get(sector);
                if (viewers == null) return;
                boolean visible = false;
                for (Player viewer : viewers) {
                    if (isGlobalStarVisible(particle, viewer, positions)) {
                        visible = true;
                        break;
                    }
                }
                if (!visible) return;
                double xPhase = tick * particle.driftXSpeed() + particle.phaseX();
                double yPhase = tick * particle.driftYSpeed() + particle.phaseY();
                double x = particle.baseX() + Math.sin(xPhase) * particle.driftXAmplitude()
                        + Math.cos(tick * particle.driftXSpeed() * 0.47 + particle.phaseY())
                        * particle.driftXAmplitude() * 0.35;
                double y = LIMBO_LIGHT_Y + particle.height()
                        + Math.sin(yPhase) * particle.driftYAmplitude()
                        + Math.cos(tick * particle.driftYSpeed() * 0.41 + particle.phaseX())
                        * particle.driftYAmplitude() * 0.35 - drop;
                double z = particle.baseZ()
                        + Math.sin(tick * particle.driftZSpeed() * 0.53 + particle.phaseZ())
                        * particle.driftZAmplitude()
                        + Math.cos(tick * particle.driftZSpeed() * 0.37 + particle.phaseY())
                        * particle.driftZAmplitude() * 0.35;
                y = Math.max(LIMBO_LIGHT_Y + 0.35, Math.min(LIMBO_LIGHT_Y + GLOBAL_STAR_MAX_HEIGHT, y));
                for (Player viewer : viewers) {
                    if (isGlobalStarVisible(particle, viewer, positions)) {
                        spawnMirroredStarParticles(viewer, x, y, z, 1, 0, 0, 0, 0, false);
                    }
                }
            });
        }
    }

    private boolean isGlobalStarVisible(GlobalStarParticle particle, Player viewer,
                                        Map<UUID, Location> positions) {
        Location position = positions.get(viewer.getUniqueId());
        double dx = particle.baseX() - position.getX();
        double dz = particle.baseZ() - position.getZ();
        return LimboGeometry.starWithinView(dx, dz);
    }

    private static int adaptiveStarAnchorLimit(int limboPlayerCount) {
        int safePlayerCount = Math.max(0, limboPlayerCount);
        if (safePlayerCount <= 2) {
            return 64;
        }
        if (safePlayerCount <= 8) {
            return 48;
        }
        if (safePlayerCount <= 16) {
            return 40;
        }
        if (safePlayerCount <= 32) {
            return 32;
        }
        return 24;
    }

    private static int adaptiveStarParticlesPerAnchor(int limboPlayerCount) {
        int safePlayerCount = Math.max(0, limboPlayerCount);
        if (safePlayerCount <= 2) {
            return STAR_PARTICLES_PER_ANCHOR;
        }
        if (safePlayerCount <= 8) {
            return 6;
        }
        if (safePlayerCount <= 16) {
            return 5;
        }
        if (safePlayerCount <= 32) {
            return 4;
        }
        return 3;
    }

    private static int particleCountForTick(int particlesPerRefresh, int refreshPeriodTicks,
                                            long tick, int anchorIndex) {
        int safeParticles = Math.max(0, particlesPerRefresh);
        int safePeriod = Math.max(1, refreshPeriodTicks);
        int base = safeParticles / safePeriod;
        int remainder = safeParticles % safePeriod;
        int phase = (int) Math.floorMod(tick + anchorIndex, (long) safePeriod);
        return base + (phase < remainder ? 1 : 0);
    }

    private static final class StarFollowState {
        private double x;
        private double y;
        private double z;

        private double playerX;
        private double playerY;
        private double playerZ;
        private long stillTicks;
        private double radiusScale = 1.0;

        private StarFollowState(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.playerX = x;
            this.playerY = y;
            this.playerZ = z;
        }

        private void update(double targetX, double targetY, double targetZ) {
            double mx = targetX - playerX;
            double my = targetY - playerY;
            double mz = targetZ - playerZ;
            double movement = mx * mx + my * my + mz * mz;
            stillTicks = movement < 0.0004 ? Math.min(300L, stillTicks + 1L) : 0L;
            radiusScale += (LimboGeometry.reactiveStarRadius(stillTicks, movement) - radiusScale) * 0.04;
            playerX = targetX;
            playerY = targetY;
            playerZ = targetZ;
            double deltaX = targetX - x;
            double deltaY = targetY - y;
            double deltaZ = targetZ - z;
            if (deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ
                    > STAR_FOLLOW_SNAP_DISTANCE * STAR_FOLLOW_SNAP_DISTANCE) {
                x = targetX;
                y = targetY;
                z = targetZ;
                return;
            }
            x += deltaX * STAR_FOLLOW_INTERPOLATION;
            y += deltaY * STAR_FOLLOW_INTERPOLATION;
            z += deltaZ * STAR_FOLLOW_INTERPOLATION;
        }
    }

    private static final class HeadStarState {
        private double x;
        private double y;
        private double z;
        private final double phase;
        private final double orbitRadius;
        private final double bobAmplitude;

        private HeadStarState(double x, double y, double z, double phase,
                              double orbitRadius, double bobAmplitude) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.phase = phase;
            this.orbitRadius = orbitRadius;
            this.bobAmplitude = bobAmplitude;
        }

        private void update(double targetX, double targetY, double targetZ) {
            double deltaX = targetX - x;
            double deltaY = targetY - y;
            double deltaZ = targetZ - z;
            if (deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ
                    > HEAD_STAR_SNAP_DISTANCE * HEAD_STAR_SNAP_DISTANCE) {
                x = targetX;
                y = targetY;
                z = targetZ;
                return;
            }
            x += deltaX * HEAD_STAR_FOLLOW_INTERPOLATION;
            y += deltaY * HEAD_STAR_FOLLOW_INTERPOLATION;
            z += deltaZ * HEAD_STAR_FOLLOW_INTERPOLATION;
        }
    }

    private void tickCampfireSmoke(List<Player> players) {
        if (!decorationsEnabled || limboWorld == null || smokeParticles.isEmpty()) {
            return;
        }

        long tick = atmosphereTick(effectTick());
        for (SmokeParticle particle : smokeParticles) {
            double descent = (tick * particle.descentSpeed() + particle.phase()) % 1.0;
            double angle = particle.angle()
                    + Math.sin(tick * particle.driftSpeed() + particle.phase()) * 0.18;
            double radius = Math.max(0.0, particle.radius()
                    + Math.cos(tick * particle.driftSpeed() + particle.phase()) * 0.35);
            Location location = new Location(limboWorld,
                    loopCenterX + Math.cos(angle) * radius
                            + Math.sin(tick * particle.driftSpeed() + particle.phase())
                            * particle.driftXAmplitude(),
                    LIMBO_LIGHT_Y + SMOKE_TOP_HEIGHT - descent * SMOKE_DROP_HEIGHT,
                    loopCenterZ + Math.sin(angle) * radius
                            + Math.cos(tick * particle.driftSpeed() + particle.phase())
                            * particle.driftZAmplitude());
            for (Player player : players) {
                player.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, location,
                        1, 0.08, 0.02, 0.08, -0.01, (Void) null, true);
            }
        }
    }

    private void startRejectionIfDue(Player player, long now) {
        if (!rejectionEnabled) {
            return;
        }
        UUID uuid = player.getUniqueId();
        BukkitTask existingTask = rejectionTasks.get(uuid);
        if (existingTask != null) {
            if (!existingTask.isCancelled()) {
                return;
            }
            cleanupRejectionScene(uuid);
        }
        Long retryAt = rejectionRetryAt.get(uuid);
        if (retryAt != null && now < retryAt) {
            return;
        }

        SMPReviveBridge.IntegrationMode mode = integrationModeFor(player);
        boolean pairedScene = fullRevivalEnabled
                && mode == SMPReviveBridge.IntegrationMode.V1;

        long onlineTicks = getOnlineTicks(player);
        if (onlineTicks < rejectionAfterTicks) {
            return;
        }
        if (!rejectionInProgress.add(uuid)) {
            return;
        }

        RejectionScene scene = null;
        try {
            if (pairedScene) {
                World returnWorld = Bukkit.getWorld(returnWorldName);
                if (returnWorld == null || returnWorld == limboWorld
                        || LIMBO_WORLD_KEY.equals(returnWorld.getKey())) {
                    scheduleRejectionRetry(uuid, now);
                    return;
                }
                Location target = getReturnLocation(player, returnWorld);
                if (target == null) {
                    scheduleRejectionRetry(uuid, now);
                    return;
                }
                scene = createRejectionScene(player, target);
                if (scene == null || scene.display == null || scene.targetChunk == null) {
                    scheduleRejectionRetry(uuid, now);
                    return;
                }
                rejectionScenes.put(uuid, scene);
            }
            RejectionScene activeScene = scene;
            BukkitTask task = new BukkitRunnable() {
                private int tick;
                private final LimboEffectClock animationClock = new LimboEffectClock(20);

                @Override
                public void run() {
                    if (!player.isOnline() || limboWorld == null
                            || !isLimbo(player) || !isLimboWorld(player.getWorld())
                            || (activeScene != null && activeScene.cleaned)) {
                        cancelRejectionTask(uuid);
                        return;
                    }
                    try {
                        if (animationClock.advance(System.nanoTime())) {
                            playRejectionAnimationTick(
                                    player, tick, rejectionAnimationTicks, activeScene);
                        }
                        // Completion is game logic: sprint must still finish the revival.
                        tick++;
                        if (tick >= rejectionAnimationTicks) {
                            cancel();
                            rejectSoul(activeScene, player, currentTick());
                        }
                    } catch (RuntimeException exception) {
                        plugin.getLogger().warning("Limbo soul rejection animation failed: "
                                + exception.getMessage());
                        cancelRejectionTask(uuid);
                        scheduleRejectionRetry(uuid, currentTick());
                    }
                }
            }.runTaskTimer(plugin, 0L, 1L);
            rejectionTasks.put(uuid, task);
            if (scene != null) scene.task = task;
        } catch (RuntimeException exception) {
            cleanupRejectionScene(uuid);
            scheduleRejectionRetry(uuid, now);
            plugin.getLogger().warning("Could not start Limbo soul rejection animation: "
                    + exception.getMessage());
        }
    }

    private void scheduleRejectionRetry(UUID uuid, long now) {
        if (uuid == null) return;
        rejectionRetryAt.put(uuid, ConfigGuard.saturatingAdd(now, REJECTION_RETRY_TICKS));
        rejectionInProgress.remove(uuid);
    }

    private RejectionScene createRejectionScene(Player player, Location target) {
        if (player == null || target == null || target.getWorld() == null) {
            throw new IllegalArgumentException("rejection target is missing");
        }
        World world = target.getWorld();
        RejectionScene scene = new RejectionScene(
                UUID.randomUUID(),
                target,
                ThreadLocalRandom.current().nextDouble(0.0, Math.PI * 2.0),
                LimboRejectionRules.headFlightRadius(ThreadLocalRandom.current().nextDouble()));
        Chunk targetChunk = ensureRejectionChunk(target);
        boolean ticketAdded = false;
        try {
            ticketAdded = targetChunk != null && targetChunk.addPluginChunkTicket(plugin);
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("Could not keep the Limbo rejection target chunk loaded: "
                    + exception.getMessage());
        }
        scene.targetChunk = ticketAdded ? targetChunk : null;
        if (!ticketAdded) {
            return null;
        }

        try {
            ItemStack headItem = new ItemStack(Material.PLAYER_HEAD, 1);
            SkullMeta skullMeta = (SkullMeta) headItem.getItemMeta();
            if (skullMeta == null) throw new IllegalStateException("PLAYER_HEAD meta unavailable");
            skullMeta.setPlayerProfile(player.getPlayerProfile());
            headItem.setItemMeta(skullMeta);
            ItemDisplay display = world.spawn(target.clone().add(0.0, 0.35, 0.0), ItemDisplay.class);
            display.setItemStack(headItem);
            display.setPersistent(false);
            display.setViewRange(32.0f);
            display.setBillboard(Display.Billboard.CENTER);
            display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            display.getPersistentDataContainer().set(
                    rejectionDisplayKey, PersistentDataType.STRING, scene.id.toString());
            scene.display = display;
        } catch (RuntimeException exception) {
            scene.display = null;
            targetChunk.removePluginChunkTicket(plugin);
            scene.targetChunk = null;
            plugin.getLogger().warning("Could not create the normal-world Limbo rejection display: "
                    + exception.getMessage());
            return null;
        }
        return scene;
    }

    private Chunk ensureRejectionChunk(Location target) {
        if (target == null || target.getWorld() == null) return null;
        int chunkX = target.getBlockX() >> 4;
        int chunkZ = target.getBlockZ() >> 4;
        Chunk chunk = target.getWorld().getChunkAt(chunkX, chunkZ, true);
        return chunk != null && chunk.isLoaded() && chunk.isGenerated() ? chunk : null;
    }

    private void playRejectionAnimationTick(
            Player player, int tick, int durationTicks, RejectionScene scene) {
        Location center = player.getLocation().clone().add(0.0, 1.0, 0.0);
        double progress = LimboRejectionRules.progress(tick, durationTicks);
        double radius = 0.25 + progress * 1.35;
        int particles = 12 + (int) Math.round(progress * 24.0);
        center.getWorld().spawnParticle(Particle.REVERSE_PORTAL, center, particles,
                radius, 0.55 + progress * 0.35, radius, 0.03);
        center.getWorld().spawnParticle(Particle.SQUID_INK, center, 5 + particles / 3,
                radius * 0.7, 0.35, radius * 0.7, 0.01);
        if (tick % 10 == 0) {
            player.playSound(player.getLocation(), "minecraft:entity.wither.ambient",
                    SoundCategory.AMBIENT, 0.35F, 0.55F + (float) progress * 0.2F);
        }

        if (scene != null) {
            renderRejectionCloud(scene, progress, tick, durationTicks);
        }
        if (tick + 1 >= durationTicks) {
            center.getWorld().spawnParticle(Particle.EXPLOSION_EMITTER, center, 1);
            center.getWorld().spawnParticle(Particle.PORTAL, center, 80,
                    1.2, 0.8, 1.2, 0.45);
        }
    }

    private void renderRejectionCloud(
            RejectionScene scene, double progress, int tick, int durationTicks) {
        if (scene == null || scene.cleaned || scene.cloudLocation.getWorld() == null) return;
        Location cloud = scene.cloudLocation.clone().add(0.0, 1.0, 0.0);
        World world = cloud.getWorld();
        int cloudParticles = 24 + (int) Math.round(progress * 18.0);
        world.spawnParticle(Particle.SQUID_INK, cloud, cloudParticles,
                0.65 + progress * 0.25, 0.5, 0.65 + progress * 0.25, 0.01);
        world.spawnParticle(Particle.SMOKE, cloud, 12 + cloudParticles / 3,
                0.5, 0.75, 0.5, 0.02);
        world.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, cloud, 4,
                0.35, 0.4, 0.35, 0.01);

        if (tick == 0) {
            playRejectionSound(scene.cloudLocation, "limbo.rejection.wither");
            playRejectionSound(scene.cloudLocation, "limbo.rejection.basalt");
        }
        if (tick == Math.max(1, durationTicks / 2)) {
            playRejectionSound(scene.cloudLocation, "limbo.rejection.trident");
            playRejectionSound(scene.cloudLocation, "limbo.rejection.cave");
        }
        if (tick + 1 >= durationTicks) {
            playRejectionSound(scene.cloudLocation, "limbo.rejection.lightning-impact");
            playRejectionSound(scene.cloudLocation, "limbo.rejection.lightning-thunder");
            world.spawnParticle(Particle.EXPLOSION_EMITTER, cloud, 1);
            world.spawnParticle(Particle.PORTAL, cloud, 50, 1.0, 0.7, 1.0, 0.35);
        }

        ItemDisplay display = scene.display;
        if (display == null || display.isDead()) return;
        double x = 0.0;
        double z = 0.0;
        double y;
        if (progress < 0.5) {
            double rise = progress * 2.0;
            double eased = rise * rise * (3.0 - 2.0 * rise);
            y = 0.35 + eased * 1.6;
        } else {
            double flight = (progress - 0.5) * 2.0;
            double eased = flight * flight * (3.0 - 2.0 * flight);
            x = Math.cos(scene.flightAngle) * scene.flightRadius * eased;
            z = Math.sin(scene.flightAngle) * scene.flightRadius * eased;
            y = 1.95 + Math.sin(Math.PI * flight) * 0.8 - eased * 0.2;
        }
        float spin = (float) (progress * Math.PI * 8.0);
        float scale = 0.9f - (float) progress * 0.15f;
        display.setTransformation(new Transformation(
                new Vector3f(),
                new Quaternionf(0.0f, (float) Math.sin(spin), 0.0f, (float) Math.cos(spin)),
                new Vector3f(scale, scale, scale),
                new Quaternionf()));
        display.teleport(scene.cloudLocation.clone().add(x, y, z));
    }

    private void playRejectionSound(Location location, String configPath) {
        if (plugin instanceof SpaydisCorePlugin spaydisCorePlugin) {
            spaydisCorePlugin.playSoundToNearby(location, configPath);
        }
    }

    private void rejectSoul(RejectionScene scene, Player player, long now) {
        if (player == null || !player.isOnline() || !isLimbo(player)) {
            if (player != null) cleanupRejectionScene(player.getUniqueId());
            return;
        }
        World returnWorld = Bukkit.getWorld(returnWorldName);
        if (returnWorld == null || returnWorld == limboWorld
                || LIMBO_WORLD_KEY.equals(returnWorld.getKey())) {
            cleanupRejectionScene(player.getUniqueId());
            scheduleRejectionRetry(player.getUniqueId(), now);
            return;
        }

        // The border (or terrain) can change during the scene. Validate again before revival.
        Location target = scene == null ? null : scene.cloudLocation.clone();
        if (!isSafeReturnLocation(target)) target = getReturnLocation(player, returnWorld);
        if (target == null) {
            cleanupRejectionScene(player.getUniqueId());
            scheduleRejectionRetry(player.getUniqueId(), now);
            return;
        }
        UUID uuid = player.getUniqueId();
        releaseBypasses.add(uuid);
        boolean teleported;
        SMPReviveBridge.IntegrationMode mode = integrationModeFor(player);
        boolean v1Revival = mode == SMPReviveBridge.IntegrationMode.V1;
        try {
            if (mode == SMPReviveBridge.IntegrationMode.V1) {
                teleported = smpReviveBridge.reviveFromLimbo(player, target);
            } else if (mode == SMPReviveBridge.IntegrationMode.LEGACY) {
                teleported = smpReviveBridge.reviveLegacy(player)
                        && teleportToReturnLocation(player, returnWorld);
            } else if (mode == SMPReviveBridge.IntegrationMode.UNSUPPORTED) {
                cleanupRejectionScene(uuid);
                scheduleRejectionRetry(uuid, now);
                return;
            } else {
                teleported = player.teleport(target);
            }
        } finally {
            releaseBypasses.remove(uuid);
        }
        if (!teleported && (v1Revival || mode == SMPReviveBridge.IntegrationMode.LEGACY)
                && smpReviveBridge.confirmsRevived(player)) {
            teleported = teleportToReturnLocation(player, returnWorld);
        }
        if (!teleported) {
            cleanupRejectionScene(uuid);
            scheduleRejectionRetry(uuid, now);
            return;
        }

        cleanupRejectionScene(uuid);
        rejectionRetryAt.remove(uuid);
        if (v1Revival || mode == SMPReviveBridge.IntegrationMode.LEGACY) {
            clearState(player, false);
        } else {
            clearState(player);
        }
        disableSurvivalFlight(player);
    }

    private void cancelRejectionTasks() {
        Set<UUID> ids = new HashSet<>(rejectionTasks.keySet());
        ids.addAll(rejectionScenes.keySet());
        for (UUID uuid : ids) cleanupRejectionScene(uuid);
        rejectionTasks.clear();
        rejectionScenes.clear();
        rejectionInProgress.clear();
        rejectionRetryAt.clear();
    }

    private void cancelRejectionTask(UUID uuid) {
        if (uuid == null) {
            return;
        }
        cleanupRejectionScene(uuid);
    }

    private void cleanupRejectionScene(UUID uuid) {
        if (uuid == null) return;
        RejectionScene scene = rejectionScenes.remove(uuid);
        BukkitTask task = rejectionTasks.remove(uuid);
        if (task != null) task.cancel();
        if (scene != null) {
            scene.cleaned = true;
            if (scene.task != null && scene.task != task) scene.task.cancel();
            if (scene.targetChunk != null) scene.targetChunk.removePluginChunkTicket(plugin);
            ItemDisplay display = scene.display;
            if (display != null && !display.isDead()) {
                String owner = display.getPersistentDataContainer().get(
                        rejectionDisplayKey, PersistentDataType.STRING);
                if (scene.id.toString().equals(owner)) display.remove();
            }
        }
        rejectionInProgress.remove(uuid);
    }

    private void disableSurvivalFlight(Player player) {
        if (player == null) return;
        GameMode gameMode = player.getGameMode();
        if (gameMode == GameMode.SURVIVAL || gameMode == GameMode.ADVENTURE) {
            player.setFlying(false);
            player.setAllowFlight(false);
        }
    }

    private long chunkKey(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffff_ffffL);
    }

    private long blockKey(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffff_ffffL);
    }

    private void relocateIsolatedPlayers(List<Player> players) {
        if (!aloneTeleportEnabled || players.size() < 2) {
            aloneSince.clear();
            return;
        }

        long now = currentTick();
        java.util.Set<UUID> present = new java.util.HashSet<>();
        Map<UUID, Location> positions = new HashMap<>();
        for (Player player : players) {
            present.add(player.getUniqueId());
            positions.put(player.getUniqueId(), player.getLocation());
        }
        aloneSince.keySet().removeIf(uuid -> !present.contains(uuid));

        double radiusSquared = aloneRadius * aloneRadius;
        for (Player player : players) {
            Player nearest = findNearestOrNearbyLimboPlayer(player, players, positions, radiusSquared);
            if (nearest == null || positions.get(player.getUniqueId())
                    .distanceSquared(positions.get(nearest.getUniqueId())) <= radiusSquared) {
                aloneSince.remove(player.getUniqueId());
                continue;
            }

            long isolatedSince = aloneSince.computeIfAbsent(player.getUniqueId(), ignored -> now);
            if (now < ConfigGuard.saturatingAdd(isolatedSince, aloneAfterTicks)) {
                continue;
            }
            if (teleportAlonePlayer(player, nearest)) {
                positions.put(player.getUniqueId(), player.getLocation());
                aloneSince.put(player.getUniqueId(), now);
            }
        }
    }

    static Player findNearestOrNearbyLimboPlayer(Player player, List<Player> players,
                                                Map<UUID, Location> positions, double nearbyRadiusSquared) {
        Location origin = positions.get(player.getUniqueId());
        Player nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (Player candidate : players) {
            Location target = positions.get(candidate.getUniqueId());
            if (candidate == player || target.getWorld() != origin.getWorld()) {
                continue;
            }
            double dx = origin.getX() - target.getX();
            double dy = origin.getY() - target.getY();
            double dz = origin.getZ() - target.getZ();
            double distance = dx * dx + dy * dy + dz * dz;
            // Any nearby player clears isolation. Only an isolated player needs
            // the exact nearest destination, so clustered groups stop early.
            if (distance <= nearbyRadiusSquared) return candidate;
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearest = candidate;
            }
        }
        return nearest;
    }

    private boolean teleportAlonePlayer(Player player, Player nearbyPlayer) {
        Location target = nearbyPlayer.getLocation().clone();
        if (aloneOffsetRadius > 0.0) {
            double angle = ThreadLocalRandom.current().nextDouble(0.0, Math.PI * 2.0);
            double distance = ThreadLocalRandom.current().nextDouble() * aloneOffsetRadius;
            target.add(Math.cos(angle) * distance, 0.0, Math.sin(angle) * distance);
        }
        target.setY(65.0);
        target.setYaw(player.getYaw());
        target.setPitch(player.getPitch());
        return player.teleport(target);
    }

    private void reapplyOnlinePlayers() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (isLimboWorld(player.getWorld()) || hasActiveMarker(player)) {
                captureState(player);
                trackActive(player);
                if (isLimboWorld(player.getWorld())) {
                    applyState(player, false);
                }
            }
        }
    }

    private void captureState(Player player) {
        captureState(player, null);
    }

    private void captureState(Player player, LimboState snapshot) {
        returnTrails.remove(player.getUniqueId());
        PersistentDataContainer data = player.getPersistentDataContainer();
        boolean wasActive = hasActiveMarker(player);
        long now = currentTick();
        Long onlineTicks = data.get(limboOnlineTicksKey, PersistentDataType.LONG);
        if (onlineTicks == null || onlineTicks < 0L) {
            data.set(limboOnlineTicksKey, PersistentDataType.LONG, 0L);
        }
        Long lastOnlineTick = data.get(lastOnlineTickKey, PersistentDataType.LONG);
        if (lastOnlineTick == null || lastOnlineTick < 0L || lastOnlineTick > now) {
            data.set(lastOnlineTickKey, PersistentDataType.LONG, now);
        }
        data.remove(enteredAtTickKey);
        data.remove(legacyEnteredAtKey);
        if (wasActive && stateSnapshots.containsKey(player.getUniqueId())) {
            return;
        }

        if (snapshot == null) {
            snapshot = snapshotState(player, wasActive);
        }

        if (!wasActive) {
            data.set(activeKey, PersistentDataType.BYTE, (byte) 1);
            data.set(originalGameModeKey, PersistentDataType.STRING, snapshot.gameMode().name());
            data.set(originalGravityKey, PersistentDataType.BYTE, (byte) (snapshot.gravity() ? 1 : 0));
            data.set(originalAllowFlightKey, PersistentDataType.BYTE, (byte) (snapshot.allowFlight() ? 1 : 0));
            data.set(originalFlyingKey, PersistentDataType.BYTE, (byte) (snapshot.flying() ? 1 : 0));
        }

        stateSnapshots.put(player.getUniqueId(), snapshot);
    }

    private LimboState snapshotState(Player player, boolean wasActive) {
        // A restarted server cannot reconstruct old potion effects, so do not preserve
        // Limbo's own effects as if they were the player's original effects.
        return new LimboState(
                wasActive ? null : player.getActivePotionEffects().stream()
                        .filter(effect -> effect.getType().equals(PotionEffectType.INVISIBILITY))
                        .findFirst().orElse(null),
                wasActive ? null : player.getActivePotionEffects().stream()
                        .filter(effect -> effect.getType().equals(PotionEffectType.DARKNESS))
                        .findFirst().orElse(null),
                player.getScoreboard(), player.getGameMode(), player.hasGravity(),
                player.getAllowFlight(), player.isFlying());
    }

    private boolean hasActiveMarker(Player player) {
        return player.getPersistentDataContainer().has(activeKey, PersistentDataType.BYTE);
    }

    private void applyState(Player player, boolean resetVelocity) {
        returnTrails.remove(player.getUniqueId());
        if (!isLimboWorld(player.getWorld())) {
            return;
        }
        captureState(player);
        player.getPersistentDataContainer().remove(spectatorKey);
        trackActive(player);
        addVoiceEchoParticipant(player.getUniqueId());
        if (player.getGameMode() != GameMode.ADVENTURE) player.setGameMode(GameMode.ADVENTURE);
        if (player.hasGravity()) player.setGravity(false);
        if (player.isFlying()) player.setFlying(false);
        if (player.getAllowFlight()) player.setAllowFlight(false);
        if (resetVelocity) {
            player.setVelocity(new Vector(0.0, 0.0, 0.0));
        }
        ensureLimboEffect(player, PotionEffectType.INVISIBILITY);
        ensureLimboEffect(player, PotionEffectType.DARKNESS);

        Scoreboard scoreboard = getLimboScoreboard();
        if (scoreboard == null) {
            return;
        }
        Team team = getOrCreateLimboTeam(scoreboard);
        // Other scoreboard plugins may have moved an occupant to a different team.
        // Every viewer needs every Limbo occupant in this friendly-invisible team.
        for (Player occupant : limboWorld.getPlayers()) {
            if (!team.hasEntry(occupant.getName())) team.addEntry(occupant.getName());
        }
        // Join/reconnect has explicit rebuild/resync windows; steady state needs no resend.
        if (player.getScoreboard() != scoreboard) player.setScoreboard(scoreboard);
    }

    private static void ensureLimboEffect(Player player, PotionEffectType type) {
        PotionEffect current = player.getPotionEffect(type);
        if (isManagedLimboEffect(current)) return;
        // These effects belong to Limbo and are removed/restored by clearState().
        // A 60-game-tick duration would expire/rebroadcast hundreds of times during sprint.
        player.addPotionEffect(new PotionEffect(type, PotionEffect.INFINITE_DURATION,
                0, false, false, false), true);
    }

    private static boolean isManagedLimboEffect(PotionEffect effect) {
        return effect != null && effect.isInfinite() && effect.getAmplifier() == 0
                && !effect.isAmbient() && !effect.hasParticles() && !effect.hasIcon();
    }

    private static void expireLimboEffect(Player player, PotionEffectType type) {
        if (!isManagedLimboEffect(player.getPotionEffect(type))) return;
        // Explicit removal is needed: a shorter duration may not replace an infinite effect.
        player.removePotionEffect(type);
        player.addPotionEffect(new PotionEffect(type, 60, 0, false, false, false));
    }

    private void scheduleLimboScoreboardResync() {
        for (long delay : SCOREBOARD_RESYNC_DELAYS) {
            // Use absolute due ticks: simultaneous joins share work, later joins
            // still receive every one of their own reconnect resync windows.
            long dueTick = ConfigGuard.saturatingAdd(currentTick(), delay);
            scoreboardResyncTasks.computeIfAbsent(dueTick, ignored -> Bukkit.getScheduler().runTaskLater(plugin, () -> {
                scoreboardResyncTasks.remove(dueTick);
                if (enabled && limboWorld != null) {
                    rebuildLimboScoreboard();
                }
            }, delay));
        }
    }

    private void cancelScoreboardResyncs() {
        for (BukkitTask task : scoreboardResyncTasks.values()) task.cancel();
        scoreboardResyncTasks.clear();
        lastScoreboardRebuildTick = Long.MIN_VALUE;
    }

    private void rebuildLimboScoreboard() {
        if (limboWorld == null || Bukkit.getScoreboardManager() == null) {
            return;
        }
        long now = currentTick();
        if (lastScoreboardRebuildTick == now) return;
        lastScoreboardRebuildTick = now;
        Scoreboard scoreboard = Bukkit.getScoreboardManager().getNewScoreboard();
        Team team = getOrCreateLimboTeam(scoreboard);
        for (Player candidate : Bukkit.getOnlinePlayers()) {
            if (isLimboWorld(candidate.getWorld())) {
                team.addEntry(candidate.getName());
            }
        }
        limboScoreboard = scoreboard;
        for (Player candidate : Bukkit.getOnlinePlayers()) {
            if (isLimboWorld(candidate.getWorld())) {
                candidate.setScoreboard(scoreboard);
            }
        }
    }

    private Scoreboard getLimboScoreboard() {
        if (limboScoreboard != null) {
            return limboScoreboard;
        }
        if (Bukkit.getScoreboardManager() == null) {
            return null;
        }
        limboScoreboard = Bukkit.getScoreboardManager().getNewScoreboard();
        return limboScoreboard;
    }

    static Team getOrCreateLimboTeam(Scoreboard scoreboard) {
        Team team = scoreboard.getTeam(LIMBO_TEAM_NAME);
        if (team == null) {
            team = scoreboard.registerNewTeam(LIMBO_TEAM_NAME);
        }
        // Paper broadcasts team metadata even for same-value setters.
        if (team.allowFriendlyFire()) team.setAllowFriendlyFire(false);
        if (!team.canSeeFriendlyInvisibles()) team.setCanSeeFriendlyInvisibles(true);
        if (team.getOption(Team.Option.COLLISION_RULE) != Team.OptionStatus.NEVER)
            team.setOption(Team.Option.COLLISION_RULE, Team.OptionStatus.NEVER);
        if (team.getOption(Team.Option.NAME_TAG_VISIBILITY) != Team.OptionStatus.NEVER)
            team.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.NEVER);
        return team;
    }

    public enum EnterResult {
        ENTERED,
        ALREADY_IN_LIMBO,
        UNAVAILABLE,
        TELEPORT_FAILED
    }

    public enum SpectateResult {
        SPECTATING,
        RETURNED_TO_LIMBO,
        NOT_ADMIN,
        NOT_IN_LIMBO,
        RETURN_WORLD_MISSING,
        TELEPORT_FAILED,
        UNAVAILABLE
    }

    /** Forced entries ignore SMPRevive's "alive" state so they cannot walk out through a teleport. */
    private boolean isForced(Player player) {
        return player != null && player.getPersistentDataContainer().has(forcedKey, PersistentDataType.BYTE);
    }

    public boolean isSpectating(Player player) {
        return player != null
                && player.getPersistentDataContainer().has(spectatorKey, PersistentDataType.BYTE);
    }

    private SMPReviveBridge.IntegrationMode integrationModeFor(Player player) {
        return isForced(player) ? SMPReviveBridge.IntegrationMode.ABSENT : smpReviveBridge.capability();
    }

    /** Sends a living player to Limbo until an admin releases them or the rejection timer ends. */
    public EnterResult enter(Player player) {
        Location spawn = getSpawn();
        if (player == null || !player.isOnline() || player.isDead() || !isEnabled() || spawn == null) {
            return EnterResult.UNAVAILABLE;
        }
        if (isLimbo(player)) {
            return EnterResult.ALREADY_IN_LIMBO;
        }
        storeReturnLocation(player, player.getLocation());
        captureState(player);
        player.getPersistentDataContainer().set(forcedKey, PersistentDataType.BYTE, (byte) 1);
        trackActive(player);
        if (!player.teleport(spawn)) {
            clearState(player);
            return EnterResult.TELEPORT_FAILED;
        }
        applyState(player, true);
        return EnterResult.ENTERED;
    }

    /**
     * Lets an admin occupant leave the Limbo world as a spectator while staying marked as a
     * Limbo occupant (so revival still works); calling it again returns them to Limbo.
     */
    public SpectateResult toggleSpectate(Player player) {
        if (player == null || !isEnabled()) {
            return SpectateResult.UNAVAILABLE;
        }
        if (!player.hasPermission("spaydiscore.admin")) {
            return SpectateResult.NOT_ADMIN;
        }
        if (!isLimbo(player)) {
            return SpectateResult.NOT_IN_LIMBO;
        }
        UUID uuid = player.getUniqueId();
        PersistentDataContainer data = player.getPersistentDataContainer();
        if (isSpectating(player)) {
            Location spawn = getSpawn();
            if (spawn == null) {
                return SpectateResult.UNAVAILABLE;
            }
            data.remove(spectatorKey);
            if (!player.teleport(spawn)) {
                data.set(spectatorKey, PersistentDataType.BYTE, (byte) 1);
                return SpectateResult.TELEPORT_FAILED;
            }
            applyState(player, true);
            return SpectateResult.RETURNED_TO_LIMBO;
        }
        if (!isLimboWorld(player.getWorld())) {
            return SpectateResult.NOT_IN_LIMBO;
        }
        World returnWorld = Bukkit.getWorld(returnWorldName);
        if (returnWorld == null || isLimboWorld(returnWorld)) {
            return SpectateResult.RETURN_WORLD_MISSING;
        }
        Location target = getReturnLocation(player, returnWorld);
        if (target == null) {
            return SpectateResult.TELEPORT_FAILED;
        }
        data.set(spectatorKey, PersistentDataType.BYTE, (byte) 1);
        releaseBypasses.add(uuid);
        boolean teleported;
        try {
            teleported = player.teleport(target);
        } finally {
            releaseBypasses.remove(uuid);
        }
        if (!teleported) {
            data.remove(spectatorKey);
            return SpectateResult.TELEPORT_FAILED;
        }
        // Drop the Limbo presentation but keep the marker, snapshot and activePlayers entry.
        cancelRejectionTask(uuid);
        BoundaryWrap wrap = boundaryWraps.remove(uuid);
        if (wrap != null) {
            clearBoundaryBlink(player);
        }
        nextAmbientSoundAt.remove(uuid);
        nextRandomBlinkAt.remove(uuid);
        aloneSince.remove(uuid);
        player.stopSound(PANGEA_AMBIENT_SOUND, SoundCategory.AMBIENT);
        removeVoiceEchoParticipant(uuid);
        if (isManagedLimboEffect(player.getPotionEffect(PotionEffectType.INVISIBILITY))) {
            player.removePotionEffect(PotionEffectType.INVISIBILITY);
        }
        if (isManagedLimboEffect(player.getPotionEffect(PotionEffectType.DARKNESS))) {
            player.removePotionEffect(PotionEffectType.DARKNESS);
        }
        LimboState snapshot = stateSnapshots.get(uuid);
        if (snapshot != null && snapshot.scoreboard() != null
                && player.getScoreboard() != snapshot.scoreboard()) {
            player.setScoreboard(snapshot.scoreboard());
        }
        player.setGravity(true);
        player.setGameMode(GameMode.SPECTATOR);
        return SpectateResult.SPECTATING;
    }

    LimboRoster roster() {
        LimboRoster current = roster;
        if (current == null) {
            synchronized (this) {
                current = roster;
                if (current == null) {
                    java.io.File folder = null;
                    try {
                        folder = plugin.getDataFolder();
                    } catch (RuntimeException ignored) {
                        // No data folder (unit tests): the roster then lives in memory only.
                    }
                    roster = current = new LimboRoster(folder, plugin.getLogger());
                }
            }
        }
        return current;
    }

    private void trackActive(Player player) {
        activePlayers.add(player.getUniqueId());
        roster().add(player.getUniqueId(), player.getName());
    }

    /** Every player in Limbo (online or not): name, whether they are online, queued release. */
    public List<RosterEntry> listLimboPlayers() {
        LimboRoster current = roster();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (isLimbo(player)) current.add(player.getUniqueId(), player.getName());
        }
        List<RosterEntry> entries = new ArrayList<>();
        for (Map.Entry<UUID, String> entry : current.snapshot().entrySet()) {
            Player online = Bukkit.getPlayer(entry.getKey());
            entries.add(new RosterEntry(entry.getKey(), entry.getValue(), online != null,
                    current.isQueued(entry.getKey())));
        }
        entries.sort(Comparator.comparing((RosterEntry entry) -> !entry.online())
                .thenComparing(entry -> entry.name().toLowerCase(Locale.ROOT)));
        return entries;
    }

    public record RosterEntry(UUID uuid, String name, boolean online, boolean queued) {}

    public List<String> rosterNamesStartingWith(String prefix) {
        return roster().namesStartingWith(prefix);
    }

    public enum OfflineReleaseResult { QUEUED, ALREADY_QUEUED, UNKNOWN }

    /** Queues a release that is carried out the moment an offline player logs in. */
    public OfflineReleaseResult queueOfflineRelease(String name) {
        LimboRoster current = roster();
        UUID uuid = current.findByName(name);
        if (uuid == null) return OfflineReleaseResult.UNKNOWN;
        if (current.isQueued(uuid)) return OfflineReleaseResult.ALREADY_QUEUED;
        current.queueRelease(uuid);
        return OfflineReleaseResult.QUEUED;
    }

    public String rosterName(String name) {
        LimboRoster current = roster();
        UUID uuid = current.findByName(name);
        return uuid == null ? name : current.nameOf(uuid);
    }

    public ReleaseResult release(Player player) {
        if (player == null || !isLimbo(player)) {
            return ReleaseResult.NOT_IN_LIMBO;
        }
        World returnWorld = Bukkit.getWorld(returnWorldName);
        if (returnWorld == null) {
            return ReleaseResult.RETURN_WORLD_MISSING;
        }
        if (returnWorld == limboWorld || LIMBO_WORLD_KEY.equals(returnWorld.getKey())) {
            return ReleaseResult.TELEPORT_FAILED;
        }
        Location target = getReturnLocation(player, returnWorld);
        if (target == null) {
            return ReleaseResult.TELEPORT_FAILED;
        }
        UUID uuid = player.getUniqueId();
        releaseBypasses.add(uuid);
        try {
            if (!player.teleport(target)) {
                return ReleaseResult.TELEPORT_FAILED;
            }
        } finally {
            releaseBypasses.remove(uuid);
        }
        clearState(player);
        smpReviveBridge.openCasinoIfNeeded(player);
        return ReleaseResult.RELEASED;
    }

    private void clearState(Player player) {
        clearState(player, true);
    }

    /**
     * SMPRevive keeps sending a player with this flag set to Limbo on every join. Once they
     * are out of Limbo (released, revived, rejected) the newcomer admission is over.
     */
    private void clearNewcomerFlag(Player player) {
        NamespacedKey newbieKey = new NamespacedKey("smprevive", "newbie_waiting");
        PersistentDataContainer data = player.getPersistentDataContainer();
        Byte flag = data.get(newbieKey, PersistentDataType.BYTE);
        if (flag != null && flag == (byte) 1 && !isLimboWorld(player.getWorld())) {
            data.set(newbieKey, PersistentDataType.BYTE, (byte) 0);
        }
    }

    private void clearState(Player player, boolean restoreGameMode) {
        boolean returning = isLimbo(player) && player.isOnline() && !isLimboWorld(player.getWorld());
        clearNewcomerFlag(player);
        player.stopSound(PANGEA_AMBIENT_SOUND, SoundCategory.AMBIENT);
        UUID uuid = player.getUniqueId();
        BoundaryWrap boundaryWrap = boundaryWraps.remove(uuid);
        if (boundaryWrap != null) {
            clearBoundaryBlink(player);
        }
        PersistentDataContainer data = player.getPersistentDataContainer();
        cancelRejectionTask(uuid);
        rejectionRetryAt.remove(uuid);
        nextAmbientSoundAt.remove(uuid);
        nextRandomBlinkAt.remove(uuid);
        pendingRevivalCleanup.remove(uuid);
        localStarCollapseStates.remove(uuid);
        localStarArrivalStates.remove(uuid);
        localStarsEnabledByPlayer.remove(uuid);
        headStarStates.remove(uuid);
        LimboState snapshot = stateSnapshots.get(uuid);
        PotionEffect oldInvisibility = snapshot == null ? null : snapshot.invisibility();
        PotionEffect oldDarkness = snapshot == null ? null : snapshot.darkness();

        player.removePotionEffect(PotionEffectType.INVISIBILITY);
        player.removePotionEffect(PotionEffectType.DARKNESS);
        if (oldInvisibility != null) {
            player.addPotionEffect(oldInvisibility, true);
        }
        if (oldDarkness != null) {
            player.addPotionEffect(oldDarkness, true);
        }

        player.setGravity(readByte(data, originalGravityKey, true));
        player.setAllowFlight(readByte(data, originalAllowFlightKey, false));
        if (player.getAllowFlight()) {
            player.setFlying(readByte(data, originalFlyingKey, false));
        } else {
            player.setFlying(false);
        }
        if (restoreGameMode) {
            player.setGameMode(readGameMode(data.get(originalGameModeKey, PersistentDataType.STRING)));
        }

        if (snapshot != null && snapshot.scoreboard() != null
                && player.getScoreboard() != snapshot.scoreboard()) {
            // Restore the complete previous board. Never call Team#removeEntry:
            // vanish/nametag plugins may own the client-side team state.
            player.setScoreboard(snapshot.scoreboard());
        }
        aloneSince.remove(uuid);
        nextDistortedEchoAt.remove(uuid);
        data.remove(activeKey);
        data.remove(limboOnlineTicksKey);
        data.remove(lastOnlineTickKey);
        data.remove(enteredAtTickKey);
        data.remove(legacyEnteredAtKey);
        data.remove(originalGameModeKey);
        data.remove(originalGravityKey);
        data.remove(originalAllowFlightKey);
        data.remove(originalFlyingKey);
        data.remove(returnLocationKey);
        data.remove(forcedKey);
        data.remove(spectatorKey);
        stateSnapshots.remove(uuid);
        activePlayers.remove(uuid);
        roster().remove(uuid);
        removeVoiceEchoParticipant(uuid);
        removeVoiceCaptureParticipant(uuid);
        if (returning) returnTrails.putIfAbsent(uuid,
                new ReturnTrail(System.nanoTime(), player.getLocation()));
    }

    private static final class ReturnTrail {
        private final long startedNanos;
        private Location previous;

        private ReturnTrail(long startedNanos, Location previous) {
            this.startedNanos = startedNanos;
            this.previous = previous;
        }
    }

    private void tickReturnTrails(long tick) {
        long now = System.nanoTime();
        var iterator = returnTrails.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            Player player = Bukkit.getPlayer(entry.getKey());
            ReturnTrail trail = entry.getValue();
            if (player == null || !player.isOnline() || player.isDead() || isLimbo(player)
                    || !LimboGeometry.trailActive(trail.startedNanos, now)) {
                iterator.remove();
                continue;
            }
            if (tick % 4L != 0L) continue;
            Location position = player.getLocation();
            if (position.getWorld() == trail.previous.getWorld()) {
                double moved = position.distanceSquared(trail.previous);
                if (moved > 0.0025 && moved < 16.0) {
                    position.getWorld().spawnParticle(Particle.DUST,
                            trail.previous.clone().add(0.0, 0.12, 0.0), 1,
                            0.06, 0.04, 0.06, 0.0, RETURN_DUST);
                }
            }
            trail.previous = position;
        }
    }

    private boolean readByte(PersistentDataContainer data, NamespacedKey key, boolean fallback) {
        Byte value = data.get(key, PersistentDataType.BYTE);
        return value == null ? fallback : value != 0;
    }

    private GameMode readGameMode(String value) {
        if (value == null) {
            return GameMode.SURVIVAL;
        }
        try {
            return GameMode.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return GameMode.SURVIVAL;
        }
    }

    public enum ReleaseResult {
        RELEASED,
        NOT_IN_LIMBO,
        RETURN_WORLD_MISSING,
        TELEPORT_FAILED
    }

    private void removeLimboCreatures(World world) {
        for (Entity entity : world.getEntities()) {
            if (!isAllowedLimboEntity(entity)) {
                entity.remove();
            }
        }
    }

    private record LimboState(PotionEffect invisibility, PotionEffect darkness, Scoreboard scoreboard,
                              GameMode gameMode, boolean gravity, boolean allowFlight, boolean flying) {
    }

    private record VoiceSnapshot(Set<UUID> echoParticipants, Set<UUID> captureParticipants) {
    }

    private record FloorParticle(double baseX, double baseZ, double height,
                                 double driftXAmplitude, double driftYAmplitude,
                                 double driftZAmplitude, double driftXSpeed,
                                 double driftYSpeed, double driftZSpeed,
                                 double phaseX, double phaseY, double phaseZ,
                                 double twinkleSpeed, double twinklePhase) {
    }

    private record GlobalStarParticle(int index, double baseX, double baseZ, double height,
                                      double driftXAmplitude, double driftYAmplitude,
                                      double driftZAmplitude, double driftXSpeed,
                                      double driftYSpeed, double driftZSpeed,
                                      double phaseX, double phaseY, double phaseZ) {
    }

    private record LocalStarCollapse(double x, double y, double z, long startTick) {
    }

    private record LocalStarArrival(List<ArrivalPoint> points, long startTick) {
    }

    private record ArrivalPoint(double x, double y, double z, double delay) {
    }

    private record SmokeParticle(double radius, double angle, double phase,
                                 double descentSpeed, double driftXAmplitude,
                                 double driftZAmplitude, double driftSpeed) {
    }

    private record LightPoint(int x, int y, int z, int level) {
    }

    private record BoundaryWrap(Location start, Location target, long startTick,
                                boolean reposition, boolean crossed) {
    }

    private static final class RejectionScene {
        private final UUID id;
        private final Location cloudLocation;
        private final double flightAngle;
        private final double flightRadius;
        private Chunk targetChunk;
        private ItemDisplay display;
        private BukkitTask task;
        private boolean cleaned;

        private RejectionScene(
                UUID id, Location cloudLocation, double flightAngle, double flightRadius) {
            this.id = id;
            this.cloudLocation = cloudLocation.clone();
            this.flightAngle = flightAngle;
            this.flightRadius = flightRadius;
        }
    }
}
