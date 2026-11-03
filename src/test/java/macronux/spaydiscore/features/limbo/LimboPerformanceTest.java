package macronux.spaydiscore.features.limbo;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

/** Check the optimized scheduling against the original emission rule and count metadata writes. */
public final class LimboPerformanceTest {
    public static void run() {
        for (int players : new int[] {0, 1, 2, 8, 20, 50, 100, 1000, Integer.MAX_VALUE}) {
            for (StarQuality quality : StarQuality.values()) {
                int period = quality.globalPeriod(players);
                for (long tick : new long[] {0, 1, 2, 19, 20, 599, 1200, Integer.MAX_VALUE, Long.MAX_VALUE}) {
                    List<Integer> expected = new ArrayList<>();
                    for (int index = 0; index < 10_000; index++) {
                        if (index % period == tick % period) expected.add(index);
                    }
                    List<Integer> actual = new ArrayList<>();
                    quality.forEachGlobalStar(players, tick, 10_000, actual::add);
                    require(actual.equals(expected), "due stars must match the original rule: "
                            + players + "/" + quality + "/" + tick);
                }
                quality.forEachGlobalStar(players, 0, 0, index -> {
                    throw new AssertionError("empty fields cannot emit");
                });
            }
        }
        for (int players : new int[] {1, 20, 50, 100}) {
            int[] visits = {0};
            StarQuality.NORMAL.forEachGlobalStar(players, 0, 10_000, index -> visits[0]++);
            require(visits[0] == 2000 / players, "normal candidate work falls as player count rises");
            System.out.println("Global star candidates at " + players + " players: " + visits[0]);
        }

        TeamState state = new TeamState();
        Team team = (Team) Proxy.newProxyInstance(Team.class.getClassLoader(), new Class<?>[] {Team.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "allowFriendlyFire" -> state.friendlyFire;
                    case "canSeeFriendlyInvisibles" -> state.friendlyInvisibles;
                    case "getOption" -> state.options.get((Team.Option) args[0]);
                    case "setAllowFriendlyFire" -> {
                        state.friendlyFire = (boolean) args[0]; state.writes++; yield null;
                    }
                    case "setCanSeeFriendlyInvisibles" -> {
                        state.friendlyInvisibles = (boolean) args[0]; state.writes++; yield null;
                    }
                    case "setOption" -> {
                        state.options.put((Team.Option) args[0], (Team.OptionStatus) args[1]);
                        state.writes++; yield null;
                    }
                    default -> throw new AssertionError("Unexpected team call: " + method.getName());
                });
        Scoreboard board = (Scoreboard) Proxy.newProxyInstance(Scoreboard.class.getClassLoader(),
                new Class<?>[] {Scoreboard.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getTeam" -> state.registered ? team : null;
                    case "registerNewTeam" -> { state.registered = true; yield team; }
                    default -> throw new AssertionError("Unexpected scoreboard call: " + method.getName());
                });
        require(LimboManager.getOrCreateLimboTeam(board) == team, "register the shared team");
        require(state.writes == 4, "initial team flags are applied");
        for (int tick = 0; tick < 60; tick++) {
            for (int player = 0; player < 100; player++) LimboManager.getOrCreateLimboTeam(board);
        }
        require(state.writes == 4, "100-player steady state must not rebroadcast unchanged flags");
        state.options.put(Team.Option.COLLISION_RULE, Team.OptionStatus.ALWAYS);
        LimboManager.getOrCreateLimboTeam(board);
        require(state.writes == 5 && state.options.get(Team.Option.COLLISION_RULE) == Team.OptionStatus.NEVER,
                "externally changed team rules are still repaired");
        checkIsolationSearch();
        System.out.println("Limbo phase scheduling and 100-player team metadata checks passed");
    }

    private static void checkIsolationSearch() {
        List<Player> players = new ArrayList<>();
        Map<UUID, Location> positions = new HashMap<>();
        AtomicInteger identityReads = new AtomicInteger();
        for (int index = 0; index < 100; index++) {
            UUID id = new UUID(0, index);
            Player player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
                    new Class<?>[] {Player.class}, (proxy, method, args) -> {
                        if (method.getName().equals("getUniqueId")) { identityReads.incrementAndGet(); return id; }
                        throw new AssertionError("Search must use cached positions: " + method.getName());
                    });
            players.add(player);
            positions.put(id, new Location(null, index * 0.05, 65, 0));
        }
        for (Player player : players) {
            require(LimboManager.findNearestOrNearbyLimboPlayer(player, players, positions, 32 * 32) != null,
                    "clustered players are not isolated");
        }
        require(identityReads.get() < 500, "100-player cluster search must stop at a nearby player");
        Player subject = players.getFirst();
        List<Player> isolatedGroup = players.subList(0, 3);
        positions.put(new UUID(0, 1), new Location(null, 100, 65, 0));
        positions.put(new UUID(0, 2), new Location(null, 50, 65, 0));
        require(LimboManager.findNearestOrNearbyLimboPlayer(subject, isolatedGroup, positions, 32 * 32)
                == players.get(2), "isolated players still choose the exact nearest destination");
        positions.put(new UUID(0, 2), new Location(null, 32, 65, 0));
        require(LimboManager.findNearestOrNearbyLimboPlayer(subject, isolatedGroup, positions, 32 * 32)
                == players.get(2), "a player on the radius boundary is nearby");
        require(LimboManager.findNearestOrNearbyLimboPlayer(subject, List.of(subject), positions, 32 * 32)
                == null, "a player cannot be their own neighbor");
        World otherWorld = (World) Proxy.newProxyInstance(World.class.getClassLoader(),
                new Class<?>[] {World.class}, (proxy, method, args) -> {
                    throw new AssertionError("World methods are not needed for isolation geometry");
                });
        positions.put(new UUID(0, 2), new Location(otherWorld, 1, 65, 0));
        require(LimboManager.findNearestOrNearbyLimboPlayer(subject, isolatedGroup, positions, 32 * 32)
                == players.get(1), "a closer player in another world is excluded");
        System.out.println("Cached isolation geometry and clustered 100-player search checks passed");
    }

    private static final class TeamState {
        private boolean registered;
        private boolean friendlyFire = true;
        private boolean friendlyInvisibles;
        private int writes;
        private final EnumMap<Team.Option, Team.OptionStatus> options = new EnumMap<>(Team.Option.class);
        private TeamState() {
            for (Team.Option option : Team.Option.values()) options.put(option, Team.OptionStatus.ALWAYS);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
