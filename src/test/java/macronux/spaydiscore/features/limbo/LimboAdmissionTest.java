package macronux.spaydiscore.features.limbo;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import java.util.stream.Stream;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionEffectTypeCategory;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scoreboard.Scoreboard;
import io.papermc.paper.registry.RegistryAccess;

/** Behavioral checks for Limbo admission when SMPRevive owns player death state. */
public final class LimboAdmissionTest {
    private static final NamespacedKey ACTIVE_KEY = new NamespacedKey("spaydiscore", "limbo-active");
    private static final NamespacedKey ORIGINAL_ALLOW_FLIGHT_KEY =
            new NamespacedKey("spaydiscore", "limbo-original-allow-flight");
    private static final NamespacedKey ORIGINAL_FLYING_KEY =
            new NamespacedKey("spaydiscore", "limbo-original-flying");
    private static final Map<String, BorderWorldModel> WORLD_MODELS = new ConcurrentHashMap<>();

    private LimboAdmissionTest() {
    }

    public static void run() throws Exception {
        Harness harness = new Harness();
        Field bukkitServer = Bukkit.class.getDeclaredField("server");
        bukkitServer.setAccessible(true);
        Object previousServer = bukkitServer.get(null);
        Object previousRegistryAccess = installRegistryAccess();
        try {
            bukkitServer.set(null, harness.server);
            cancelledDeathsDoNotEnroll(harness);
            committedDeathPreservesOriginalState(harness);
            joinAndRevivalCallbacksDoNotReenrollOrDoubleClear(harness);
            staleMarkerIsClearedForConfirmedAlivePlayer(harness);
            firstFreeDeathRemainsOutsideLimbo(harness);
            laterDeathIsAdmitted(harness);
            finalDeadStateWinsAfterPrecheckSkipped(harness);
            confirmedAliveOccupantInLimboRemainsThere(harness);
            legacyApiUsesItsCommittedState(harness);
            unknownStatePreservesStandaloneMarkerBehavior(harness);
            rejectionTargetsStayInsideShrunkWorldBorder(harness);
            ambientSoundCooldownIncludesPlaybackDuration(harness);
            overworldChatExcludesLimboRecipients(harness);
        } finally {
            bukkitServer.set(null, previousServer);
            setRegistryAccess(previousRegistryAccess);
        }

        System.out.println("Limbo SMPRevive admission and respawn checks passed");
    }

    private static void overworldChatExcludesLimboRecipients(Harness harness) throws Exception {
        Fixture outside = harness.fixture(harness.normalWorld);
        Fixture inside = harness.fixture(harness.limboWorld);
        Field active = LimboManager.class.getDeclaredField("activePlayers");
        active.setAccessible(true);
        @SuppressWarnings("unchecked") Set<UUID> members = (Set<UUID>) active.get(outside.manager);
        members.add(inside.player.getUniqueId());
        Set<Player> recipients = new java.util.HashSet<>(List.of(outside.player, inside.player));
        outside.manager.onLegacyWorldChat(new org.bukkit.event.player.AsyncPlayerChatEvent(
                true, outside.player, "overworld message", recipients));
        require(recipients.contains(outside.player) && !recipients.contains(inside.player),
                "normal-world chat must exclude Limbo recipients while keeping normal recipients");
        recipients.add(inside.player);
        outside.manager.onLegacyWorldChat(new org.bukkit.event.player.AsyncPlayerChatEvent(
                true, inside.player, "limbo message", recipients));
        require(recipients.contains(inside.player), "Limbo chat must not be removed by the overworld filter");
    }

    private static void cancelledDeathsDoNotEnroll(Harness harness) throws Exception {
        harness.uninstallRevive();
        for (EventPriority cancellation : List.of(EventPriority.NORMAL, EventPriority.HIGHEST)) {
            Fixture fixture = harness.fixture(harness.normalWorld);
            PlayerDeathEvent event = deathEvent(fixture.player);
            dispatchDeath(fixture.manager, event, EventPriority.LOWEST);
            require(fixture.persistentData.values.isEmpty() && !fixture.manager.isLimbo(fixture.player),
                    "LOWEST must stage death without writing persistent Limbo state");
            // A third-party listener cancels after the early snapshot.
            event.setCancelled(true);
            dispatchDeath(fixture.manager, event, cancellation);
            dispatchDeath(fixture.manager, event, EventPriority.MONITOR);
            require(fixture.persistentData.values.isEmpty() && !fixture.manager.isLimbo(fixture.player),
                    "cancelled death must not enroll the player: " + cancellation);
            Field pending = LimboManager.class.getDeclaredField("pendingDeathStates");
            pending.setAccessible(true);
            require(((Map<?, ?>) pending.get(fixture.manager)).isEmpty(),
                    "cancelled deaths must discard their transient snapshot");
        }
        Fixture occupant = harness.fixture(harness.limboWorld);
        occupant.persistentData.values.put(ACTIVE_KEY, (byte) 1);
        occupant.persistentData.values.put(ORIGINAL_FLYING_KEY, (byte) 1);
        Map<NamespacedKey, Object> previous = Map.copyOf(occupant.persistentData.values);
        PlayerDeathEvent event = deathEvent(occupant.player);
        dispatchDeath(occupant.manager, event, EventPriority.LOWEST);
        event.setCancelled(true);
        dispatchDeath(occupant.manager, event, EventPriority.MONITOR);
        require(occupant.persistentData.values.equals(previous),
                "cancelling an occupant's death must retain their existing saved state");
    }

    private static void committedDeathPreservesOriginalState(Harness harness) throws Exception {
        harness.uninstallRevive();
        Fixture fixture = harness.fixture(harness.normalWorld);
        fixture.playerState.gameMode = GameMode.CREATIVE;
        fixture.playerState.allowFlight = true;
        fixture.playerState.flying = true;
        fixture.playerState.gravity = false;
        PotionEffect originalEffect = new PotionEffect(PotionEffectType.INVISIBILITY, 100, 0);
        fixture.playerState.effects.put(originalEffect.getType(), originalEffect);
        Scoreboard originalBoard = (Scoreboard) Proxy.newProxyInstance(Scoreboard.class.getClassLoader(),
                new Class<?>[] {Scoreboard.class}, (proxy, method, args) -> defaultValue(method.getReturnType()));
        fixture.playerState.scoreboard = originalBoard;
        PlayerDeathEvent event = deathEvent(fixture.player);
        dispatchDeath(fixture.manager, event, EventPriority.LOWEST);
        // Simulate SMPRevive/another listener changing the player after LOWEST.
        fixture.playerState.gameMode = GameMode.SPECTATOR;
        fixture.playerState.allowFlight = false;
        fixture.playerState.flying = false;
        fixture.playerState.gravity = true;
        fixture.playerState.effects.clear();
        fixture.playerState.scoreboard = null;
        dispatchDeath(fixture.manager, event, EventPriority.MONITOR);
        require(fixture.manager.isLimbo(fixture.player), "an uncancelled death must commit admission");
        clearState(fixture);
        require(fixture.playerState.gameMode == GameMode.CREATIVE && fixture.playerState.allowFlight
                        && fixture.playerState.flying && !fixture.playerState.gravity,
                "release must restore pre-death flags rather than the later spectator state");
        require(fixture.playerState.effects.get(originalEffect.getType()) == originalEffect
                        && fixture.playerState.scoreboard == originalBoard,
                "release must restore the original effects and scoreboard from the early snapshot");
    }

    private static void clearState(Fixture fixture) throws Exception {
        var clear = LimboManager.class.getDeclaredMethod("clearState", Player.class);
        clear.setAccessible(true);
        clear.invoke(fixture.manager, fixture.player);
    }

    private static void joinAndRevivalCallbacksDoNotReenrollOrDoubleClear(Harness harness) throws Exception {
        for (boolean coreJoinFirst : List.of(false, true)) {
            harness.scheduled.clear();
            DirectState revive = new DirectState();
            revive.dead = true;
            harness.installDirect(revive);
            Fixture fixture = harness.fixture(harness.normalWorld);
            PotionEffect original = new PotionEffect(PotionEffectType.INVISIBILITY, 120, 0);
            fixture.playerState.effects.put(original.getType(), original);
            completeDeath(fixture);
            fixture.playerState.effects.clear();
            fixture.playerState.world = harness.limboWorld;
            if (coreJoinFirst) fixture.manager.onPlayerJoin(new PlayerJoinEvent(fixture.player, (String) null));

            // SMPRevive provisionally clears dead before its successful join teleport.
            revive.dead = false;
            var teleport = new PlayerTeleportEvent(fixture.player,
                    new Location(harness.limboWorld, 0, 65, 0), new Location(harness.normalWorld, 0, 65, 0));
            fixture.manager.onPlayerTeleport(teleport);
            require(!teleport.isCancelled(), "confirmed revival must allow the exit teleport");
            fixture.playerState.world = harness.normalWorld;
            if (!coreJoinFirst) fixture.manager.onPlayerJoin(new PlayerJoinEvent(fixture.player, (String) null));

            harness.runScheduledTick();
            harness.runScheduledTick();
            require(!fixture.manager.isLimbo(fixture.player) && fixture.playerState.world == harness.normalWorld,
                    "join callbacks must not re-enroll an already released player: " + coreJoinFirst);
            require(fixture.playerState.effects.get(original.getType()) == original,
                    "later callbacks must not strip effects restored by the first cleanup");
            require(harness.scheduled.isEmpty() && fixture.playerState.teleports == 0,
                    "completed revival must not schedule repeated cleanup or a Limbo reentry");
        }

        // A release can also finish independently while a teleport cleanup is queued.
        harness.scheduled.clear();
        DirectState revive = new DirectState();
        harness.installDirect(revive);
        Fixture fixture = harness.fixture(harness.normalWorld);
        fixture.persistentData.values.put(ACTIVE_KEY, (byte) 1);
        fixture.manager.onPlayerTeleport(new PlayerTeleportEvent(fixture.player,
                new Location(harness.limboWorld, 0, 65, 0), fixture.player.getLocation()));
        clearState(fixture);
        PotionEffect normalEffect = new PotionEffect(PotionEffectType.DARKNESS, 80, 0);
        fixture.playerState.effects.put(normalEffect.getType(), normalEffect);
        harness.runScheduledTick();
        require(fixture.playerState.effects.get(normalEffect.getType()) == normalEffect,
                "an obsolete queued cleanup must leave a released player's new effects alone");
    }

    private static void completeDeath(Fixture fixture) throws Exception {
        PlayerDeathEvent event = deathEvent(fixture.player);
        for (EventPriority priority : EventPriority.values()) dispatchDeath(fixture.manager, event, priority);
    }

    private static void dispatchDeath(LimboManager manager, PlayerDeathEvent event,
                                      EventPriority priority) throws Exception {
        for (var method : LimboManager.class.getMethods()) {
            EventHandler handler = method.getAnnotation(EventHandler.class);
            if (handler != null && handler.priority() == priority && method.getParameterCount() == 1
                    && method.getParameterTypes()[0] == PlayerDeathEvent.class
                    && (!event.isCancelled() || !handler.ignoreCancelled())) method.invoke(manager, event);
        }
    }

    private static void firstFreeDeathRemainsOutsideLimbo(Harness harness) throws Exception {
        DirectState revive = new DirectState();
        revive.firstDeathPending = true;
        harness.installDirect(revive);
        Fixture fixture = harness.fixture(harness.normalWorld);

        completeDeath(fixture);
        require(!fixture.manager.isLimbo(fixture.player),
                "the predicted free first death is not enrolled before SMPRevive handles it");
        require(!fixture.persistentData.contains(ACTIVE_KEY),
                "the predicted free first death does not receive the Limbo marker");

        revive.firstDeathPending = false;
        PlayerRespawnEvent firstRespawn = respawnEvent(fixture.player, harness.normalWorld);
        fixture.manager.onPlayerRespawn(firstRespawn);
        require(firstRespawn.getRespawnLocation().getWorld() == harness.normalWorld,
                "SMPRevive's committed living state leaves the first respawn in the normal world");
        require(!fixture.manager.isLimbo(fixture.player),
                "the first free respawn remains outside Limbo");
    }

    private static void laterDeathIsAdmitted(Harness harness) throws Exception {
        DirectState revive = new DirectState();
        harness.installDirect(revive);
        Fixture fixture = harness.fixture(harness.normalWorld);

        revive.dead = true;
        completeDeath(fixture);
        require(fixture.persistentData.contains(ACTIVE_KEY),
                "a later committed death captures Limbo state");
        PlayerRespawnEvent respawn = respawnEvent(fixture.player, harness.normalWorld);
        fixture.manager.onPlayerRespawn(respawn);
        require(respawn.getRespawnLocation().getWorld() == harness.limboWorld,
                "a later committed death respawns in Limbo");
    }

    private static void finalDeadStateWinsAfterPrecheckSkipped(Harness harness) throws Exception {
        DirectState revive = new DirectState();
        revive.firstDeathPending = true;
        harness.installDirect(revive);
        Fixture fixture = harness.fixture(harness.normalWorld);

        completeDeath(fixture);
        require(!fixture.persistentData.contains(ACTIVE_KEY),
                "a predicted free death can skip the early Limbo capture");

        revive.firstDeathPending = false;
        revive.dead = true;
        PlayerRespawnEvent respawn = respawnEvent(fixture.player, harness.normalWorld);
        fixture.manager.onPlayerRespawn(respawn);
        require(respawn.getRespawnLocation().getWorld() == harness.limboWorld,
                "the committed dead state admits a player even when the early precheck skipped capture");
    }

    private static void staleMarkerIsClearedForConfirmedAlivePlayer(Harness harness) throws Exception {
        DirectState revive = new DirectState();
        revive.firstDeathPending = true;
        harness.installDirect(revive);
        Fixture fixture = harness.fixture(harness.normalWorld);
        fixture.persistentData.values.put(ACTIVE_KEY, (byte) 1);
        fixture.persistentData.values.put(ORIGINAL_ALLOW_FLIGHT_KEY, (byte) 1);
        fixture.persistentData.values.put(ORIGINAL_FLYING_KEY, (byte) 1);
        fixture.playerState.allowFlight = true;
        fixture.playerState.flying = true;

        completeDeath(fixture);
        PlayerRespawnEvent respawn = respawnEvent(fixture.player, harness.normalWorld);
        fixture.manager.onPlayerRespawn(respawn);

        require(respawn.getRespawnLocation().getWorld() == harness.normalWorld,
                "a confirmed living player with a stale marker keeps the ordinary respawn location");
        require(!fixture.persistentData.contains(ACTIVE_KEY) && !fixture.manager.isLimbo(fixture.player),
                "a confirmed living player outside the Limbo world has stale Limbo state removed");
        require(!fixture.playerState.allowFlight && !fixture.playerState.flying,
                "stale Limbo cleanup disables survival flight after restoring saved flags");
        require(fixture.playerState.gameModeWrites == 0,
                "reconciling a stale marker does not overwrite SMPRevive's chosen game mode");
    }

    private static void confirmedAliveOccupantInLimboRemainsThere(Harness harness) throws Exception {
        DirectState revive = new DirectState();
        harness.installDirect(revive);
        Fixture fixture = harness.fixture(harness.limboWorld);
        fixture.persistentData.values.put(ACTIVE_KEY, (byte) 1);
        Location original = new Location(harness.limboWorld, 4.0, 70.0, 6.0);

        PlayerRespawnEvent respawn = new PlayerRespawnEvent(fixture.player, original, false);
        fixture.manager.onPlayerRespawn(respawn);

        require(respawn.getRespawnLocation().getWorld() == harness.limboWorld,
                "a confirmed living player already in Limbo continues to respawn there");
        require(fixture.persistentData.contains(ACTIVE_KEY) && fixture.manager.isLimbo(fixture.player),
                "an existing Limbo occupant keeps its active marker");
    }

    private static void legacyApiUsesItsCommittedState(Harness harness) throws Exception {
        LegacyState revive = new LegacyState();
        revive.firstDeathPending = true;
        harness.installLegacy(revive);
        Fixture fixture = harness.fixture(harness.normalWorld);

        completeDeath(fixture);
        require(!fixture.persistentData.contains(ACTIVE_KEY),
                "the legacy free-first-death API also skips predicted capture");
        revive.firstDeathPending = false;
        PlayerRespawnEvent freeRespawn = respawnEvent(fixture.player, harness.normalWorld);
        fixture.manager.onPlayerRespawn(freeRespawn);
        require(freeRespawn.getRespawnLocation().getWorld() == harness.normalWorld,
                "the legacy data-manager API confirms a living first respawn");

        revive.dead = true;
        completeDeath(fixture);
        PlayerRespawnEvent laterRespawn = respawnEvent(fixture.player, harness.normalWorld);
        fixture.manager.onPlayerRespawn(laterRespawn);
        require(laterRespawn.getRespawnLocation().getWorld() == harness.limboWorld,
                "the legacy data-manager API admits a later committed death");
    }

    private static void unknownStatePreservesStandaloneMarkerBehavior(Harness harness) throws Exception {
        harness.uninstallRevive();
        Fixture noMarker = harness.fixture(harness.normalWorld);
        PlayerRespawnEvent unknownRespawn = respawnEvent(noMarker.player, harness.normalWorld);
        noMarker.manager.onPlayerRespawn(unknownRespawn);
        require(unknownRespawn.getRespawnLocation().getWorld() == harness.normalWorld,
                "without SMPRevive, an unmarked player keeps ordinary respawn behavior");
        require(!noMarker.manager.isLimbo(noMarker.player),
                "an unknown state alone does not enroll an unmarked player");

        DirectState unrecorded = new DirectState();
        unrecorded.known = false;
        harness.installDirect(unrecorded);
        Fixture staleUnknown = harness.fixture(harness.normalWorld);
        staleUnknown.persistentData.values.put(ACTIVE_KEY, (byte) 1);
        PlayerRespawnEvent markedRespawn = respawnEvent(staleUnknown.player, harness.normalWorld);
        staleUnknown.manager.onPlayerRespawn(markedRespawn);
        require(markedRespawn.getRespawnLocation().getWorld() == harness.limboWorld,
                "an unknown SMPRevive state preserves the existing standalone marker behavior");
        require(staleUnknown.persistentData.contains(ACTIVE_KEY),
                "unknown state does not erase a marker that still represents Limbo membership");
    }

    private static void rejectionTargetsStayInsideShrunkWorldBorder(Harness harness) throws Exception {
        BorderWorldModel model = WORLD_MODELS.get("world");

        // A previously saved point is now outside the border. The spawn is safe and inside.
        model.setBorder(100.0, 0.0, 16.0);
        model.spawnX = 100.0;
        model.spawnZ = 0.0;
        model.setSafeTerrain(100, 0);
        model.resetTerrainReads();
        Fixture outsideSaved = harness.fixture(harness.normalWorld);
        putSavedReturn(outsideSaved, harness.normalWorld, 0.0, 65.0, 0.0);
        Location spawnFallback = getReturnLocation(outsideSaved, harness.normalWorld);
        require(spawnFallback != null && isSafeReturnLocation(outsideSaved, spawnFallback),
                "an outside saved point must fall back to safe terrain at the inside spawn; got " + spawnFallback);
        require(Math.abs(spawnFallback.getX() - 100.0) <= 1.0,
                "safe terrain near the inside spawn should be selected: " + spawnFallback);
        require(model.outsideBorderTerrainReads == 0,
                "searching from an outside saved point must not read terrain outside the border");

        // Both saved point and spawn are beyond an eight-block scan from the border center.
        model.setBorder(100.0, 0.0, 100.0);
        model.spawnX = 0.0;
        model.spawnZ = 0.0;
        model.setSafeTerrain(100, 0);
        model.resetTerrainReads();
        Fixture centerFallback = harness.fixture(harness.normalWorld);
        putSavedReturn(centerFallback, harness.normalWorld, 0.0, 65.0, 0.0);
        Location borderCenterFallback = getReturnLocation(centerFallback, harness.normalWorld);
        require(borderCenterFallback != null && isSafeReturnLocation(centerFallback, borderCenterFallback),
                "outside saved and spawn points must fall back to safe terrain near border center; got "
                        + borderCenterFallback);
        require(Math.abs(borderCenterFallback.getX() - 100.5) < 1.0
                        && Math.abs(borderCenterFallback.getZ() - 0.5) < 1.0,
                "border-center fallback should select the only safe center terrain: " + borderCenterFallback);
        require(model.outsideBorderTerrainReads == 0,
                "border-center fallback search must not read terrain outside the border");

        // The original scene target was safe when selected, then the border shrank over it.
        model.setBorder(0.0, 0.0, 64.0);
        model.spawnX = 0.0;
        model.spawnZ = 0.0;
        model.setSafeTerrain(2, 2);
        Fixture shrinkingBorder = harness.fixture(harness.limboWorld);
        shrinkingBorder.persistentData.values.put(ACTIVE_KEY, (byte) 1);
        putSavedReturn(shrinkingBorder, harness.normalWorld, 2.5, 65.0, 2.5);
        Location originalTarget = getReturnLocation(shrinkingBorder, harness.normalWorld);
        require(originalTarget != null && Math.abs(originalTarget.getX() - 2.5) < 0.01
                        && Math.abs(originalTarget.getZ() - 2.5) < 0.01,
                "test setup must choose the original scene target inside the large border: " + originalTarget);
        PairedState paired = new PairedState();
        harness.installPaired(paired);
        setField(shrinkingBorder.manager, "returnWorldName", "world");
        Object shrinkingScene = rejectionScene(originalTarget);
        rejectionScenes(shrinkingBorder.manager).put(shrinkingBorder.playerState.id, shrinkingScene);
        model.setBorder(0.0, 0.0, 4.0);
        model.setSafeTerrain(0, 0);
        model.resetTerrainReads();
        invoke(shrinkingBorder.manager, "rejectSoul",
                new Class<?>[] {shrinkingScene.getClass(), Player.class, long.class},
                shrinkingScene, shrinkingBorder.player, 2000L);
        require(paired.revivalCalls == 1 && paired.lastTarget != null,
                "a V1 revival should receive one replacement target after a border shrink");
        require(!samePosition(originalTarget, paired.lastTarget),
                "rejection must not forward the old scene target after it leaves the border: " + paired.lastTarget);
        require(model.isInside(paired.lastTarget) && isSafeReturnLocation(shrinkingBorder, paired.lastTarget),
                "replacement V1 target must be currently safe and inside the shrunk border: " + paired.lastTarget);
        require(model.outsideBorderTerrainReads == 0,
                "revalidating a stale scene target must not read terrain outside the border");

        // If no safe terrain remains, the stale scene is cleaned up and retried in 30 seconds.
        Fixture noSafeTerrain = harness.fixture(harness.limboWorld);
        noSafeTerrain.persistentData.values.put(ACTIVE_KEY, (byte) 1);
        putSavedReturn(noSafeTerrain, harness.normalWorld, 0.5, 65.0, 0.5);
        PairedState notCalled = new PairedState();
        harness.installPaired(notCalled);
        setField(noSafeTerrain.manager, "returnWorldName", "world");
        Object unsafeScene = rejectionScene(new Location(harness.normalWorld, 0.5, 65.0, 0.5));
        rejectionScenes(noSafeTerrain.manager).put(noSafeTerrain.playerState.id, unsafeScene);
        model.setBorder(0.0, 0.0, 64.0);
        model.setSafeTerrain(0, 0);
        model.terrainUnsafe = true;
        model.resetTerrainReads();
        long now = 5000L;
        invoke(noSafeTerrain.manager, "rejectSoul",
                new Class<?>[] {unsafeScene.getClass(), Player.class, long.class},
                unsafeScene, noSafeTerrain.player, now);
        Map<UUID, Long> retries = rejectionRetries(noSafeTerrain.manager);
        require(notCalled.revivalCalls == 0,
                "V1 revival must not be called when neither the scene nor any fallback target is safe");
        require(Boolean.TRUE.equals(getField(unsafeScene, "cleaned"))
                        && !rejectionScenes(noSafeTerrain.manager).containsKey(noSafeTerrain.playerState.id),
                "an unsafe scene must be cleaned up before retrying");
        require(Long.valueOf(now + 30L * 20L).equals(retries.get(noSafeTerrain.playerState.id)),
                "an unsafe rejection target must schedule a 30-second retry: " + retries);
        require(noSafeTerrain.persistentData.contains(ACTIVE_KEY),
                "failed target selection must retain the Limbo marker for retry");

        // A one-block border with a negative, fractional center still has an interior safe column.
        model.setBorder(-4.6, 7.3, 1.0);
        model.spawnX = 40.0;
        model.spawnZ = 40.0;
        model.setSafeTerrain(-5, 7);
        model.terrainUnsafe = false;
        model.resetTerrainReads();
        model.setObstruction(-6, 65, 7);
        Location nearBorderEdge = new Location(harness.normalWorld, -4.75, 65.0, 7.3);
        require(!isSafeReturnLocation(harness.fixture(harness.normalWorld), nearBorderEdge),
                "a solid block in a neighboring column intersecting the player footprint must reject the point");
        require(model.outsideBorderTerrainReads == 0,
                "a partial neighboring block that intersects the border is a valid terrain read");
        model.clearObstruction();
        model.resetTerrainReads();
        Fixture tinyBorder = harness.fixture(harness.normalWorld);
        putSavedReturn(tinyBorder, harness.normalWorld, 40.0, 65.0, 40.0);
        Location tinyBorderTarget = getReturnLocation(tinyBorder, harness.normalWorld);
        require(tinyBorderTarget != null && model.isInside(tinyBorderTarget)
                        && Math.abs(tinyBorderTarget.getX() - model.centerX) <= model.size / 2.0 - 0.31 + 1.0e-6
                        && Math.abs(tinyBorderTarget.getZ() - model.centerZ) <= model.size / 2.0 - 0.31 + 1.0e-6
                        && isSafeReturnLocation(tinyBorder, tinyBorderTarget),
                "safe terrain in a tiny fractional border must return with 0.31-block clearance: "
                        + tinyBorderTarget);
        require(model.outsideBorderTerrainReads == 0,
                "tiny-border projected search must not read terrain outside the border");

        // A saved point that remains safe is preferred over a different safe spawn.
        model.setBorder(0.0, 0.0, 100.0);
        model.spawnX = 20.0;
        model.spawnZ = 20.0;
        model.setSafeTerrain(0, 0);
        model.resetTerrainReads();
        Fixture safeSaved = harness.fixture(harness.normalWorld);
        putSavedReturn(safeSaved, harness.normalWorld, 0.5, 65.0, 0.5);
        Location preservedSaved = getReturnLocation(safeSaved, harness.normalWorld);
        require(preservedSaved != null && Math.abs(preservedSaved.getX() - 0.5) < 0.01
                        && Math.abs(preservedSaved.getZ() - 0.5) < 0.01
                        && isSafeReturnLocation(safeSaved, preservedSaved),
                "a still-safe saved point should remain the selected return column: " + preservedSaved);
        require(model.outsideBorderTerrainReads == 0,
                "safe saved-point validation must not read terrain outside the border");

        // The full vertical body footprint must include a fractional-height ceiling block.
        model.setObstruction(0, 67, 0);
        model.resetTerrainReads();
        Fixture ceilingSaved = harness.fixture(harness.normalWorld);
        putSavedReturn(ceilingSaved, harness.normalWorld, 0.5, 65.9, 0.5);
        Location ceilingFallback = getReturnLocation(ceilingSaved, harness.normalWorld);
        require(ceilingFallback != null && Math.abs(ceilingFallback.getY() - 65.9) > 0.05
                        && isSafeReturnLocation(ceilingSaved, ceilingFallback),
                "a saved point intersecting a ceiling at y=67 must be moved to a safe body height: "
                        + ceilingFallback);
        require(model.outsideBorderTerrainReads == 0,
                "vertical footprint validation must not read terrain outside the border");
    }

    private static void putSavedReturn(Fixture fixture, World world, double x, double y, double z) throws Exception {
        NamespacedKey key = (NamespacedKey) getField(fixture.manager, "returnLocationKey");
        fixture.persistentData.values.put(key, world.getName() + "," + x + "," + y + "," + z + ",0.0,0.0");
    }

    private static Location getReturnLocation(Fixture fixture, World fallbackWorld) throws Exception {
        return (Location) invoke(fixture.manager, "getReturnLocation",
                new Class<?>[] {Player.class, World.class}, fixture.player, fallbackWorld);
    }

    private static boolean isSafeReturnLocation(Fixture fixture, Location location) throws Exception {
        return (boolean) invoke(fixture.manager, "isSafeReturnLocation",
                new Class<?>[] {Location.class}, location);
    }

    private static Object rejectionScene(Location target) throws Exception {
        Class<?> sceneType = Class.forName(LimboManager.class.getName() + "$RejectionScene");
        var constructor = sceneType.getDeclaredConstructor(UUID.class, Location.class, double.class, double.class);
        constructor.setAccessible(true);
        return constructor.newInstance(UUID.randomUUID(), target, 0.0, 1.0);
    }

    @SuppressWarnings("unchecked")
    private static Map<UUID, Object> rejectionScenes(LimboManager manager) throws Exception {
        return (Map<UUID, Object>) getField(manager, "rejectionScenes");
    }

    @SuppressWarnings("unchecked")
    private static Map<UUID, Long> rejectionRetries(LimboManager manager) throws Exception {
        return (Map<UUID, Long>) getField(manager, "rejectionRetryAt");
    }

    private static boolean samePosition(Location first, Location second) {
        return first.getWorld() == second.getWorld()
                && Math.abs(first.getX() - second.getX()) < 0.01
                && Math.abs(first.getY() - second.getY()) < 0.01
                && Math.abs(first.getZ() - second.getZ()) < 0.01;
    }

    private static Object invoke(Object target, String methodName, Class<?>[] signature, Object... args)
            throws Exception {
        var method = target.getClass().getDeclaredMethod(methodName, signature);
        method.setAccessible(true);
        return method.invoke(target, args);
    }

    private static Object getField(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void ambientSoundCooldownIncludesPlaybackDuration(Harness harness) throws Exception {
        Fixture fixture = harness.fixture(harness.limboWorld);
        @SuppressWarnings("unchecked") Map<UUID, Long> nextSounds =
                (Map<UUID, Long>) getField(fixture.manager, "nextAmbientSoundAt");
        long now = 10_000L;
        nextSounds.put(fixture.playerState.id, now);
        invoke(fixture.manager, "tickAmbientSounds", new Class<?>[] {Player.class, long.class},
                fixture.player, now);
        long deadline = nextSounds.get(fixture.playerState.id);
        require(deadline == now + (long) getField(fixture.manager, "pangeaDurationTicks"),
                "continuous ambient cooldown must use the configured track duration: "
                        + deadline);
        invoke(fixture.manager, "tickAmbientSounds", new Class<?>[] {Player.class, long.class},
                fixture.player, now + 1L);
        require(nextSounds.get(fixture.playerState.id) == deadline,
                "checking before the ambient deadline must not stack another playback interval");
    }

    private static PlayerDeathEvent deathEvent(Player player) {
        return new PlayerDeathEvent(player, null, java.util.List.of(), 0, "test death");
    }

    private static PlayerRespawnEvent respawnEvent(Player player, World world) {
        return new PlayerRespawnEvent(player, new Location(world, 0.0, 65.0, 0.0), false);
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static JavaPlugin createPlugin() throws Exception {
        Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
        Field singleton = unsafeType.getDeclaredField("theUnsafe");
        singleton.setAccessible(true);
        Object unsafe = singleton.get(null);
        return (JavaPlugin) unsafeType.getMethod("allocateInstance", Class.class)
                .invoke(unsafe, TestPlugin.class);
    }

    private static Object installRegistryAccess() throws Exception {
        AtomicInteger effectIds = new AtomicInteger();
        Registry<?>[] effectRegistry = new Registry<?>[1];
        Registry<?>[] blockRegistry = new Registry<?>[1];
        RegistryAccess access = (RegistryAccess) Proxy.newProxyInstance(RegistryAccess.class.getClassLoader(),
                new Class<?>[] {RegistryAccess.class}, (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) return proxyObjectMethod(proxy, method, args);
                    if (method.getName().equals("getRegistry")) {
                        Object registryKey = args == null || args.length == 0 ? null : args[0];
                        boolean blocks = registryKey == org.bukkit.block.BlockType.class
                                || registryKey == io.papermc.paper.registry.RegistryKey.BLOCK;
                        Registry<?>[] slot = blocks ? blockRegistry : effectRegistry;
                        if (slot[0] == null) {
                            slot[0] = (Registry<?>) Proxy.newProxyInstance(Registry.class.getClassLoader(),
                                    new Class<?>[] {Registry.class}, (registryProxy, registryMethod, registryArgs) -> {
                                        if (registryMethod.getDeclaringClass() == Object.class) {
                                            return proxyObjectMethod(registryProxy, registryMethod, registryArgs);
                                        }
                                        return switch (registryMethod.getName()) {
                                            case "get", "getOrThrow" -> blocks
                                                    ? blockType(toNamespacedKey(registryArgs[0]))
                                                    : new TestPotionEffectType(toNamespacedKey(registryArgs[0]),
                                                            effectIds.incrementAndGet());
                                            case "getKey" -> ((org.bukkit.Keyed) registryArgs[0]).getKey();
                                            case "hasTag" -> false;
                                            case "getTags" -> List.of();
                                            case "stream", "keyStream" -> Stream.empty();
                                            case "size" -> 0;
                                            case "iterator" -> List.of().iterator();
                                            default -> defaultValue(registryMethod.getReturnType());
                                        };
                                    });
                        }
                        return slot[0];
                    }
                    return defaultValue(method.getReturnType());
                });
        Class<?> holder = Class.forName("io.papermc.paper.registry.RegistryAccessHolder", true,
                LimboAdmissionTest.class.getClassLoader());
        Field instance = holder.getDeclaredField("INSTANCE");
        instance.setAccessible(true);
        Object previous = instance.get(null);
        setRegistryAccess(java.util.Optional.of(access));
        return previous;
    }

    private static org.bukkit.block.BlockType blockType(NamespacedKey key) {
        return (org.bukkit.block.BlockType) Proxy.newProxyInstance(
                org.bukkit.block.BlockType.class.getClassLoader(),
                new Class<?>[] {org.bukkit.block.BlockType.class}, (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) return proxyObjectMethod(proxy, method, args);
                    return switch (method.getName()) {
                        case "getKey" -> key;
                        case "getTranslationKey" -> "block." + key.getNamespace() + "." + key.getKey();
                        case "isSolid", "hasCollision", "isOccluding" -> key.getKey().equals("stone");
                        case "isAir" -> key.getKey().equals("air");
                        case "isFlammable", "isBurnable", "hasGravity", "isInteractable" -> false;
                        case "asMaterial" -> Material.getMaterial(key.getKey().toUpperCase(java.util.Locale.ROOT));
                        case "getHardness", "getBlastResistance", "getSlipperiness" -> 1.0f;
                        default -> defaultValue(method.getReturnType());
                    };
                });
    }

    private static void setRegistryAccess(Object accessValue) throws Exception {
        Class<?> holder = Class.forName("io.papermc.paper.registry.RegistryAccessHolder", true,
                LimboAdmissionTest.class.getClassLoader());
        Field instance = holder.getDeclaredField("INSTANCE");
        instance.setAccessible(true);
        Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
        Field singleton = unsafeType.getDeclaredField("theUnsafe");
        singleton.setAccessible(true);
        Object unsafe = singleton.get(null);
        Object base = unsafeType.getMethod("staticFieldBase", Field.class).invoke(unsafe, instance);
        long offset = (long) unsafeType.getMethod("staticFieldOffset", Field.class).invoke(unsafe, instance);
        unsafeType.getMethod("putObjectVolatile", Object.class, long.class, Object.class)
                .invoke(unsafe, base, offset, accessValue);
    }

    private static NamespacedKey toNamespacedKey(Object key) {
        if (key instanceof NamespacedKey namespacedKey) return namespacedKey;
        net.kyori.adventure.key.Key adventureKey = (net.kyori.adventure.key.Key) key;
        return new NamespacedKey(adventureKey.namespace(), adventureKey.value());
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive() || type == void.class) return null;
        if (type == boolean.class) return false;
        if (type == char.class) return '\0';
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0.0f;
        if (type == double.class) return 0.0d;
        throw new AssertionError("Unknown primitive: " + type);
    }

    private static Object proxyObjectMethod(Object proxy, java.lang.reflect.Method method, Object[] args) {
        return switch (method.getName()) {
            case "toString" -> "LimboAdmissionTest proxy";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new AssertionError("Unexpected object method: " + method.getName());
        };
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    /** The manager constructor only needs the namespace for its persistent keys. */
    private static final class TestPlugin extends JavaPlugin {
        private static final Logger LOGGER = Logger.getAnonymousLogger();
        static { LOGGER.setLevel(java.util.logging.Level.OFF); }
        @Override public Logger getLogger() { return LOGGER; }
        @Override
        public String namespace() {
            return "spaydiscore";
        }
    }

    private static final class TestPotionEffectType extends PotionEffectType {
        private final NamespacedKey key;
        private final int id;

        private TestPotionEffectType(NamespacedKey key, int id) {
            this.key = key;
            this.id = id;
        }

        @Override public NamespacedKey getKey() { return key; }
        @Override public PotionEffect createEffect(int duration, int amplifier) { return null; }
        @Override public boolean isInstant() { return false; }
        @Override public PotionEffectTypeCategory getCategory() { return null; }
        @Override public Color getColor() { return null; }
        @Override public double getDurationModifier() { return 1.0; }
        @Override public int getId() { return id; }
        @Override public String getName() { return key.getKey(); }
        @Override public String getTranslationKey() { return "effect.minecraft." + key.getKey(); }
        @Override public String translationKey() { return "effect.minecraft." + key.getKey(); }
        @Override public Map<Attribute, AttributeModifier> getEffectAttributes() { return Map.of(); }
        @Override public double getAttributeModifierAmount(Attribute attribute, int amplifier) { return 0.0; }
        @Override public Category getEffectCategory() { return null; }
    }

    public interface DirectReviveApi {
        boolean hasPlayerData(UUID playerId);
        boolean isPlayerDead(UUID playerId);
        boolean isFirstDeathPending(Player player);
    }

    public interface PairedReviveApi {
        boolean hasPlayerData(UUID playerId);
        boolean isPlayerDead(UUID playerId);
        boolean reviveFromLimbo(Player player, Location location);
    }

    private static final class PairedState {
        private int revivalCalls;
        private Location lastTarget;
        private boolean revivalResult = true;
    }

    public interface LegacyReviveApi {
        LegacyDataManager getDataManager();
        boolean isFreeFirstDeath();
    }

    public interface LegacyDataManager {
        boolean hasPlayerData(UUID playerId);
        boolean isDead(UUID playerId);
        boolean hasDied(UUID playerId);
    }

    private static final class DirectState {
        private boolean known = true;
        private boolean dead;
        private boolean firstDeathPending;
    }

    private static final class LegacyState {
        private boolean known = true;
        private boolean dead;
        private boolean firstDeathPending;
    }

    private static final class Harness {
        private final JavaPlugin plugin;
        private final World normalWorld = world("world", new NamespacedKey("minecraft", "overworld"));
        private final World limboWorld = world("limbo", new NamespacedKey("spaydiscore", "limbo"));
        private Plugin revivePlugin;
        private final List<Runnable> scheduled = new ArrayList<>();
        private final PluginManager pluginManager;
        private final BukkitScheduler scheduler;
        private final Server server;

        private Harness() throws Exception {
            plugin = createPlugin();
            pluginManager = (PluginManager) Proxy.newProxyInstance(PluginManager.class.getClassLoader(),
                    new Class<?>[] {PluginManager.class}, (proxy, method, args) -> {
                        if (method.getDeclaringClass() == Object.class) return proxyObjectMethod(proxy, method, args);
                        return switch (method.getName()) {
                            case "getPlugin" -> revivePlugin != null
                                    && "SMPRevive".equalsIgnoreCase((String) args[0]) ? revivePlugin : null;
                            case "getPlugins" -> revivePlugin == null
                                    ? new Plugin[0] : new Plugin[] {revivePlugin};
                            default -> defaultValue(method.getReturnType());
                        };
                    });
            scheduler = (BukkitScheduler) Proxy.newProxyInstance(BukkitScheduler.class.getClassLoader(),
                    new Class<?>[] {BukkitScheduler.class}, (proxy, method, args) -> {
                        if (method.getDeclaringClass() == Object.class) return proxyObjectMethod(proxy, method, args);
                        if (method.getName().equals("runTask")) {
                            scheduled.add((Runnable) args[1]);
                            return null;
                        }
                        return defaultValue(method.getReturnType());
                    });
            server = (Server) Proxy.newProxyInstance(Server.class.getClassLoader(), new Class<?>[] {Server.class},
                    (proxy, method, args) -> {
                        if (method.getDeclaringClass() == Object.class) return proxyObjectMethod(proxy, method, args);
                        return switch (method.getName()) {
                            case "getPluginManager" -> pluginManager;
                            case "getScheduler" -> scheduler;
                            case "getCurrentTick" -> 1;
                            case "getWorld" -> {
                                if (args[0].equals(normalWorld.getName()) || args[0].equals(normalWorld.getUID())) {
                                    yield normalWorld;
                                }
                                if (args[0].equals(limboWorld.getName()) || args[0].equals(limboWorld.getUID())) {
                                    yield limboWorld;
                                }
                                yield null;
                            }
                            default -> defaultValue(method.getReturnType());
                        };
                    });
        }

        private Fixture fixture(World initialWorld) throws Exception {
            LimboManager manager = new LimboManager(plugin);
            setField(manager, "enabled", true);
            setField(manager, "limboWorld", limboWorld);
            PlayerState playerState = new PlayerState(initialWorld);
            Player player = player(playerState);
            return new Fixture(manager, playerState, player, playerState.persistentData);
        }

        private void runScheduledTick() {
            List<Runnable> tasks = List.copyOf(scheduled);
            scheduled.clear();
            for (Runnable task : tasks) task.run();
        }

        private void installDirect(DirectState state) {
            revivePlugin = apiPlugin(DirectReviveApi.class, (method, args) -> switch (method.getName()) {
                case "hasPlayerData" -> state.known;
                case "isPlayerDead" -> state.dead;
                case "isFirstDeathPending" -> state.firstDeathPending;
                default -> defaultValue(method.getReturnType());
            });
        }

        private void installPaired(PairedState state) {
            revivePlugin = apiPlugin(PairedReviveApi.class, (method, args) -> switch (method.getName()) {
                case "hasPlayerData" -> true;
                case "isPlayerDead" -> true;
                case "reviveFromLimbo" -> {
                    state.revivalCalls++;
                    state.lastTarget = ((Location) args[1]).clone();
                    if (state.revivalResult) ((Player) args[0]).teleport(state.lastTarget);
                    yield state.revivalResult;
                }
                default -> defaultValue(method.getReturnType());
            });
        }

        private void installLegacy(LegacyState state) {
            LegacyDataManager data = (LegacyDataManager) Proxy.newProxyInstance(
                    LegacyDataManager.class.getClassLoader(), new Class<?>[] {LegacyDataManager.class},
                    (proxy, method, args) -> {
                        if (method.getDeclaringClass() == Object.class) return proxyObjectMethod(proxy, method, args);
                        return switch (method.getName()) {
                            case "hasPlayerData" -> state.known;
                            case "isDead" -> state.dead;
                            case "hasDied" -> !state.firstDeathPending;
                            default -> defaultValue(method.getReturnType());
                        };
                    });
            revivePlugin = apiPlugin(LegacyReviveApi.class, (method, args) -> switch (method.getName()) {
                case "getDataManager" -> data;
                case "isFreeFirstDeath" -> true;
                default -> defaultValue(method.getReturnType());
            });
        }

        private Plugin apiPlugin(Class<?> api, ApiInvocation invocation) {
            InvocationHandler handler = (proxy, method, args) -> {
                if (method.getDeclaringClass() == Object.class) return proxyObjectMethod(proxy, method, args);
                return switch (method.getName()) {
                    case "getName" -> "SMPRevive";
                    case "isEnabled" -> true;
                    case "getLogger" -> Logger.getLogger("SMPRevive-test");
                    default -> api.isInstance(proxy)
                            ? invocation.invoke(method, args) : defaultValue(method.getReturnType());
                };
            };
            return (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(),
                    new Class<?>[] {Plugin.class, api}, handler);
        }

        private void uninstallRevive() {
            revivePlugin = null;
        }

        @FunctionalInterface
        private interface ApiInvocation {
            Object invoke(java.lang.reflect.Method method, Object[] args) throws Throwable;
        }
    }

    private static World world(String name, NamespacedKey key) {
        BorderWorldModel model = new BorderWorldModel(name);
        WORLD_MODELS.put(name, model);
        World[] worldRef = new World[1];
        WorldBorder border = (WorldBorder) Proxy.newProxyInstance(WorldBorder.class.getClassLoader(),
                new Class<?>[] {WorldBorder.class}, (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) return proxyObjectMethod(proxy, method, args);
                    return switch (method.getName()) {
                        case "getSize" -> model.size;
                        case "getCenter" -> model.size > 0.0
                                ? new Location(worldRef[0], model.centerX, 0.0, model.centerZ) : null;
                        case "isInside" -> args != null && args.length == 1
                                && args[0] instanceof Location location && model.isInside(location);
                        default -> defaultValue(method.getReturnType());
                    };
                });
        worldRef[0] = (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[] {World.class},
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) return proxyObjectMethod(proxy, method, args);
                    return switch (method.getName()) {
                        case "getName" -> name;
                        case "getKey" -> key;
                        case "getUID" -> UUID.nameUUIDFromBytes(name.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        case "getSpawnLocation" -> new Location((World) proxy, model.spawnX, 65, model.spawnZ);
                        case "getWorldBorder" -> border;
                        case "getMinHeight" -> 0;
                        case "getMaxHeight" -> 320;
                        case "getHighestBlockYAt" -> {
                            if (args[0] instanceof Location location) {
                                model.recordTerrainRead(location.getBlockX(), location.getBlockZ());
                            } else {
                                model.recordTerrainRead((int) args[0], (int) args[1]);
                            }
                            yield 64;
                        }
                        case "getBlockAt" -> {
                            if (args[0] instanceof Location location) {
                                yield model.block(location.getBlockX(), location.getBlockY(), location.getBlockZ());
                            }
                            yield model.block((int) args[0], (int) args[1], (int) args[2]);
                        }
                        default -> defaultValue(method.getReturnType());
                    };
                });
        return worldRef[0];
    }

    private static final class BorderWorldModel {
        private final String name;
        private double centerX;
        private double centerZ;
        private double size;
        private double spawnX;
        private double spawnZ;
        private int safeTerrainX;
        private int safeTerrainZ;
        private boolean terrainUnsafe;
        private BlockPosition obstruction;
        private int terrainReads;
        private int outsideBorderTerrainReads;

        private BorderWorldModel(String name) {
            this.name = name;
        }

        private void setBorder(double x, double z, double newSize) {
            centerX = x;
            centerZ = z;
            size = newSize;
        }

        private void setSafeTerrain(int x, int z) {
            safeTerrainX = x;
            safeTerrainZ = z;
        }

        private void setObstruction(int x, int y, int z) {
            obstruction = new BlockPosition(x, y, z);
        }

        private void clearObstruction() {
            obstruction = null;
        }

        private void resetTerrainReads() {
            terrainReads = 0;
            outsideBorderTerrainReads = 0;
        }

        private boolean isInside(Location location) {
            return size > 0.0 && Math.abs(location.getX() - centerX) <= size / 2.0
                    && Math.abs(location.getZ() - centerZ) <= size / 2.0;
        }

        private void recordTerrainRead(int x, int z) {
            terrainReads++;
            if (!blockColumnIntersectsBorder(x, z)) {
                outsideBorderTerrainReads++;
            }
        }

        private boolean blockColumnIntersectsBorder(int x, int z) {
            if (size <= 0.0) return false;
            double minX = centerX - size / 2.0;
            double maxX = centerX + size / 2.0;
            double minZ = centerZ - size / 2.0;
            double maxZ = centerZ + size / 2.0;
            return x + 1.0 >= minX && x <= maxX && z + 1.0 >= minZ && z <= maxZ;
        }

        private Block block(int x, int y, int z) {
            recordTerrainRead(x, z);
            Material material = !terrainUnsafe && obstruction != null
                    && obstruction.x == x && obstruction.y == y && obstruction.z == z
                    ? Material.STONE
                    : !terrainUnsafe && y == 64 && Math.abs(x - safeTerrainX) <= 1
                            && Math.abs(z - safeTerrainZ) <= 1 ? Material.STONE : Material.AIR;
            return (Block) Proxy.newProxyInstance(Block.class.getClassLoader(), new Class<?>[] {Block.class},
                    (proxy, method, args) -> {
                        if (method.getDeclaringClass() == Object.class) return proxyObjectMethod(proxy, method, args);
                        return switch (method.getName()) {
                            case "getType" -> material;
                            case "isLiquid" -> false;
                            case "isPassable" -> material == Material.AIR;
                            case "getX" -> x;
                            case "getY" -> y;
                            case "getZ" -> z;
                            default -> defaultValue(method.getReturnType());
                        };
                    });
        }
    }

    private record BlockPosition(int x, int y, int z) {
    }

    private static Player player(PlayerState state) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[] {Player.class},
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) return proxyObjectMethod(proxy, method, args);
                    return switch (method.getName()) {
                        case "getUniqueId" -> state.id;
                        case "getWorld" -> state.world;
                        case "getPersistentDataContainer" -> state.persistentData.proxy;
                        case "getGameMode" -> state.gameMode;
                        case "setGameMode" -> { state.gameMode = (GameMode) args[0]; state.gameModeWrites++; yield null; }
                        case "getAllowFlight" -> state.allowFlight;
                        case "setAllowFlight" -> { state.allowFlight = (boolean) args[0]; yield null; }
                        case "isFlying" -> state.flying;
                        case "setFlying" -> { state.flying = (boolean) args[0]; yield null; }
                        case "hasGravity" -> state.gravity;
                        case "setGravity" -> { state.gravity = (boolean) args[0]; yield null; }
                        case "getActivePotionEffects" -> List.copyOf(state.effects.values());
                        case "removePotionEffect" -> { state.effects.remove(args[0]); yield null; }
                        case "addPotionEffect" -> { var effect = (PotionEffect) args[0];
                            state.effects.put(effect.getType(), effect); yield true; }
                        case "getScoreboard" -> state.scoreboard;
                        case "setScoreboard" -> { state.scoreboard = (Scoreboard) args[0]; yield null; }
                        case "isOnline" -> true;
                        case "isDead" -> false;
                        case "getLocation" -> new Location(state.world, 0.0, 65.0, 0.0);
                        case "teleport" -> { state.teleports++; state.world = ((Location) args[0]).getWorld(); yield true; }
                        case "getName" -> "AdmissionTestPlayer";
                        default -> defaultValue(method.getReturnType());
                    };
                });
    }

    private static final class PlayerState {
        private final UUID id = UUID.randomUUID();
        private final PersistentData persistentData = new PersistentData();
        private World world;
        private GameMode gameMode = GameMode.SURVIVAL;
        private boolean allowFlight;
        private boolean flying;
        private boolean gravity = true;
        private int gameModeWrites;
        private int teleports;
        private final Map<PotionEffectType, PotionEffect> effects = new HashMap<>();
        private Scoreboard scoreboard;

        private PlayerState(World world) {
            this.world = world;
        }
    }

    private static final class PersistentData {
        private final Map<NamespacedKey, Object> values = new HashMap<>();
        private final PersistentDataContainer proxy = (PersistentDataContainer) Proxy.newProxyInstance(
                PersistentDataContainer.class.getClassLoader(), new Class<?>[] {PersistentDataContainer.class},
                (target, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) return proxyObjectMethod(target, method, args);
                    return switch (method.getName()) {
                        case "get" -> values.get(args[0]);
                        case "has" -> values.containsKey(args[0]);
                        case "set" -> { values.put((NamespacedKey) args[0], args[2]); yield null; }
                        case "remove" -> { values.remove(args[0]); yield null; }
                        case "getKeys" -> Set.copyOf(values.keySet());
                        default -> defaultValue(method.getReturnType());
                    };
                });

        private boolean contains(NamespacedKey key) {
            return values.containsKey(key);
        }
    }

    private record Fixture(LimboManager manager, PlayerState playerState, Player player,
                           PersistentData persistentData) {
    }
}
