package macronux.spaydiscore.features.portals;

import macronux.spaydiscore.SpaydisCorePlugin;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.PortalType;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.EndPortalFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.PortalCreateEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/** Controls End portals without touching End gateways. */
public final class EndPortalManager implements Listener {

    private static final int END_PORTAL_SIZE = 3;
    private static final int FRAME_RADIUS = 2;
    private static final int MIN_OPENING_TICKS = 100;
    private static final int MAX_OPENING_TICKS = 160;
    private static final int DISABLED_ANIMATION_TICKS = 40;
    private static final int SCAN_Y_LAYERS_PER_TICK = 32;
    private static final double PORTAL_PLANE_OFFSET = 0.04;
    private static final Particle.DustOptions VOID_DUST =
            new Particle.DustOptions(Color.fromRGB(155, 35, 255), 1.15F);
    private static final Particle.DustOptions DARK_DUST =
            new Particle.DustOptions(Color.fromRGB(10, 5, 18), 1.1F);

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

    public EndPortalManager(SpaydisCorePlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.mode = PortalMode.from(plugin.getConfig().getString("end-portals.mode", "on"));
    }

    public void start() {
        if (mode == PortalMode.DISABLED) {
            scheduleLoadedPortalBreak();
        }
    }

    public PortalMode getMode() {
        return mode;
    }

    public void setMode(PortalMode newMode) {
        if (newMode != null) {
            applyMode(newMode, true);
        }
    }

    public void reloadMode() {
        PortalMode configured = PortalMode.from(
                plugin.getConfig().getString("end-portals.mode", PortalMode.ENABLED.configValue()));
        if (configured != mode) {
            applyMode(configured, false);
        }
    }

    public void shutdown() {
        cancelPortalScan();
        stopAllOpenings(true);
        stopAllDisabledAnimations();
    }

    private void applyMode(PortalMode newMode, boolean persist) {
        PortalMode previous = mode;
        mode = newMode;

        if (persist) {
            plugin.getConfig().set("end-portals.mode", newMode.configValue());
            plugin.saveConfig();
        }

        if (newMode == PortalMode.DISABLED) {
            stopAllOpenings(false);
            scheduleLoadedPortalBreak();
        } else {
            cancelPortalScan();
            stopAllDisabledAnimations();
            if (newMode == PortalMode.ENABLED) {
                stopAllOpenings(true);
            }
        }

        if (previous != newMode) {
            plugin.getLogger().info("End portal mode: " + newMode.configValue());
        }
    }

    /** Handles plugin/API-created End portal blocks. Manual frame activation is handled below. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPortalCreate(PortalCreateEvent event) {
        if (plugin.isLimboWorld(event.getWorld())) {
            event.setCancelled(true);
            return;
        }
        List<PortalCell> cells = collectPortalCells(event.getBlocks());
        if (cells.isEmpty() || mode == PortalMode.ENABLED) {
            return;
        }

        event.setCancelled(true);
        EndPortalLayout layout = layoutForPortalCells(cells);
        if (layout == null) {
            removePortalCells(cells);
            removePortalCellsNextTick(cells);
            return;
        }

        if (mode == PortalMode.DISABLED) {
            removePortalCells(layout.cells());
            removePortalCellsNextTick(layout.cells());
            startDisabledAnimation(layout.cells(), true);
        } else {
            removePortalCells(layout.cells());
            if (startOpening(layout)) {
                removePortalCellsNextTick(layout.cells());
            }
        }
    }

    /** EnderEyeItem activates the frame directly, so it does not reliably emit PortalCreateEvent. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFrameInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK
                || event.getClickedBlock() == null
                || event.getItem() == null
                || event.getItem().getType() != Material.ENDER_EYE) {
            return;
        }

        Block clicked = event.getClickedBlock();
        if (plugin.isLimboWorld(clicked.getWorld())) {
            event.setCancelled(true);
            return;
        }
        if (mode == PortalMode.ENABLED) {
            return;
        }
        if (!(clicked.getBlockData() instanceof EndPortalFrame frame) || frame.hasEye()) {
            return;
        }

        EndPortalLayout layout = findActivationLayout(clicked);
        if (layout == null) {
            return;
        }

        denyFrameActivation(event);
        handleFrameActivation(event.getPlayer(), event.getHand(), layout);
    }

    /** EnderEyeItem writes the last eye through EntityChangeBlockEvent before it writes the portal blocks. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEnderEyeFrameChange(EntityChangeBlockEvent event) {
        if (!(event.getEntity() instanceof Player player)
                || event.getBlock() == null
                || event.getBlock().getType() != Material.END_PORTAL_FRAME
                || event.getTo() != Material.END_PORTAL_FRAME
                || !(event.getBlockData() instanceof EndPortalFrame frame)
                || !frame.hasEye()) {
            return;
        }

        if (plugin.isLimboWorld(event.getBlock().getWorld())) {
            event.setCancelled(true);
            return;
        }
        if (mode == PortalMode.ENABLED) {
            return;
        }

        // Paper calls this event immediately before EnderEyeItem writes the frame and the portal.
        // Cancel before any strict layout check, otherwise vanilla can still create the portal.
        event.setCancelled(true);
        if (event.getBlock().getBlockData() instanceof EndPortalFrame current && current.hasEye()) {
            return;
        }

        EndPortalLayout layout = findActivationLayout(event.getBlock());
        if (layout == null) {
            setFrameEye(new FramePosition(event.getBlock().getWorld(), event.getBlock().getX(),
                    event.getBlock().getY(), event.getBlock().getZ()), true);
            consumeEye(player, findEyeHand(player));
            return;
        }

        handleFrameActivation(player, findEyeHand(player), layout);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityPortal(EntityPortalEvent event) {
        if (event.getPortalType() == PortalType.ENDER
                && shouldBlockEndTravel(event.getFrom())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerPortal(PlayerPortalEvent event) {
        if (event.getCause() == TeleportCause.END_PORTAL
                && shouldBlockEndTravel(event.getFrom())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkLoad(ChunkLoadEvent event) {
        if (mode == PortalMode.DISABLED) {
            enqueuePortalScan(event.getChunk());
            ensurePortalScanTask();
        }
    }

    private boolean shouldBlockEndTravel(Location location) {
        if (location == null || location.getWorld() == null
                || location.getWorld().getEnvironment() == World.Environment.THE_END) {
            return false;
        }
        return mode == PortalMode.DISABLED
                || (mode == PortalMode.OPENING && isOpeningLocation(location));
    }

    private boolean isOpeningLocation(Location location) {
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
            if (state == null || state.getType() != Material.END_PORTAL) {
                continue;
            }
            Block block = state.getBlock();
            Location location = block.getLocation();
            BlockData data = state.getBlockData();
            if (data == null || data.getMaterial() != Material.END_PORTAL) {
                data = Bukkit.createBlockData(Material.END_PORTAL);
            }
            cells.add(new PortalCell(location.getWorld(), location.getBlockX(), location.getBlockY(),
                    location.getBlockZ(), data));
        }
        return cells;
    }

    private EndPortalLayout findActivationLayout(Block clicked) {
        World world = clicked.getWorld();
        int clickedX = clicked.getX();
        int clickedY = clicked.getY();
        int clickedZ = clicked.getZ();

        for (int centerX = clickedX - FRAME_RADIUS; centerX <= clickedX + FRAME_RADIUS; centerX++) {
            for (int centerZ = clickedZ - FRAME_RADIUS; centerZ <= clickedZ + FRAME_RADIUS; centerZ++) {
                int dx = clickedX - centerX;
                int dz = clickedZ - centerZ;
                if (!isFrameSlot(dx, dz)
                        || !isCompleteFrame(world, centerX, clickedY, centerZ, clickedX, clickedY, clickedZ)) {
                    continue;
                }

                List<PortalCell> cells = createInteriorCells(world, centerX, clickedY, centerZ);
                return new EndPortalLayout(cells,
                        new FramePosition(world, clickedX, clickedY, clickedZ));
            }
        }
        return null;
    }

    private boolean isCompleteFrame(World world, int centerX, int y, int centerZ,
                                    int clickedX, int clickedY, int clickedZ) {
        for (int dx = -FRAME_RADIUS; dx <= FRAME_RADIUS; dx++) {
            for (int dz = -FRAME_RADIUS; dz <= FRAME_RADIUS; dz++) {
                Block block = world.getBlockAt(centerX + dx, y, centerZ + dz);
                if (isFrameSlot(dx, dz)) {
                    if (block.getType() != Material.END_PORTAL_FRAME
                            || !(block.getBlockData() instanceof EndPortalFrame frame)) {
                        return false;
                    }
                    boolean clickedFrame = block.getX() == clickedX
                            && block.getY() == clickedY && block.getZ() == clickedZ;
                    if (clickedFrame ? frame.hasEye() : !frame.hasEye()) {
                        return false;
                    }
                }
            }
        }
        return hasVanillaFrameOrientation(world, centerX, y, centerZ);
    }

    private BlockFace expectedFacing(int dx, int dz) {
        if (dx == -FRAME_RADIUS) {
            return BlockFace.WEST;
        }
        if (dx == FRAME_RADIUS) {
            return BlockFace.EAST;
        }
        if (dz == -FRAME_RADIUS) {
            return BlockFace.NORTH;
        }
        return BlockFace.SOUTH;
    }

    private boolean hasVanillaFrameOrientation(World world, int centerX, int y, int centerZ) {
        Boolean outward = null;
        for (int dx = -FRAME_RADIUS; dx <= FRAME_RADIUS; dx++) {
            for (int dz = -FRAME_RADIUS; dz <= FRAME_RADIUS; dz++) {
                if (!isFrameSlot(dx, dz)) {
                    continue;
                }
                Block block = world.getBlockAt(centerX + dx, y, centerZ + dz);
                if (!(block.getBlockData() instanceof EndPortalFrame frame)) {
                    return false;
                }
                BlockFace expected = expectedFacing(dx, dz);
                BlockFace actual = frame.getFacing();
                if (actual != expected && actual != expected.getOppositeFace()) {
                    return false;
                }
                boolean pointsOutward = actual == expected;
                if (outward == null) {
                    outward = pointsOutward;
                } else if (outward != pointsOutward) {
                    return false;
                }
            }
        }
        return true;
    }

    private List<PortalCell> createInteriorCells(World world, int centerX, int y, int centerZ) {
        BlockData data = Bukkit.createBlockData(Material.END_PORTAL);
        List<PortalCell> cells = new ArrayList<>(END_PORTAL_SIZE * END_PORTAL_SIZE);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                cells.add(new PortalCell(world, centerX + dx, y, centerZ + dz, data));
            }
        }
        return cells;
    }

    private EndPortalLayout layoutForPortalCells(List<PortalCell> cells) {
        if (cells.size() != END_PORTAL_SIZE * END_PORTAL_SIZE) {
            return null;
        }
        PortalBounds bounds = calculateBounds(cells);
        if (bounds.maxX() - bounds.minX() != END_PORTAL_SIZE - 1
                || bounds.maxZ() - bounds.minZ() != END_PORTAL_SIZE - 1
                || bounds.minY() != bounds.maxY()) {
            return null;
        }

        Set<String> coordinates = cells.stream()
                .map(cell -> cell.x() + ":" + cell.y() + ":" + cell.z())
                .collect(Collectors.toSet());
        for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
            for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                if (!coordinates.contains(x + ":" + bounds.minY() + ":" + z)) {
                    return null;
                }
            }
        }
        return new EndPortalLayout(List.copyOf(cells), null);
    }

    private EndPortalLayout findExistingPortalLayout(Block portalBlock) {
        World world = portalBlock.getWorld();
        for (int centerX = portalBlock.getX() - 1; centerX <= portalBlock.getX() + 1; centerX++) {
            for (int centerZ = portalBlock.getZ() - 1; centerZ <= portalBlock.getZ() + 1; centerZ++) {
                List<PortalCell> cells = createInteriorCells(world, centerX, portalBlock.getY(), centerZ);
                if (!arePortalCellsLoaded(cells)
                        || cells.stream().anyMatch(cell -> world.getBlockAt(cell.x(), cell.y(), cell.z()).getType()
                        != Material.END_PORTAL)) {
                    continue;
                }
                return new EndPortalLayout(cells, null);
            }
        }
        return null;
    }

    private boolean arePortalCellsLoaded(List<PortalCell> cells) {
        return cells.stream().allMatch(cell ->
                cell.world().isChunkLoaded(cell.x() >> 4, cell.z() >> 4));
    }

    private boolean startOpening(EndPortalLayout layout) {
        String signature = signature(layout.cells());
        if (openingAnimations.containsKey(signature)) {
            return false;
        }

        OpeningAnimation animation = new OpeningAnimation(signature, layout);
        openingAnimations.put(signature, animation);
        try {
            animation.start();
            return true;
        } catch (RuntimeException exception) {
            openingAnimations.remove(signature);
            plugin.getLogger().warning("End portal opening animation failed: " + exception.getMessage());
            animation.stop(false);
            return false;
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
        for (OpeningAnimation animation : animations) {
            animation.stop(materialize);
        }
    }

    private void startDisabledAnimation(List<PortalCell> cells, boolean ejectEyesOnFinish) {
        String signature = signature(cells);
        if (disabledAnimations.containsKey(signature)) {
            return;
        }
        DisabledPortalAnimation animation = new DisabledPortalAnimation(signature, cells, ejectEyesOnFinish);
        disabledAnimations.put(signature, animation);
        try {
            animation.start();
        } catch (RuntimeException exception) {
            disabledAnimations.remove(signature);
            plugin.getLogger().warning("Disabled End portal animation failed: " + exception.getMessage());
            animation.stop();
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
        if (chunk != null && portalScanQueued.add(chunkScanKey(chunk))) {
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
                                + " loaded End portal blocks while portals are disabled.");
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
                    if (block.getType() != Material.END_PORTAL) {
                        continue;
                    }
                    EndPortalLayout layout = findExistingPortalLayout(block);
                    if (layout != null) {
                        removed += removePortalCells(layout.cells());
                        startDisabledAnimation(layout.cells(), true);
                    } else {
                        Location location = block.getLocation().add(0.5, 0.5, 0.5);
                        block.setType(Material.AIR, false);
                        spawnDisabledEffects(world, location);
                        removed++;
                    }
                }
            }
        }
        return removed;
    }

    private void denyFrameActivation(PlayerInteractEvent event) {
        event.setCancelled(true);
        event.setUseInteractedBlock(Event.Result.DENY);
        event.setUseItemInHand(Event.Result.DENY);
    }

    private void handleFrameActivation(Player player, EquipmentSlot hand, EndPortalLayout layout) {
        String signature = signature(layout.cells());
        if (mode == PortalMode.DISABLED) {
            if (disabledAnimations.containsKey(signature)) {
                return;
            }
            setFrameEye(layout.activatedFrame(), true);
            syncFrameRing(layout.cells());
            consumeEye(player, hand);
            removePortalCells(layout.cells());
            removePortalCellsNextTick(layout.cells());
            startDisabledAnimation(layout.cells(), true);
            return;
        }

        if (openingAnimations.containsKey(signature) || !startOpening(layout)) {
            return;
        }
        setFrameEye(layout.activatedFrame(), true);
        syncFrameRing(layout.cells());
        consumeEye(player, hand);
        removePortalCells(layout.cells());
        removePortalCellsNextTick(layout.cells());
    }

    private EquipmentSlot findEyeHand(Player player) {
        if (player.getInventory().getItemInMainHand().getType() == Material.ENDER_EYE) {
            return EquipmentSlot.HAND;
        }
        return EquipmentSlot.OFF_HAND;
    }

    private int removePortalCells(List<PortalCell> cells) {
        int removed = 0;
        for (PortalCell cell : cells) {
            Block block = cell.world().getBlockAt(cell.x(), cell.y(), cell.z());
            if (block.getType() == Material.END_PORTAL) {
                block.setType(Material.AIR, false);
                removed++;
            }
        }
        return removed;
    }

    private void removePortalCellsNextTick(List<PortalCell> cells) {
        List<PortalCell> snapshot = List.copyOf(cells);
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (mode != PortalMode.ENABLED) {
                removePortalCells(snapshot);
            }
        });
    }

    private boolean consumeEye(org.bukkit.entity.Player player, EquipmentSlot hand) {
        if (player.getGameMode() == GameMode.CREATIVE) {
            return false;
        }
        if (hand == null) {
            hand = findEyeHand(player);
        }
        ItemStack item = hand == EquipmentSlot.OFF_HAND
                ? player.getInventory().getItemInOffHand()
                : player.getInventory().getItemInMainHand();
        if (item == null || item.getType() != Material.ENDER_EYE || item.getAmount() <= 0) {
            return false;
        }
        ItemStack remaining = item.clone();
        if (remaining.getAmount() == 1) {
            remaining = null;
        } else {
            remaining.setAmount(remaining.getAmount() - 1);
        }
        if (hand == EquipmentSlot.OFF_HAND) {
            player.getInventory().setItemInOffHand(remaining);
        } else {
            player.getInventory().setItemInMainHand(remaining);
        }
        // The interaction event is cancelled before vanilla writes the hand back.
        // Explicitly update the slot and client so the consumed eye cannot reappear.
        player.updateInventory();
        return true;
    }

    private void setFrameEye(FramePosition position, boolean hasEye) {
        if (position == null) {
            return;
        }
        Block block = position.world().getBlockAt(position.x(), position.y(), position.z());
        if (!(block.getBlockData() instanceof EndPortalFrame frame)) {
            return;
        }
        if (frame.hasEye() != hasEye) {
            EndPortalFrame updated = (EndPortalFrame) frame.clone();
            updated.setEye(hasEye);
            block.setBlockData(updated, false);
        }
        syncBlockState(block);
    }

    private void syncFrameRing(World world, int centerX, int y, int centerZ) {
        for (int dx = -FRAME_RADIUS; dx <= FRAME_RADIUS; dx++) {
            for (int dz = -FRAME_RADIUS; dz <= FRAME_RADIUS; dz++) {
                if (isFrameSlot(dx, dz)) {
                    syncBlockState(world.getBlockAt(centerX + dx, y, centerZ + dz));
                }
            }
        }
    }

    private void syncFrameRing(List<PortalCell> cells) {
        if (cells.isEmpty()) {
            return;
        }
        PortalBounds bounds = calculateBounds(cells);
        syncFrameRing(cells.get(0).world(), bounds.minX() + 1, bounds.minY(), bounds.minZ() + 1);
    }

    private void syncBlockState(Block block) {
        if (block == null || !block.getWorld().isChunkLoaded(block.getX() >> 4, block.getZ() >> 4)) {
            return;
        }
        block.getState().update(true, false);
        Location location = block.getLocation();
        BlockData data = block.getBlockData();
        for (Player player : block.getWorld().getPlayers()) {
            player.sendBlockChange(location, data);
        }
    }

    private void ejectFrameEyes(EndPortalLayout layout) {
        ejectFrameEyes(layout.cells());
    }

    private void ejectFrameEyes(List<PortalCell> cells) {
        PortalBounds bounds = calculateBounds(cells);
        if (bounds.maxX() - bounds.minX() != END_PORTAL_SIZE - 1
                || bounds.maxZ() - bounds.minZ() != END_PORTAL_SIZE - 1
                || bounds.minY() != bounds.maxY()) {
            return;
        }

        World world = cells.get(0).world();
        int centerX = bounds.minX() + 1;
        int centerZ = bounds.minZ() + 1;
        for (int dx = -FRAME_RADIUS; dx <= FRAME_RADIUS; dx++) {
            for (int dz = -FRAME_RADIUS; dz <= FRAME_RADIUS; dz++) {
                if (!isFrameSlot(dx, dz)) {
                    continue;
                }
                Block block = world.getBlockAt(centerX + dx, bounds.minY(), centerZ + dz);
                if (!(block.getBlockData() instanceof EndPortalFrame frame) || !frame.hasEye()) {
                    continue;
                }
                EndPortalFrame empty = (EndPortalFrame) frame.clone();
                empty.setEye(false);
                block.setBlockData(empty, false);
                syncBlockState(block);
                world.dropItemNaturally(block.getLocation().add(0.5, 0.5, 0.5),
                        new ItemStack(Material.ENDER_EYE));
            }
        }
        syncFrameRing(cells);
    }

    private boolean isFrameSlot(int dx, int dz) {
        return (Math.abs(dx) == FRAME_RADIUS && Math.abs(dz) <= 1)
                || (Math.abs(dz) == FRAME_RADIUS && Math.abs(dx) <= 1);
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

    private enum EndAnimationVariant {
        VOID_RIFT,
        END_CORE,
        DRAGON_EYE
    }

    private record PortalBounds(int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
    }

    private record FramePosition(World world, int x, int y, int z) {
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

    private record EndPortalLayout(List<PortalCell> cells, FramePosition activatedFrame) {
        private EndPortalLayout {
            cells = List.copyOf(cells);
        }
    }

    private final class DisabledPortalAnimation {
        private final String signature;
        private final List<PortalCell> cells;
        private final Location center;
        private final PortalBounds bounds;
        private final boolean ejectEyesOnFinish;
        private BukkitTask task;
        private int tick;

        private DisabledPortalAnimation(String signature, List<PortalCell> cells, boolean ejectEyesOnFinish) {
            this.signature = signature;
            this.cells = List.copyOf(cells);
            this.center = calculateCenter(cells);
            this.bounds = calculateBounds(cells);
            this.ejectEyesOnFinish = ejectEyesOnFinish;
        }

        private void start() {
            center.getWorld().playSound(center, Sound.BLOCK_END_PORTAL_FRAME_FILL,
                    SoundCategory.BLOCKS, 0.8F, 0.45F);
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
            if (++tick >= DISABLED_ANIMATION_TICKS) {
                finish();
            }
        }

        private boolean isLoaded() {
            return cells.stream().allMatch(cell ->
                    cell.world().isChunkLoaded(cell.x() >> 4, cell.z() >> 4));
        }

        private void spawnDisabledVortex() {
            World world = center.getWorld();
            double angleOffset = tick * 0.22;
            for (int i = 0; i < cells.size(); i++) {
                PortalCell cell = cells.get(i);
                Location point = cell.center().add(0.0, PORTAL_PLANE_OFFSET, 0.0);
                world.spawnParticle(Particle.SQUID_INK, point, 3,
                        0.12, 0.025, 0.12, 0.0);
                world.spawnParticle(Particle.DUST, point, 2,
                        0.10, 0.025, 0.10, 0.0, DARK_DUST);
                if ((tick + i) % 3 == 0) {
                    world.spawnParticle(Particle.REVERSE_PORTAL, point, 2,
                            0.08, 0.02, 0.08, 0.04);
                }
            }

            double radiusX = Math.min(1.35, Math.max(0.25, (bounds.maxX() - bounds.minX() + 1) * 0.48));
            double radiusZ = Math.min(1.35, Math.max(0.25, (bounds.maxZ() - bounds.minZ() + 1) * 0.48));
            for (int i = 0; i < 16; i++) {
                double angle = angleOffset + Math.PI * 2.0 * i / 16.0;
                Location point = center.clone().add(
                        Math.cos(angle) * radiusX,
                        PORTAL_PLANE_OFFSET,
                        Math.sin(angle) * radiusZ);
                world.spawnParticle(Particle.DUST, point, 1,
                        0.025, 0.02, 0.025, 0.0, DARK_DUST);
            }
        }

        private void finish() {
            if (ejectEyesOnFinish) {
                ejectFrameEyes(cells);
            }
            stop();
        }

        private void stop() {
            if (task != null) {
                task.cancel();
                task = null;
            }
            disabledAnimations.remove(signature, this);
        }
    }

    private final class OpeningAnimation {
        private final String signature;
        private final EndPortalLayout layout;
        private final List<PortalCell> cells;
        private final Location center;
        private final PortalBounds bounds;
        private final int durationTicks;
        private final EndAnimationVariant variant;
        private BukkitTask task;
        private int tick;

        private OpeningAnimation(String signature, EndPortalLayout layout) {
            this.signature = signature;
            this.layout = layout;
            this.cells = layout.cells();
            this.center = calculateCenter(cells);
            this.bounds = calculateBounds(cells);
            this.durationTicks = Math.max(MIN_OPENING_TICKS,
                    Math.min(MAX_OPENING_TICKS, cells.size() * 12));
            EndAnimationVariant[] variants = EndAnimationVariant.values();
            this.variant = variants[ThreadLocalRandom.current().nextInt(variants.length)];
        }

        private void start() {
            center.getWorld().playSound(center, Sound.BLOCK_END_PORTAL_FRAME_FILL,
                    SoundCategory.BLOCKS, 1.2F, 0.55F);
            task = new BukkitRunnable() {
                @Override
                public void run() {
                    runTick();
                }
            }.runTaskTimer(plugin, 0L, 1L);
        }

        private void runTick() {
            if (mode == PortalMode.DISABLED) {
                stop(false);
                return;
            }
            if (mode == PortalMode.ENABLED) {
                stop(true);
                return;
            }
            if (!isLoaded()) {
                return;
            }

            if (tick % 20 == 0) {
                float progress = tick / (float) durationTicks;
                plugin.playSoundToNearby(center, "portals.charge", 0.3F + 0.7F * progress,
                        0.7F + 0.5F * progress);
            }
            switch (variant) {
                case VOID_RIFT -> renderVoidRift();
                case END_CORE -> renderEndCore();
                case DRAGON_EYE -> renderDragonEye();
            }
            if (++tick >= durationTicks) {
                complete();
            }
        }

        private boolean isLoaded() {
            return cells.stream().allMatch(cell ->
                    cell.world().isChunkLoaded(cell.x() >> 4, cell.z() >> 4));
        }

        private boolean contains(Location location) {
            if (location == null || location.getWorld() != center.getWorld()) {
                return false;
            }
            return cells.stream().anyMatch(cell -> cell.x() == location.getBlockX()
                    && cell.y() == location.getBlockY() && cell.z() == location.getBlockZ());
        }

        private void renderVoidRift() {
            World world = center.getWorld();
            double progress = smoothStep(tick / (double) durationTicks);
            double maxRadius = Math.min(1.42, Math.max(0.30,
                    Math.max(bounds.maxX() - bounds.minX() + 1,
                            bounds.maxZ() - bounds.minZ() + 1) * 0.48));
            double radius = 0.08 + maxRadius * progress;

            for (int i = 0; i < 28; i++) {
                double angle = tick * 0.15 + Math.PI * 2.0 * i / 28.0;
                Location point = center.clone().add(
                        Math.cos(angle) * radius,
                        PORTAL_PLANE_OFFSET,
                        Math.sin(angle) * radius);
                world.spawnParticle(Particle.PORTAL, point, 2,
                        0.035, 0.025, 0.035, 0.08);
                world.spawnParticle(Particle.DUST, point, 1,
                        0.02, 0.02, 0.02, 0.0, VOID_DUST);
            }
            for (PortalCell cell : cells) {
                double distance = Math.hypot(cell.x() + 0.5 - center.getX(),
                        cell.z() + 0.5 - center.getZ());
                if (distance <= radius + 0.45) {
                    world.spawnParticle(Particle.PORTAL, cell.center(), 10,
                            0.34, 0.025, 0.34, 0.12);
                    world.spawnParticle(Particle.REVERSE_PORTAL, cell.center(), 4,
                            0.25, 0.02, 0.25, 0.08);
                }
            }
            if (tick % 15 == 0) {
                world.playSound(center, Sound.BLOCK_PORTAL_AMBIENT,
                        SoundCategory.BLOCKS, 0.7F, 0.65F + (float) progress * 0.4F);
            }
        }

        private void renderEndCore() {
            World world = center.getWorld();
            double progress = smoothStep((tick + 1.0) / durationTicks);
            double gather = smoothStep(Math.min(1.0, progress / 0.82));
            int centerX = bounds.minX() + 1;
            int centerZ = bounds.minZ() + 1;
            int pathSamples = 7;

            for (int dx = -FRAME_RADIUS; dx <= FRAME_RADIUS; dx++) {
                for (int dz = -FRAME_RADIUS; dz <= FRAME_RADIUS; dz++) {
                    if (!isFrameSlot(dx, dz)) {
                        continue;
                    }

                    Location frame = new Location(world, centerX + dx + 0.5,
                            center.getY() + PORTAL_PLANE_OFFSET, centerZ + dz + 0.5);
                    for (int sample = 0; sample < pathSamples; sample++) {
                        double pathProgress = gather * sample / (pathSamples - 1.0);
                        Location point = frame.clone().add(
                                (center.getX() - frame.getX()) * pathProgress,
                                0.0,
                                (center.getZ() - frame.getZ()) * pathProgress);
                        world.spawnParticle(Particle.SQUID_INK, point, 1,
                                0.05, 0.025, 0.05, 0.0);
                        world.spawnParticle(Particle.DUST, point, 2,
                                0.04, 0.02, 0.04, 0.0, DARK_DUST);
                    }
                }
            }

            double fillProgress = smoothStep(Math.max(0.0,
                    Math.min(1.0, (gather - 0.12) / 0.88)));
            for (int cellX = -1; cellX <= 1; cellX++) {
                for (int cellZ = -1; cellZ <= 1; cellZ++) {
                    double distanceFromCenter = Math.max(Math.abs(cellX), Math.abs(cellZ));
                    double centerDelay = distanceFromCenter == 0.0 ? 0.58 : 0.0;
                    double cellReveal = smoothStep(Math.max(0.0,
                            Math.min(1.0, (fillProgress - centerDelay) / (1.0 - centerDelay))));
                    if (cellReveal <= 0.0) {
                        continue;
                    }

                    int sourceCellX;
                    int sourceCellZ;
                    if (cellX == 0 && cellZ == 0) {
                        sourceCellX = 0;
                        sourceCellZ = -FRAME_RADIUS;
                    } else if (Math.abs(cellX) >= Math.abs(cellZ)) {
                        sourceCellX = cellX * FRAME_RADIUS;
                        sourceCellZ = cellZ;
                    } else {
                        sourceCellX = cellX;
                        sourceCellZ = cellZ * FRAME_RADIUS;
                    }

                    Location sourceCell = new Location(world,
                            centerX + sourceCellX + 0.5,
                            center.getY() + PORTAL_PLANE_OFFSET,
                            centerZ + sourceCellZ + 0.5);
                    Location targetCell = center.clone().add(cellX,
                            PORTAL_PLANE_OFFSET, cellZ);
                    double cellSpread = 0.025 + 0.38 * cellReveal;
                    int cellPathSamples = 5;
                    for (int sample = 0; sample < cellPathSamples; sample++) {
                        double cellPathProgress = cellReveal * sample / (cellPathSamples - 1.0);
                        Location point = sourceCell.clone().add(
                                (targetCell.getX() - sourceCell.getX()) * cellPathProgress,
                                0.0,
                                (targetCell.getZ() - sourceCell.getZ()) * cellPathProgress);
                        double pathSpread = 0.018 + 0.10 * cellReveal;
                        world.spawnParticle(Particle.SQUID_INK, point, 1,
                                pathSpread, 0.015, pathSpread, 0.0);
                        world.spawnParticle(Particle.DUST, point, 2,
                                pathSpread, 0.012, pathSpread, 0.0, DARK_DUST);
                    }

                    Location cellFront = sourceCell.clone().add(
                            (targetCell.getX() - sourceCell.getX()) * cellReveal,
                            0.0,
                            (targetCell.getZ() - sourceCell.getZ()) * cellReveal);
                    int particleCount = 2 + (int) Math.round(cellReveal * 10.0);
                    world.spawnParticle(Particle.SQUID_INK, cellFront, particleCount,
                            cellSpread, 0.03, cellSpread, 0.0);
                    world.spawnParticle(Particle.DUST, cellFront, particleCount + 4,
                            cellSpread, 0.03, cellSpread, 0.0, DARK_DUST);
                }
            }
            if (tick % 18 == 0) {
                world.playSound(center, Sound.ENTITY_ENDERMAN_TELEPORT,
                        SoundCategory.BLOCKS, 0.65F, 0.75F + (float) gather * 0.5F);
            }
        }

        private void renderDragonEye() {
            World world = center.getWorld();
            double progress = smoothStep(tick / (double) durationTicks);
            double collapse = smoothStep(Math.max(0.0, (progress - 0.54) / 0.46));
            double radius = Math.max(0.12, 1.38 - collapse * 1.08);
            double rotation = tick * 0.19;

            for (int i = 0; i < 32; i++) {
                double angle = rotation + Math.PI * 2.0 * i / 32.0;
                double wobble = Math.sin(tick * 0.16 + i) * 0.045;
                Location point = center.clone().add(
                        Math.cos(angle) * radius,
                        PORTAL_PLANE_OFFSET + wobble,
                        Math.sin(angle) * radius);
                // Keep a dense vanilla portal layer underneath the dragon effect.
                // DRAGON_BREATH can be nearly invisible with reduced client particles.
                world.spawnParticle(Particle.PORTAL, point, 5,
                        0.04, 0.025, 0.04, 0.08);
                world.spawnParticle(Particle.DUST, point, 2,
                        0.035, 0.02, 0.035, 0.0, VOID_DUST);
                world.spawnParticle(Particle.DRAGON_BREATH, point, 2,
                        0.035, 0.025, 0.035, 0.02, 1.0F);
                world.spawnParticle(Particle.REVERSE_PORTAL, point, 2,
                        0.025, 0.02, 0.025, 0.04);
            }
            world.spawnParticle(Particle.END_ROD, center.clone().add(0.0, PORTAL_PLANE_OFFSET, 0.0),
                    progress > 0.82 ? 12 : 4, 0.18, 0.025, 0.18, 0.01);
            if (tick % 20 == 0) {
                world.playSound(center, Sound.ENTITY_ENDER_DRAGON_GROWL,
                        SoundCategory.BLOCKS, 0.75F, 1.35F);
            }
        }

        private boolean isFrameIntact() {
            if (!isLoaded()) {
                return false;
            }
            PortalBounds portalBounds = bounds;
            if (portalBounds.maxX() - portalBounds.minX() != END_PORTAL_SIZE - 1
                    || portalBounds.maxZ() - portalBounds.minZ() != END_PORTAL_SIZE - 1
                    || portalBounds.minY() != portalBounds.maxY()) {
                return false;
            }

            World world = center.getWorld();
            int centerX = portalBounds.minX() + 1;
            int centerZ = portalBounds.minZ() + 1;
            for (int dx = -FRAME_RADIUS; dx <= FRAME_RADIUS; dx++) {
                for (int dz = -FRAME_RADIUS; dz <= FRAME_RADIUS; dz++) {
                    if (!isFrameSlot(dx, dz)) {
                        continue;
                    }
                    Block block = world.getBlockAt(centerX + dx, portalBounds.minY(), centerZ + dz);
                    if (block.getType() != Material.END_PORTAL_FRAME
                            || !(block.getBlockData() instanceof EndPortalFrame frame)) {
                        return false;
                    }
                    boolean activated = layout.activatedFrame() != null
                            && layout.activatedFrame().x() == block.getX()
                            && layout.activatedFrame().y() == block.getY()
                            && layout.activatedFrame().z() == block.getZ();
                    if (!activated && !frame.hasEye()) {
                        return false;
                    }
                }
            }
            if (!hasVanillaFrameOrientation(world, centerX, portalBounds.minY(), centerZ)) {
                return false;
            }
            // Vanilla accepts any blocks in the 3x3 interior and replaces them with portal blocks.
            return true;
        }

        private boolean materializeBlocksOnly() {
            if (!isFrameIntact()) {
                return false;
            }

            if (layout.activatedFrame() != null) {
                FramePosition position = layout.activatedFrame();
                Block block = position.world().getBlockAt(position.x(), position.y(), position.z());
                if (block.getBlockData() instanceof EndPortalFrame frame && !frame.hasEye()) {
                    EndPortalFrame updated = (EndPortalFrame) frame.clone();
                    updated.setEye(true);
                    block.setBlockData(updated, false);
                }
            }
            syncFrameRing(cells);

            BlockData portalData = Bukkit.createBlockData(Material.END_PORTAL);
            for (PortalCell cell : cells) {
                Block block = cell.world().getBlockAt(cell.x(), cell.y(), cell.z());
                if (!block.getType().isAir() && block.getType() != Material.END_PORTAL) {
                    block.breakNaturally();
                }
                block.setBlockData(portalData, false);
            }
            return true;
        }

        private void complete() {
            if (!materializeBlocksOnly()) {
                stop(false);
                return;
            }
            spawnPortalExplosion();
            stop(false);
        }

        private void spawnPortalExplosion() {
            plugin.playSoundToNearby(center, "portals.open");
            World world = center.getWorld();
            Location point = center.clone().add(0.0, PORTAL_PLANE_OFFSET, 0.0);
            world.spawnParticle(Particle.EXPLOSION_EMITTER, point, 1);
            world.spawnParticle(Particle.PORTAL, point, 100, 1.15, 0.08, 1.15, 0.65);
            world.spawnParticle(Particle.REVERSE_PORTAL, point, 80, 1.10, 0.08, 1.10, 0.55);
            world.spawnParticle(Particle.DUST, point, 80, 1.12, 0.08, 1.12, 0.25, VOID_DUST);
            world.spawnParticle(Particle.END_ROD, point, 28, 0.95, 0.06, 0.95, 0.08);
            world.playSound(point, Sound.BLOCK_END_PORTAL_SPAWN,
                    SoundCategory.BLOCKS, 1.8F, 0.95F);
            world.playSound(point, Sound.ENTITY_ENDER_DRAGON_GROWL,
                    SoundCategory.BLOCKS, 1.0F, 1.15F);
        }

        private void stop(boolean materialize) {
            if (task != null) {
                task.cancel();
                task = null;
            }
            if (materialize) {
                if (!materializeBlocksOnly()) {
                    if (mode == PortalMode.ENABLED && task == null && plugin.isEnabled()) {
                        task = new BukkitRunnable() {
                            @Override
                            public void run() {
                                if (mode != PortalMode.ENABLED) {
                                    cancel();
                                    task = null;
                                    openingAnimations.remove(signature, OpeningAnimation.this);
                                } else if (materializeBlocksOnly()) {
                                    cancel();
                                    task = null;
                                    openingAnimations.remove(signature, OpeningAnimation.this);
                                }
                            }
                        }.runTaskTimer(plugin, 20L, 20L);
                    }
                    return;
                }
            }
            openingAnimations.remove(signature, this);
        }
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

    private void spawnDisabledEffects(World world, Location location) {
        world.spawnParticle(Particle.SQUID_INK, location, 6,
                0.12, 0.025, 0.12, 0.0);
        world.spawnParticle(Particle.DUST, location, 4,
                0.10, 0.02, 0.10, 0.0, DARK_DUST);
    }

    private double smoothStep(double value) {
        double t = Math.max(0.0, Math.min(1.0, value));
        return t * t * (3.0 - 2.0 * t);
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
}
