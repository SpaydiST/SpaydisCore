package macronux.spaydiscore.features.allergy;

import macronux.spaydiscore.SpaydisCorePlugin;
import macronux.spaydiscore.config.ConfigGuard;
import macronux.spaydiscore.messages.MessageManager;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Particle;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Tracks Netherite exposure and owns the allergy's persistent player state. */
public final class NetheriteAllergyModule implements Listener {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private static final long TICKS_PER_SECOND = 20L;
    private static final long MILLIS_PER_TICK = 50L;
    private static final long SCAN_INTERVAL_TICKS = 10L;

    private final SpaydisCorePlugin plugin;
    private final MessageManager messages;
    private final Set<UUID> allergicPlayers = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> wearTicks = new HashMap<>();
    private final Map<UUID, Long> nextSneezeAt = new HashMap<>();
    private final Map<UUID, Long> suppressedSince = new HashMap<>();
    private final Set<UUID> allergyNauseaPlayers = new java.util.HashSet<>();
    private UUID applyingNausea;
    private static final long HONEY_RELIEF_NANOS = 120_000_000_000L;
    private boolean dirty;
    private long nextSaveAt;
    private File allergiesFile;
    private boolean persistenceAvailable = true;

    private boolean enabled = true;
    private int developSeconds = 120;
    private int sneezeIntervalSeconds = 12;
    private int dizzinessSeconds = 4;
    private int stage2ThresholdSeconds = 240;
    private int stage2SneezeIntervalSeconds = 4;
    private double stage2KnockbackBlocks = 5.0;
    private int stage2DizzinessSeconds = 6;
    private boolean started;
    private org.bukkit.scheduler.BukkitTask scanTask;

    public NetheriteAllergyModule(SpaydisCorePlugin plugin, MessageManager messages) {
        this.plugin = plugin;
        this.messages = messages;
    }

    /** Loads validated settings without resetting saved allergy progress. */
    public void reload() {
        FileConfiguration config = plugin.getConfig();
        enabled = config.getBoolean("features.netherite-allergy", true);
        developSeconds = ConfigGuard.atLeastOne(
                config.getInt("netherite-allergy.wear-time-to-develop-seconds", 120), 120);
        sneezeIntervalSeconds = ConfigGuard.atLeastOne(
                config.getInt("netherite-allergy.sneeze-interval-seconds", 12), 12);
        dizzinessSeconds = ConfigGuard.atLeastOne(
                config.getInt("netherite-allergy.dizziness-duration-seconds", 4), 4);
        stage2ThresholdSeconds = ConfigGuard.atLeastOne(
                config.getInt("netherite-allergy.stage2.threshold-seconds", 240), 240);
        stage2SneezeIntervalSeconds = ConfigGuard.atLeastOne(
                config.getInt("netherite-allergy.stage2.sneeze-interval-seconds", 4), 4);
        stage2KnockbackBlocks = ConfigGuard.boundedFiniteNonNegative(
                config.getDouble("netherite-allergy.stage2.knockback-blocks", 5.0), 5.0, 10.0);
        stage2DizzinessSeconds = ConfigGuard.atLeastOne(
                config.getInt("netherite-allergy.stage2.dizziness-duration-seconds", 6), 6);
    }

    public void resetSettingsToDefaults() {
        enabled = true;
        developSeconds = 120;
        sneezeIntervalSeconds = 12;
        dizzinessSeconds = 4;
        stage2ThresholdSeconds = 240;
        stage2SneezeIntervalSeconds = 4;
        stage2KnockbackBlocks = 5.0;
        stage2DizzinessSeconds = 6;
    }

    public void load() {
        allergiesFile = new File(plugin.getDataFolder(), "allergies.yml");
        if (!allergiesFile.exists()) return;
        YamlConfiguration config = new YamlConfiguration();
        try {
            config.load(allergiesFile);
        } catch (IOException | InvalidConfigurationException exception) {
            // Preserve the unreadable file; saving an empty in-memory state would erase it.
            persistenceAvailable = false;
            throw new IllegalStateException("Could not read allergies.yml", exception);
        }
        for (String value : config.getStringList("allergic")) {
            try {
                allergicPlayers.add(UUID.fromString(value));
            } catch (IllegalArgumentException ignored) {
            }
        }

        // Wear progress is stored in ticks; older files are migrated from milliseconds once.
        var tickProgress = config.getConfigurationSection("wear-progress-ticks");
        if (tickProgress != null) {
            for (String key : tickProgress.getKeys(false)) {
                try {
                    wearTicks.put(UUID.fromString(key), Math.max(0L, tickProgress.getLong(key)));
                } catch (IllegalArgumentException ignored) {
                }
            }
        } else {
            var legacyProgress = config.getConfigurationSection("wear-progress");
            if (legacyProgress != null) {
                for (String key : legacyProgress.getKeys(false)) {
                    try {
                        wearTicks.put(UUID.fromString(key), millisToTicks(Math.max(0L, legacyProgress.getLong(key))));
                    } catch (IllegalArgumentException ignored) {
                    }
                }
            }
        }
    }

    public void start() {
        if (started) return;
        started = true;
        nextSaveAt = Integer.toUnsignedLong(Bukkit.getCurrentTick()) + 1200L;
        scanTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (enabled) scan();
            long now = Integer.toUnsignedLong(Bukkit.getCurrentTick());
            if (dirty && now >= nextSaveAt) {
                saveNow();
                nextSaveAt = now + 1200L;
            }
        }, 20L, SCAN_INTERVAL_TICKS);
    }

    public void shutdown() {
        if (scanTask != null) {
            scanTask.cancel();
            scanTask = null;
        }
        started = false;
        saveNow();
        wearTicks.clear();
        suppressedSince.clear();
        allergyNauseaPlayers.clear();
        nextSneezeAt.clear();
        allergicPlayers.clear();
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        // Keep cumulative wear progress across sessions, but restart the sneeze interval on login.
        nextSneezeAt.remove(event.getPlayer().getUniqueId());
        allergyNauseaPlayers.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(ignoreCancelled = true, priority = org.bukkit.event.EventPriority.MONITOR)
    public void onHoneyConsumed(PlayerItemConsumeEvent event) {
        Player player = event.getPlayer();
        if (!enabled || event.getItem().getType() != Material.HONEY_BOTTLE
                || !allergicPlayers.contains(player.getUniqueId())) return;
        suppressedSince.put(player.getUniqueId(), System.nanoTime());
        nextSneezeAt.remove(player.getUniqueId());
        if (allergyNauseaPlayers.contains(player.getUniqueId())) {
            player.removePotionEffect(PotionEffectType.NAUSEA);
        }
        player.sendMessage(LEGACY.deserialize(messages.get(player, "allergy-honey-relief")));
    }

    @EventHandler(ignoreCancelled = true, priority = org.bukkit.event.EventPriority.MONITOR)
    public void onNauseaChanged(EntityPotionEffectEvent event) {
        if (event.getModifiedType() == PotionEffectType.NAUSEA
                && event.getEntity() instanceof Player player
                && nauseaWasChanged(event.getAction(), event.isOverride())
                && !player.getUniqueId().equals(applyingNausea)) {
            // Another source replaced/removed our nausea; honey must leave it alone.
            allergyNauseaPlayers.remove(player.getUniqueId());
        }
    }

    static boolean nauseaWasChanged(EntityPotionEffectEvent.Action action, boolean override) {
        return action != EntityPotionEffectEvent.Action.CHANGED || override;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Set<UUID> allergicPlayers() {
        return Set.copyOf(allergicPlayers);
    }

    public boolean give(OfflinePlayer target, int stage) {
        UUID id = target.getUniqueId();
        Player online = target.getPlayer();
        if (online != null && online.isOnline()) {
            giveAllergy(online, false);
            if (stage == 2) {
                wearTicks.put(id, secondsToTicks(stage2ThresholdSeconds));
            }
        } else {
            allergicPlayers.add(id);
            if (stage == 2) {
                wearTicks.put(id, secondsToTicks(stage2ThresholdSeconds));
            } else {
                wearTicks.remove(id);
            }
        }
        save();
        return true;
    }

    public boolean remove(UUID id) {
        boolean removed = allergicPlayers.remove(id);
        if (removed) {
            wearTicks.remove(id);
            nextSneezeAt.remove(id);
            suppressedSince.remove(id);
            save();
        }
        return removed;
    }

    private void scan() {
        long now = Integer.toUnsignedLong(Bukkit.getCurrentTick());
        long reliefNow = System.nanoTime();
        suppressedSince.values().removeIf(start -> !reliefActive(start, reliefNow));
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID id = player.getUniqueId();
            boolean wearing = isWearingNetherite(player);

            if (!allergicPlayers.contains(id)) {
                if (wearing && player.getGameMode() != GameMode.SPECTATOR) {
                    long total = ConfigGuard.saturatingAdd(wearTicks.getOrDefault(id, 0L), SCAN_INTERVAL_TICKS);
                    wearTicks.put(id, total);
                    dirty = true;
                    if (total >= secondsToTicks(developSeconds)) {
                        giveAllergy(player, true);
                        save();
                    }
                }
                continue;
            }

            long stage2Progress = wearTicks.getOrDefault(id, 0L);
            boolean stage2 = stage2Progress >= secondsToTicks(stage2ThresholdSeconds);
            if (!stage2 && !wearing) {
                nextSneezeAt.remove(id);
                continue;
            }
            if (!stage2) {
                stage2Progress = ConfigGuard.saturatingAdd(stage2Progress, SCAN_INTERVAL_TICKS);
                wearTicks.put(id, stage2Progress);
                dirty = true;
                stage2 = stage2Progress >= secondsToTicks(stage2ThresholdSeconds);
            }

            Long reliefStart = suppressedSince.get(id);
            if (reliefStart != null) {
                if (reliefActive(reliefStart, reliefNow)) {
                    nextSneezeAt.remove(id);
                    continue;
                }
                suppressedSince.remove(id);
            }
            boolean hasTrigger = stage2 ? hasNetheriteInInventory(player) : wearing;
            if (!hasTrigger) {
                nextSneezeAt.remove(id);
                continue;
            }

            int stage = stage2 ? 2 : 1;
            int interval = stage == 2 ? stage2SneezeIntervalSeconds : sneezeIntervalSeconds;
            Long nextAt = nextSneezeAt.get(id);
            if (nextAt == null) {
                nextSneezeAt.put(id, ConfigGuard.saturatingAdd(now, secondsToTicks(interval)));
            } else if (now >= nextAt) {
                nextSneezeAt.put(id, ConfigGuard.saturatingAdd(now, secondsToTicks(interval)));
                performSneeze(player, stage);
            }
        }
    }

    private void giveAllergy(Player target, boolean natural) {
        suppressedSince.remove(target.getUniqueId());
        allergicPlayers.add(target.getUniqueId());
        wearTicks.remove(target.getUniqueId());
        target.sendMessage(LEGACY.deserialize(messages.get(target,
                natural ? "allergy-developed" : "allergy-given-self")));
        plugin.playSoundToPlayer(target, "allergy.develop");
        plugin.getLogger().info("Netherite allergy " + (natural ? "developed" : "granted")
                + ": " + target.getName());
    }

    private void performSneeze(Player player, int stage) {
        plugin.playSound(player.getLocation(), "allergy.sneeze");
        int particleCount = stage == 2 ? 28 : 14;
        player.getWorld().spawnParticle(Particle.SNEEZE,
                player.getLocation().add(0, Math.max(1.2, player.getHeight() * 0.9), 0),
                particleCount, 0.25, 0.25, 0.25, 0.01);

        // The configured knockback is a target distance, so convert it to a bounded velocity.
        double pushStrength = stage == 2 ? -Math.min(0.6, stage2KnockbackBlocks * 0.1) : -0.9;
        double pushY = stage == 2 ? 0.4 : 0.25;
        Vector facing = player.getLocation().getDirection().setY(0);
        if (facing.lengthSquared() < 1.0E-4) facing = new Vector(0, 0, 1);
        Vector push = facing.normalize().multiply(pushStrength).setY(pushY);
        if (stage == 2) {
            Vector current = player.getVelocity();
            player.setVelocity(new Vector(push.getX(), Math.max(current.getY(), pushY), push.getZ()));
        } else {
            player.setVelocity(player.getVelocity().add(push));
        }

        if (stage == 2) {
            PlayerInventory inventory = player.getInventory();
            for (int slot = 0; slot < 36; slot++) {
                ItemStack stack = inventory.getItem(slot);
                if (stack != null && isNetheriteAllergyItem(stack)) {
                    inventory.setItem(slot, null);
                    player.getWorld().dropItemNaturally(player.getLocation().add(0, 1, 0), stack);
                }
            }
            ItemStack offhand = inventory.getItemInOffHand();
            if (isNetheriteAllergyItem(offhand)) {
                inventory.setItemInOffHand(null);
                player.getWorld().dropItemNaturally(player.getLocation().add(0, 1, 0), offhand);
            }
        }

        org.bukkit.inventory.EntityEquipment equipment = player.getEquipment();
        if (equipment != null) {
            EquipmentSlot[] slots = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
            for (EquipmentSlot slot : slots) {
                ItemStack piece = switch (slot) {
                    case HEAD -> equipment.getHelmet();
                    case CHEST -> equipment.getChestplate();
                    case LEGS -> equipment.getLeggings();
                    case FEET -> equipment.getBoots();
                    default -> null;
                };
                if (piece == null || !isNetheriteArmor(piece.getType())) continue;
                switch (slot) {
                    case HEAD -> equipment.setHelmet(null);
                    case CHEST -> equipment.setChestplate(null);
                    case LEGS -> equipment.setLeggings(null);
                    case FEET -> equipment.setBoots(null);
                    default -> { }
                }
                player.getWorld().dropItemNaturally(player.getLocation().add(0, 1, 0), piece);
            }
        }

        int effectSeconds = stage == 2 ? stage2DizzinessSeconds : dizzinessSeconds;
        long durationTicks = Math.max(TICKS_PER_SECOND, (long) effectSeconds * TICKS_PER_SECOND);
        int ticks = (int) Math.min(Integer.MAX_VALUE, durationTicks);
        int amplifier = stage == 2 ? 1 : 0;
        if (player.hasPotionEffect(PotionEffectType.NAUSEA)
                && !allergyNauseaPlayers.contains(player.getUniqueId())) return;
        applyingNausea = player.getUniqueId();
        try {
            if (player.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA,
                    ticks, amplifier, false, true, true))) {
                allergyNauseaPlayers.add(player.getUniqueId());
            }
        } finally {
            applyingNausea = null;
        }
    }

    private boolean isWearingNetherite(Player player) {
        for (ItemStack item : player.getInventory().getArmorContents()) {
            if (item != null && isNetheriteArmor(item.getType())) return true;
        }
        return false;
    }

    private boolean hasNetheriteInInventory(Player player) {
        PlayerInventory inventory = player.getInventory();
        for (int slot = 0; slot < 36; slot++) {
            if (isNetheriteAllergyItem(inventory.getItem(slot))) return true;
        }
        if (isNetheriteAllergyItem(inventory.getItemInOffHand())) return true;
        for (ItemStack item : inventory.getArmorContents()) {
            if (isNetheriteAllergyItem(item)) return true;
        }
        return false;
    }

    private boolean isNetheriteArmor(Material material) {
        return material == Material.NETHERITE_HELMET || material == Material.NETHERITE_CHESTPLATE
                || material == Material.NETHERITE_LEGGINGS || material == Material.NETHERITE_BOOTS;
    }

    private boolean isNetheriteAllergyItem(ItemStack item) {
        return item != null && isNetheriteAllergyItem(item.getType());
    }

    private boolean isNetheriteAllergyItem(Material material) {
        return isNetheriteItem(material) || material == Material.NETHERITE_UPGRADE_SMITHING_TEMPLATE;
    }

    private boolean isNetheriteItem(Material material) {
        return material == Material.NETHERITE_HELMET || material == Material.NETHERITE_CHESTPLATE
                || material == Material.NETHERITE_LEGGINGS || material == Material.NETHERITE_BOOTS
                || material == Material.NETHERITE_SWORD || material == Material.NETHERITE_PICKAXE
                || material == Material.NETHERITE_AXE || material == Material.NETHERITE_SHOVEL
                || material == Material.NETHERITE_HOE || material == Material.NETHERITE_SPEAR
                || material == Material.NETHERITE_NAUTILUS_ARMOR || material == Material.NETHERITE_HORSE_ARMOR
                || material == Material.NETHERITE_BLOCK || material == Material.NETHERITE_INGOT
                || material == Material.NETHERITE_SCRAP || material == Material.ANCIENT_DEBRIS;
    }

    private void save() {
        long deadline = Integer.toUnsignedLong(Bukkit.getCurrentTick()) + 20L;
        nextSaveAt = dirty ? Math.min(nextSaveAt, deadline) : deadline;
        dirty = true;
    }

    private void saveNow() {
        if (!persistenceAvailable) return;
        if (allergiesFile == null) allergiesFile = new File(plugin.getDataFolder(), "allergies.yml");
        YamlConfiguration config = new YamlConfiguration();
        List<String> players = new ArrayList<>();
        for (UUID id : allergicPlayers) players.add(id.toString());
        config.set("allergic", players);
        for (Map.Entry<UUID, Long> entry : wearTicks.entrySet()) {
            if (entry.getValue() != null && entry.getValue() > 0) {
                config.set("wear-progress-ticks." + entry.getKey(), entry.getValue());
            }
        }
        try {
            writeAtomically(allergiesFile.toPath(), config.saveToString());
            dirty = false;
        } catch (IOException exception) {
            plugin.getLogger().warning("Failed to save allergies.yml: " + exception.getMessage());
        }
    }

    static boolean reliefActive(long startedNanos, long nowNanos) {
        return nowNanos - startedNanos >= 0L && nowNanos - startedNanos < HONEY_RELIEF_NANOS;
    }

    public static void writeAtomically(java.nio.file.Path target, String contents) throws IOException {
        java.nio.file.Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(temporary, contents, StandardCharsets.UTF_8);
        try {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private long secondsToTicks(long seconds) {
        if (seconds <= 0L) return 0L;
        return seconds > Long.MAX_VALUE / TICKS_PER_SECOND
                ? Long.MAX_VALUE : seconds * TICKS_PER_SECOND;
    }

    private long millisToTicks(long millis) {
        if (millis <= 0L) return 0L;
        return millis > (Long.MAX_VALUE - (MILLIS_PER_TICK - 1L))
                ? Long.MAX_VALUE : (millis + (MILLIS_PER_TICK - 1L)) / MILLIS_PER_TICK;
    }
}
