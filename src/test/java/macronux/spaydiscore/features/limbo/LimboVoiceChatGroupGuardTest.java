package macronux.spaydiscore.features.limbo;

import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.events.Event;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Focused checks for the voicechat group privacy guard. */
public final class LimboVoiceChatGroupGuardTest {
    private LimboVoiceChatGroupGuardTest() {
    }

    public static void run() {
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicBoolean inGroup = new AtomicBoolean(true);
        AtomicInteger leaves = new AtomicInteger();

        boolean blocked = LimboVoiceChatIntegration.enforceGroupPrivacy(
                true, connection(inGroup, leaves), event(cancelled));
        require(blocked && cancelled.get() && !inGroup.get() && leaves.get() == 1,
                "Limbo group attempts must be cancelled and leave the current group");

        cancelled.set(false);
        inGroup.set(true);
        leaves.set(0);
        boolean allowed = LimboVoiceChatIntegration.enforceGroupPrivacy(
                false, connection(inGroup, leaves), event(cancelled));
        require(!allowed && !cancelled.get() && inGroup.get() && leaves.get() == 0,
                "Players outside Limbo must keep their group");
    }

    private static Event event(AtomicBoolean cancelled) {
        return (Event) Proxy.newProxyInstance(Event.class.getClassLoader(), new Class<?>[]{Event.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "isCancellable" -> true;
                    case "cancel" -> {
                        cancelled.set(true);
                        yield true;
                    }
                    case "isCancelled" -> cancelled.get();
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static VoicechatConnection connection(AtomicBoolean inGroup, AtomicInteger leaves) {
        return (VoicechatConnection) Proxy.newProxyInstance(VoicechatConnection.class.getClassLoader(),
                new Class<?>[]{VoicechatConnection.class}, (proxy, method, args) -> {
                    if (method.getName().equals("isInGroup")) {
                        return inGroup.get();
                    }
                    if (method.getName().equals("setGroup")) {
                        if (args[0] == null) {
                            inGroup.set(false);
                            leaves.incrementAndGet();
                        }
                        return null;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
