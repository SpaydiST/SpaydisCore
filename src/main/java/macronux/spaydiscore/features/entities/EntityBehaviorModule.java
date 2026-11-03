package macronux.spaydiscore.features.entities;

import macronux.spaydiscore.SpaydisCorePlugin;
import macronux.spaydiscore.config.ConfigGuard;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/** Owns name-tag-driven mob behavior, color cycles, and their repeating scans. */
public final class EntityBehaviorModule implements Listener {
    private static final DyeColor[] JEB_COLORS = {
            DyeColor.PINK, DyeColor.PURPLE, DyeColor.BLUE, DyeColor.CYAN,
            DyeColor.GREEN, DyeColor.LIME, DyeColor.YELLOW, DyeColor.ORANGE, DyeColor.RED
    };
    private static final DyeColor[] TONI_COLORS = {
            DyeColor.WHITE, DyeColor.LIGHT_GRAY, DyeColor.GRAY, DyeColor.BLACK,
            DyeColor.GRAY, DyeColor.LIGHT_GRAY
    };
    private static final List<Material> PICKAXES = List.of(
            Material.WOODEN_PICKAXE, Material.STONE_PICKAXE, Material.IRON_PICKAXE,
            Material.GOLDEN_PICKAXE, Material.DIAMOND_PICKAXE, Material.NETHERITE_PICKAXE
    );

    private final SpaydisCorePlugin plugin;
    private final Map<Material, Integer> pickaxeWeights = new EnumMap<>(Material.class);
    private final Map<String, Map<UUID, CyclingEntity>> cyclingEntitiesByWorld = new ConcurrentHashMap<>();
    private final Map<String, Set<UUID>> meneleetEntitiesByWorld = new ConcurrentHashMap<>();
    private final Map<String, Set<UUID>> spaydisEntitiesByWorld = new ConcurrentHashMap<>();
    private BukkitRunnable cycleTask;
    private BukkitRunnable cleanupTask;
    private BukkitRunnable meneleetTask;
    private boolean started;

    private int cycleIntervalTicks = 5;
    private int viewDistanceBlocks = 64;
    private double zombiePickaxeChance = 0.15;
    private int zombiePickaxeMaxY = 50;
    private boolean featureJebShulker = true;
    private boolean featureToniKlark = true;
    private boolean featureZombiePickaxes = true;
    private boolean featureBreedingPrevention = true;
    private String breedingPreventionName = "Rereler";
    private boolean featureMeneleetAggression = true;
    private String meneleetTriggerName = "Meneleet";
    private boolean meneleetAttackHostile = true;
    private boolean meneleetAttackPassive = true;
    private boolean meneleetAttackPlayers = true;
    private int meneleetAggroRange = 32;
    private boolean featureSpaydisPacifist = true;
    private String spaydisTriggerName = "Spaydis";
    private boolean debugLogCyclingEvents;

    public EntityBehaviorModule(SpaydisCorePlugin plugin) {
        this.plugin = plugin;
    }

    public void loadConfig() {
        var config = plugin.getConfig();
        cycleIntervalTicks = ConfigGuard.atLeastOne(config.getInt("general.cycle-interval-ticks", 5), 5);
        viewDistanceBlocks = ConfigGuard.boundedNonNegative(
                config.getInt("general.view-distance-blocks", 64), 64, 256);
        zombiePickaxeChance = ConfigGuard.unitInterval(config.getDouble("zombie-pickaxe.chance", 0.15), 0.15);
        zombiePickaxeMaxY = config.getInt("zombie-pickaxe.max-y", 50);
        featureJebShulker = config.getBoolean("features.jeb-shulker", true);
        featureToniKlark = config.getBoolean("features.toni-klark", true);
        featureZombiePickaxes = config.getBoolean("features.zombie-pickaxes", true);
        featureBreedingPrevention = config.getBoolean("features.prevent-breeding-named", true);
        breedingPreventionName = config.getString("breeding-prevention.prevent-name", "Rereler");
        featureMeneleetAggression = config.getBoolean("features.meneleet-aggression", true);
        meneleetTriggerName = config.getString("meneleet.trigger-name",
                config.getString("features.meneleet.trigger-name", "Meneleet"));
        meneleetAttackHostile = config.getBoolean("meneleet.attack-hostile-mobs",
                config.getBoolean("features.meneleet.attack-hostile-mobs", true));
        meneleetAttackPassive = config.getBoolean("meneleet.attack-passive-mobs",
                config.getBoolean("features.meneleet.attack-passive-mobs", true));
        meneleetAttackPlayers = config.getBoolean("meneleet.attack-players",
                config.getBoolean("features.meneleet.attack-players", true));
        meneleetAggroRange = ConfigGuard.boundedNonNegative(config.getInt("meneleet.aggro-range",
                config.getInt("features.meneleet.aggro-range", 32)), 32, 128);
        featureSpaydisPacifist = config.getBoolean("features.spaydis-pacifist", true);
        spaydisTriggerName = config.getString("spaydis.trigger-name",
                config.getString("features.spaydis.trigger-name", "Spaydis"));
        debugLogCyclingEvents = config.getBoolean("debug.log-cycling-events", false);

        pickaxeWeights.clear();
        if (config.isConfigurationSection("zombie-pickaxe.pickaxe-weights")) {
            for (String key : config.getConfigurationSection("zombie-pickaxe.pickaxe-weights").getKeys(false)) {
                try {
                    Material material = Material.valueOf(key.toUpperCase(Locale.ROOT));
                    int weight = config.getInt("zombie-pickaxe.pickaxe-weights." + key, 0);
                    if (weight > 0 && PICKAXES.contains(material)) pickaxeWeights.put(material, weight);
                } catch (IllegalArgumentException ignored) {
                    plugin.getLogger().warning("Invalid pickaxe material in config: " + key);
                }
            }
        }
        if (pickaxeWeights.isEmpty()) {
            pickaxeWeights.put(Material.STONE_PICKAXE, 30);
            pickaxeWeights.put(Material.IRON_PICKAXE, 50);
            pickaxeWeights.put(Material.GOLDEN_PICKAXE, 5);
            pickaxeWeights.put(Material.DIAMOND_PICKAXE, 2);
        }
    }

    public void resetSettingsToDefaults() {
        cycleIntervalTicks = 5;
        viewDistanceBlocks = 64;
        zombiePickaxeChance = 0.15;
        zombiePickaxeMaxY = 50;
        featureJebShulker = true;
        featureToniKlark = true;
        featureZombiePickaxes = true;
        featureBreedingPrevention = true;
        breedingPreventionName = "Rereler";
        featureMeneleetAggression = true;
        meneleetTriggerName = "Meneleet";
        meneleetAttackHostile = true;
        meneleetAttackPassive = true;
        meneleetAttackPlayers = true;
        meneleetAggroRange = 32;
        featureSpaydisPacifist = true;
        spaydisTriggerName = "Spaydis";
        debugLogCyclingEvents = false;
        pickaxeWeights.clear();
        pickaxeWeights.put(Material.STONE_PICKAXE, 30);
        pickaxeWeights.put(Material.IRON_PICKAXE, 50);
        pickaxeWeights.put(Material.GOLDEN_PICKAXE, 5);
        pickaxeWeights.put(Material.DIAMOND_PICKAXE, 2);
    }

    public void start() {
        if (started) return;
        started = true;
        startCycleTask();
        cleanupTask = new BukkitRunnable() {
            @Override
            public void run() {
                for (Map<UUID, CyclingEntity> worldEntities : cyclingEntitiesByWorld.values()) {
                    worldEntities.entrySet().removeIf(entry -> {
                        Entity entity = Bukkit.getEntity(entry.getKey());
                        return entity == null || !entity.isValid();
                    });
                }
                cyclingEntitiesByWorld.entrySet().removeIf(entry -> entry.getValue().isEmpty());
            }
        };
        cleanupTask.runTaskTimer(plugin, 1200L, 1200L);
        meneleetTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (featureMeneleetAggression && !meneleetEntitiesByWorld.isEmpty()) scanMeneleetAggro();
                if (featureSpaydisPacifist && !spaydisEntitiesByWorld.isEmpty()) scanSpaydisPacifist();
            }
        };
        meneleetTask.runTaskTimer(plugin, 20L, 10L);
        registerExistingEntities();
    }

    public void reload() {
        loadConfig();
        if (started) {
            if (cycleTask != null) startCycleTask();
            cleanupFeatureRegistrations();
            registerExistingEntities();
        }
    }

    public void shutdown() {
        if (cycleTask != null) cycleTask.cancel();
        if (cleanupTask != null) cleanupTask.cancel();
        if (meneleetTask != null) meneleetTask.cancel();
        cycleTask = null;
        cleanupTask = null;
        meneleetTask = null;
        started = false;
        cyclingEntitiesByWorld.clear();
        meneleetEntitiesByWorld.clear();
        spaydisEntitiesByWorld.clear();
    }

    public record CyclingEntityInfo(UUID uuid, EntityType type, boolean toni, Location location) { }

    public List<CyclingEntityInfo> cyclingEntities() {
        List<CyclingEntityInfo> result = new ArrayList<>();
        for (Map<UUID, CyclingEntity> entities : cyclingEntitiesByWorld.values()) {
            for (CyclingEntity cycling : entities.values()) {
                Entity entity = Bukkit.getEntity(cycling.uuid());
                if (entity != null && entity.isValid()) {
                    result.add(new CyclingEntityInfo(cycling.uuid(), cycling.type(), cycling.isToni(), entity.getLocation()));
                }
            }
        }
        return List.copyOf(result);
    }

    public boolean stopCyclingEntity(UUID id) {
        for (Map<UUID, CyclingEntity> entities : cyclingEntitiesByWorld.values()) {
            if (entities.remove(id) != null) return true;
        }
        return false;
    }

    private record CyclingEntity(UUID uuid, EntityType type, boolean isToni, int colorIndex, int direction) { }

    private Map<UUID, CyclingEntity> getWorldCyclingEntities(String worldName) {
        return cyclingEntitiesByWorld.computeIfAbsent(worldName, k -> new ConcurrentHashMap<>());
    }

    private void startCycleTask() {
        if (cycleTask != null) {
            cycleTask.cancel();
        }
        cycleTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (cyclingEntitiesByWorld.isEmpty()) return;
                Map<World, List<Location>> playerLocations = new HashMap<>();
                long viewDistanceSq = (long) viewDistanceBlocks * viewDistanceBlocks;

                for (Map<UUID, CyclingEntity> worldEntities : cyclingEntitiesByWorld.values()) {
                    if (worldEntities.isEmpty()) continue;

                    Iterator<Map.Entry<UUID, CyclingEntity>> it = worldEntities.entrySet().iterator();
                    while (it.hasNext()) {
                        Map.Entry<UUID, CyclingEntity> entry = it.next();
                        CyclingEntity ce = entry.getValue();
                        Entity entity = Bukkit.getEntity(ce.uuid());
                        if (entity == null || !entity.isValid()
                                || entity.getType() != ce.type()
                                || !isCyclingName(entity, ce.isToni())) {
                            it.remove();
                            continue;
                        }

                        List<Location> viewers = playerLocations.computeIfAbsent(entity.getWorld(),
                                world -> world.getPlayers().stream().map(Player::getLocation).toList());
                        Location entityLocation = entity.getLocation();
                        boolean hasNearbyPlayer = viewers.stream()
                                .anyMatch(position -> position.distanceSquared(entityLocation) <= viewDistanceSq);
                        if (!hasNearbyPlayer) continue;

                        DyeColor[] colors = getColorsForEntity(ce);
                        DyeColor newColor = colors[ce.colorIndex()];
                        if (entity instanceof Shulker shulker) {
                            shulker.setColor(newColor);
                        } else if (entity instanceof Sheep sheep) {
                            sheep.setColor(newColor);
                        }

                        int newIndex = ce.colorIndex();
                        final int newDir = ce.direction();
                        // TONI_COLORS already lists the way back, so both cycles simply wrap.
                        newIndex = (newIndex + 1) % colors.length;
                        worldEntities.put(ce.uuid(), new CyclingEntity(
                                ce.uuid(), ce.type(), ce.isToni(), newIndex, newDir));
                    }
                }
            }
        };
        cycleTask.runTaskTimer(plugin, 0L, cycleIntervalTicks);
    }

    private DyeColor[] getColorsForEntity(CyclingEntity ce) {
        return ce.isToni() ? TONI_COLORS : JEB_COLORS;
    }

    private void cleanupFeatureRegistrations() {
        for (Map<UUID, CyclingEntity> entities : cyclingEntitiesByWorld.values()) {
            entities.entrySet().removeIf(entry -> entry.getValue().isToni()
                    ? !featureToniKlark
                    : !featureJebShulker);
        }
        cyclingEntitiesByWorld.entrySet().removeIf(entry -> entry.getValue().isEmpty());
        if (!featureMeneleetAggression) {
            clearTrackedMobTargets(meneleetEntitiesByWorld);
            meneleetEntitiesByWorld.clear();
        }
        if (!featureSpaydisPacifist) {
            clearTrackedMobTargets(spaydisEntitiesByWorld);
            spaydisEntitiesByWorld.clear();
        }
    }

    private void clearTrackedMobTargets(Map<String, Set<UUID>> trackedEntities) {
        for (Set<UUID> ids : trackedEntities.values()) {
            for (UUID id : ids) {
                Entity entity = Bukkit.getEntity(id);
                if (entity instanceof Mob mob) {
                    mob.setTarget(null);
                }
            }
        }
    }

    private void registerExistingEntities() {
        if (!featureJebShulker && !featureToniKlark
                && !featureMeneleetAggression && !featureSpaydisPacifist) return;

        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if ((featureJebShulker || featureToniKlark) && entity instanceof Shulker shulker) {
                    tryStartJebTask(shulker);
                } else if (featureToniKlark && entity instanceof Sheep sheep) {
                    startToniSheepTask(sheep);
                }
                if (featureMeneleetAggression && isNamedLike(entity, meneleetTriggerName)) {
                    registerMeneleet(entity);
                }
                if (featureSpaydisPacifist) {
                    registerSpaydis(entity);
                }
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        if (plugin.isLimboWorld(event.getLocation().getWorld())) return;
        if (!featureJebShulker && !featureToniKlark && !featureZombiePickaxes
                && !featureMeneleetAggression && !featureSpaydisPacifist) return;

        Entity entity = event.getEntity();

        if ((featureJebShulker || featureToniKlark) && entity instanceof Shulker shulker) {
            tryStartJebTask(shulker);
        } else if (featureToniKlark && entity instanceof Sheep sheep) {
            startToniSheepTask(sheep);
        } else if (featureZombiePickaxes && entity instanceof Zombie zombie) {
            handleZombiePickaxe(zombie, event.getSpawnReason());
        }

        // /summon with CustomName "Meneleet"/"Spaydis" etc.
        if (featureMeneleetAggression && isNamedLike(entity, meneleetTriggerName)) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> registerMeneleet(entity), 2L);
        }
        registerSpaydis(entity);
    }

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        if (plugin.isLimboWorld(event.getWorld())) return;
        if (!featureJebShulker && !featureToniKlark && !featureMeneleetAggression
                && !featureSpaydisPacifist) return;

        for (Entity entity : event.getEntities()) {
            if ((featureJebShulker || featureToniKlark) && entity instanceof Shulker shulker) {
                tryStartJebTask(shulker);
            } else if (featureToniKlark && entity instanceof Sheep sheep) {
                String name = sheep.getCustomName();
                if (name != null && "toni_klark".equalsIgnoreCase(name.trim())) {
                    registerCyclingEntity(sheep);
                }
            }

            // Re-apply Meneleet AI after chunk load (goals are not persisted in NBT)
            if (featureMeneleetAggression && isNamedLike(entity, meneleetTriggerName)) {
                Bukkit.getScheduler().runTaskLater(plugin, () -> registerMeneleet(entity), 2L);
            }
            registerSpaydis(entity);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        EquipmentSlot hand = event.getHand();
        ItemStack item = hand == EquipmentSlot.HAND
                ? event.getPlayer().getInventory().getItemInMainHand()
                : event.getPlayer().getInventory().getItemInOffHand();

        if (!featureJebShulker && !featureToniKlark && !featureMeneleetAggression && !featureSpaydisPacifist) return;

        Entity clicked = event.getRightClicked();
        if (isSpecialNameTag(item)) {
            String name = PlainTextComponentSerializer.plainText().serialize(item.getItemMeta().displayName()).trim().toLowerCase(Locale.ROOT);
            if ("jeb_".equals(name) && featureJebShulker) {
                if (clicked instanceof Shulker shulker) {
                    Bukkit.getScheduler().runTaskLater(plugin, () -> tryStartJebTask(shulker), 2L);
                }
            } else if ("toni_klark".equals(name) && featureToniKlark) {
                if (clicked instanceof Shulker shulker) {
                    Bukkit.getScheduler().runTaskLater(plugin, () -> tryStartJebTask(shulker), 2L);
                } else if (clicked instanceof Sheep sheep) {
                    // Schedule for next tick so vanilla applies the name tag first
                    Bukkit.getScheduler().runTaskLater(plugin, () -> startToniSheepTask(sheep), 2L);
                }
            }
            if ((isTriggerName(name, meneleetTriggerName) || isTriggerName(name, spaydisTriggerName))
                    && clicked instanceof Mob mob) {
                // Reconcile both roles after vanilla applies the new name.
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    registerMeneleet(mob);
                    registerSpaydis(mob);
                }, 2L);
            }
        }
    }

    private boolean isSpecialNameTag(ItemStack item) {
        if (item == null || item.getType() != Material.NAME_TAG) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null || !meta.hasDisplayName()) return false;
        Component displayName = meta.displayName();
        if (displayName == null) return false;
        String text = PlainTextComponentSerializer.plainText().serialize(displayName).trim();
        return "jeb_".equalsIgnoreCase(text) || "toni_klark".equalsIgnoreCase(text)
                || isTriggerName(text, meneleetTriggerName) || isTriggerName(text, spaydisTriggerName);
    }

    private void tryStartJebTask(Shulker shulker) {
        if (!shulker.isValid()) return;
        String worldName = shulker.getWorld().getName();
        String name = shulker.getCustomName();
        if (name == null) return;
        String trimmed = name.trim();
        if (!"jeb_".equalsIgnoreCase(trimmed) && !"toni_klark".equalsIgnoreCase(trimmed)) return;
        if ("jeb_".equalsIgnoreCase(trimmed) && !featureJebShulker) return;
        if ("toni_klark".equalsIgnoreCase(trimmed) && !featureToniKlark) return;

        CyclingEntity current = getWorldCyclingEntities(worldName).get(shulker.getUniqueId());
        boolean isToni = "toni_klark".equalsIgnoreCase(trimmed);
        if (current != null && current.isToni() == isToni) return;

        registerCyclingEntity(shulker);
    }

    private void registerCyclingEntity(Entity entity) {
        String name = entity.getCustomName();
        if (name == null) return;
        String trimmed = name.trim().toLowerCase(Locale.ROOT);
        if (!"jeb_".equals(trimmed) && !"toni_klark".equals(trimmed)) return;

        boolean isToni = "toni_klark".equals(trimmed);
        if (isToni ? !featureToniKlark : !featureJebShulker) return;
        entity.setCustomNameVisible(true);
        String worldName = entity.getWorld().getName();
        UUID entityId = entity.getUniqueId();
        removeCyclingEntityFromOtherWorlds(entityId, worldName);
        getWorldCyclingEntities(worldName).put(entityId, new CyclingEntity(
                entityId,
                entity.getType(),
                isToni,
                0, // colorIndex
                1  // direction
        ));
        if (debugLogCyclingEvents) {
            plugin.getLogger().info("Registered cycling entity " + entity.getUniqueId() + " in " + worldName);
        }
    }

    private void removeCyclingEntityFromOtherWorlds(UUID entityId, String currentWorld) {
        for (Map.Entry<String, Map<UUID, CyclingEntity>> entry : cyclingEntitiesByWorld.entrySet()) {
            if (entry.getKey().equals(currentWorld)) continue;
            Map<UUID, CyclingEntity> entities = entry.getValue();
            entities.remove(entityId);
            if (entities.isEmpty()) {
                cyclingEntitiesByWorld.remove(entry.getKey(), entities);
            }
        }
    }

    private boolean isNamedLike(Entity entity, String triggerName) {
        return entity != null && isTriggerName(entity.getCustomName(), triggerName);
    }

    private boolean isTriggerName(String name, String triggerName) {
        return name != null && triggerName != null && !triggerName.isBlank()
                && triggerName.trim().equalsIgnoreCase(name.trim());
    }

    private boolean isCyclingName(Entity entity, boolean isToni) {
        String name = entity.getCustomName();
        String expected = isToni ? "toni_klark" : "jeb_";
        return name != null && expected.equalsIgnoreCase(name.trim());
    }

    private void registerMeneleet(Entity entity) {
        if (!featureMeneleetAggression) return;
        if (!(entity instanceof org.bukkit.entity.Mob)) return;
        if (!isNamedLike(entity, meneleetTriggerName)) return;
        String worldName = entity.getWorld().getName();
        removeTrackedEntityFromOtherWorlds(meneleetEntitiesByWorld, entity.getUniqueId(), worldName);
        meneleetEntitiesByWorld
                .computeIfAbsent(worldName, k -> ConcurrentHashMap.newKeySet())
                .add(entity.getUniqueId());
    }

    private void registerSpaydis(Entity entity) {
        if (!featureSpaydisPacifist) return;
        if (!(entity instanceof org.bukkit.entity.Mob mob)) return;
        if (!isNamedLike(entity, spaydisTriggerName)) return;
        String worldName = entity.getWorld().getName();
        removeTrackedEntityFromOtherWorlds(spaydisEntitiesByWorld, entity.getUniqueId(), worldName);
        spaydisEntitiesByWorld
                .computeIfAbsent(worldName, k -> ConcurrentHashMap.newKeySet())
                .add(entity.getUniqueId());
        // Drop any target the mob already had before being named
        mob.setTarget(null);
    }

    private void removeTrackedEntityFromOtherWorlds(Map<String, Set<UUID>> trackedEntities,
                                                    UUID entityId, String currentWorld) {
        for (Map.Entry<String, Set<UUID>> entry : trackedEntities.entrySet()) {
            if (entry.getKey().equals(currentWorld)) continue;
            Set<UUID> entities = entry.getValue();
            entities.remove(entityId);
            if (entities.isEmpty()) {
                trackedEntities.remove(entry.getKey(), entities);
            }
        }
    }

    private void scanSpaydisPacifist() {
        for (Set<UUID> ids : spaydisEntitiesByWorld.values()) {
            Iterator<UUID> it = ids.iterator();
            while (it.hasNext()) {
                UUID id = it.next();
                Entity ent = Bukkit.getEntity(id);
                if (!(ent instanceof org.bukkit.entity.Mob mob)
                        || !ent.isValid()
                        || !isNamedLike(ent, spaydisTriggerName)) {
                    if (ent instanceof Mob mob) {
                        mob.setTarget(null);
                    }
                    it.remove();
                    continue;
                }
                LivingEntity cur = mob.getTarget();
                if (cur != null && !isBossEntity(cur)) {
                    mob.setTarget(null);
                }
            }
        }
    }

    private void scanMeneleetAggro() {
        double rangeSq = (double) meneleetAggroRange * meneleetAggroRange;
        for (Set<UUID> ids : meneleetEntitiesByWorld.values()) {
            if (ids.isEmpty()) continue;
            Iterator<UUID> it = ids.iterator();
            while (it.hasNext()) {
                UUID id = it.next();
                Entity ent = Bukkit.getEntity(id);
                if (!(ent instanceof org.bukkit.entity.Mob mob)
                        || !ent.isValid()
                        || !isNamedLike(ent, meneleetTriggerName)) {
                    if (ent instanceof Mob mob) {
                        mob.setTarget(null);
                    }
                    it.remove();
                    continue;
                }
                Location loc = ent.getLocation();
                LivingEntity cur = mob.getTarget();
                if (cur != null && cur.isValid() && cur.getWorld() == mob.getWorld()
                        && cur.getLocation().distanceSquared(loc) <= rangeSq
                        && isAllowedMeneleetTarget(cur)) {
                    continue;
                }
                LivingEntity nearest = null;
                double bestSq = Double.MAX_VALUE;
                for (Entity near : ent.getWorld().getNearbyEntities(
                        loc, meneleetAggroRange, meneleetAggroRange, meneleetAggroRange)) {
                    if (!(near instanceof LivingEntity le) || !le.isValid() || le.equals(ent)) continue;
                    if (!isAllowedMeneleetTarget(le)) continue;
                    // Zombies cannot attack undead-cursed players; picking them would starve other targets.
                    if (mob instanceof Zombie && plugin.isUndeadCursed(le)) continue;
                    double d = loc.distanceSquared(le.getLocation());
                    if (d > rangeSq) continue;
                    if (d < bestSq) {
                        bestSq = d;
                        nearest = le;
                    }
                }
                if (nearest == null) {
                    if (cur != null && (!cur.isValid()
                            || cur.getWorld() != mob.getWorld()
                            || cur.getLocation().distanceSquared(loc) > rangeSq
                            || !isAllowedMeneleetTarget(cur))) {
                        mob.setTarget(null);
                    }
                    continue;
                }

                boolean needNewTarget = cur == null || !cur.isValid()
                        || cur.getWorld() != mob.getWorld()
                        || cur.getLocation().distanceSquared(loc) > rangeSq
                        || !isAllowedMeneleetTarget(cur);
                if (needNewTarget && mob.getTarget() != nearest) {
                    mob.setTarget(nearest);
                }
            }
        }
    }

    private boolean isAllowedMeneleetTarget(LivingEntity t) {
        if (t instanceof Player p) {
            return meneleetAttackPlayers
                    && p.getGameMode() != GameMode.CREATIVE
                    && p.getGameMode() != GameMode.SPECTATOR;
        }
        // Two Meneleets won't fight each other
        if (t.getCustomName() != null && isNamedLike(t, meneleetTriggerName)) return false;

        // Hostile mode: target ANY living entity except explicitly excluded
        if (meneleetAttackHostile) {
            // Passive mobs only if passive flag is on
            if (!meneleetAttackPassive && (t instanceof Animals || t instanceof Villager)) {
                return false;
            }
            return true;
        }

        // Passive mode only
        if (meneleetAttackPassive && (t instanceof Animals || t instanceof Villager)) return true;

        // Golems (when neither hostile nor passive is on)
        if (t instanceof IronGolem || t instanceof Snowman) return true;

        return false;
    }

    private void startToniSheepTask(Sheep sheep) {
        if (!isCyclingName(sheep, true)) return;
        registerCyclingEntity(sheep);
    }

    private void handleZombiePickaxe(Zombie zombie, CreatureSpawnEvent.SpawnReason reason) {
        if (reason == CreatureSpawnEvent.SpawnReason.SPAWNER) return;
        if (zombie.getLocation().getBlockY() >= zombiePickaxeMaxY) return;

        if (ThreadLocalRandom.current().nextDouble() < zombiePickaxeChance) {
            Material pickaxe = getWeightedRandomPickaxe();
            if (pickaxe != null) {
                zombie.getEquipment().setItemInMainHand(new ItemStack(pickaxe));
                zombie.getEquipment().setItemInMainHandDropChance(0.0f);
            }
        }
    }

    private Material getWeightedRandomPickaxe() {
        if (pickaxeWeights.isEmpty()) return null;

        long totalWeight = pickaxeWeights.values().stream().mapToLong(Integer::longValue).sum();
        if (totalWeight <= 0) return null;

        long randomWeight = ThreadLocalRandom.current().nextLong(totalWeight);
        long currentWeight = 0;

        for (Map.Entry<Material, Integer> entry : pickaxeWeights.entrySet()) {
            currentWeight += entry.getValue();
            if (randomWeight < currentWeight) {
                return entry.getKey();
            }
        }

        // Fallback to first entry
        return pickaxeWeights.keySet().iterator().next();
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityBreed(EntityBreedEvent event) {
        if (!featureBreedingPrevention) return;
        if (breedingPreventionName == null || breedingPreventionName.isBlank()) return;

        String preventName = breedingPreventionName.trim().toLowerCase(Locale.ROOT);

        // Check mother
        Entity mother = event.getMother();
        if (mother != null) {
            String motherName = mother.getCustomName();
            if (motherName != null && motherName.trim().toLowerCase(Locale.ROOT).equals(preventName)) {
                event.setCancelled(true);
                return;
            }
        }

        // Check father
        Entity father = event.getFather();
        if (father != null) {
            String fatherName = father.getCustomName();
            if (fatherName != null && fatherName.trim().toLowerCase(Locale.ROOT).equals(preventName)) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityTargetMeneleet(EntityTargetEvent event) {
        if (!featureMeneleetAggression) {
            // plugin.getLogger().info("Meneleet: feature disabled");
            return;
        }
        if (meneleetTriggerName == null || meneleetTriggerName.isBlank()) {
            // plugin.getLogger().info("Meneleet: trigger name blank");
            return;
        }

        Entity entity = event.getEntity();
        if (!(entity instanceof Mob)) {
            // plugin.getLogger().info("Meneleet: not LivingEntity: " + entity.getType());
            return;
        }

        if (!isNamedLike(entity, meneleetTriggerName)) return;

        Entity target = event.getTarget();
        if (target == null) {
            // plugin.getLogger().info("Meneleet: no target");
            return;
        }

        if (!(target instanceof LivingEntity livingTarget)) {
            event.setCancelled(true);
            return;
        }

        double rangeSq = (double) meneleetAggroRange * meneleetAggroRange;
        if (entity.getWorld() != target.getWorld()
                || entity.getLocation().distanceSquared(target.getLocation()) > rangeSq
                || !isAllowedMeneleetTarget(livingTarget)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityTargetSpaydis(EntityTargetEvent event) {
        if (!featureSpaydisPacifist) return;

        Entity entity = event.getEntity();
        if (!isNamedLike(entity, spaydisTriggerName)) return;

        Entity target = event.getTarget();
        if (target == null) return; // clearing current target is fine

        // Spaydis never fights anyone except bosses
        if (!isBossEntity(target)) {
            event.setCancelled(true);
        }
    }

    private boolean isBossEntity(Entity e) {
        // Vanilla bosses only (Warden is excluded per request)
        return e instanceof EnderDragon || e instanceof Wither;
    }
}
