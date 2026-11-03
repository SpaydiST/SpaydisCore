package macronux.spaydiscore.features.limbo;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.event.block.Action;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Custom item abilities themed around Limbo. This intentionally uses PDC instead of vanilla
 * enchantments so it cannot change an item's normal enchantment rules or anvil behaviour.
 */
public final class LimbiificationManager implements Listener {

    private static final int MAX_LEVEL = 3;
    private static final NamespacedKey LIMBIFICATION_LEVEL_KEY =
            new NamespacedKey("spaydiscore", "limbification_level");
    private static final NamespacedKey LEGACY_DAMAGE_KEY =
            new NamespacedKey("spaydiscore", "limbification_damage_level");
    private static final NamespacedKey OWN_NAME_KEY =
            new NamespacedKey("spaydiscore", "limbification_damage_own_name");
    private static final NamespacedKey OWN_LORE_KEY =
            new NamespacedKey("spaydiscore", "limbification_damage_own_lore");
    private static final String LIMBIFICATION_NAME_KEY =
            "item.spaydiscore.limbification_damage";
    private static final String LIMBIFICATION_LORE_KEY =
            "item.spaydiscore.limbification_lore";
    private static final String LEGACY_LORE_KEY =
            "item.spaydiscore.limbification_damage_lore";

    enum ItemCategory {
        WEAPON,
        RANGED,
        TOOL,
        ARMOR,
        SHIELD,
        CONSUMABLE,
        OTHER
    }

    public LimbiificationManager(JavaPlugin plugin) {
        // The constructor keeps the manager tied to the plugin lifecycle; the marker namespace
        // is deliberately stable so external integrations do not change.
        Objects.requireNonNull(plugin, "plugin");
    }

    /** Returns the bonus damage for a valid Limbiification level, or zero for invalid input. */
    static double bonusDamage(int level) {
        return level >= 1 && level <= MAX_LEVEL ? level : 0.0;
    }

    public int getLevel(ItemStack item) {
        if (item == null || item.getType().isAir()) return 0;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return 0;

        Byte value = meta.getPersistentDataContainer().get(
                LIMBIFICATION_LEVEL_KEY, PersistentDataType.BYTE);
        if (value == null) {
            value = meta.getPersistentDataContainer().get(
                    LEGACY_DAMAGE_KEY, PersistentDataType.BYTE);
        }
        if (value == null) return 0;
        return validLevel(value) ? value : 0;
    }

    static ItemCategory categoryFor(Material material) {
        if (material == null) return ItemCategory.OTHER;
        String name = material.name();
        if (name.equals("AIR") || name.endsWith("_AIR")) return ItemCategory.OTHER;
        if (material == Material.SHIELD) return ItemCategory.SHIELD;

        if (name.equals("BOW") || name.equals("CROSSBOW") || name.equals("TRIDENT")
                || name.endsWith("_SPEAR")) {
            return ItemCategory.RANGED;
        }
        if (name.endsWith("_SWORD") || name.endsWith("_AXE") || name.equals("MACE")) {
            return ItemCategory.WEAPON;
        }
        if (name.endsWith("_PICKAXE") || name.endsWith("_SHOVEL")
                || name.endsWith("_HOE") || name.equals("SHEARS")
                || name.equals("BRUSH") || name.equals("FLINT_AND_STEEL")
                || name.equals("FISHING_ROD")) {
            return ItemCategory.TOOL;
        }
        if (name.endsWith("_HELMET") || name.endsWith("_CHESTPLATE")
                || name.endsWith("_LEGGINGS") || name.endsWith("_BOOTS")
                || name.endsWith("_HORSE_ARMOR") || name.endsWith("_NAUTILUS_ARMOR")
                || name.equals("ELYTRA")) {
            return ItemCategory.ARMOR;
        }
        if (material == Material.POTION || isConsumableName(name)) {
            return ItemCategory.CONSUMABLE;
        }
        return ItemCategory.OTHER;
    }

    public boolean setMainHandLevel(Player player, int level) {
        if (player == null) return false;
        if (level < 0 || level > MAX_LEVEL) return false;
        ItemStack item = player.getInventory().getItemInMainHand();
        if (item == null || item.getType().isAir()) return false;
        return level == 0 ? remove(item) : set(item, level);
    }

    private boolean set(ItemStack item, int level) {
        if (!validLevel(level) || item == null || item.getType().isAir()) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;

        meta.getPersistentDataContainer().set(
                LIMBIFICATION_LEVEL_KEY, PersistentDataType.BYTE, (byte) level);
        meta.getPersistentDataContainer().remove(LEGACY_DAMAGE_KEY);
        removeLegacyName(meta);
        updateLore(meta, level);
        item.setItemMeta(meta);
        return true;
    }

    private boolean remove(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;
        boolean hadAbility = getLevel(item) > 0;
        meta.getPersistentDataContainer().remove(LIMBIFICATION_LEVEL_KEY);
        meta.getPersistentDataContainer().remove(LEGACY_DAMAGE_KEY);
        removeLimbiificationLore(meta);
        removeLegacyName(meta);
        meta.getPersistentDataContainer().remove(OWN_LORE_KEY);
        item.setItemMeta(meta);
        return hadAbility;
    }

    /** Migrates old ability items after a reconnect or plugin reload. */
    public void migratePlayerItems(Player player) {
        if (player == null) return;
        for (int slot = 0; slot < player.getInventory().getSize(); slot++) {
            ItemStack item = player.getInventory().getItem(slot);
            if (migrateItem(item)) {
                player.getInventory().setItem(slot, item);
            }
        }
    }

    private boolean migrateItem(ItemStack item) {
        int level = getLevel(item);
        if (level <= 0) return false;

        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;
        meta.getPersistentDataContainer().set(
                LIMBIFICATION_LEVEL_KEY, PersistentDataType.BYTE, (byte) level);
        meta.getPersistentDataContainer().remove(LEGACY_DAMAGE_KEY);
        removeLegacyName(meta);
        updateLore(meta, level);
        item.setItemMeta(meta);
        return true;
    }

    private void updateLore(ItemMeta meta, int level) {
        List<Component> lore = meta.lore() == null
                ? new ArrayList<>()
                : new ArrayList<>(meta.lore());
        lore.removeIf(this::isLimbiificationLore);
        lore.add(Component.translatable(LIMBIFICATION_LORE_KEY)
                .append(Component.text(" " + roman(level)))
                .color(NamedTextColor.DARK_PURPLE)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        meta.getPersistentDataContainer().set(OWN_LORE_KEY, PersistentDataType.BYTE, (byte) 1);
    }

    private void removeLimbiificationLore(ItemMeta meta) {
        List<Component> lore = meta.lore();
        if (lore == null || lore.isEmpty()) return;
        List<Component> updated = new ArrayList<>(lore);
        if (!updated.removeIf(this::isLimbiificationLore)) return;
        meta.lore(updated.isEmpty() ? null : updated);
    }

    private boolean isLimbiificationLore(Component line) {
        return line instanceof TranslatableComponent translatable
                && (LIMBIFICATION_LORE_KEY.equals(translatable.key())
                || LEGACY_LORE_KEY.equals(translatable.key()));
    }

    private void removeLegacyName(ItemMeta meta) {
        Byte ownName = meta.getPersistentDataContainer().get(OWN_NAME_KEY, PersistentDataType.BYTE);
        if (ownName != null && ownName == 1) {
            if (meta.hasDisplayName() && isLegacyName(meta.displayName())) {
                meta.displayName(null);
            }
            meta.getPersistentDataContainer().remove(OWN_NAME_KEY);
        }
    }

    private boolean isLegacyName(Component name) {
        return name instanceof TranslatableComponent translatable
                && LIMBIFICATION_NAME_KEY.equals(translatable.key());
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        migratePlayerItems(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) return;

        ItemStack item = player.getInventory().getItemInMainHand();
        if (categoryFor(item == null ? null : item.getType()) != ItemCategory.WEAPON) return;
        int level = getLevel(item);
        double extraDamage = bonusDamage(level);
        if (extraDamage == 0.0 || !Double.isFinite(event.getDamage())) return;

        // Scale with the attack charge so spam-clicking cannot get the full flat bonus.
        double charge = Math.max(0.2, Math.min(1.0, player.getAttackCooldown()));
        event.setDamage(event.getDamage() + extraDamage * charge);

        Location effectLocation = event.getEntity().getLocation().add(0.0, 0.6, 0.0);
        effectLocation.getWorld().spawnParticle(
                Particle.SQUID_INK, effectLocation, 4 * level, 0.22, 0.30, 0.22, 0.015);
        player.playSound(effectLocation, "minecraft:entity.warden.attack",
                SoundCategory.PLAYERS, 0.20f, 1.35f - level * 0.08f);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onProjectileLaunch(ProjectileLaunchEvent event) {
        if (!(event.getEntity().getShooter() instanceof Player player)) return;
        ItemStack item = player.getInventory().getItemInMainHand();
        if (categoryFor(item == null ? null : item.getType()) != ItemCategory.RANGED) return;
        int level = getLevel(item);
        if (level <= 0) return;

        Location location = player.getEyeLocation();
        location.getWorld().spawnParticle(
                Particle.END_ROD, location, 6 + level * 4, 0.18, 0.18, 0.18, 0.02);
        event.getEntity().setVelocity(event.getEntity().getVelocity()
                .multiply(1.0 + level * 0.03));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onProjectileHit(ProjectileHitEvent event) {
        if (!(event.getEntity().getShooter() instanceof Player player)) return;
        ItemStack item = player.getInventory().getItemInMainHand();
        if (categoryFor(item == null ? null : item.getType()) != ItemCategory.RANGED) return;
        int level = getLevel(item);
        if (level <= 0) return;

        Location location = event.getEntity().getLocation();
        location.getWorld().spawnParticle(
                Particle.END_ROD, location, 8 + level * 3, 0.30, 0.30, 0.30, 0.03);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onToolBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        ItemStack item = player.getInventory().getItemInMainHand();
        if (categoryFor(item == null ? null : item.getType()) != ItemCategory.TOOL) return;
        int level = getLevel(item);
        if (level <= 0) return;

        player.addPotionEffect(new PotionEffect(
                PotionEffectType.HASTE, 30 + level * 10, level - 1, true, false, false));
        Location location = event.getBlock().getLocation().add(0.5, 0.5, 0.5);
        location.getWorld().spawnParticle(
                Particle.CRIT, location, 6 + level * 3, 0.35, 0.35, 0.35, 0.05);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onArmorDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)
                || !Double.isFinite(event.getDamage())) return;
        switch (event.getCause()) {
            case VOID, STARVATION, SUICIDE, KILL, FIRE_TICK, POISON, WITHER, DROWNING, SUFFOCATION -> {
                return;
            }
            default -> { }
        }

        int totalLevel = 0;
        int pieces = 0;
        for (ItemStack item : player.getInventory().getArmorContents()) {
            if (categoryFor(item == null ? null : item.getType()) != ItemCategory.ARMOR) continue;
            int level = getLevel(item);
            if (level <= 0) continue;
            totalLevel += level;
            pieces++;
        }
        if (pieces == 0) return;

        double reduction = Math.min(0.60, totalLevel * 0.04);
        event.setDamage(event.getDamage() * (1.0 - reduction));
        Location location = player.getLocation().add(0.0, 1.0, 0.0);
        location.getWorld().spawnParticle(
                Particle.PORTAL, location, 3 + totalLevel * 2, 0.30, 0.50, 0.30, 0.05);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onShieldBlock(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player player) || !player.isBlocking()) return;

        ItemStack mainHand = player.getInventory().getItemInMainHand();
        ItemStack shield = mainHand.getType() == Material.SHIELD
                ? mainHand : player.getInventory().getItemInOffHand();
        if (shield == null || shield.getType() != Material.SHIELD) return;
        int level = getLevel(shield);
        if (level <= 0) return;
        // Only react when the vanilla shield actually absorbed this hit.
        if (!event.isApplicable(EntityDamageEvent.DamageModifier.BLOCKING)
                || event.getDamage(EntityDamageEvent.DamageModifier.BLOCKING) == 0.0) return;

        event.setDamage(event.getDamage() * Math.max(0.0, 1.0 - level * 0.05));
        Location location = player.getLocation().add(0.0, 1.0, 0.0);
        location.getWorld().spawnParticle(
                Particle.WITCH, location, 8 + level * 3, 0.35, 0.45, 0.35, 0.02);

        if (event.getDamager() instanceof LivingEntity attacker) {
            Vector push = attacker.getLocation().toVector()
                    .subtract(player.getLocation().toVector());
            if (push.lengthSquared() > 0.0001) {
                push.normalize().multiply(0.25 + level * 0.12);
                push.setY(0.12 + level * 0.04);
                attacker.setVelocity(push);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        ItemStack item = event.getItem();
        if (categoryFor(item == null ? null : item.getType()) != ItemCategory.CONSUMABLE) return;
        int level = getLevel(item);
        if (level <= 0) return;

        Player player = event.getPlayer();
        player.addPotionEffect(new PotionEffect(
                PotionEffectType.ABSORPTION, 100 + level * 40, level - 1, true, false, false));
        Location location = player.getLocation().add(0.0, 1.0, 0.0);
        location.getWorld().spawnParticle(
                Particle.HAPPY_VILLAGER, location, 8 + level * 3, 0.30, 0.60, 0.30, 0.02);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onOtherUse(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR
                && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack item = event.getItem();
        if (item == null || categoryFor(item.getType()) != ItemCategory.OTHER) return;
        int level = getLevel(item);
        if (level <= 0) return;

        Location location = event.getPlayer().getLocation().add(0.0, 1.0, 0.0);
        location.getWorld().spawnParticle(
                Particle.ENCHANT, location, 6 + level * 3, 0.30, 0.50, 0.30, 0.15);
    }

    private static boolean validLevel(int level) {
        return level >= 1 && level <= MAX_LEVEL;
    }

    private static boolean validLevel(Byte level) {
        return level != null && validLevel(level.intValue());
    }

    private static boolean isConsumableName(String name) {
        return switch (name) {
            case "APPLE", "GOLDEN_APPLE", "ENCHANTED_GOLDEN_APPLE", "BREAD", "COOKIE",
                    "MELON_SLICE", "PUMPKIN_PIE", "CARROT", "GOLDEN_CARROT", "POTATO",
                    "BAKED_POTATO", "POISONOUS_POTATO", "BEETROOT", "CHORUS_FRUIT",
                    "GLOW_BERRIES", "SWEET_BERRIES", "DRIED_KELP", "BEEF", "COOKED_BEEF",
                    "PORKCHOP", "COOKED_PORKCHOP", "CHICKEN", "COOKED_CHICKEN", "MUTTON",
                    "COOKED_MUTTON", "RABBIT", "COOKED_RABBIT", "COD", "COOKED_COD",
                    "SALMON", "COOKED_SALMON", "TROPICAL_FISH", "PUFFERFISH", "ROTTEN_FLESH",
                    "SPIDER_EYE", "HONEY_BOTTLE", "SUSPICIOUS_STEW", "MUSHROOM_STEW",
                    "RABBIT_STEW", "BEETROOT_SOUP", "MILK_BUCKET", "OMINOUS_BOTTLE" -> true;
            default -> false;
        };
    }

    private static String roman(int level) {
        return switch (level) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            default -> "?";
        };
    }
}
