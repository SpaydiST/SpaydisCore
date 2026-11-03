package macronux.spaydiscore.features.death;

import macronux.spaydiscore.SpaydisCorePlugin;
import macronux.spaydiscore.messages.MessageManager;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Tameable;
import org.bukkit.entity.Warden;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Owns localized Warden deaths and the name-tag memories left by named mobs. */
public final class DeathMessageModule implements Listener {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private static final Pattern DEATH_PLACEHOLDER_PATTERN = Pattern.compile("\\{(killer|context)}");
    private static final List<String> DEFAULT_WARDEN_MESSAGE_KEYS = List.of(
            "warden-too-deep", "warden-too-noisy", "warden-library");
    private static final NamespacedKey BROKEN_NAMETAG_KEY = new NamespacedKey("spaydiscore", "broken_nametag");
    private static final NamespacedKey MOB_TYPE_KEY = new NamespacedKey("spaydiscore", "mob_type");
    private static final NamespacedKey DEATH_MESSAGE_KEY = new NamespacedKey("spaydiscore", "death_message");
    private static final NamespacedKey DEATH_TIME_KEY = new NamespacedKey("spaydiscore", "death_time");
    private static final DateTimeFormatter MEMORY_DATE = DateTimeFormatter
            .ofPattern("dd.MM.yyyy HH:mm 'UTC'").withZone(ZoneOffset.UTC);

    private final SpaydisCorePlugin plugin;
    private final MessageManager messages;

    private boolean featureWardenDeathMessages = true;
    private List<String> wardenDeathMessageKeys = DEFAULT_WARDEN_MESSAGE_KEYS;
    private boolean featureBrokenNametag = true;
    private boolean brokenNametagIncludeDeathCause = true;

    public DeathMessageModule(SpaydisCorePlugin plugin, MessageManager messages) {
        this.plugin = plugin;
        this.messages = messages;
    }

    /** Restores the same safe defaults used when the bundled config cannot be read. */
    public void resetSettingsToDefaults() {
        featureWardenDeathMessages = true;
        wardenDeathMessageKeys = DEFAULT_WARDEN_MESSAGE_KEYS;
        featureBrokenNametag = true;
        brokenNametagIncludeDeathCause = true;
    }

    /** Refreshes the module's feature flags while keeping its persistent item format stable. */
    public void reload() {
        FileConfiguration config = plugin.getConfig();
        featureWardenDeathMessages = config.getBoolean("features.warden-death-messages", true);
        featureBrokenNametag = config.getBoolean("features.broken-nametag", true);
        brokenNametagIncludeDeathCause = config.getBoolean("broken-nametag.include-death-cause", true);

        List<String> configuredKeys = config.getStringList("warden-death.messages").stream()
                .map(String::trim)
                .filter(key -> !key.isEmpty())
                .distinct()
                .toList();
        wardenDeathMessageKeys = configuredKeys.isEmpty()
                ? DEFAULT_WARDEN_MESSAGE_KEYS
                : List.copyOf(configuredKeys);
    }

    @EventHandler
    public void onEntityDeath(EntityDeathEvent event) {
        LivingEntity entity = event.getEntity();
        if (featureWardenDeathMessages) {
            handleWardenDeathMessage(entity, event);
        }
        if (featureBrokenNametag) {
            handleBrokenNametag(entity, event);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        if (featureBrokenNametag && isBrokenNametag(event.getHand() == org.bukkit.inventory.EquipmentSlot.HAND
                ? event.getPlayer().getInventory().getItemInMainHand()
                : event.getPlayer().getInventory().getItemInOffHand())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onAnvilPrepare(PrepareAnvilEvent event) {
        if (!featureBrokenNametag) return;

        ItemStack first = event.getInventory().getFirstItem();
        ItemStack second = event.getInventory().getSecondItem();
        if (isBrokenNametag(first) || isBrokenNametag(second)) {
            event.setResult(null);
        }
    }

    private void handleWardenDeathMessage(LivingEntity entity, EntityDeathEvent event) {
        if (!(entity instanceof Player player) || !(event instanceof PlayerDeathEvent playerDeathEvent)) return;

        EntityDamageEvent damageEvent = entity.getLastDamageCause();
        if (!(damageEvent instanceof EntityDamageByEntityEvent entityDamage)) return;
        if (!(entityDamage.getDamager() instanceof Warden)) return;

        // Use displayName so nickname-hiding plugins can keep the real name private.
        Component shownName = player.displayName();
        String key = wardenDeathMessageKeys.get(
                ThreadLocalRandom.current().nextInt(wardenDeathMessageKeys.size()));
        String template = messages.get(player, key);

        Component customMessage;
        int playerIndex = template.indexOf("{player}");
        if (playerIndex >= 0) {
            customMessage = LEGACY.deserialize(template.substring(0, playerIndex))
                    .append(shownName)
                    .append(LEGACY.deserialize(template.substring(playerIndex + "{player}".length())));
        } else {
            customMessage = LEGACY.deserialize(template);
        }
        customMessage = customMessage.color(NamedTextColor.WHITE)
                .decoration(TextDecoration.ITALIC, false);

        playerDeathEvent.deathMessage(customMessage);
    }

    private void handleBrokenNametag(LivingEntity entity, EntityDeathEvent event) {
        if (entity.getCustomName() == null || entity instanceof Player) return;

        String customName = entity.getCustomName();
        OfflinePlayer owner = getTamerOf(entity);
        ItemStack brokenTag = createBrokenNametag(entity, owner);

        if (owner != null && owner.getPlayer() != null) {
            Player onlineOwner = owner.getPlayer();
            giveOrDrop(onlineOwner, brokenTag);
            onlineOwner.sendMessage(LEGACY.deserialize(
                    messages.format(onlineOwner, "tag-received", "{name}", customName)));
            plugin.playSoundToPlayer(onlineOwner, "tag.received");
        } else {
            entity.getWorld().dropItemNaturally(entity.getLocation(), brokenTag);
        }
    }

    private OfflinePlayer getTamerOf(LivingEntity entity) {
        if (entity instanceof Tameable tameable && tameable.getOwner() instanceof OfflinePlayer owner) {
            return owner;
        }
        return null;
    }

    private ItemStack createBrokenNametag(LivingEntity entity, OfflinePlayer owner) {
        Locale locale = owner != null && owner.getPlayer() != null ? owner.getPlayer().locale() : null;
        String customName = entity.getCustomName();
        String mobType = entity.getType().name();
        Component deathLine = buildDeathLine(entity, locale);

        ItemStack tag = new ItemStack(Material.NAME_TAG);
        ItemMeta meta = tag.getItemMeta();
        if (meta != null) {
            meta.getPersistentDataContainer().set(BROKEN_NAMETAG_KEY, PersistentDataType.BYTE, (byte) 1);
            meta.getPersistentDataContainer().set(MOB_TYPE_KEY, PersistentDataType.STRING, mobType);
            Instant deathTime = Instant.now();
            meta.getPersistentDataContainer().set(DEATH_TIME_KEY, PersistentDataType.LONG,
                    deathTime.toEpochMilli());
            meta.getPersistentDataContainer().set(DEATH_MESSAGE_KEY, PersistentDataType.STRING,
                    PlainTextComponentSerializer.plainText().serialize(deathLine));
            meta.displayName(Component.text(customName)
                    .color(NamedTextColor.DARK_GRAY).decorate(TextDecoration.ITALIC));

            List<Component> lore = new ArrayList<>();
            lore.add(buildMobLine(locale, mobType, owner));
            lore.add(LEGACY.deserialize(messages.get(locale, "tag-death-date")
                    .replace("{date}", MEMORY_DATE.format(deathTime))));
            if (brokenNametagIncludeDeathCause) {
                lore.add(deathLine);
            }
            lore.add(LEGACY.deserialize(messages.get(locale, "tag-memory")));
            meta.lore(lore);
            tag.setItemMeta(meta);
        }
        return tag;
    }

    private Component buildMobLine(Locale locale, String mobType, OfflinePlayer owner) {
        String ownerName = null;
        if (owner != null) {
            Player online = owner.getPlayer();
            if (owner.getName() != null) ownerName = owner.getName();
            else if (online != null) ownerName = online.getName();
        }
        boolean hasOwner = ownerName != null;

        String template = messages.get(locale, hasOwner ? "tag-mob-of-owner" : "tag-mob-line");
        Component mobName = Component.translatable("entity.minecraft." + mobType.toLowerCase(Locale.ROOT))
                .color(NamedTextColor.GRAY);
        String[] parts = template.split("\\{mob\\}", -1);
        Component line = Component.empty();
        for (int index = 0; index < parts.length; index++) {
            String segment = hasOwner ? parts[index].replace("{owner}", ownerName) : parts[index];
            if (!segment.isEmpty()) line = line.append(LEGACY.deserialize(segment));
            if (index < parts.length - 1) line = line.append(mobName);
        }
        return line.colorIfAbsent(NamedTextColor.GRAY);
    }

    private Component buildDeathLine(LivingEntity entity, Locale locale) {
        String key = "death-generic";
        Entity damager = null;
        String context = "";

        EntityDamageEvent damage = entity.getLastDamageCause();
        EntityDamageEvent.DamageCause cause = damage != null ? damage.getCause() : null;
        if (cause != null) {
            switch (cause) {
                case LAVA -> { key = "death-lava"; context = "lava-context"; }
                case FIRE, FIRE_TICK -> { key = "death-fire"; context = "fire-context"; }
                case DROWNING -> { key = "death-drowning"; context = "drowning-context"; }
                case FALL -> { key = "death-fall"; context = "fall-context"; }
                case VOID -> { key = "death-void"; context = "void-context"; }
                case SUFFOCATION -> { key = "death-suffocation"; context = "suffocation-context"; }
                case CRAMMING -> { key = "death-cramming"; context = "cramming-context"; }
                case LIGHTNING -> { key = "death-lightning"; context = "lightning-context"; }
                case STARVATION -> { key = "death-starvation"; context = "starvation-context"; }
                case POISON -> { key = "death-poison"; context = "poison-context"; }
                case MAGIC -> { key = "death-magic"; context = "magic-context"; }
                case WITHER -> { key = "death-wither"; context = "wither-context"; }
                case FALLING_BLOCK -> { key = "death-falling-block"; context = "falling-block-context"; }
                case ENTITY_ATTACK, ENTITY_SWEEP_ATTACK, PROJECTILE -> {
                    key = cause == EntityDamageEvent.DamageCause.PROJECTILE ? "death-shot" : "death-slain";
                    context = cause == EntityDamageEvent.DamageCause.PROJECTILE
                            ? "shot-context" : "slain-context";
                    if (damage instanceof EntityDamageByEntityEvent byEntity) {
                        damager = byEntity.getDamager();
                        if (damager instanceof Projectile projectile
                                && projectile.getShooter() instanceof LivingEntity shooter) {
                            damager = shooter;
                        }
                    }
                }
                default -> {
                    if (cause.name().contains("EXPLOSION")) {
                        key = "death-explosion";
                        context = "explosion-context";
                    }
                }
            }
        }

        Component line = Component.empty().color(NamedTextColor.RED).decorate(TextDecoration.ITALIC);
        if (entity.getCustomName() != null && !entity.getCustomName().isBlank()) {
            line = line.append(Component.text(entity.getCustomName())).append(Component.space());
        }

        String narrativeKey = key + "-narrative";
        String template = messages.get(locale, narrativeKey);
        if (template == null || template.equals(narrativeKey)) template = messages.get(locale, key);

        Matcher matcher = DEATH_PLACEHOLDER_PATTERN.matcher(template);
        int previousEnd = 0;
        while (matcher.find()) {
            if (matcher.start() > previousEnd) {
                line = line.append(LEGACY.deserialize(template.substring(previousEnd, matcher.start())));
            }
            Component replacement = "killer".equals(matcher.group(1))
                    ? killerComponent(damager)
                    : Component.text(messages.get(locale, context));
            line = line.append(replacement);
            previousEnd = matcher.end();
        }
        if (previousEnd < template.length()) {
            line = line.append(LEGACY.deserialize(template.substring(previousEnd)));
        }
        return line;
    }

    private Component killerComponent(Entity damager) {
        if (damager == null) return Component.text("?");
        String name = damager.getCustomName();
        if (name != null && !name.isBlank()) return LEGACY.deserialize(name);
        return Component.translatable("entity.minecraft." + damager.getType().name().toLowerCase(Locale.ROOT));
    }

    private boolean isBrokenNametag(ItemStack item) {
        if (item == null || item.getType() != Material.NAME_TAG) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;
        Byte value = meta.getPersistentDataContainer().get(BROKEN_NAMETAG_KEY, PersistentDataType.BYTE);
        return value != null && value == 1;
    }

    private void giveOrDrop(Player player, ItemStack item) {
        if (item == null || item.getType().isAir() || item.getAmount() <= 0) return;
        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(item);
        for (ItemStack leftover : leftovers.values()) {
            if (leftover != null && !leftover.getType().isAir() && leftover.getAmount() > 0) {
                player.getWorld().dropItemNaturally(player.getLocation(), leftover);
            }
        }
    }
}
