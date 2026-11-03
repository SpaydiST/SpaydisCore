package macronux.spaydiscore.features.undead;

import macronux.spaydiscore.SpaydisCorePlugin;
import macronux.spaydiscore.config.ConfigGuard;
import macronux.spaydiscore.features.allergy.NetheriteAllergyModule;
import macronux.spaydiscore.messages.MessageManager;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Zombie;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionType;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.entity.AreaEffectCloudApplyEvent;
import org.bukkit.event.entity.PotionSplashEvent;
import java.util.Collection;
import java.util.Iterator;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persistent "undead" curse: only rotten flesh is edible, poison and hunger are ignored,
 * the sun burns the player (unless a helmet is worn) and zombies leave them alone.
 */
public final class UndeadCurseModule implements Listener {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private static final long TICK_PERIOD = 20L;
    private static final int BURN_TICKS = 160;
    private static final int DEFAULT_HELMET_DAMAGE_SECONDS = 4;

    private final SpaydisCorePlugin plugin;
    private final MessageManager messages;
    private final Set<UUID> cursed = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Integer> helmetCounters = new HashMap<>();
    private File file;
    private boolean persistenceAvailable = true;
    private boolean enabled = true;
    private int helmetDamageSeconds = DEFAULT_HELMET_DAMAGE_SECONDS;
    private BukkitTask task;

    public UndeadCurseModule(SpaydisCorePlugin plugin, MessageManager messages) {
        this.plugin = plugin;
        this.messages = messages;
    }

    public void reload() {
        enabled = plugin.getConfig().getBoolean("features.undead-curse", true);
        helmetDamageSeconds = ConfigGuard.atLeastOne(plugin.getConfig().getInt(
                "undead-curse.helmet-damage-interval-seconds", DEFAULT_HELMET_DAMAGE_SECONDS),
                DEFAULT_HELMET_DAMAGE_SECONDS);
    }

    public void load() {
        file = new File(plugin.getDataFolder(), "undead.yml");
        if (!file.exists()) return;
        YamlConfiguration config = new YamlConfiguration();
        try {
            config.load(file);
        } catch (IOException | InvalidConfigurationException exception) {
            // Keep the unreadable file untouched instead of overwriting it with an empty set.
            persistenceAvailable = false;
            throw new IllegalStateException("Could not read undead.yml", exception);
        }
        for (String value : config.getStringList("cursed")) {
            try {
                cursed.add(UUID.fromString(value));
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    public void start() {
        if (task != null) return;
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tickSun, TICK_PERIOD, TICK_PERIOD);
    }

    public void shutdown() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        helmetCounters.clear();
        cursed.clear();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isCursed(UUID id) {
        return enabled && id != null && cursed.contains(id);
    }

    public boolean isCursed(Entity entity) {
        return entity instanceof Player player && isCursed(player.getUniqueId());
    }

    public Set<UUID> cursedPlayers() {
        return Set.copyOf(cursed);
    }

    public boolean give(OfflinePlayer target) {
        UUID id = target.getUniqueId();
        boolean added = cursed.add(id);
        if (added) {
            save();
            Player online = target.getPlayer();
            if (online != null && online.isOnline()) {
                online.sendMessage(LEGACY.deserialize(messages.get(online, "undead-given-self")));
                dropZombieTargets(online);
            }
        }
        return added;
    }

    public boolean remove(OfflinePlayer target) {
        UUID id = target.getUniqueId();
        boolean removed = cursed.remove(id);
        if (removed) {
            helmetCounters.remove(id);
            save();
            Player online = target.getPlayer();
            if (online != null && online.isOnline()) {
                online.sendMessage(LEGACY.deserialize(messages.get(online, "undead-removed-self")));
            }
        }
        return removed;
    }

    private void dropZombieTargets(Player player) {
        for (Entity near : player.getNearbyEntities(48, 48, 48)) {
            if (near instanceof Zombie zombie && player.equals(zombie.getTarget())) {
                zombie.setTarget(null);
            }
        }
    }

    private void save() {
        if (!persistenceAvailable) return;
        if (file == null) file = new File(plugin.getDataFolder(), "undead.yml");
        YamlConfiguration config = new YamlConfiguration();
        List<String> ids = new ArrayList<>();
        for (UUID id : cursed) ids.add(id.toString());
        config.set("cursed", ids);
        try {
            NetheriteAllergyModule.writeAtomically(file.toPath(), config.saveToString());
        } catch (IOException exception) {
            plugin.getLogger().warning("Failed to save undead.yml: " + exception.getMessage());
        }
    }

    private void tickSun() {
        if (!enabled || cursed.isEmpty()) return;
        for (UUID id : cursed) {
            Player player = plugin.getServer().getPlayer(id);
            if (player == null || !player.isOnline() || player.isDead()) continue;
            if (!isExposedToSun(player)) {
                helmetCounters.remove(id);
                continue;
            }
            ItemStack helmet = player.getInventory().getHelmet();
            if (helmet == null || helmet.getType().isAir()) {
                player.setFireTicks(Math.max(player.getFireTicks(), BURN_TICKS));
                helmetCounters.remove(id);
                continue;
            }
            // A worn helmet blocks the sun but wears down slowly. Items without durability
            // (carved pumpkin, mob heads) protect forever, like they do for vanilla zombies.
            int seconds = helmetCounters.merge(id, 1, Integer::sum);
            if (seconds >= helmetDamageSeconds) {
                helmetCounters.put(id, 0);
                player.damageItemStack(EquipmentSlot.HEAD, 1);
            }
        }
    }

    private boolean isExposedToSun(Player player) {
        World world = player.getWorld();
        if (world.getEnvironment() != World.Environment.NORMAL || plugin.isLimboWorld(world)) return false;
        GameMode mode = player.getGameMode();
        if (mode == GameMode.CREATIVE || mode == GameMode.SPECTATOR) return false;
        if (!world.isDayTime() || world.hasStorm() || player.isInWater()) return false;
        Block eye = player.getEyeLocation().getBlock();
        return eye.getLightFromSky() >= 15;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        Player player = event.getPlayer();
        Material type = event.getItem().getType();
        if (!isCursed(player.getUniqueId()) || !type.isEdible() || type == Material.ROTTEN_FLESH) return;
        event.setCancelled(true);
        denyFood(player);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCakeInteract(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || block == null
                || !isCursed(event.getPlayer().getUniqueId())) return;
        if (block.getType() == Material.CAKE || Tag.CANDLE_CAKES.isTagged(block.getType())) {
            event.setUseInteractedBlock(Event.Result.DENY);
            denyFood(event.getPlayer());
        }
    }

    private void denyFood(Player player) {
        player.sendActionBar(LEGACY.deserialize(messages.get(player, "undead-food-denied")));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPotionEffect(EntityPotionEffectEvent event) {
        PotionEffectType type = event.getModifiedType();
        if (!isCursed(event.getEntity())
                || (type != PotionEffectType.POISON && type != PotionEffectType.HUNGER)) return;
        if (event.getAction() == EntityPotionEffectEvent.Action.ADDED
                || event.getAction() == EntityPotionEffectEvent.Action.CHANGED) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onZombieTarget(EntityTargetEvent event) {
        if (event.getEntity() instanceof Zombie && isCursed(event.getTarget())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onZombieDamage(EntityDamageByEntityEvent event) {
        if (!isCursed(event.getEntity())) return;
        Entity damager = event.getDamager();
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Entity shooter) {
            damager = shooter;
        }
        if (damager instanceof Zombie) {
            event.setCancelled(true);
        }
    }

    // ---- undead potion rules: healing hurts, harming heals ---------------------------------

    private static boolean isInstant(PotionEffectType type) {
        return type == PotionEffectType.INSTANT_HEALTH || type == PotionEffectType.INSTANT_DAMAGE;
    }

    private static boolean hasInstant(Collection<PotionEffect> effects) {
        for (PotionEffect effect : effects) {
            if (isInstant(effect.getType())) return true;
        }
        return false;
    }

    /** Net health change for an undead target: positive heals, negative hurts. */
    private static double reversedDelta(Collection<PotionEffect> effects, double scale) {
        double delta = 0.0;
        for (PotionEffect effect : effects) {
            double level = Math.pow(2.0, effect.getAmplifier());
            if (effect.getType() == PotionEffectType.INSTANT_HEALTH) delta -= 6.0 * level * scale;
            else if (effect.getType() == PotionEffectType.INSTANT_DAMAGE) delta += 4.0 * level * scale;
        }
        return delta;
    }

    private void applyDelta(Player player, double delta) {
        if (delta > 0.0) {
            AttributeInstance max = player.getAttribute(Attribute.MAX_HEALTH);
            double limit = max == null ? 20.0 : max.getValue();
            player.setHealth(Math.min(limit, player.getHealth() + delta));
        } else if (delta < 0.0) {
            drain(player, -delta, true);
        }
    }

    private void drain(Player player, double amount, boolean canKill) {
        double damage = canKill ? amount : Math.min(amount, player.getHealth() - 1.0);
        if (damage > 0.0) {
            player.damage(damage, DamageSource.builder(DamageType.MAGIC).build());
        }
    }

    /**
     * Regeneration works like poison on the undead: the effect and its icon stay, but every
     * pulse takes health instead of adding it, and it never kills.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onRegeneration(EntityRegainHealthEvent event) {
        if (event.getRegainReason() != EntityRegainHealthEvent.RegainReason.MAGIC_REGEN
                || !(event.getEntity() instanceof Player player) || !isCursed(player.getUniqueId())) return;
        event.setCancelled(true);
        drain(player, event.getAmount(), false);
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onSplash(PotionSplashEvent event) {
        Collection<PotionEffect> effects = event.getPotion().getEffects();
        if (!hasInstant(effects)) return;
        for (LivingEntity entity : new ArrayList<>(event.getAffectedEntities())) {
            if (entity instanceof Player player && isCursed(player.getUniqueId())) {
                double intensity = event.getIntensity(entity);
                event.setIntensity(entity, 0.0);
                applyDelta(player, reversedDelta(effects, intensity));
            }
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onCloud(AreaEffectCloudApplyEvent event) {
        AreaEffectCloud cloud = event.getEntity();
        List<PotionEffect> effects = new ArrayList<>(cloud.getCustomEffects());
        PotionType base = cloud.getBasePotionType();
        if (base != null) effects.addAll(base.getPotionEffects());
        if (!hasInstant(effects)) return;
        Iterator<LivingEntity> iterator = event.getAffectedEntities().iterator();
        while (iterator.hasNext()) {
            if (iterator.next() instanceof Player player && isCursed(player.getUniqueId())) {
                iterator.remove();
                applyDelta(player, reversedDelta(effects, 0.5));
            }
        }
    }

    /** Drinking: the instant effect is swapped out of the consumed potion and applied reversed. */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onDrinkPotion(PlayerItemConsumeEvent event) {
        Player player = event.getPlayer();
        ItemStack item = event.getItem();
        if (!isCursed(player.getUniqueId()) || item.getType() != Material.POTION
                || !(item.getItemMeta() instanceof PotionMeta meta)) return;
        List<PotionEffect> effects = new ArrayList<>(meta.getCustomEffects());
        PotionType base = meta.getBasePotionType();
        if (base != null) effects.addAll(base.getPotionEffects());
        if (!hasInstant(effects)) return;

        ItemStack replacement = item.clone();
        PotionMeta replacementMeta = (PotionMeta) replacement.getItemMeta();
        replacementMeta.setBasePotionType(PotionType.WATER);
        replacementMeta.clearCustomEffects();
        for (PotionEffect effect : effects) {
            if (!isInstant(effect.getType())) replacementMeta.addCustomEffect(effect, true);
        }
        replacement.setItemMeta(replacementMeta);
        event.setItem(replacement);
        applyDelta(player, reversedDelta(effects, 1.0));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        helmetCounters.remove(event.getPlayer().getUniqueId());
    }
}
