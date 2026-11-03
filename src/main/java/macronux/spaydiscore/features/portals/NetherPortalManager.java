package macronux.spaydiscore.features.portals;

import macronux.spaydiscore.SpaydisCorePlugin;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.PortalType;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPortalEnterEvent;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.PortalCreateEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/** Controls Nether portal creation while preserving vanilla portal behavior in the enabled mode. */
public final class NetherPortalManager implements Listener {

    private static final String DISPLAY_TAG = "spaydiscore_nether_opening";
    private static final int MIN_OPENING_TICKS = 80;
    private static final int MAX_OPENING_TICKS = 200;
    private static final int MAX_PARTICLE_CELLS_PER_TICK = 64;
    private static final int DISABLED_ANIMATION_TICKS = 40;
    private static final int SCAN_Y_LAYERS_PER_TICK = 32;
    private static final double DISABLED_DEPTH_OFFSET = 0.02;
    private static final float STAR_MAX_SCALE = 0.85F;
    private static final float STAR_MIN_START_SCALE = 0.22F;
    private static final float STAR_GROW_ROTATION_SPEED = 0.75F;
    private static final float STAR_SHRINK_ROTATION_SPEED = 1.10F;
    private static final Particle.DustOptions OPENING_DUST =
            new Particle.DustOptions(Color.fromRGB(155, 35, 255), 1.1F);
    private static final Particle.DustOptions DISABLED_DUST =
            new Particle.DustOptions(Color.fromRGB(8, 8, 8), 1.2F);

    private final SpaydisCorePlugin plugin;
    private final Map<String, OpeningAnimation> openingAnimations = new HashMap<>();
    private final Map<String, DisabledPortalAnimation> disabledAnimations = new HashMap<>();
    private final ArrayDeque<Chunk> portalScanQueue = new ArrayDeque<>();
    private final Set<String> portalScanQueued = new HashSet<>();
    private PortalMode mode;
    private BukkitTask portalScanTask;
    private Chunk activePortalScanChunk;
    private int activePortalScanY;
    private int totalPortalScanRemoved;

    public NetherPortalManager(SpaydisCorePlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.mode = PortalMode.from(plugin.getConfig().getString("nether-portals.mode", "on"));
        removeStaleAnimationDisplays();
    }

    /** Starts maintenance after the plugin has registered this listener. */
    public void start() {
        if (mode == PortalMode.DISABLED) {
            scheduleLoadedPortalBreak();
        }
    }

    public PortalMode getMode() {
        return mode;
    }

    public void setMode(PortalMode newMode) {
        if (newMode == null) {
            return;
        }
        applyMode(newMode, true);
    }

    /** Re-reads only the persisted mode when /spaydiscore reload is used. */
    public void reloadMode() {
        PortalMode configured = PortalMode.from(
                plugin.getConfig().getString("nether-portals.mode", PortalMode.ENABLED.configValue()));
        if (configured != mode) {
            applyMode(configured, false);
        }
    }

    public void shutdown() {
        cancelPortalScan();
        stopAllOpenings(false);
        stopAllDisabledAnimations();
        removeStaleAnimationDisplays();
    }

    private void applyMode(PortalMode newMode, boolean persist) {
        PortalMode previous = mode;
        mode = newMode;

        if (persist) {
            plugin.getConfig().set("nether-portals.mode", newMode.configValue());
            plugin.saveConfig();
        }

        if (newMode == PortalMode.DISABLED) {
            stopAllOpenings(false);
            scheduleLoadedPortalBreak();
        } else {
            cancelPortalScan();
            stopAllDisabledAnimations();
            if (newMode == PortalMode.ENABLED) {
                // A portal cancelled for an opening animation must become real when an admin enables vanilla mode.
                for (OpeningAnimation animation : new ArrayList<>(openingAnimations.values())) {
                    if (animation.isLoaded()) {
                        animation.stop(true);
                    }
                }
            }
        }

        if (previous != newMode) {
            plugin.getLogger().info("Nether portal mode: " + newMode.configValue());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPortalCreate(PortalCreateEvent event) {
        if (plugin.isLimboWorld(event.getWorld())) {
            event.setCancelled(true);
            return;
        }
        if (mode == PortalMode.ENABLED || isTechnicalPortalPair(event)) {
            return;
        }

        List<PortalCell> cells = collectPortalCells(event.getBlocks());
        if (cells.isEmpty()) {
            return;
        }

        removePortalFire(cells);
        if (mode == PortalMode.DISABLED) {
            event.setCancelled(true);
            startDisabledAnimation(cells);
            return;
        }

        // The real portal is placed only after its particle animation is complete.
        event.setCancelled(true);
        startOpening(cells);
    }

    /** Display entities are visual effects and must never be transported by a portal. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityPortalEnter(EntityPortalEnterEvent event) {
        if (isDisplayEntity(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityPortal(EntityPortalEvent event) {
        if (isDisplayEntity(event.getEntity())
                || (event.getPortalType() == PortalType.NETHER
                && shouldBlockNetherTravel(event.getFrom()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerPortal(PlayerPortalEvent event) {
        if (event.getCause() == TeleportCause.NETHER_PORTAL
                && shouldBlockNetherTravel(event.getFrom())) {
            event.setCancelled(true);
        }
    }

    private boolean isDisplayEntity(Entity entity) {
        return entity instanceof ItemDisplay || entity instanceof BlockDisplay;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkLoad(ChunkLoadEvent event) {
        if (mode != PortalMode.DISABLED) {
            return;
        }

        Chunk chunk = event.getChunk();
        enqueuePortalScan(chunk);
        ensurePortalScanTask();
    }

    private boolean shouldBlockNetherTravel(Location location) {
        if (location == null || location.getWorld() == null) {
            return false;
        }
        if (location.getWorld().getEnvironment() == World.Environment.NETHER) {
            return false;
        }
        return mode == PortalMode.DISABLED
                || (mode == PortalMode.OPENING && isOpeningLocation(location));
    }

    private boolean isTechnicalPortalPair(PortalCreateEvent event) {
        return event.getReason() == PortalCreateEvent.CreateReason.NETHER_PAIR;
    }

    private boolean isOpeningLocation(Location location) {
        if (location == null) {
            return false;
        }
        for (OpeningAnimation animation : openingAnimations.values()) {
            if (animation.contains(location)) {
                return true;
            }
        }
        return false;
    }

    private List<PortalCell> collectPortalCells(List<BlockState> states) {
        if (states == null || states.isEmpty()) {
            return List.of();
        }

        List<PortalCell> cells = new ArrayList<>();
        for (BlockState state : states) {
            if (state == null || state.getType() != Material.NETHER_PORTAL) {
                continue;
            }

            Block block = state.getBlock();
            Location location = block.getLocation();
            BlockData data = state.getBlockData();
            if (data == null || data.getMaterial() != Material.NETHER_PORTAL) {
                data = Bukkit.createBlockData(Material.NETHER_PORTAL);
            }
            cells.add(new PortalCell(location.getWorld(), location.getBlockX(), location.getBlockY(),
                    location.getBlockZ(), data));
        }
        return cells;
    }

    private void removePortalFire(List<PortalCell> cells) {
        for (PortalCell cell : cells) {
            Block block = cell.world().getBlockAt(cell.x(), cell.y(), cell.z());
            if (block.getType() == Material.FIRE || block.getType() == Material.SOUL_FIRE) {
                block.setType(Material.AIR, false);
            }
        }
    }

    private void startOpening(List<PortalCell> cells) {
        String signature = signature(cells);
        if (openingAnimations.containsKey(signature)) {
            return;
        }

        OpeningAnimation animation = new OpeningAnimation(signature, cells);
        openingAnimations.put(signature, animation);
        try {
            animation.start();
        } catch (RuntimeException exception) {
            openingAnimations.remove(signature);
            plugin.getLogger().warning("Portal opening animation failed: " + exception.getMessage());
            animation.stop(true);
        }
    }

    private String signature(List<PortalCell> cells) {
        String coordinates = cells.stream()
                .map(cell -> cell.x() + ":" + cell.y() + ":" + cell.z())
                .sorted()
                .collect(Collectors.joining(";"));
        return cells.get(0).world().getUID() + ":" + coordinates;
    }

    private void stopAllOpenings(boolean materialize) {
        if (openingAnimations.isEmpty()) {
            return;
        }

        List<OpeningAnimation> animations = new ArrayList<>(openingAnimations.values());
        openingAnimations.clear();
        for (OpeningAnimation animation : animations) {
            animation.stop(materialize);
        }
    }

    private void startDisabledAnimation(List<PortalCell> cells) {
        String signature = signature(cells);
        if (disabledAnimations.containsKey(signature)) {
            return;
        }

        DisabledPortalAnimation animation = new DisabledPortalAnimation(signature, cells);
        disabledAnimations.put(signature, animation);
        try {
            animation.start();
        } catch (RuntimeException exception) {
            disabledAnimations.remove(signature);
            plugin.getLogger().warning("Disabled portal animation failed: " + exception.getMessage());
            animation.stop();
            spawnDisabledEffects(cells);
        }
    }

    private void stopAllDisabledAnimations() {
        if (disabledAnimations.isEmpty()) {
            return;
        }

        List<DisabledPortalAnimation> animations = new ArrayList<>(disabledAnimations.values());
        disabledAnimations.clear();
        for (DisabledPortalAnimation animation : animations) {
            animation.stop();
        }
    }

    private void scheduleLoadedPortalBreak() {
        cancelPortalScan();

        for (World world : Bukkit.getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                enqueuePortalScan(chunk);
            }
        }
        ensurePortalScanTask();
    }

    private void enqueuePortalScan(Chunk chunk) {
        if (chunk == null) return;
        String key = chunkScanKey(chunk);
        if (portalScanQueued.add(key)) {
            portalScanQueue.addLast(chunk);
        }
    }

    private String chunkScanKey(Chunk chunk) {
        return chunk.getWorld().getUID() + ":" + chunk.getX() + ":" + chunk.getZ();
    }

    private void ensurePortalScanTask() {
        if (portalScanTask != null || portalScanQueue.isEmpty()) {
            return;
        }

        totalPortalScanRemoved = 0;
        portalScanTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (mode != PortalMode.DISABLED) {
                    cancelPortalScan();
                    return;
                }

                if (activePortalScanChunk == null) {
                    activePortalScanChunk = portalScanQueue.pollFirst();
                    if (activePortalScanChunk == null) {
                        cancelPortalScan();
                        return;
                    }
                    activePortalScanY = activePortalScanChunk.getWorld().getMinHeight();
                }

                if (!activePortalScanChunk.isLoaded()) {
                    portalScanQueued.remove(chunkScanKey(activePortalScanChunk));
                    activePortalScanChunk = null;
                    return;
                }

                World world = activePortalScanChunk.getWorld();
                int scanEndY = Math.min(world.getMaxHeight(), activePortalScanY + SCAN_Y_LAYERS_PER_TICK);
                totalPortalScanRemoved += removePortalsFromChunk(
                        activePortalScanChunk, activePortalScanY, scanEndY);
                activePortalScanY = scanEndY;

                if (activePortalScanY >= world.getMaxHeight()) {
                    portalScanQueued.remove(chunkScanKey(activePortalScanChunk));
                    activePortalScanChunk = null;
                }

                if (activePortalScanChunk == null && portalScanQueue.isEmpty()) {
                    if (totalPortalScanRemoved > 0) {
                        plugin.getLogger().info("Removed " + totalPortalScanRemoved
                                + " loaded Nether portal blocks while portals are disabled.");
                    }
                    cancelPortalScan();
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    private void cancelPortalScan() {
        if (portalScanTask != null) {
            portalScanTask.cancel();
            portalScanTask = null;
        }
        portalScanQueue.clear();
        portalScanQueued.clear();
        activePortalScanChunk = null;
        totalPortalScanRemoved = 0;
    }

    private int removePortalsFromChunk(Chunk chunk, int minY, int maxY) {
        World world = chunk.getWorld();
        int removed = 0;
        for (int y = minY; y < maxY; y++) {
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    Block block = chunk.getBlock(x, y, z);
                    if (block.getType() != Material.NETHER_PORTAL) {
                        continue;
                    }

                    Location location = block.getLocation().add(0.5, 0.5, 0.5);
                    block.setType(Material.AIR, false);
                    spawnDisabledEffects(world, location);
                    removed++;
                }
            }
        }
        return removed;
    }

    private void spawnDisabledEffects(List<PortalCell> cells) {
        for (PortalCell cell : cells) {
            spawnDisabledEffects(cell.world(), cell.center());
        }
    }

    private void spawnDisabledEffects(World world, Location location) {
        world.spawnParticle(Particle.DUST, location, 10,
                DISABLED_DEPTH_OFFSET, 0.12, DISABLED_DEPTH_OFFSET, 0.0, DISABLED_DUST);
        world.spawnParticle(Particle.SQUID_INK, location, 6,
                DISABLED_DEPTH_OFFSET, 0.16, DISABLED_DEPTH_OFFSET, 0.0);
        world.spawnParticle(Particle.SMOKE, location, 3,
                DISABLED_DEPTH_OFFSET, 0.12, DISABLED_DEPTH_OFFSET, 0.0);
    }

    private void removeStaleAnimationDisplays() {
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (entity.getScoreboardTags().contains(DISPLAY_TAG)) {
                    entity.remove();
                }
            }
        }
    }

    private final class DisabledPortalAnimation {
        private final String signature;
        private final List<PortalCell> cells;
        private final Location center;
        private final PortalBounds bounds;
        private final boolean planeAlongX;
        private final int portalWidth;
        private final int portalHeight;
        private BukkitTask task;
        private int tick;

        private DisabledPortalAnimation(String signature, List<PortalCell> cells) {
            this.signature = signature;
            this.cells = List.copyOf(cells);
            this.center = calculateCenter(cells);
            this.bounds = calculateBounds(this.cells);
            this.planeAlongX = portalPlaneAlongX(bounds);
            this.portalWidth = portalWidth(bounds);
            this.portalHeight = portalHeight(bounds);
        }

        private Location calculateCenter(List<PortalCell> cells) {
            double x = 0.0;
            double y = 0.0;
            double z = 0.0;
            World world = cells.get(0).world();
            for (PortalCell cell : cells) {
                x += cell.x() + 0.5;
                y += cell.y() + 0.5;
                z += cell.z() + 0.5;
            }
            double count = cells.size();
            return new Location(world, x / count, y / count, z / count);
        }

        private void start() {
            center.getWorld().playSound(center, Sound.BLOCK_FIRE_EXTINGUISH,
                    SoundCategory.BLOCKS, 1.0F, 0.65F);
            center.getWorld().playSound(center, Sound.BLOCK_PORTAL_AMBIENT,
                    SoundCategory.BLOCKS, 0.7F, 0.45F);
            task = new BukkitRunnable() {
                @Override
                public void run() {
                    runTick();
                }
            }.runTaskTimer(plugin, 0L, 1L);
        }

        private void runTick() {
            if (mode != PortalMode.DISABLED || !isLoaded()) {
                stop();
                return;
            }

            spawnDisabledVortex();
            if (tick + 1 >= DISABLED_ANIMATION_TICKS) {
                finish();
                return;
            }
            tick++;
        }

        private boolean isLoaded() {
            for (PortalCell cell : cells) {
                if (!cell.world().isChunkLoaded(cell.x() >> 4, cell.z() >> 4)) {
                    return false;
                }
            }
            return true;
        }

        private void spawnDisabledVortex() {
            World world = center.getWorld();
            double progress = tick / (double) (DISABLED_ANIMATION_TICKS - 1);
            double pull = 0.08 + progress * 0.58;
            int stride = Math.max(1, (cells.size() + MAX_PARTICLE_CELLS_PER_TICK - 1)
                    / MAX_PARTICLE_CELLS_PER_TICK);
            int phase = tick % stride;
            int index = 0;

            for (PortalCell cell : cells) {
                if (index++ % stride != phase) {
                    continue;
                }
                double angle = tick * 0.42 + index * 0.65;
                Location point = portalPlanePoint(cell, pull, angle);
                spawnPlaneDust(point, 5, 0.10, 0.14);
                spawnPlaneParticle(Particle.SQUID_INK, point, 3, 0.10, 0.14, 0.0);
                if (tick % 2 == 0) {
                    spawnPlaneParticle(Particle.SMOKE, point, 1, 0.08, 0.12, 0.0);
                }
            }

            double widthRadius = Math.max(0.12, Math.min(0.42, portalWidth * 0.18));
            double heightRadius = Math.max(0.16, Math.min(0.55, portalHeight * 0.16));
            for (int i = 0; i < 12; i++) {
                double angle = tick * 0.56 + (Math.PI * 2.0 * i / 12.0);
                double width = Math.cos(angle) * widthRadius;
                double y = Math.sin(angle * 1.6 + i) * heightRadius;
                Location point = center.clone();
                if (planeAlongX) {
                    point.setX(center.getX() + width);
                    point.setZ(center.getZ() + Math.sin(angle) * DISABLED_DEPTH_OFFSET);
                } else {
                    point.setX(center.getX() + Math.sin(angle) * DISABLED_DEPTH_OFFSET);
                    point.setZ(center.getZ() + width);
                }
                point.setY(Math.max(bounds.minY() + 0.05,
                        Math.min(bounds.maxY() + 0.95, center.getY() + y)));
                spawnPlaneDust(point, 2, 0.04, 0.06);
                spawnPlaneParticle(Particle.SQUID_INK, point, 1, 0.03, 0.04, 0.0);
            }
        }

        private Location portalPlanePoint(PortalCell cell, double pull, double angle) {
            double x = cell.x() + 0.5;
            double y = cell.y() + 0.5;
            double z = cell.z() + 0.5;
            if (planeAlongX) {
                x += (center.getX() - x) * pull + Math.cos(angle) * 0.10;
                z = center.getZ();
            } else {
                x = center.getX();
                z += (center.getZ() - z) * pull + Math.cos(angle) * 0.10;
            }
            y += (center.getY() - y) * pull + Math.sin(angle) * 0.12;
            if (planeAlongX) {
                x = Math.max(bounds.minX() + 0.05, Math.min(bounds.maxX() + 0.95, x));
            } else {
                z = Math.max(bounds.minZ() + 0.05, Math.min(bounds.maxZ() + 0.95, z));
            }
            y = Math.max(bounds.minY() + 0.05, Math.min(bounds.maxY() + 0.95, y));
            return new Location(center.getWorld(), x, y, z);
        }

        private void finish() {
            World world = center.getWorld();
            spawnDisabledBurst();
            world.playSound(center, Sound.BLOCK_FIRE_EXTINGUISH,
                    SoundCategory.BLOCKS, 1.1F, 0.55F);
            stop();
        }

        private void spawnDisabledBurst() {
            double widthOffset = Math.min(0.35, Math.max(0.12, portalWidth * 0.12));
            double heightOffset = Math.min(0.50, Math.max(0.16, portalHeight * 0.12));
            spawnPlaneParticle(Particle.SQUID_INK, center, 28,
                    widthOffset, heightOffset, 0.0);
            spawnPlaneParticle(Particle.SMOKE, center, 20,
                    widthOffset * 0.8, heightOffset, 0.0);
        }

        private void spawnPlaneParticle(Particle particle, Location location, int count,
                                        double widthSpread, double heightSpread, double extra) {
            World world = center.getWorld();
            world.spawnParticle(particle, location, count,
                    planeAlongX ? widthSpread : 0.0,
                    heightSpread,
                    planeAlongX ? 0.0 : widthSpread,
                    extra);
        }

        private void spawnPlaneDust(Location location, int count,
                                    double widthSpread, double heightSpread) {
            World world = center.getWorld();
            world.spawnParticle(Particle.DUST, location, count,
                    planeAlongX ? widthSpread : 0.0,
                    heightSpread,
                    planeAlongX ? 0.0 : widthSpread,
                    0.0, DISABLED_DUST);
        }

        private void stop() {
            if (task != null) {
                task.cancel();
                task = null;
            }
            disabledAnimations.remove(signature, this);
        }
    }

    private record PortalBounds(int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
    }

    private PortalBounds calculateBounds(List<PortalCell> cells) {
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (PortalCell cell : cells) {
            minX = Math.min(minX, cell.x());
            maxX = Math.max(maxX, cell.x());
            minY = Math.min(minY, cell.y());
            maxY = Math.max(maxY, cell.y());
            minZ = Math.min(minZ, cell.z());
            maxZ = Math.max(maxZ, cell.z());
        }
        return new PortalBounds(minX, maxX, minY, maxY, minZ, maxZ);
    }

    private boolean portalPlaneAlongX(PortalBounds bounds) {
        return bounds.maxX() - bounds.minX() >= bounds.maxZ() - bounds.minZ();
    }

    private int portalWidth(PortalBounds bounds) {
        return (portalPlaneAlongX(bounds) ? bounds.maxX() - bounds.minX()
                : bounds.maxZ() - bounds.minZ()) + 1;
    }

    private int portalHeight(PortalBounds bounds) {
        return bounds.maxY() - bounds.minY() + 1;
    }

    private int portalMinorDimension(PortalBounds bounds) {
        return Math.min(portalWidth(bounds), portalHeight(bounds));
    }

    private record PortalCell(World world, int x, int y, int z, BlockData data) {
        private PortalCell {
            Objects.requireNonNull(world, "world");
            data = Objects.requireNonNull(data, "data").clone();
        }

        private Location center() {
            return new Location(world, x + 0.5, y + 0.5, z + 0.5);
        }
    }

    private enum AnimationVariant {
        PARTICLE_WAVE,
        NETHER_STAR_STRIKE,
        VOID_RIFT
    }

    public enum PortalMode {
        DISABLED("off", "disabled"),
        OPENING("opening", "opening"),
        ENABLED("on", "enabled");

        private final String commandValue;
        private final String configValue;

        PortalMode(String commandValue, String configValue) {
            this.commandValue = commandValue;
            this.configValue = configValue;
        }

        public String commandValue() {
            return commandValue;
        }

        public String configValue() {
            return configValue;
        }

        public static PortalMode from(String value) {
            if (value == null) {
                return ENABLED;
            }
            return switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "off", "disabled" -> DISABLED;
                case "opening", "open" -> OPENING;
                case "on", "enabled" -> ENABLED;
                default -> ENABLED;
            };
        }
    }

    private final class OpeningAnimation {
        private final String signature;
        private final List<PortalCell> cells;
        private final Location center;
        private final Location bottomCenter;
        private final PortalBounds bounds;
        private final float starMaxScale;
        private final float starStartScale;
        private final int minY;
        private final int maxY;
        private final int durationTicks;
        private final AnimationVariant variant;
        private ItemDisplay itemDisplay;
        private BukkitTask task;
        private int tick;

        private OpeningAnimation(String signature, List<PortalCell> cells) {
            this.signature = signature;
            this.cells = List.copyOf(cells);
            this.center = calculateCenter(cells);
            this.bounds = calculateBounds(this.cells);
            this.starMaxScale = calculateStarMaxScale();
            this.starStartScale = starMaxScale * 0.5F;
            this.minY = cells.stream().mapToInt(PortalCell::y).min().orElse(0);
            this.maxY = cells.stream().mapToInt(PortalCell::y).max().orElse(minY);
            this.durationTicks = openingDurationTicks(this.cells.size());
            AnimationVariant[] variants = AnimationVariant.values();
            this.variant = variants[ThreadLocalRandom.current().nextInt(variants.length)];
            this.bottomCenter = calculateBottomCenter();
        }

        private float calculateStarMaxScale() {
            return (float) Math.max(STAR_MIN_START_SCALE * 2.0F,
                    Math.min(STAR_MAX_SCALE, portalMinorDimension(bounds) * 0.24F));
        }

        private int openingDurationTicks(int cellCount) {
            int requested = (int) Math.min(MAX_OPENING_TICKS, (long) cellCount * 10L);
            return Math.max(MIN_OPENING_TICKS, requested);
        }

        private Location calculateCenter(List<PortalCell> cells) {
            double x = 0.0;
            double y = 0.0;
            double z = 0.0;
            World world = cells.get(0).world();
            for (PortalCell cell : cells) {
                x += cell.x() + 0.5;
                y += cell.y() + 0.5;
                z += cell.z() + 0.5;
            }
            double count = cells.size();
            return new Location(world, x / count, y / count, z / count);
        }

        private Location calculateBottomCenter() {
            double x = 0.0;
            double z = 0.0;
            int count = 0;
            for (PortalCell cell : cells) {
                if (cell.y() == minY) {
                    x += cell.x() + 0.5;
                    z += cell.z() + 0.5;
                    count++;
                }
            }
            if (count == 0) {
                return center.clone();
            }
            return new Location(center.getWorld(), x / count, minY + 0.5, z / count);
        }

        private void start() {
            World world = center.getWorld();
            world.playSound(center, Sound.BLOCK_PORTAL_TRIGGER, SoundCategory.BLOCKS, 2.0F, 0.65F);
            if (variant == AnimationVariant.NETHER_STAR_STRIKE) {
                spawnStarDisplay(world);
            }
            task = new BukkitRunnable() {
                @Override
                public void run() {
                    tick();
                }
            }.runTaskTimer(plugin, 0L, 1L);
        }

        private void tick() {
            if (mode == PortalMode.DISABLED) {
                stop(false);
                return;
            }
            if (!isLoaded()) {
                return;
            }
            if (mode == PortalMode.ENABLED) {
                stop(true);
                return;
            }

            if (tick % 20 == 0) {
                float progress = tick / (float) durationTicks;
                plugin.playSoundToNearby(center, "portals.charge", 0.3F + 0.7F * progress,
                        0.7F + 0.5F * progress);
            }
            switch (variant) {
                case PARTICLE_WAVE -> renderParticleWave();
                case NETHER_STAR_STRIKE -> renderStarStrike();
                case VOID_RIFT -> renderVoidRift();
            }

            if (tick + 1 >= durationTicks) {
                complete();
                return;
            }
            tick++;
        }

        private boolean isLoaded() {
            for (PortalCell cell : cells) {
                if (!cell.world().isChunkLoaded(cell.x() >> 4, cell.z() >> 4)) {
                    return false;
                }
            }
            return true;
        }

        private boolean contains(Location location) {
            if (location.getWorld() != center.getWorld()) {
                return false;
            }
            int x = location.getBlockX();
            int y = location.getBlockY();
            int z = location.getBlockZ();
            return cells.stream().anyMatch(cell -> cell.x() == x && cell.y() == y && cell.z() == z);
        }

        private void renderParticleWave() {
            int verticalHeight = maxY - minY + 1;
            int revealedHeight = (int) Math.ceil((tick + 1) * verticalHeight / (double) durationTicks);
            int highestRevealedY = minY + revealedHeight - 1;
            spawnDensePortalParticles(highestRevealedY, 24, 9);
            if (tick % 12 == 0) {
                center.getWorld().playSound(center, Sound.BLOCK_PORTAL_AMBIENT,
                        SoundCategory.BLOCKS, 0.9F, 1.2F);
            }
        }

        private void renderStarStrike() {
            animateStarDisplay();
        }

        /** End-style expanding rift, rotated into the Nether portal's vertical plane. */
        private void renderVoidRift() {
            double progress = smoothStep((tick + 1.0) / durationTicks);
            double halfWidth = portalWidth(bounds) * 0.46 * progress;
            double halfHeight = portalHeight(bounds) * 0.46 * progress;
            boolean alongX = portalPlaneAlongX(bounds);
            for (int index = 0; index < 28; index++) {
                double angle = tick * 0.15 + Math.PI * 2.0 * index / 28.0;
                double horizontal = Math.cos(angle) * halfWidth;
                Location point = center.clone().add(alongX ? horizontal : 0.04,
                        Math.sin(angle) * halfHeight, alongX ? 0.04 : horizontal);
                center.getWorld().spawnParticle(Particle.PORTAL, point, 2,
                        0.025, 0.025, 0.025, 0.08);
                center.getWorld().spawnParticle(Particle.DUST, point, 1,
                        0.02, 0.02, 0.02, 0.0, OPENING_DUST);
            }
            if (tick % 3 == 0) {
                spawnDensePortalParticles(maxY, 2 + (int) (progress * 8),
                        1 + (int) (progress * 3));
            }
        }

        private void spawnDensePortalParticles(int highestY, int portalCount, int dustCount) {
            int eligible = 0;
            for (PortalCell cell : cells) {
                if (cell.y() <= highestY) {
                    eligible++;
                }
            }
            if (eligible == 0) {
                return;
            }

            int stride = Math.max(1, (eligible + MAX_PARTICLE_CELLS_PER_TICK - 1)
                    / MAX_PARTICLE_CELLS_PER_TICK);
            int seen = 0;
            for (PortalCell cell : cells) {
                if (cell.y() > highestY || seen++ % stride != 0) {
                    continue;
                }
                Location location = cell.center();
                cell.world().spawnParticle(Particle.PORTAL, location, portalCount,
                        0.42, 0.48, 0.42, 0.18);
                cell.world().spawnParticle(Particle.DUST, location, dustCount,
                        0.30, 0.42, 0.30, 0.0, OPENING_DUST);
            }
        }

        private void spawnStarDisplay(World world) {
            itemDisplay = world.spawn(bottomCenter.clone().add(0.0, -0.45, 0.0), ItemDisplay.class, display -> {
                display.setItemStack(new ItemStack(Material.NETHER_STAR));
                display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
                display.setBillboard(Display.Billboard.CENTER);
                display.setViewRange(32.0F);
                display.setInterpolationDuration(2);
                display.setTeleportDuration(1);
                display.setGlowing(true);
                display.setGlowColorOverride(Color.fromRGB(155, 35, 255));
                display.setTransformation(starTransformation(starStartScale, 0.0F));
                display.addScoreboardTag(DISPLAY_TAG);
            });
        }

        private void animateStarDisplay() {
            int launchTicks = Math.max(20, durationTicks * 2 / 5);
            int growTicks = Math.max(16, durationTicks * 3 / 8);
            int shrinkTicks = Math.max(4, durationTicks - launchTicks - growTicks);
            int growStart = launchTicks;
            int shrinkStart = launchTicks + growTicks;
            Location starLocation;
            Location trailLocation = null;
            float rotation;
            float scale;

            if (tick < growStart) {
                double progress = smoothStep(tick / (double) launchTicks);
                starLocation = lerp(bottomCenter, center, progress);
                trailLocation = lerp(bottomCenter, center, Math.max(0.0, progress - 0.16));
                scale = (float) (starStartScale
                        + (starMaxScale - starStartScale) * 0.35 * progress);
                rotation = tick * 0.45F;
            } else if (tick < shrinkStart) {
                double progress = smoothStep((tick - growStart) / (double) growTicks);
                starLocation = center.clone();
                scale = (float) (starStartScale * 1.35F
                        + (starMaxScale - starStartScale * 1.35F) * progress);
                rotation = launchTicks * 0.45F
                        + (tick - growStart) * STAR_GROW_ROTATION_SPEED;
            } else {
                double progress = smoothStep((tick - shrinkStart) / (double) shrinkTicks);
                starLocation = center.clone();
                scale = Math.max(0.04F, starMaxScale * (float) (1.0 - progress));
                rotation = launchTicks * 0.45F
                        + growTicks * STAR_GROW_ROTATION_SPEED
                        + (tick - shrinkStart) * STAR_SHRINK_ROTATION_SPEED;
            }

            if (itemDisplay != null && itemDisplay.isValid()) {
                itemDisplay.teleport(starLocation);
                itemDisplay.setTransformation(starTransformation(scale, rotation));
            }
            World world = center.getWorld();
            if (trailLocation != null) {
                world.spawnParticle(Particle.ENCHANT, trailLocation, 5,
                        0.10, 0.10, 0.10, 0.02);
                world.spawnParticle(Particle.ENCHANT, starLocation, 2,
                        0.06, 0.06, 0.06, 0.01);
            } else {
                world.spawnParticle(Particle.ENCHANT, starLocation, 6,
                        0.20, 0.20, 0.20, 0.02);
            }
        }

        private Transformation starTransformation(float scale, float rotation) {
            float boundedScale = Math.max(0.04F, Math.min(starMaxScale, scale));
            return new Transformation(
                    new Vector3f(),
                    new AxisAngle4f(rotation, 0.0F, 1.0F, 0.0F),
                    new Vector3f(boundedScale, boundedScale, boundedScale),
                    new AxisAngle4f(rotation * 0.7F, 1.0F, 0.0F, 1.0F));
        }

        private double smoothStep(double value) {
            double t = Math.max(0.0, Math.min(1.0, value));
            return t * t * (3.0 - 2.0 * t);
        }

        private Location lerp(Location from, Location to, double progress) {
            return new Location(from.getWorld(),
                    from.getX() + (to.getX() - from.getX()) * progress,
                    from.getY() + (to.getY() - from.getY()) * progress,
                    from.getZ() + (to.getZ() - from.getZ()) * progress);
        }

        private void complete() {
            if (!materializeBlocksOnly()) {
                stop(false);
                return;
            }

            World world = center.getWorld();
            if (variant == AnimationVariant.NETHER_STAR_STRIKE) {
                world.playSound(center, Sound.ENTITY_LIGHTNING_BOLT_THUNDER,
                        SoundCategory.BLOCKS, 1.4F, 1.1F);
                world.strikeLightningEffect(center);
            }
            spawnPortalExplosion();
            stop(false);
        }

        private void spawnPortalExplosion() {
            plugin.playSoundToNearby(center, "portals.open");
            World world = center.getWorld();
            world.spawnParticle(Particle.EXPLOSION_EMITTER, center, 1);
            world.spawnParticle(Particle.PORTAL, center, 180, 1.1, 1.3, 1.1, 0.75);
            world.spawnParticle(Particle.REVERSE_PORTAL, center, 100, 1.0, 1.2, 1.0, 0.55);
            world.spawnParticle(Particle.DUST, center, 120, 1.1, 1.3, 1.1, 0.4, OPENING_DUST);
            world.playSound(center, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.BLOCKS, 1.6F, 1.15F);
        }

        private boolean isPortalFrameIntact() {
            if (!isLoaded()) {
                return false;
            }

            for (PortalCell cell : cells) {
                Material type = cell.world().getBlockAt(cell.x(), cell.y(), cell.z()).getType();
                if (!type.isAir() && type != Material.NETHER_PORTAL) {
                    return false;
                }
            }

            World world = center.getWorld();
            if (portalPlaneAlongX(bounds)) {
                int z = bounds.minZ();
                for (int y = bounds.minY(); y <= bounds.maxY(); y++) {
                    if (!isObsidian(world, bounds.minX() - 1, y, z)
                            || !isObsidian(world, bounds.maxX() + 1, y, z)) {
                        return false;
                    }
                }
                for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
                    if (!isObsidian(world, x, bounds.minY() - 1, z)
                            || !isObsidian(world, x, bounds.maxY() + 1, z)) {
                        return false;
                    }
                }
            } else {
                int x = bounds.minX();
                for (int y = bounds.minY(); y <= bounds.maxY(); y++) {
                    if (!isObsidian(world, x, y, bounds.minZ() - 1)
                            || !isObsidian(world, x, y, bounds.maxZ() + 1)) {
                        return false;
                    }
                }
                for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                    if (!isObsidian(world, x, bounds.minY() - 1, z)
                            || !isObsidian(world, x, bounds.maxY() + 1, z)) {
                        return false;
                    }
                }
            }
            return true;
        }

        private boolean isObsidian(World world, int x, int y, int z) {
            return world.isChunkLoaded(x >> 4, z >> 4)
                    && world.getBlockAt(x, y, z).getType() == Material.OBSIDIAN;
        }

        private boolean materializeBlocksOnly() {
            if (!isPortalFrameIntact()) {
                return false;
            }

            for (PortalCell cell : cells) {
                Block block = cell.world().getBlockAt(cell.x(), cell.y(), cell.z());
                BlockData data = cell.data().getMaterial() == Material.NETHER_PORTAL
                        ? cell.data().clone()
                        : Bukkit.createBlockData(Material.NETHER_PORTAL);
                block.setBlockData(data, false);
            }
            return true;
        }

        private void stop(boolean materialize) {
            if (task != null) {
                task.cancel();
                task = null;
            }
            if (materialize) {
                materializeBlocksOnly();
            }
            if (itemDisplay != null && itemDisplay.isValid()) {
                itemDisplay.remove();
            }
            itemDisplay = null;
            openingAnimations.remove(signature, this);
        }
    }
}
