package macronux.spaydiscore.commands;

import macronux.spaydiscore.SpaydisCorePlugin;
import macronux.spaydiscore.features.allergy.NetheriteAllergyModule;
import macronux.spaydiscore.features.entities.EntityBehaviorModule;
import macronux.spaydiscore.features.limbo.LimbiificationManager;
import macronux.spaydiscore.features.limbo.LimboManager;
import macronux.spaydiscore.features.limbo.StarQuality;
import macronux.spaydiscore.features.portals.EndPortalManager;
import macronux.spaydiscore.features.portals.NetherPortalManager;
import macronux.spaydiscore.features.schizophrenia.SchizophreniaModule;
import macronux.spaydiscore.features.undead.UndeadCurseModule;
import macronux.spaydiscore.messages.MessageManager;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.*;
import java.util.stream.Collectors;

/** Routes commands to the portal, Limbo, entity, and allergy modules. */
public final class PluginCommandModule {
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private static final Key ENCHANTMENT_FONT = Key.key("minecraft", "alt");

    private final SpaydisCorePlugin plugin;
    private final MessageManager messages;
    private final NetherPortalManager netherPortalManager;
    private final EndPortalManager endPortalManager;
    private final LimbiificationManager limbiificationManager;
    private final LimboManager limboManager;
    private final EntityBehaviorModule entityModule;
    private final NetheriteAllergyModule allergyModule;
    private final UndeadCurseModule undeadModule;
    private final SchizophreniaModule schizophreniaModule;
    private final AdminMenuModule menuModule;

    public PluginCommandModule(SpaydisCorePlugin plugin, MessageManager messages,
                               NetherPortalManager netherPortalManager, EndPortalManager endPortalManager,
                               LimbiificationManager limbiificationManager, LimboManager limboManager,
                               EntityBehaviorModule entityModule, NetheriteAllergyModule allergyModule,
                               UndeadCurseModule undeadModule, SchizophreniaModule schizophreniaModule,
                               AdminMenuModule menuModule) {
        this.plugin = plugin;
        this.messages = messages;
        this.netherPortalManager = netherPortalManager;
        this.endPortalManager = endPortalManager;
        this.limbiificationManager = limbiificationManager;
        this.limboManager = limboManager;
        this.entityModule = entityModule;
        this.allergyModule = allergyModule;
        this.undeadModule = undeadModule;
        this.schizophreniaModule = schizophreniaModule;
        this.menuModule = menuModule;
    }

    public void register() {
        plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            event.registrar().register("enchantfont", new BasicCommand() {
                @Override
                public void execute(CommandSourceStack source, String[] args) {
                    executeCommand(source.getSender(), "enchantfont", args);
                }

                @Override
                public Collection<String> suggest(CommandSourceStack source, String[] args) {
                    return Collections.emptyList();
                }

                @Override
                public boolean canUse(CommandSender sender) {
                    return sender.hasPermission("spaydiscore.command.enchantfont");
                }

                @Override
                public String permission() {
                    return "spaydiscore.command.enchantfont";
                }
            });

            event.registrar().register("spaydiscore", List.of("sc"), new BasicCommand() {
                @Override
                public void execute(CommandSourceStack source, String[] args) {
                    executeCommand(source.getSender(), "spaydiscore", args);
                }

                @Override
                public Collection<String> suggest(CommandSourceStack source, String[] args) {
                    return completeCommand(source.getSender(), "spaydiscore", args);
                }

                @Override
                public boolean canUse(CommandSender sender) {
                    return sender.hasPermission("spaydiscore.admin");
                }

                @Override
                public String permission() {
                    return "spaydiscore.admin";
                }
            });
        });
    }

    private boolean executeCommand(CommandSender sender, String commandName, String[] args) {
        String cmdName = commandName.toLowerCase(Locale.ROOT);

        if (cmdName.equals("enchantfont")) {
            return handleEnchantFontCommand(sender, args);
        } else if (isAdminCommandName(cmdName)) {
            return handleAdminCommand(sender, args);
        }
        return false;
    }

    private boolean handleEnchantFontCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(LEGACY.deserialize(messages.get(sender, "enchantfont-players-only")));
            return true;
        }
        if (args.length == 0) {
            player.sendMessage(LEGACY.deserialize(messages.get(player, "enchantfont-usage")));
            return true;
        }

        String text = String.join(" ", args);
        String enchantedText = toEnchantFontText(text);
        player.sendMessage(Component.text(enchantedText)
                .font(ENCHANTMENT_FONT)
                .clickEvent(ClickEvent.copyToClipboard(enchantedText)));
        return true;
    }

    private String toEnchantFontText(String text) {
        StringBuilder result = new StringBuilder(text.length());
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if ((character >= 'A' && character <= 'Z')
                    || (character >= 'a' && character <= 'z')) {
                result.append((char) (0xE000 + character));
            } else {
                result.append(character);
            }
        }
        return result.toString();
    }

    private boolean isAdminCommandName(String commandName) {
        return commandName.equals("spaydiscore") || commandName.equals("sc");
    }

    private boolean handleAdminCommand(CommandSender sender, String[] args) {
        // Permission check for admin commands
        if (!sender.hasPermission("spaydiscore.admin")) {
            sender.sendMessage(LEGACY.deserialize("&cYou don't have permission to use admin commands."));
            return true;
        }

        if (args.length == 0) {
            sendAdminHelp(sender);
            return true;
        }

        String subCommand = args[0].toLowerCase(Locale.ROOT);

        switch (subCommand) {
            case "reload" -> {
                plugin.reloadConfig();
                sender.sendMessage(LEGACY.deserialize("&aSpaydisCore configuration reloaded!"));
                return true;
            }
            case "list" -> {
                return handleListCommand(sender);
            }
            case "stop" -> {
                return handleStopCommand(sender, args);
            }
            case "allergy" -> {
                return handleAllergyCommand(sender, args);
            }
            case "nether" -> {
                return handleNetherCommand(sender, args);
            }
            case "end" -> {
                return handleEndCommand(sender, args);
            }
            case "limbo" -> {
                return handleLimboCommand(sender, args);
            }
            case "debug" -> {
                return handleDebugCommand(sender, args);
            }
            case "limbification" -> {
                return handleLimbiificationCommand(sender, args);
            }
            case "undead" -> {
                return handleUndeadCommand(sender, args);
            }
            case "schizo" -> {
                return handleSchizophreniaCommand(sender, args);
            }
            case "menu" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(LEGACY.deserialize(messages.get(sender, "enchantfont-players-only")));
                    return true;
                }
                menuModule.openMain(player);
                return true;
            }
            default -> {
                sendAdminHelp(sender);
                return true;
            }
        }
    }

    private void sendAdminHelp(CommandSender sender) {
        sender.sendMessage(LEGACY.deserialize("&e=== &6SpaydisCore Admin Commands &e==="));
        sender.sendMessage(LEGACY.deserialize("&e/spaydiscore reload &7- Reload configuration and language files"));
        sender.sendMessage(LEGACY.deserialize("&e/spaydiscore list &7- List all active cycling entities"));
        sender.sendMessage(LEGACY.deserialize("&e/spaydiscore stop <entity-uuid> &7- Stop cycling for specific entity"));
        sender.sendMessage(LEGACY.deserialize("&e/spaydiscore allergy give <player> [stage] &7- Give allergy (stage 1 or 2)"));
        sender.sendMessage(LEGACY.deserialize("&e/spaydiscore allergy remove <player> &7- Remove allergy from player"));
        sender.sendMessage(LEGACY.deserialize("&e/spaydiscore allergy list &7- List all allergic players"));
        sender.sendMessage(LEGACY.deserialize("&e/spaydiscore nether <off|opening|on> &7- Control Nether portals"));
        sender.sendMessage(LEGACY.deserialize("&e/spaydiscore end <off|opening|on> &7- Control End portals"));
        sender.sendMessage(LEGACY.deserialize("&e/spaydiscore limbo leave <player> &7- Release a player from Limbo"));
        sender.sendMessage(LEGACY.deserialize("&e/spaydiscore limbo skip <player> &7- Skip to the Limbo rejection animation"));
        sender.sendMessage(LEGACY.deserialize("&e/spaydiscore limbo stars <on|off|low|normal|high|status> &7- Control local Limbo stars"));
        sender.sendMessage(LEGACY.deserialize(messages.get(sender, "admin.help-debug")));
        sender.sendMessage(LEGACY.deserialize("&e/sc limbification <1|2|3|off> [player] &7- Apply/remove Limbiification on the held item"));
        sender.sendMessage(LEGACY.deserialize(messages.get(sender, "admin.help-limbo-enter")));
        sender.sendMessage(LEGACY.deserialize(messages.get(sender, "admin.help-undead")));
        sender.sendMessage(LEGACY.deserialize(messages.get(sender, "admin.help-schizo")));
        sender.sendMessage(LEGACY.deserialize(messages.get(sender, "admin.help-menu")));
        sender.sendMessage(LEGACY.deserialize("&7Alias: &o/sc"));
    }

    private boolean handleNetherCommand(CommandSender sender, String[] args) {
        if (netherPortalManager == null) {
            sender.sendMessage(LEGACY.deserialize("&cNether portal manager is not ready."));
            return true;
        }
        if (args.length < 2 || args[1].isBlank() || args[1].equalsIgnoreCase("status")) {
            sender.sendMessage(LEGACY.deserialize("&eNether portal mode: &6"
                    + netherPortalManager.getMode().configValue()));
            sender.sendMessage(LEGACY.deserialize("&7Usage: &e/spaydiscore nether <off|opening|on>"));
            return true;
        }

        NetherPortalManager.PortalMode mode = switch (args[1].toLowerCase(Locale.ROOT)) {
            case "off" -> NetherPortalManager.PortalMode.DISABLED;
            case "opening" -> NetherPortalManager.PortalMode.OPENING;
            case "on" -> NetherPortalManager.PortalMode.ENABLED;
            default -> null;
        };
        if (mode == null) {
            sender.sendMessage(LEGACY.deserialize("&cUsage: /spaydiscore nether <off|opening|on>"));
            return true;
        }

        netherPortalManager.setMode(mode);
        sender.sendMessage(LEGACY.deserialize("&aNether portal mode set to &6"
                + mode.configValue() + "&a."));
        if (mode == NetherPortalManager.PortalMode.DISABLED) {
            sender.sendMessage(LEGACY.deserialize("&7Loaded portal blocks are being removed; frames are preserved."));
        } else if (mode == NetherPortalManager.PortalMode.OPENING) {
            sender.sendMessage(LEGACY.deserialize("&7New portals will open with the animation until an admin switches the mode."));
        }
        return true;
    }

    private boolean handleEndCommand(CommandSender sender, String[] args) {
        if (endPortalManager == null) {
            sender.sendMessage(LEGACY.deserialize("&cEnd portal manager is not ready."));
            return true;
        }
        if (args.length < 2 || args[1].isBlank() || args[1].equalsIgnoreCase("status")) {
            sender.sendMessage(LEGACY.deserialize("&eEnd portal mode: &6"
                    + endPortalManager.getMode().configValue()));
            sender.sendMessage(LEGACY.deserialize("&7Usage: &e/spaydiscore end <off|opening|on>"));
            return true;
        }

        EndPortalManager.PortalMode mode = switch (args[1].toLowerCase(Locale.ROOT)) {
            case "off" -> EndPortalManager.PortalMode.DISABLED;
            case "opening" -> EndPortalManager.PortalMode.OPENING;
            case "on" -> EndPortalManager.PortalMode.ENABLED;
            default -> null;
        };
        if (mode == null) {
            sender.sendMessage(LEGACY.deserialize("&cUsage: /spaydiscore end <off|opening|on>"));
            return true;
        }

        endPortalManager.setMode(mode);
        sender.sendMessage(LEGACY.deserialize("&aEnd portal mode set to &6"
                + mode.configValue() + "&a."));
        if (mode == EndPortalManager.PortalMode.DISABLED) {
            sender.sendMessage(LEGACY.deserialize("&7Loaded End portal blocks are being removed; frames are preserved."));
        } else if (mode == EndPortalManager.PortalMode.OPENING) {
            sender.sendMessage(LEGACY.deserialize("&7New End portals will use one of three particle animations."));
        }
        return true;
    }

    private boolean handleLimboCommand(CommandSender sender, String[] args) {
        if (limboManager == null) {
            sender.sendMessage(LEGACY.deserialize(messages.get(sender, "limbo-unavailable")));
            return true;
        }
        if (args.length < 2 || args[1].isBlank() || args[1].equalsIgnoreCase("status")) {
            sender.sendMessage(LEGACY.deserialize(messages.format(sender, "limbo-status",
                    "{world}", limboManager.getWorldName(),
                    "{count}", String.valueOf(limboManager.getOnlinePlayerCount()))));
            sender.sendMessage(LEGACY.deserialize(messages.get(sender, "limbo-usage")));
            return true;
        }

        String action = args[1].toLowerCase(Locale.ROOT);
        if (action.equals("stars")) {
            return handleLimboStarsCommand(sender, args);
        }
        if (action.equals("skip")) {
            return handleLimboSkipCommand(sender, args);
        }
        if (action.equals("phrases")) {
            return handleLimboPhrasesCommand(sender, args);
        }
        if (action.equals("enter")) {
            return handleLimboEnterCommand(sender, args);
        }
        if (action.equals("spectate")) {
            return handleLimboSpectateCommand(sender, args);
        }
        if (action.equals("list")) {
            return handleLimboListCommand(sender);
        }
        if (!action.equals("leave")) {
            sender.sendMessage(LEGACY.deserialize(messages.get(sender, "limbo-usage")));
            return true;
        }
        if (args.length < 3 || args[2].isBlank()) {
            sender.sendMessage(LEGACY.deserialize(messages.get(sender, "limbo-usage")));
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[2]);
        if (target == null) {
            // Offline: the release is queued and carried out when they next log in.
            LimboManager.OfflineReleaseResult queued = limboManager.queueOfflineRelease(args[2]);
            String shown = limboManager.rosterName(args[2]);
            switch (queued) {
                case QUEUED -> sender.sendMessage(LEGACY.deserialize(messages.format(sender,
                        "limbo-release-queued", "{player}", shown)));
                case ALREADY_QUEUED -> sender.sendMessage(LEGACY.deserialize(messages.format(sender,
                        "limbo-release-already-queued", "{player}", shown)));
                case UNKNOWN -> sender.sendMessage(LEGACY.deserialize(messages.format(sender,
                        "limbo-player-not-found", "{player}", args[2])));
            }
            return true;
        }

        LimboManager.ReleaseResult result = limboManager.release(target);
        switch (result) {
            case RELEASED -> sender.sendMessage(LEGACY.deserialize(messages.format(sender,
                    "limbo-release-success", "{player}", target.getName())));
            case NOT_IN_LIMBO -> sender.sendMessage(LEGACY.deserialize(messages.format(sender,
                    "limbo-not-in", "{player}", target.getName())));
            case RETURN_WORLD_MISSING -> sender.sendMessage(LEGACY.deserialize(messages.format(sender,
                    "limbo-return-world-missing", "{world}", limboManager.getReturnWorldName())));
            case TELEPORT_FAILED -> sender.sendMessage(LEGACY.deserialize(messages.format(sender,
                    "limbo-release-failed", "{player}", target.getName())));
        }
        return true;
    }

    /** Everybody in Limbo: online names green, offline gray; a click releases the player. */
    private boolean handleLimboListCommand(CommandSender sender) {
        List<LimboManager.RosterEntry> entries = limboManager.listLimboPlayers();
        if (entries.isEmpty()) {
            sender.sendMessage(LEGACY.deserialize(messages.get(sender, "limbo-list-empty")));
            return true;
        }
        sender.sendMessage(LEGACY.deserialize(messages.format(sender, "limbo-list-header",
                "{count}", String.valueOf(entries.size()))));
        for (LimboManager.RosterEntry entry : entries) {
            Component line = Component.text(" - ")
                    .color(NamedTextColor.DARK_GRAY)
                    .append(Component.text(entry.name())
                            .color(entry.online() ? NamedTextColor.GREEN : NamedTextColor.GRAY)
                            .hoverEvent(HoverEvent.showText(LEGACY.deserialize(messages.format(sender,
                                    "limbo-list-hover", "{player}", entry.name()))))
                            .clickEvent(ClickEvent.runCommand("/spaydiscore limbo leave " + entry.name())));
            if (entry.queued()) {
                line = line.append(LEGACY.deserialize(messages.get(sender, "limbo-list-queued")));
            }
            sender.sendMessage(line);
        }
        return true;
    }

    private boolean handleDebugCommand(CommandSender sender, String[] args) {
        if (limboManager == null || args.length < 3
                || !args[1].equalsIgnoreCase("limbo")) {
            sender.sendMessage(LEGACY.deserialize(messages.get(sender, "limbo-debug-usage")));
            return true;
        }

        String action = args[2].toLowerCase(Locale.ROOT);
        if (action.equals("breath")) {
            if (args.length != 3) {
                sender.sendMessage(LEGACY.deserialize(messages.get(sender, "limbo-debug-usage")));
                return true;
            }
            sender.sendMessage(LEGACY.deserialize(messages.get(sender,
                    limboManager.debugTriggerVoidBreath()
                            ? "limbo-debug-breath-started" : "limbo-debug-no-players")));
            return true;
        }
        if (!action.equals("silhouette") && !action.equals("step")
                && !action.equals("phrase")) {
            sender.sendMessage(LEGACY.deserialize(messages.get(sender, "limbo-debug-usage")));
            return true;
        }
        if (args.length > 4 || (args.length < 4 && !(sender instanceof Player))) {
            sender.sendMessage(LEGACY.deserialize(messages.get(sender, "limbo-debug-usage")));
            return true;
        }

        Player target = resolveDebugPlayer(sender, args, action.equals("silhouette"));
        if (target == null) {
            if (args.length == 4) {
                sender.sendMessage(LEGACY.deserialize(messages.format(sender,
                        "limbo-player-not-found", "{player}", args[3])));
            } else {
                sender.sendMessage(LEGACY.deserialize(messages.get(sender, "limbo-debug-no-players")));
            }
            return true;
        }
        if (!limboManager.isLimbo(target) || !plugin.isLimboWorld(target.getWorld())) {
            sender.sendMessage(LEGACY.deserialize(messages.format(sender,
                    "limbo-not-in", "{player}", target.getName())));
            return true;
        }

        boolean triggered = switch (action) {
            case "silhouette" -> limboManager.debugTriggerDistantSilhouette(target);
            case "step" -> limboManager.debugTriggerDistortedStepEcho(target);
            case "phrase" -> debugPlayLatestPhrase(target);
            default -> false;
        };
        if (!triggered) {
            sender.sendMessage(LEGACY.deserialize(messages.get(sender,
                    action.equals("phrase") ? "limbo-debug-no-phrase" : "limbo-debug-failed")));
            return true;
        }
        String messageKey = switch (action) {
            case "silhouette" -> "limbo-debug-silhouette-started";
            case "step" -> "limbo-debug-step-started";
            default -> "limbo-debug-phrase-started";
        };
        sender.sendMessage(LEGACY.deserialize(messages.format(sender, messageKey,
                "{player}", target.getName())));
        return true;
    }

    private Player resolveDebugPlayer(CommandSender sender, String[] args, boolean allowFirstLimboPlayer) {
        if (args.length == 4) {
            return Bukkit.getPlayerExact(args[3]);
        }
        if (sender instanceof Player player) {
            return player;
        }
        if (allowFirstLimboPlayer) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (limboManager.isLimbo(player) && plugin.isLimboWorld(player.getWorld())) {
                    return player;
                }
            }
        }
        return null;
    }

    private boolean debugPlayLatestPhrase(Player target) {
        Object integration = plugin.getLimboVoiceChatIntegration();
        if (integration == null) {
            return false;
        }
        try {
            return (boolean) integration.getClass()
                    .getMethod("debugPlayLatestPhrase", Player.class)
                    .invoke(integration, target);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException exception) {
            plugin.getLogger().warning("Could not trigger Limbo voice phrase debug playback: "
                    + exception.getMessage());
            return false;
        }
    }

    private boolean handleLimboSkipCommand(CommandSender sender, String[] args) {
        if (args.length != 3 || args[2].isBlank()) {
            sender.sendMessage(LEGACY.deserialize(messages.get(sender, "limbo-skip-usage")));
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[2]);
        if (target == null) {
            sender.sendMessage(LEGACY.deserialize(messages.format(sender,
                    "limbo-player-not-found", "{player}", args[2])));
            return true;
        }
        if (!limboManager.skipRejection(target)) {
            sender.sendMessage(LEGACY.deserialize(messages.format(sender,
                    "limbo-skip-failed", "{player}", target.getName())));
            return true;
        }
        sender.sendMessage(LEGACY.deserialize(messages.format(sender,
                "limbo-skip-success", "{player}", target.getName())));
        return true;
    }

    private boolean handleLimboStarsCommand(CommandSender sender, String[] args) {
        if (args.length < 3 || args.length > 4 || args[2].isBlank()) {
            sender.sendMessage(LEGACY.deserialize(messages.get(sender, "limbo-stars-usage")));
            return true;
        }

        Player target;
        if (args.length == 4) {
            target = Bukkit.getPlayerExact(args[3]);
            if (target == null) {
                sender.sendMessage(LEGACY.deserialize(messages.format(sender,
                        "limbo-player-not-found", "{player}", args[3])));
                return true;
            }
        } else if (sender instanceof Player player) {
            target = player;
        } else {
            sender.sendMessage(LEGACY.deserialize(messages.get(sender, "limbo-stars-usage")));
            return true;
        }

        if (args[2].equalsIgnoreCase("status")) {
            sender.sendMessage(LEGACY.deserialize(messages.format(sender, "limbo-stars-status",
                    "{player}", target.getName(),
                    "{state}", messages.get(sender,
                            limboManager.areLocalStarsEnabled(target)
                                    ? "limbo-stars-on" : "limbo-stars-off"))));
            sender.sendMessage(LEGACY.deserialize(messages.get(sender, "limbo-stars-usage")));
            sender.sendMessage(LEGACY.deserialize(messages.format(sender, "limbo-stars-quality-status",
                    "{quality}", messages.get(sender, "limbo-stars-quality-"
                            + limboManager.getStarQuality(target).name().toLowerCase(Locale.ROOT)))));
            return true;
        }

        StarQuality quality = StarQuality.parse(args[2]);
        if (quality != null) {
            limboManager.setStarQuality(target, quality);
            sender.sendMessage(LEGACY.deserialize(messages.format(sender, "limbo-stars-quality-set",
                    "{player}", target.getName(), "{quality}", messages.get(sender,
                            "limbo-stars-quality-" + quality.name().toLowerCase(Locale.ROOT)))));
            return true;
        }
        Boolean enabled = switch (args[2].toLowerCase(Locale.ROOT)) {
            case "on", "enable", "enabled" -> true;
            case "off", "disable", "disabled" -> false;
            default -> null;
        };
        if (enabled == null) {
            sender.sendMessage(LEGACY.deserialize(messages.get(sender, "limbo-stars-usage")));
            return true;
        }

        limboManager.setLocalStarsEnabled(target, enabled);
        sender.sendMessage(LEGACY.deserialize(messages.format(sender,
                enabled ? "limbo-stars-set-on" : "limbo-stars-set-off",
                "{player}", target.getName())));
        return true;
    }

    private boolean handleLimboPhrasesCommand(CommandSender sender, String[] args) {
        if (args.length != 3 || args[2].isBlank()) {
            sender.sendMessage(LEGACY.deserialize(messages.get(sender, "limbo-phrases-usage")));
            return true;
        }
        Object integration = plugin.getLimboVoiceChatIntegration();
        if (integration == null) {
            sender.sendMessage(LEGACY.deserialize(messages.get(sender, "limbo-voice-unavailable")));
            return true;
        }

        String requested = args[2].toLowerCase(Locale.ROOT);
        if (!requested.equals("normal") && !requested.equals("barrel")
                && !requested.equals("underwater")
                && !requested.equals("status")) {
            sender.sendMessage(LEGACY.deserialize(messages.get(sender, "limbo-phrases-usage")));
            return true;
        }
        try {
            String mode;
            if (requested.equals("status")) {
                mode = (String) integration.getClass()
                        .getMethod("getPhraseMode")
                        .invoke(integration);
            } else {
                boolean changed = (boolean) integration.getClass()
                        .getMethod("setPhraseMode", String.class)
                        .invoke(integration, requested);
                if (!changed) {
                    sender.sendMessage(LEGACY.deserialize(
                            messages.get(sender, "limbo-phrases-usage")));
                    return true;
                }
                mode = requested;
            }
            String modeLabel = messages.get(sender,
                    mode.equalsIgnoreCase("barrel") || mode.equalsIgnoreCase("underwater")
                            ? "limbo-phrases-barrel" : "limbo-phrases-normal");
            sender.sendMessage(LEGACY.deserialize(messages.format(sender,
                    requested.equals("status") ? "limbo-phrases-status" : "limbo-phrases-mode-set",
                    "{mode}", modeLabel)));
        } catch (ReflectiveOperationException | LinkageError | RuntimeException exception) {
            plugin.getLogger().warning("Could not change Limbo phrase mode: " + exception.getMessage());
            sender.sendMessage(LEGACY.deserialize(messages.get(sender, "limbo-phrases-usage")));
        }
        return true;
    }

    private boolean handleLimboEnterCommand(CommandSender sender, String[] args) {
        if (args.length != 3 || args[2].isBlank()) {
            sender.sendMessage(LEGACY.deserialize(messages.get(sender, "limbo-enter-usage")));
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[2]);
        if (target == null) {
            sender.sendMessage(LEGACY.deserialize(messages.format(sender,
                    "limbo-player-not-found", "{player}", args[2])));
            return true;
        }
        LimboManager.EnterResult result = limboManager.enter(target);
        String key = switch (result) {
            case ENTERED -> "limbo-enter-success";
            case ALREADY_IN_LIMBO -> "limbo-enter-already";
            case UNAVAILABLE -> "limbo-unavailable";
            case TELEPORT_FAILED -> "limbo-enter-failed";
        };
        sender.sendMessage(LEGACY.deserialize(messages.format(sender, key, "{player}", target.getName())));
        return true;
    }

    private boolean handleLimboSpectateCommand(CommandSender sender, String[] args) {
        if (args.length > 3) {
            sender.sendMessage(LEGACY.deserialize(messages.get(sender, "limbo-spectate-usage")));
            return true;
        }
        Player target;
        if (args.length == 3) {
            target = Bukkit.getPlayerExact(args[2]);
            if (target == null) {
                sender.sendMessage(LEGACY.deserialize(messages.format(sender,
                        "limbo-player-not-found", "{player}", args[2])));
                return true;
            }
        } else if (sender instanceof Player player) {
            target = player;
        } else {
            sender.sendMessage(LEGACY.deserialize(messages.get(sender, "limbo-spectate-usage")));
            return true;
        }
        LimboManager.SpectateResult result = limboManager.toggleSpectate(target);
        String key = switch (result) {
            case SPECTATING -> "limbo-spectate-started";
            case RETURNED_TO_LIMBO -> "limbo-spectate-returned";
            case NOT_ADMIN -> "limbo-spectate-not-admin";
            case NOT_IN_LIMBO -> "limbo-not-in";
            case RETURN_WORLD_MISSING -> "limbo-return-world-missing";
            case TELEPORT_FAILED -> "limbo-release-failed";
            case UNAVAILABLE -> "limbo-unavailable";
        };
        sender.sendMessage(LEGACY.deserialize(messages.format(sender, key,
                "{player}", target.getName(), "{world}", limboManager.getReturnWorldName())));
        return true;
    }

    private boolean handleUndeadCommand(CommandSender sender, String[] args) {
        if (!undeadModule.isEnabled()) {
            sender.sendMessage(LEGACY.deserialize(messages.get(sender, "undead-disabled")));
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(LEGACY.deserialize(messages.get(sender, "undead-usage")));
            return true;
        }
        String sub = args[1].toLowerCase(Locale.ROOT);
        if (sub.equals("list")) {
            Set<UUID> cursedPlayers = undeadModule.cursedPlayers();
            if (cursedPlayers.isEmpty()) {
                sender.sendMessage(LEGACY.deserialize(messages.get(sender, "undead-list-empty")));
                return true;
            }
            sender.sendMessage(LEGACY.deserialize(messages.format(sender,
                    "undead-list-header", "{count}", String.valueOf(cursedPlayers.size()))));
            for (UUID id : cursedPlayers) {
                OfflinePlayer player = Bukkit.getOfflinePlayer(id);
                String name = player.getName() != null ? player.getName() : id.toString();
                sender.sendMessage(LEGACY.deserialize(messages.format(sender,
                        "undead-list-entry", "{player}", name)));
            }
            return true;
        }
        if ((!sub.equals("give") && !sub.equals("remove")) || args.length != 3) {
            sender.sendMessage(LEGACY.deserialize(messages.get(sender, "undead-usage")));
            return true;
        }
        OfflinePlayer target = Bukkit.getPlayerExact(args[2]);
        if (target == null) target = Bukkit.getOfflinePlayerIfCached(args[2]);
        if (target == null) {
            sender.sendMessage(LEGACY.deserialize(messages.format(sender,
                    "limbo-player-not-found", "{player}", args[2])));
            return true;
        }
        String shownName = target.getName() != null ? target.getName() : args[2];
        boolean changed = sub.equals("give") ? undeadModule.give(target) : undeadModule.remove(target);
        String key = sub.equals("give")
                ? (changed ? "undead-given" : "undead-already")
                : (changed ? "undead-removed" : "undead-not-cursed");
        sender.sendMessage(LEGACY.deserialize(messages.format(sender, key, "{player}", shownName)));
        return true;
    }

    private boolean handleSchizophreniaCommand(CommandSender sender, String[] args) {
        if (!schizophreniaModule.isEnabled()) {
            sender.sendMessage(LEGACY.deserialize(messages.get(sender, "schizo-disabled")));
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(LEGACY.deserialize(messages.get(sender, "schizo-usage")));
            return true;
        }
        String sub = args[1].toLowerCase(Locale.ROOT);
        if (sub.equals("list")) {
            Set<UUID> affected = schizophreniaModule.affectedPlayers();
            if (affected.isEmpty()) {
                sender.sendMessage(LEGACY.deserialize(messages.get(sender, "schizo-list-empty")));
                return true;
            }
            sender.sendMessage(LEGACY.deserialize(messages.format(sender,
                    "schizo-list-header", "{count}", String.valueOf(affected.size()))));
            for (UUID id : affected) {
                OfflinePlayer player = Bukkit.getOfflinePlayer(id);
                String name = player.getName() != null ? player.getName() : id.toString();
                sender.sendMessage(LEGACY.deserialize(messages.format(sender,
                        "schizo-list-entry", "{player}", name)));
            }
            return true;
        }
        if ((!sub.equals("give") && !sub.equals("remove")) || args.length != 3) {
            sender.sendMessage(LEGACY.deserialize(messages.get(sender, "schizo-usage")));
            return true;
        }
        OfflinePlayer target = Bukkit.getPlayerExact(args[2]);
        if (target == null) target = Bukkit.getOfflinePlayerIfCached(args[2]);
        if (target == null) {
            sender.sendMessage(LEGACY.deserialize(messages.format(sender,
                    "limbo-player-not-found", "{player}", args[2])));
            return true;
        }
        String shownName = target.getName() != null ? target.getName() : args[2];
        boolean changed = sub.equals("give") ? schizophreniaModule.give(target) : schizophreniaModule.remove(target);
        String key = sub.equals("give")
                ? (changed ? "schizo-given" : "schizo-already")
                : (changed ? "schizo-removed" : "schizo-not-affected");
        // The affected player is deliberately not told anything.
        sender.sendMessage(LEGACY.deserialize(messages.format(sender, key, "{player}", shownName)));
        return true;
    }

    private boolean handleLimbiificationCommand(CommandSender sender, String[] args) {
        if (limbiificationManager == null) {
            sender.sendMessage(LEGACY.deserialize("&cLimbiification manager is not ready."));
            return true;
        }
        if (args.length < 2 || args.length > 3) {
            sender.sendMessage(LEGACY.deserialize(messages.get(sender, "limbification-usage")));
            return true;
        }

        int level;
        String levelInput = args[1].toLowerCase(Locale.ROOT);
        if (levelInput.equals("off")) {
            level = 0;
        } else {
            try {
                level = Integer.parseInt(levelInput);
            } catch (NumberFormatException ignored) {
                sender.sendMessage(LEGACY.deserialize(messages.get(sender, "limbification-invalid-level")));
                return true;
            }
            if (level < 1 || level > 3) {
                sender.sendMessage(LEGACY.deserialize(messages.get(sender, "limbification-invalid-level")));
                return true;
            }
        }

        Player target;
        if (args.length == 3) {
            target = Bukkit.getPlayerExact(args[2]);
            if (target == null) {
                sender.sendMessage(LEGACY.deserialize(messages.format(sender,
                        "limbo-player-not-found", "{player}", args[2])));
                return true;
            }
        } else if (sender instanceof Player player) {
            target = player;
        } else {
            sender.sendMessage(LEGACY.deserialize(messages.get(sender, "limbification-usage")));
            return true;
        }

        ItemStack held = target.getInventory().getItemInMainHand();
        if (held == null || held.getType().isAir()) {
            sender.sendMessage(LEGACY.deserialize(messages.format(sender,
                    "limbification-empty-hand", "{player}", target.getName())));
            return true;
        }

        boolean changed = limbiificationManager.setMainHandLevel(target, level);
        if (!changed && level == 0) {
            sender.sendMessage(LEGACY.deserialize(messages.format(sender,
                    "limbification-not-found", "{player}", target.getName())));
            return true;
        }
        if (!changed) {
            sender.sendMessage(LEGACY.deserialize(messages.format(sender,
                    "limbification-failed", "{player}", target.getName())));
            return true;
        }

        String key = level == 0 ? "limbification-removed" : "limbification-set";
        sender.sendMessage(LEGACY.deserialize(messages.format(sender, key,
                "{player}", target.getName(), "{level}", limbiificationLevelName(level))));
        return true;
    }

    private String limbiificationLevelName(int level) {
        return switch (level) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            default -> "";
        };
    }


    private boolean handleListCommand(CommandSender sender) {
        List<EntityBehaviorModule.CyclingEntityInfo> entries = entityModule.cyclingEntities();
        if (entries.isEmpty()) {
            sender.sendMessage(LEGACY.deserialize(messages.get(sender, "admin.list-empty")));
            return true;
        }

        sender.sendMessage(LEGACY.deserialize(messages.get(sender, "admin.list-header")));
        for (EntityBehaviorModule.CyclingEntityInfo entry : entries) {
            String type = entry.toni() ? "&dToni_Klark" : "&djeb_";
            Location location = entry.location();
            String coordinates = String.format("&7(%.0f, %.0f, %.0f)",
                    location.getX(), location.getY(), location.getZ());
            sender.sendMessage(LEGACY.deserialize("&e- &6" + entry.type().name() + " " + type
                    + " &7" + coordinates + " &8[" + entry.uuid() + "]"));
        }
        sender.sendMessage(LEGACY.deserialize(messages.format(sender, "admin.list-total",
                "{count}", String.valueOf(entries.size()))));
        return true;
    }

    private boolean handleStopCommand(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(LEGACY.deserialize(messages.get(sender, "admin.stop-invalid-uuid")));
            return true;
        }

        try {
            UUID uuid = UUID.fromString(args[1]);
            boolean removed = entityModule.stopCyclingEntity(uuid);
            String messageKey = removed ? "admin.stop-success" : "admin.stop-not-found";
            sender.sendMessage(LEGACY.deserialize(messages.format(sender, messageKey,
                    "{uuid}", uuid.toString())));
        } catch (IllegalArgumentException e) {
            sender.sendMessage(LEGACY.deserialize(messages.get(sender, "admin.stop-invalid-uuid")));
        }
        return true;
    }

    private boolean handleAllergyCommand(CommandSender sender, String[] args) {
        if (!allergyModule.isEnabled()) {
            sender.sendMessage(LEGACY.deserialize("&cNetherite allergy feature is disabled."));
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(LEGACY.deserialize("&e/spaydiscore allergy &7<give|remove|list> [player] [stage]"));
            return true;
        }

        String sub = args[1].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "list" -> {
                Set<UUID> allergicPlayers = allergyModule.allergicPlayers();
                if (allergicPlayers.isEmpty()) {
                    sender.sendMessage(LEGACY.deserialize(messages.get(sender, "allergy-list-empty")));
                    return true;
                }
                sender.sendMessage(LEGACY.deserialize(messages.format(sender,
                        "allergy-list-header", "{count}", String.valueOf(allergicPlayers.size()))));
                for (UUID id : allergicPlayers) {
                    OfflinePlayer player = Bukkit.getOfflinePlayer(id);
                    String name = player.getName() != null ? player.getName() : id.toString();
                    sender.sendMessage(LEGACY.deserialize(messages.format(sender,
                            "allergy-list-entry", "{player}", name)));
                }
                return true;
            }
            case "give", "remove" -> {
                if (args.length < 3) {
                    sender.sendMessage(LEGACY.deserialize("&e/spaydiscore allergy " + sub + " <player> [stage]"));
                    return true;
                }
                OfflinePlayer target = Bukkit.getPlayerExact(args[2]);
                if (target == null) target = Bukkit.getOfflinePlayerIfCached(args[2]);
                if (target == null) {
                    sender.sendMessage(LEGACY.deserialize(messages.format(sender,
                            "command-not-found", "{player}", args[2])));
                    return true;
                }
                String shownName = target.getName() != null ? target.getName() : args[2];

                if (sub.equals("give")) {
                    int stage = 1;
                    if (args.length >= 4) {
                        try {
                            stage = Math.max(1, Math.min(2, Integer.parseInt(args[3])));
                        } catch (NumberFormatException ignored) {
                            sender.sendMessage(LEGACY.deserialize("&cInvalid stage, using 1."));
                        }
                    }
                    allergyModule.give(target, stage);
                    sender.sendMessage(LEGACY.deserialize(messages.format(sender,
                            "admin.allergy-given", "{player}", shownName)
                            + (stage == 2 ? " &c(Stage 2)" : "")));
                } else {
                    boolean removed = allergyModule.remove(target.getUniqueId());
                    String key = removed ? "admin.allergy-removed" : "admin.allergy-not-allergic";
                    sender.sendMessage(LEGACY.deserialize(messages.format(sender, key,
                            "{player}", shownName)));
                }
                return true;
            }
            default -> {
                sender.sendMessage(LEGACY.deserialize("&e/spaydiscore allergy &7<give|remove|list> [player] [stage]"));
                return true;
            }
        }
    }

    private List<String> completeCommand(CommandSender sender, String commandName, String[] args) {
        if (args == null || args.length == 0) {
            args = new String[]{""};
        }
        String cmdName = commandName.toLowerCase(Locale.ROOT);

        if (isAdminCommandName(cmdName)) {
            if (args.length == 1) {
                String prefix = args[0].toLowerCase(Locale.ROOT);
                return Arrays.asList("reload", "list", "stop", "allergy", "nether", "end", "limbo", "debug", "limbification", "undead", "schizo", "menu")
                        .stream().filter(s -> s.startsWith(prefix)).collect(Collectors.toList());
            }
            if (args.length == 2 && isLimbiificationCommand(args[0])) {
                String prefix = args[1].toLowerCase(Locale.ROOT);
                return Arrays.asList("1", "2", "3", "off").stream()
                        .filter(s -> s.startsWith(prefix)).collect(Collectors.toList());
            }
            if (args.length == 3 && isLimbiificationCommand(args[0])
                    && !args[1].isBlank()) {
                String prefix = args[2].toLowerCase(Locale.ROOT);
                return Bukkit.getOnlinePlayers().stream()
                        .map(Player::getName)
                        .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                        .collect(Collectors.toList());
            }
            if (args.length == 2 && args[0].equalsIgnoreCase("nether")) {
                String prefix = args[1].toLowerCase(Locale.ROOT);
                return Arrays.asList("off", "opening", "on")
                        .stream().filter(s -> s.startsWith(prefix)).collect(Collectors.toList());
            }
            if (args.length == 2 && args[0].equalsIgnoreCase("end")) {
                String prefix = args[1].toLowerCase(Locale.ROOT);
                return Arrays.asList("off", "opening", "on")
                        .stream().filter(s -> s.startsWith(prefix)).collect(Collectors.toList());
            }
            if (args.length == 2 && args[0].equalsIgnoreCase("allergy")) {
                String prefix = args[1].toLowerCase(Locale.ROOT);
                return Arrays.asList("give", "remove", "list")
                        .stream().filter(s -> s.startsWith(prefix)).collect(Collectors.toList());
            }
            if (args.length == 2 && args[0].equalsIgnoreCase("limbo")) {
                String prefix = args[1].toLowerCase(Locale.ROOT);
                return Arrays.asList("status", "list", "leave", "skip", "stars", "phrases", "enter", "spectate")
                        .stream().filter(s -> s.startsWith(prefix)).collect(Collectors.toList());
            }
            if (args.length == 2 && args[0].equalsIgnoreCase("debug")) {
                String prefix = args[1].toLowerCase(Locale.ROOT);
                return Arrays.asList("limbo").stream()
                        .filter(s -> s.startsWith(prefix)).collect(Collectors.toList());
            }
            if (args.length == 3 && args[0].equalsIgnoreCase("debug")
                    && args[1].equalsIgnoreCase("limbo")) {
                String prefix = args[2].toLowerCase(Locale.ROOT);
                return Arrays.asList("breath", "silhouette", "step", "phrase").stream()
                        .filter(s -> s.startsWith(prefix)).collect(Collectors.toList());
            }
            if (args.length == 4 && args[0].equalsIgnoreCase("debug")
                    && args[1].equalsIgnoreCase("limbo")
                    && (args[2].equalsIgnoreCase("silhouette")
                    || args[2].equalsIgnoreCase("step")
                    || args[2].equalsIgnoreCase("phrase"))) {
                String prefix = args[3].toLowerCase(Locale.ROOT);
                return Bukkit.getOnlinePlayers().stream()
                        .filter(player -> limboManager != null
                                && limboManager.isLimbo(player)
                                && plugin.isLimboWorld(player.getWorld()))
                        .map(Player::getName)
                        .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                        .collect(Collectors.toList());
            }
            if (args.length == 3 && args[0].equalsIgnoreCase("limbo")
                    && args[1].equalsIgnoreCase("stars")) {
                String prefix = args[2].toLowerCase(Locale.ROOT);
                return Arrays.asList("status", "off", "on", "low", "normal", "high")
                        .stream().filter(s -> s.startsWith(prefix)).collect(Collectors.toList());
            }
            if (args.length == 3 && args[0].equalsIgnoreCase("limbo")
                    && args[1].equalsIgnoreCase("phrases")) {
                String prefix = args[2].toLowerCase(Locale.ROOT);
                return Arrays.asList("normal", "barrel", "underwater", "status")
                        .stream().filter(s -> s.startsWith(prefix)).collect(Collectors.toList());
            }
            if (args.length == 4 && args[0].equalsIgnoreCase("limbo")
                    && args[1].equalsIgnoreCase("stars")) {
                String prefix = args[3].toLowerCase(Locale.ROOT);
                return Bukkit.getOnlinePlayers().stream()
                        .map(Player::getName)
                        .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                        .collect(Collectors.toList());
            }
            if (args.length == 2 && args[0].equalsIgnoreCase("schizo")) {
                String prefix = args[1].toLowerCase(Locale.ROOT);
                return Arrays.asList("give", "remove", "list").stream()
                        .filter(s -> s.startsWith(prefix)).collect(Collectors.toList());
            }
            if (args.length == 3 && args[0].equalsIgnoreCase("schizo")
                    && (args[1].equalsIgnoreCase("give") || args[1].equalsIgnoreCase("remove"))) {
                String prefix = args[2].toLowerCase(Locale.ROOT);
                return Bukkit.getOnlinePlayers().stream()
                        .map(Player::getName)
                        .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                        .collect(Collectors.toList());
            }
            if (args.length == 2 && args[0].equalsIgnoreCase("undead")) {
                String prefix = args[1].toLowerCase(Locale.ROOT);
                return Arrays.asList("give", "remove", "list").stream()
                        .filter(s -> s.startsWith(prefix)).collect(Collectors.toList());
            }
            if (args.length == 3 && args[0].equalsIgnoreCase("undead")
                    && (args[1].equalsIgnoreCase("give") || args[1].equalsIgnoreCase("remove"))) {
                String prefix = args[2].toLowerCase(Locale.ROOT);
                return Bukkit.getOnlinePlayers().stream()
                        .map(Player::getName)
                        .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                        .collect(Collectors.toList());
            }
            if (args.length == 3 && args[0].equalsIgnoreCase("limbo")
                    && (args[1].equalsIgnoreCase("enter") || args[1].equalsIgnoreCase("spectate"))) {
                String prefix = args[2].toLowerCase(Locale.ROOT);
                return Bukkit.getOnlinePlayers().stream()
                        .map(Player::getName)
                        .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                        .collect(Collectors.toList());
            }
            if (args.length == 3 && args[0].equalsIgnoreCase("limbo")
                    && args[1].equalsIgnoreCase("leave")) {
                String prefix = args[2].toLowerCase(Locale.ROOT);
                if (limboManager != null) {
                    return limboManager.rosterNamesStartingWith(prefix);
                }
                return Bukkit.getOnlinePlayers().stream()
                        .map(Player::getName)
                        .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                        .collect(Collectors.toList());
            }
            if (args.length == 3 && args[0].equalsIgnoreCase("limbo")
                    && args[1].equalsIgnoreCase("skip")) {
                String prefix = args[2].toLowerCase(Locale.ROOT);
                return Bukkit.getOnlinePlayers().stream()
                        .map(Player::getName)
                        .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                        .collect(Collectors.toList());
            }
            if (args.length == 3 && args[0].equalsIgnoreCase("allergy")
                    && (args[1].equalsIgnoreCase("give") || args[1].equalsIgnoreCase("remove"))) {
                String prefix = args[2].toLowerCase(Locale.ROOT);
                return Bukkit.getOnlinePlayers().stream()
                        .map(Player::getName)
                        .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                        .collect(Collectors.toList());
            }
            if (args.length == 4 && args[0].equalsIgnoreCase("allergy")
                    && args[1].equalsIgnoreCase("give")) {
                String prefix = args[3].toLowerCase(Locale.ROOT);
                return Arrays.asList("1", "2")
                        .stream().filter(s -> s.startsWith(prefix)).collect(Collectors.toList());
            }
            if (args.length == 2 && args[0].equalsIgnoreCase("stop")) {
                String prefix = args[1].toLowerCase(Locale.ROOT);
                return entityModule.cyclingEntities().stream()
                        .map(entry -> entry.uuid().toString())
                        .filter(id -> id.startsWith(prefix))
                        .collect(Collectors.toList());
            }
        }
        return Collections.emptyList();
    }

    private boolean isLimbiificationCommand(String value) {
        return value != null && value.equalsIgnoreCase("limbification");
    }

}
