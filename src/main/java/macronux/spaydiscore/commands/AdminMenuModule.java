package macronux.spaydiscore.commands;

import macronux.spaydiscore.SpaydisCorePlugin;
import macronux.spaydiscore.features.allergy.NetheriteAllergyModule;
import macronux.spaydiscore.features.entities.EntityBehaviorModule;
import macronux.spaydiscore.features.limbo.LimboManager;
import macronux.spaydiscore.features.portals.EndPortalManager;
import macronux.spaydiscore.features.portals.NetherPortalManager;
import macronux.spaydiscore.features.schizophrenia.SchizophreniaModule;
import macronux.spaydiscore.features.undead.UndeadCurseModule;
import macronux.spaydiscore.messages.MessageManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Chest-GUI for admins: portal modes, player management, reload and quick status. */
public final class AdminMenuModule implements Listener {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private static final String PERMISSION = "spaydiscore.admin";
    private static final int PLAYERS_PER_PAGE = 45;

    private static final int MAIN_NETHER = 10;
    private static final int MAIN_END = 12;
    private static final int MAIN_PLAYERS = 14;
    private static final int MAIN_RELOAD = 16;
    private static final int MAIN_LIMBO = 20;
    private static final int MAIN_CYCLING = 22;
    private static final int MAIN_STATS = 24;
    private static final int MAIN_CLOSE = 31;

    private static final int LIST_PREVIOUS = 45;
    private static final int LIST_BACK = 49;
    private static final int LIST_NEXT = 53;

    private static final int PLAYER_HEAD = 4;
    private static final int PLAYER_LIMBO = 10;
    private static final int PLAYER_SKIP = 11;
    private static final int PLAYER_SPECTATE = 12;
    private static final int PLAYER_SCHIZO = 13;
    private static final int PLAYER_ALLERGY = 14;
    private static final int PLAYER_UNDEAD = 15;
    private static final int PLAYER_TELEPORT = 16;
    private static final int PLAYER_BACK = 22;

    private enum Screen { MAIN, PLAYERS, PLAYER }

    /** Identifies our inventories by holder, never by title. */
    private static final class MenuHolder implements InventoryHolder {
        private final Screen screen;
        private final int page;
        private final UUID target;
        private Inventory inventory;

        private MenuHolder(Screen screen, int page, UUID target) {
            this.screen = screen;
            this.page = page;
            this.target = target;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final SpaydisCorePlugin plugin;
    private final MessageManager messages;
    private final NetherPortalManager netherPortalManager;
    private final EndPortalManager endPortalManager;
    private final LimboManager limboManager;
    private final EntityBehaviorModule entityModule;
    private final NetheriteAllergyModule allergyModule;
    private final UndeadCurseModule undeadModule;
    private final SchizophreniaModule schizophreniaModule;

    public AdminMenuModule(SpaydisCorePlugin plugin, MessageManager messages,
                           NetherPortalManager netherPortalManager, EndPortalManager endPortalManager,
                           LimboManager limboManager, EntityBehaviorModule entityModule,
                           NetheriteAllergyModule allergyModule, UndeadCurseModule undeadModule,
                           SchizophreniaModule schizophreniaModule) {
        this.plugin = plugin;
        this.messages = messages;
        this.netherPortalManager = netherPortalManager;
        this.endPortalManager = endPortalManager;
        this.limboManager = limboManager;
        this.entityModule = entityModule;
        this.allergyModule = allergyModule;
        this.undeadModule = undeadModule;
        this.schizophreniaModule = schizophreniaModule;
    }

    public void openMain(Player viewer) {
        MenuHolder holder = new MenuHolder(Screen.MAIN, 0, null);
        Inventory inventory = create(holder, 36, viewer, "menu-title-main");
        NetherPortalManager.PortalMode nether = netherPortalManager.getMode();
        EndPortalManager.PortalMode end = endPortalManager.getMode();

        inventory.setItem(MAIN_NETHER, item(Material.OBSIDIAN, text(viewer, "menu-nether-name"), List.of(
                text(viewer, "menu-mode-line", "{mode}", modeLabel(viewer, nether.configValue())),
                text(viewer, "menu-click-cycle"))));
        inventory.setItem(MAIN_END, item(Material.END_PORTAL_FRAME, text(viewer, "menu-end-name"), List.of(
                text(viewer, "menu-mode-line", "{mode}", modeLabel(viewer, end.configValue())),
                text(viewer, "menu-click-cycle"))));
        inventory.setItem(MAIN_PLAYERS, item(Material.PLAYER_HEAD, text(viewer, "menu-players-name"), List.of(
                text(viewer, "menu-players-lore", "{count}", String.valueOf(Bukkit.getOnlinePlayers().size())))));
        inventory.setItem(MAIN_RELOAD, item(Material.REDSTONE, text(viewer, "menu-reload-name"), List.of(
                text(viewer, "menu-reload-lore"))));
        inventory.setItem(MAIN_LIMBO, item(Material.ENDER_EYE, text(viewer, "menu-limbo-name"), List.of(
                text(viewer, "menu-limbo-lore", "{world}", limboManager.getWorldName(),
                        "{count}", String.valueOf(limboManager.getOnlinePlayerCount())))));
        inventory.setItem(MAIN_CYCLING, item(Material.SHULKER_SHELL, text(viewer, "menu-cycling-name"), List.of(
                text(viewer, "menu-cycling-lore", "{count}", String.valueOf(entityModule.cyclingEntities().size())),
                text(viewer, "menu-cycling-stop"))));
        inventory.setItem(MAIN_STATS, item(Material.ROTTEN_FLESH, text(viewer, "menu-stats-name"), List.of(
                text(viewer, "menu-stats-allergy", "{count}", String.valueOf(allergyModule.allergicPlayers().size())),
                text(viewer, "menu-stats-undead", "{count}", String.valueOf(undeadModule.cursedPlayers().size())),
                text(viewer, "menu-stats-schizo", "{count}", String.valueOf(schizophreniaModule.affectedPlayers().size())))));
        inventory.setItem(MAIN_CLOSE, item(Material.BARRIER, text(viewer, "menu-close"), List.of()));
        viewer.openInventory(inventory);
    }

    private void openPlayers(Player viewer, int requestedPage) {
        List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());
        players.sort(Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER));
        int pages = Math.max(1, (players.size() + PLAYERS_PER_PAGE - 1) / PLAYERS_PER_PAGE);
        int page = Math.max(0, Math.min(requestedPage, pages - 1));

        MenuHolder holder = new MenuHolder(Screen.PLAYERS, page, null);
        Inventory inventory = create(holder, 54, viewer, "menu-title-players");
        int start = page * PLAYERS_PER_PAGE;
        for (int index = 0; index < PLAYERS_PER_PAGE && start + index < players.size(); index++) {
            Player player = players.get(start + index);
            inventory.setItem(index, head(player, "&e" + player.getName(), stateLines(viewer, player)));
        }
        if (page > 0) {
            inventory.setItem(LIST_PREVIOUS, item(Material.ARROW, text(viewer, "menu-previous"), List.of()));
        }
        inventory.setItem(LIST_BACK, item(Material.BARRIER, text(viewer, "menu-back"),
                List.of(text(viewer, "menu-page", "{page}", String.valueOf(page + 1),
                        "{pages}", String.valueOf(pages)))));
        if (page + 1 < pages) {
            inventory.setItem(LIST_NEXT, item(Material.ARROW, text(viewer, "menu-next"), List.of()));
        }
        viewer.openInventory(inventory);
    }

    private void openPlayer(Player viewer, Player target) {
        MenuHolder holder = new MenuHolder(Screen.PLAYER, 0, target.getUniqueId());
        Inventory inventory = create(holder, 27, viewer, "menu-title-player", "{player}", target.getName());
        boolean inLimbo = limboManager.isLimbo(target);
        boolean allergic = allergyModule.allergicPlayers().contains(target.getUniqueId());
        boolean cursed = undeadModule.cursedPlayers().contains(target.getUniqueId());
        boolean haunted = schizophreniaModule.isAffected(target.getUniqueId());

        inventory.setItem(PLAYER_HEAD, head(target, "&e" + target.getName(), stateLines(viewer, target)));
        inventory.setItem(PLAYER_LIMBO, inLimbo
                ? item(Material.TOTEM_OF_UNDYING, text(viewer, "menu-player-limbo-release"), List.of())
                : item(Material.ENDER_PEARL, text(viewer, "menu-player-limbo-enter"), List.of()));
        inventory.setItem(PLAYER_SKIP, item(Material.CLOCK, text(viewer, "menu-player-skip"),
                List.of(text(viewer, "menu-player-skip-lore"))));
        inventory.setItem(PLAYER_SPECTATE, item(Material.SPYGLASS, text(viewer, "menu-player-spectate"),
                List.of(text(viewer, "menu-player-spectate-lore"))));
        inventory.setItem(PLAYER_SCHIZO, item(Material.FERMENTED_SPIDER_EYE, text(viewer,
                haunted ? "menu-player-schizo-remove" : "menu-player-schizo-give"), List.of()));
        inventory.setItem(PLAYER_ALLERGY, item(Material.HONEY_BOTTLE, text(viewer,
                allergic ? "menu-player-allergy-remove" : "menu-player-allergy-give"), List.of()));
        inventory.setItem(PLAYER_UNDEAD, item(Material.ROTTEN_FLESH, text(viewer,
                cursed ? "menu-player-undead-remove" : "menu-player-undead-give"), List.of()));
        inventory.setItem(PLAYER_TELEPORT, item(Material.COMPASS, text(viewer, "menu-player-teleport"), List.of()));
        inventory.setItem(PLAYER_BACK, item(Material.BARRIER, text(viewer, "menu-back"), List.of()));
        viewer.openInventory(inventory);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof MenuHolder holder)) {
            return;
        }
        // Cancelling here also stops shift-clicks and hotbar swaps coming from the bottom inventory.
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player viewer)) {
            return;
        }
        if (!viewer.hasPermission(PERMISSION)) {
            viewer.closeInventory();
            return;
        }
        if (event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }
        int slot = event.getSlot();
        ClickType click = event.getClick();
        switch (holder.screen) {
            case MAIN -> handleMain(viewer, slot, click);
            case PLAYERS -> handlePlayers(viewer, holder, slot);
            case PLAYER -> handlePlayer(viewer, holder, slot);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof MenuHolder) {
            event.setCancelled(true);
        }
    }

    private void handleMain(Player viewer, int slot, ClickType click) {
        switch (slot) {
            case MAIN_NETHER -> {
                netherPortalManager.setMode(switch (netherPortalManager.getMode()) {
                    case ENABLED -> NetherPortalManager.PortalMode.OPENING;
                    case OPENING -> NetherPortalManager.PortalMode.DISABLED;
                    default -> NetherPortalManager.PortalMode.ENABLED;
                });
                later(() -> openMain(viewer));
            }
            case MAIN_END -> {
                endPortalManager.setMode(switch (endPortalManager.getMode()) {
                    case ENABLED -> EndPortalManager.PortalMode.OPENING;
                    case OPENING -> EndPortalManager.PortalMode.DISABLED;
                    default -> EndPortalManager.PortalMode.ENABLED;
                });
                later(() -> openMain(viewer));
            }
            case MAIN_PLAYERS -> later(() -> openPlayers(viewer, 0));
            case MAIN_RELOAD -> {
                plugin.reloadConfig();
                viewer.sendMessage(LEGACY.deserialize(text(viewer, "menu-reloaded")));
                later(() -> openMain(viewer));
            }
            case MAIN_CYCLING -> {
                if (click.isShiftClick()) {
                    for (EntityBehaviorModule.CyclingEntityInfo info : entityModule.cyclingEntities()) {
                        entityModule.stopCyclingEntity(info.uuid());
                    }
                    later(() -> openMain(viewer));
                }
            }
            case MAIN_CLOSE -> later(viewer::closeInventory);
            default -> { }
        }
    }

    private void handlePlayers(Player viewer, MenuHolder holder, int slot) {
        if (slot == LIST_PREVIOUS) {
            later(() -> openPlayers(viewer, holder.page - 1));
        } else if (slot == LIST_NEXT) {
            later(() -> openPlayers(viewer, holder.page + 1));
        } else if (slot == LIST_BACK) {
            later(() -> openMain(viewer));
        } else if (slot >= 0 && slot < PLAYERS_PER_PAGE) {
            ItemStack clicked = viewer.getOpenInventory().getTopInventory().getItem(slot);
            if (clicked != null && clicked.getItemMeta() instanceof SkullMeta skull
                    && skull.getOwningPlayer() != null) {
                Player target = Bukkit.getPlayer(skull.getOwningPlayer().getUniqueId());
                if (target != null) {
                    later(() -> openPlayer(viewer, target));
                }
            }
        }
    }

    private void handlePlayer(Player viewer, MenuHolder holder, int slot) {
        Player target = holder.target == null ? null : Bukkit.getPlayer(holder.target);
        if (slot == PLAYER_BACK) {
            later(() -> openPlayers(viewer, 0));
            return;
        }
        if (target == null) {
            viewer.sendMessage(LEGACY.deserialize(text(viewer, "menu-player-offline")));
            later(() -> openPlayers(viewer, 0));
            return;
        }
        boolean ok = true;
        switch (slot) {
            case PLAYER_LIMBO -> {
                if (limboManager.isLimbo(target)) {
                    ok = limboManager.release(target) == LimboManager.ReleaseResult.RELEASED;
                } else {
                    ok = limboManager.enter(target) == LimboManager.EnterResult.ENTERED;
                }
            }
            case PLAYER_SKIP -> ok = limboManager.skipRejection(target);
            case PLAYER_SPECTATE -> {
                LimboManager.SpectateResult result = limboManager.toggleSpectate(target);
                ok = result == LimboManager.SpectateResult.SPECTATING
                        || result == LimboManager.SpectateResult.RETURNED_TO_LIMBO;
            }
            case PLAYER_ALLERGY -> {
                if (!allergyModule.isEnabled()) {
                    ok = false;
                } else if (allergyModule.allergicPlayers().contains(target.getUniqueId())) {
                    allergyModule.remove(target.getUniqueId());
                } else {
                    allergyModule.give(target, 1);
                }
            }
            case PLAYER_SCHIZO -> {
                if (schizophreniaModule.isAffected(target.getUniqueId())) {
                    schizophreniaModule.remove(target);
                } else {
                    schizophreniaModule.give(target);
                }
            }
            case PLAYER_UNDEAD -> {
                if (undeadModule.cursedPlayers().contains(target.getUniqueId())) {
                    undeadModule.remove(target);
                } else {
                    undeadModule.give(target);
                }
            }
            case PLAYER_TELEPORT -> {
                later(() -> {
                    viewer.closeInventory();
                    viewer.teleport(target.getLocation());
                });
                return;
            }
            default -> {
                return;
            }
        }
        if (!ok) {
            viewer.sendMessage(LEGACY.deserialize(text(viewer, "menu-action-failed", "{player}", target.getName())));
        }
        later(() -> openPlayer(viewer, target));
    }

    private List<String> stateLines(Player viewer, Player target) {
        List<String> lines = new ArrayList<>();
        lines.add(text(viewer, "menu-state-limbo", "{state}", yesNo(viewer, limboManager.isLimbo(target))));
        lines.add(text(viewer, "menu-state-allergy", "{state}",
                yesNo(viewer, allergyModule.allergicPlayers().contains(target.getUniqueId()))));
        lines.add(text(viewer, "menu-state-undead", "{state}",
                yesNo(viewer, undeadModule.cursedPlayers().contains(target.getUniqueId()))));
        lines.add(text(viewer, "menu-state-schizo", "{state}",
                yesNo(viewer, schizophreniaModule.isAffected(target.getUniqueId()))));
        return lines;
    }

    private String yesNo(Player viewer, boolean value) {
        return text(viewer, value ? "menu-yes" : "menu-no");
    }

    private String modeLabel(Player viewer, String configValue) {
        return switch (configValue.toLowerCase(java.util.Locale.ROOT)) {
            case "disabled", "off" -> text(viewer, "menu-mode-disabled");
            case "opening" -> text(viewer, "menu-mode-opening");
            default -> text(viewer, "menu-mode-enabled");
        };
    }

    private Inventory create(MenuHolder holder, int size, Player viewer, String titleKey, Object... placeholders) {
        Inventory inventory = Bukkit.createInventory(holder, size,
                LEGACY.deserialize(messages.format(viewer, titleKey, placeholders)));
        holder.inventory = inventory;
        return inventory;
    }

    private String text(Player viewer, String key, Object... placeholders) {
        return messages.format(viewer, key, placeholders);
    }

    private ItemStack item(Material material, String name, List<String> lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        decorate(meta, name, lore);
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack head(Player owner, String name, List<String> lore) {
        ItemStack stack = new ItemStack(Material.PLAYER_HEAD);
        if (stack.getItemMeta() instanceof SkullMeta meta) {
            meta.setOwningPlayer(owner);
            decorate(meta, name, lore);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private void decorate(ItemMeta meta, String name, List<String> lore) {
        meta.displayName(LEGACY.deserialize(name).decoration(TextDecoration.ITALIC, false));
        List<Component> lines = new ArrayList<>();
        for (String line : lore) {
            lines.add(LEGACY.deserialize(line).decoration(TextDecoration.ITALIC, false));
        }
        meta.lore(lines.isEmpty() ? null : lines);
    }

    /** Opening or closing from inside a click handler is deferred by a tick. */
    private void later(Runnable action) {
        Bukkit.getScheduler().runTask(plugin, action);
    }
}
