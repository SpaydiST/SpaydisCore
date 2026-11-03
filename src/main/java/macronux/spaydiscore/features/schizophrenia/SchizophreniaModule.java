package macronux.spaydiscore.features.schizophrenia;

import macronux.spaydiscore.SpaydisCorePlugin;
import macronux.spaydiscore.config.ConfigGuard;
import macronux.spaydiscore.features.allergy.NetheriteAllergyModule;
import macronux.spaydiscore.messages.MessageManager;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.ExplosionPrimeEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Persistent "schizophrenia" mode. Everything it shows is private to the affected player:
 * sounds are sent only to them, blocks are hidden with client-side block changes and the
 * phantom mobs are real entities that are invisible to everybody except that one player.
 */
public final class SchizophreniaModule implements Listener {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private static final long STEP_PERIOD = 2L;
    private static final long SECOND_TICKS = 20L;
    private static final long PHANTOM_LIFETIME_TICKS = 45L * SECOND_TICKS;

    private static final EntityType[] PHANTOM_TYPES = {
            EntityType.ZOMBIE, EntityType.HUSK, EntityType.SKELETON, EntityType.CREEPER, EntityType.SPIDER
    };
    private static final String[] AMBIENT_SOUNDS = {
            "minecraft:ambient.cave", "minecraft:entity.creeper.primed", "minecraft:entity.enderman.stare",
            "minecraft:entity.enderman.ambient", "minecraft:entity.zombie.ambient", "minecraft:entity.skeleton.ambient",
            "minecraft:entity.spider.ambient", "minecraft:block.wooden_door.open", "minecraft:block.wooden_door.close",
            "minecraft:block.chest.open", "minecraft:block.stone.step", "minecraft:block.gravel.step",
            "minecraft:entity.warden.heartbeat", "minecraft:entity.ghast.ambient", "minecraft:entity.witch.ambient",
            "minecraft:block.iron_door.open", "minecraft:entity.generic.explode", "minecraft:entity.phantom.ambient"
    };

    private final SpaydisCorePlugin plugin;
    private final MessageManager messages;
    private final Set<UUID> affected = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Schedule> schedules = new HashMap<>();
    private final List<Phantom> phantoms = new ArrayList<>();
    private final List<HiddenBlock> hiddenBlocks = new ArrayList<>();
    private File file;
    private boolean persistenceAvailable = true;
    private boolean enabled = true;
    private int soundMin = 25;
    private int soundMax = 90;
    private int phantomMin = 90;
    private int phantomMax = 240;
    private int blockMin = 45;
    private int blockMax = 150;
    private int hurtMin = 420;
    private int hurtMax = 780;
    private long clockSeconds;
    private long stepCounter;
    private BukkitTask task;

    private static final class Schedule {
        private long nextSound = -1;
        private long nextPhantom = -1;
        private long nextBlock = -1;
        private long nextHurt = -1;
    }

    private static final class Phantom {
        private final UUID entityId;
        private final UUID playerId;
        private final long expiresAtStep;
        private long vanishAtStep = -1;

        private Phantom(UUID entityId, UUID playerId, long expiresAtStep) {
            this.entityId = entityId;
            this.playerId = playerId;
            this.expiresAtStep = expiresAtStep;
        }
    }

    private record HiddenBlock(UUID playerId, Location location, long restoreAtStep) { }

    public SchizophreniaModule(SpaydisCorePlugin plugin, MessageManager messages) {
        this.plugin = plugin;
        this.messages = messages;
    }

    public void reload() {
        var config = plugin.getConfig();
        enabled = config.getBoolean("features.schizophrenia", true);
        soundMin = ConfigGuard.atLeastOne(config.getInt("schizophrenia.sound-min-seconds", 25), 25);
        soundMax = Math.max(soundMin, ConfigGuard.atLeastOne(config.getInt("schizophrenia.sound-max-seconds", 90), 90));
        phantomMin = ConfigGuard.atLeastOne(config.getInt("schizophrenia.phantom-min-seconds", 90), 90);
        phantomMax = Math.max(phantomMin, ConfigGuard.atLeastOne(config.getInt("schizophrenia.phantom-max-seconds", 240), 240));
        blockMin = ConfigGuard.atLeastOne(config.getInt("schizophrenia.block-min-seconds", 45), 45);
        blockMax = Math.max(blockMin, ConfigGuard.atLeastOne(config.getInt("schizophrenia.block-max-seconds", 150), 150));
        hurtMin = ConfigGuard.atLeastOne(config.getInt("schizophrenia.hurt-min-seconds", 420), 420);
        hurtMax = Math.max(hurtMin, ConfigGuard.atLeastOne(config.getInt("schizophrenia.hurt-max-seconds", 780), 780));
        schedules.clear();
        if (!enabled) {
            clearIllusions();
        }
    }

    public void load() {
        file = new File(plugin.getDataFolder(), "schizophrenia.yml");
        if (!file.exists()) return;
        YamlConfiguration config = new YamlConfiguration();
        try {
            config.load(file);
        } catch (IOException | InvalidConfigurationException exception) {
            persistenceAvailable = false;
            throw new IllegalStateException("Could not read schizophrenia.yml", exception);
        }
        for (String value : config.getStringList("affected")) {
            try {
                affected.add(UUID.fromString(value));
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    public void start() {
        if (task != null) return;
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::step, STEP_PERIOD, STEP_PERIOD);
    }

    public void shutdown() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        clearIllusions();
        schedules.clear();
        affected.clear();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isAffected(UUID id) {
        return id != null && affected.contains(id);
    }

    public Set<UUID> affectedPlayers() {
        return Set.copyOf(affected);
    }

    public boolean give(OfflinePlayer target) {
        boolean added = affected.add(target.getUniqueId());
        if (added) save();
        return added;
    }

    public boolean remove(OfflinePlayer target) {
        UUID id = target.getUniqueId();
        boolean removed = affected.remove(id);
        if (removed) {
            schedules.remove(id);
            clearIllusions(id);
            save();
        }
        return removed;
    }

    private void save() {
        if (!persistenceAvailable) return;
        if (file == null) file = new File(plugin.getDataFolder(), "schizophrenia.yml");
        YamlConfiguration config = new YamlConfiguration();
        List<String> ids = new ArrayList<>();
        for (UUID id : affected) ids.add(id.toString());
        config.set("affected", ids);
        try {
            NetheriteAllergyModule.writeAtomically(file.toPath(), config.saveToString());
        } catch (IOException exception) {
            plugin.getLogger().warning("Failed to save schizophrenia.yml: " + exception.getMessage());
        }
    }

    // ---- main loop -------------------------------------------------------------------

    private void step() {
        stepCounter++;
        if (stepCounter % (SECOND_TICKS / STEP_PERIOD) == 0) {
            clockSeconds++;
            if (enabled) {
                for (UUID id : affected) {
                    Player player = Bukkit.getPlayer(id);
                    if (player != null && player.isOnline() && canHaunt(player)) {
                        tickPlayer(player);
                    }
                }
            }
        }
        moveBlocksBack();
        tickPhantoms();
    }

    private boolean canHaunt(Player player) {
        return !player.isDead() && player.getGameMode() != GameMode.SPECTATOR
                && !plugin.isLimboWorld(player.getWorld());
    }

    private void tickPlayer(Player player) {
        Schedule schedule = schedules.computeIfAbsent(player.getUniqueId(), id -> new Schedule());
        if (schedule.nextSound < 0) {
            schedule.nextSound = clockSeconds + randomBetween(soundMin, soundMax);
            schedule.nextPhantom = clockSeconds + randomBetween(phantomMin, phantomMax);
            schedule.nextBlock = clockSeconds + randomBetween(blockMin, blockMax);
            schedule.nextHurt = clockSeconds + randomBetween(hurtMin, hurtMax);
        }
        if (clockSeconds >= schedule.nextSound) {
            schedule.nextSound = clockSeconds + randomBetween(soundMin, soundMax);
            playRandomSound(player);
        }
        if (clockSeconds >= schedule.nextPhantom) {
            schedule.nextPhantom = clockSeconds + randomBetween(phantomMin, phantomMax);
            spawnPhantomGroup(player);
        }
        if (clockSeconds >= schedule.nextHurt) {
            schedule.nextHurt = clockSeconds + randomBetween(hurtMin, hurtMax);
            fakeHurt(player);
        }
        if (clockSeconds >= schedule.nextBlock) {
            schedule.nextBlock = clockSeconds + randomBetween(blockMin, blockMax);
            hideNearbyBlocks(player);
        }
    }

    private int randomBetween(int min, int max) {
        return min >= max ? min : ThreadLocalRandom.current().nextInt(min, max + 1);
    }

    // ---- sounds ----------------------------------------------------------------------

    private void playRandomSound(Player player) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        double angle = random.nextDouble(Math.PI * 2);
        double distance = random.nextDouble(3.0, 10.0);
        Location source = player.getLocation().add(Math.cos(angle) * distance,
                random.nextDouble(-1.0, 2.0), Math.sin(angle) * distance);
        String sound = AMBIENT_SOUNDS[random.nextInt(AMBIENT_SOUNDS.length)];
        float volume = (float) random.nextDouble(0.4, 1.0);
        float pitch = (float) random.nextDouble(0.7, 1.3);
        player.playSound(source, sound, SoundCategory.AMBIENT, volume, pitch);
    }

    // ---- phantom mobs ----------------------------------------------------------------

    private void spawnPhantomGroup(Player player) {
        UUID playerId = player.getUniqueId();
        if (phantoms.stream().anyMatch(phantom -> phantom.playerId.equals(playerId))) return;

        ThreadLocalRandom random = ThreadLocalRandom.current();
        List<EntityType> pool = new ArrayList<>(List.of(PHANTOM_TYPES));
        if (plugin.isUndeadCursed(player)) {
            // Zombies are forbidden from targeting cursed players, so they could never approach.
            pool.removeAll(List.of(EntityType.ZOMBIE, EntityType.HUSK));
        }
        // Usually one apparition, sometimes a small mixed group of different mobs.
        int count = random.nextDouble() < 0.4 ? random.nextInt(2, 4) : 1;
        double baseAngle = random.nextDouble(Math.PI * 2);
        for (int index = 0; index < count && !pool.isEmpty(); index++) {
            EntityType type = pool.remove(random.nextInt(pool.size()));
            spawnPhantom(player, type, baseAngle + random.nextDouble(-0.5, 0.5));
        }
    }

    private void spawnPhantom(Player player, EntityType type, double angle) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        World world = player.getWorld();
        Location origin = player.getLocation();
        Location spawn = null;
        for (int attempt = 0; attempt < 8 && spawn == null; attempt++) {
            double distance = random.nextDouble(14.0, 20.0);
            double jitter = attempt == 0 ? 0.0 : random.nextDouble(-1.0, 1.0);
            double x = origin.getX() + Math.cos(angle + jitter) * distance;
            double z = origin.getZ() + Math.sin(angle + jitter) * distance;
            spawn = findGround(world, x, z, origin.getBlockY());
        }
        Class<? extends Entity> entityClass = type.getEntityClass();
        if (spawn == null || entityClass == null) return;

        Entity entity = world.spawn(spawn, entityClass, created -> {
            // Hidden before it is ever sent to anybody; only the haunted player is shown it below.
            created.setVisibleByDefault(false);
            created.setPersistent(false);
            created.setSilent(true);
            if (created instanceof Creeper creeper) {
                creeper.setExplosionRadius(0);
            }
            if (created instanceof Zombie zombie) {
                zombie.setCanBreakDoors(false);
                zombie.setBaby(false);
            }
            if (created instanceof LivingEntity living) {
                living.setCollidable(false);
                living.setCanPickupItems(false);
                living.setRemoveWhenFarAway(false);
                if (living.getEquipment() != null) {
                    // No bow for skeletons: they walk up and swing instead of shooting.
                    living.getEquipment().clear();
                }
                AttributeInstance followRange = living.getAttribute(Attribute.FOLLOW_RANGE);
                if (followRange != null) followRange.setBaseValue(64.0);
            }
        });
        if (!entity.isValid()) {
            entity.remove();
            return;
        }
        player.showEntity(plugin, entity);
        if (entity instanceof Mob mob) {
            mob.setTarget(player);
        }
        phantoms.add(new Phantom(entity.getUniqueId(), player.getUniqueId(),
                stepCounter + PHANTOM_LIFETIME_TICKS / STEP_PERIOD));
    }

    private Location findGround(World world, double x, double z, int nearY) {
        int blockX = (int) Math.floor(x);
        int blockZ = (int) Math.floor(z);
        if (!world.isChunkLoaded(blockX >> 4, blockZ >> 4)) return null;
        int minY = world.getMinHeight() + 1;
        int maxY = world.getMaxHeight() - 3;
        for (int y = Math.min(maxY, nearY + 4); y >= Math.max(minY, nearY - 8); y--) {
            Block floor = world.getBlockAt(blockX, y - 1, blockZ);
            if (floor.getType().isSolid() && world.getBlockAt(blockX, y, blockZ).isPassable()
                    && world.getBlockAt(blockX, y + 1, blockZ).isPassable()
                    && !world.getBlockAt(blockX, y, blockZ).isLiquid()) {
                return new Location(world, blockX + 0.5, y, blockZ + 0.5);
            }
        }
        return null;
    }

    private void tickPhantoms() {
        Iterator<Phantom> iterator = phantoms.iterator();
        while (iterator.hasNext()) {
            Phantom phantom = iterator.next();
            Entity entity = Bukkit.getEntity(phantom.entityId);
            Player player = Bukkit.getPlayer(phantom.playerId);
            if (entity == null || !entity.isValid() || player == null || !player.isOnline()
                    || player.getWorld() != entity.getWorld() || !affected.contains(phantom.playerId)
                    || stepCounter >= phantom.expiresAtStep) {
                if (entity != null) entity.remove();
                iterator.remove();
                continue;
            }
            if (phantom.vanishAtStep >= 0) {
                if (stepCounter >= phantom.vanishAtStep) {
                    vanish(player, entity);
                    iterator.remove();
                }
                continue;
            }
            if (entity instanceof Mob mob) {
                // Their one and only goal is the haunted player.
                if (!player.equals(mob.getTarget())) mob.setTarget(player);
            }
            // Backup in case the mob's own attack never lands (blocked, cooldown, odd AI).
            if (entity.getLocation().distanceSquared(player.getLocation()) <= 1.8 * 1.8) {
                attemptAttack(phantom, entity);
            }
        }
    }

    /** The mob reached the player: it swings once, then dissolves. Nothing is damaged. */
    private void attemptAttack(Phantom phantom, Entity entity) {
        if (phantom.vanishAtStep >= 0) return;
        phantom.vanishAtStep = stepCounter + 3;
        if (entity instanceof LivingEntity living) {
            living.swingMainHand();
        }
        if (entity instanceof Mob mob) {
            mob.setTarget(null);
        }
    }

    private void vanish(Player player, Entity entity) {
        player.spawnParticle(Particle.POOF, entity.getLocation().add(0, 1, 0), 12, 0.3, 0.5, 0.3, 0.02);
        entity.remove();
    }

    private Phantom phantomOf(Entity entity) {
        if (entity == null) return null;
        UUID id = entity.getUniqueId();
        for (Phantom phantom : phantoms) {
            if (phantom.entityId.equals(id)) return phantom;
        }
        return null;
    }

    /** A phantom cannot be hurt by anything; a hit from its victim just makes it vanish. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPhantomDamaged(EntityDamageEvent event) {
        Phantom phantom = phantomOf(event.getEntity());
        if (phantom == null) return;
        event.setCancelled(true);
        if (event instanceof EntityDamageByEntityEvent byEntity) {
            Entity damager = byEntity.getDamager();
            if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Entity shooter) {
                damager = shooter;
            }
            if (damager.getUniqueId().equals(phantom.playerId)) {
                Player player = Bukkit.getPlayer(phantom.playerId);
                if (player != null) vanish(player, event.getEntity());
                else event.getEntity().remove();
                phantoms.remove(phantom);
            }
        }
    }

    /** A phantom's attack hurts nobody; it counts as the "try to hit" moment. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPhantomAttack(EntityDamageByEntityEvent event) {
        Phantom phantom = phantomOf(event.getDamager());
        if (phantom == null) return;
        event.setCancelled(true);
        attemptAttack(phantom, event.getDamager());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPhantomExplode(ExplosionPrimeEvent event) {
        Phantom phantom = phantomOf(event.getEntity());
        if (phantom == null) return;
        event.setCancelled(true);
        attemptAttack(phantom, event.getEntity());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPhantomExplosion(EntityExplodeEvent event) {
        if (phantomOf(event.getEntity()) != null) {
            event.setCancelled(true);
            event.blockList().clear();
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPhantomShoot(ProjectileLaunchEvent event) {
        if (event.getEntity().getShooter() instanceof Entity shooter) {
            Phantom phantom = phantomOf(shooter);
            if (phantom != null) {
                event.setCancelled(true);
                attemptAttack(phantom, shooter);
            }
        }
    }

    /** Daylight must not ignite them. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPhantomCombust(EntityCombustEvent event) {
        if (phantomOf(event.getEntity()) != null) {
            event.setCancelled(true);
        }
    }

    /** Phantoms only ever target their player; nobody else ever targets a phantom. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onTarget(EntityTargetEvent event) {
        Phantom phantom = phantomOf(event.getEntity());
        if (phantom != null) {
            Entity target = event.getTarget();
            if (target != null && !target.getUniqueId().equals(phantom.playerId)) {
                event.setCancelled(true);
            }
            return;
        }
        if (event.getTarget() != null && phantomOf(event.getTarget()) != null) {
            event.setCancelled(true);
        }
    }

    // ---- fake hit ---------------------------------------------------------------------

    /** Screen shake and the hurt sound only: no damage, no knockback, no health change. */
    private void fakeHurt(Player player) {
        player.sendHurtAnimation(0.0F);
        player.playSound(player.getLocation(), "minecraft:entity.player.hurt",
                SoundCategory.PLAYERS, 1.0F, 1.0F);
    }

    // ---- disappearing blocks ---------------------------------------------------------

    private void hideNearbyBlocks(Player player) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        Location base = player.getLocation();
        World world = base.getWorld();
        int count = random.nextInt(1, 4);
        BlockData air = Material.AIR.createBlockData();
        for (int attempt = 0; attempt < 12 && count > 0; attempt++) {
            int dx = random.nextInt(-5, 6);
            int dz = random.nextInt(-5, 6);
            int dy = random.nextInt(-1, 3);
            if (Math.abs(dx) < 2 && Math.abs(dz) < 2 && dy < 0) {
                continue; // never the floor directly under their feet
            }
            int x = base.getBlockX() + dx;
            int y = base.getBlockY() + dy;
            int z = base.getBlockZ() + dz;
            if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
            Block block = world.getBlockAt(x, y, z);
            if (block.getType().isAir() || block.isLiquid() || block.getType() == Material.BEDROCK) continue;
            player.sendBlockChange(block.getLocation(), air);
            long restoreAt = stepCounter + randomBetween(2, 5) * (SECOND_TICKS / STEP_PERIOD);
            hiddenBlocks.add(new HiddenBlock(player.getUniqueId(), block.getLocation(), restoreAt));
            count--;
        }
    }

    private void moveBlocksBack() {
        if (hiddenBlocks.isEmpty()) return;
        Iterator<HiddenBlock> iterator = hiddenBlocks.iterator();
        while (iterator.hasNext()) {
            HiddenBlock hidden = iterator.next();
            if (stepCounter >= hidden.restoreAtStep) {
                restore(hidden);
                iterator.remove();
            }
        }
    }

    private void restore(HiddenBlock hidden) {
        Player player = Bukkit.getPlayer(hidden.playerId);
        Location location = hidden.location;
        if (player == null || !player.isOnline() || location.getWorld() == null
                || !location.getWorld().isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) {
            return;
        }
        player.sendBlockChange(location, location.getBlock().getBlockData());
    }

    // ---- cleanup ---------------------------------------------------------------------

    private void clearIllusions() {
        for (HiddenBlock hidden : hiddenBlocks) restore(hidden);
        hiddenBlocks.clear();
        for (Phantom phantom : phantoms) {
            Entity entity = Bukkit.getEntity(phantom.entityId);
            if (entity != null) entity.remove();
        }
        phantoms.clear();
    }

    private void clearIllusions(UUID playerId) {
        hiddenBlocks.removeIf(hidden -> {
            if (!hidden.playerId.equals(playerId)) return false;
            restore(hidden);
            return true;
        });
        phantoms.removeIf(phantom -> {
            if (!phantom.playerId.equals(playerId)) return false;
            Entity entity = Bukkit.getEntity(phantom.entityId);
            if (entity != null) entity.remove();
            return true;
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        schedules.remove(id);
        hiddenBlocks.removeIf(hidden -> hidden.playerId.equals(id));
        phantoms.removeIf(phantom -> {
            if (!phantom.playerId.equals(id)) return false;
            Entity entity = Bukkit.getEntity(phantom.entityId);
            if (entity != null) entity.remove();
            return true;
        });
    }

    /** Message helper for command feedback on the haunted player. */
    public void notify(Player player, String key) {
        player.sendMessage(LEGACY.deserialize(messages.get(player, key)));
    }
}
