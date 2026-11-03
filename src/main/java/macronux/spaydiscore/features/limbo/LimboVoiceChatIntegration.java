package macronux.spaydiscore.features.limbo;

import de.maxhenkel.voicechat.api.BukkitVoicechatService;
import de.maxhenkel.voicechat.api.Position;
import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.events.CreateGroupEvent;
import de.maxhenkel.voicechat.api.events.Event;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.JoinGroupEvent;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.events.PlayerConnectedEvent;
import de.maxhenkel.voicechat.api.events.PlayerDisconnectedEvent;
import de.maxhenkel.voicechat.api.opus.OpusDecoder;
import de.maxhenkel.voicechat.api.opus.OpusEncoder;
import de.maxhenkel.voicechat.api.packets.LocationalSoundPacket;
import de.maxhenkel.voicechat.api.packets.MicrophonePacket;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Optional Simple Voice Chat integration that adds a bounded Limbo echo. */
public final class LimboVoiceChatIntegration implements VoicechatPlugin, Listener {

    /*
     * Sound Physics uses four OpenAL reverb stages with short, medium and late
     * decay times. The server cannot access a client's OpenAL context, so the
     * Limbo bridge approximates those stages with bounded PCM reflection taps.
     */
    private static final double[] REVERB_STAGE_DECAY_SECONDS = {0.15, 0.55, 1.68, 4.142};
    private static final double AIR_ABSORPTION_GAIN = 0.92;
    private static final int[] ECHO_DELAY_MILLIS = {
            120, 240, 360, 480, 600, 720, 960, 1_280, 1_680, 2_400, 3_200, 4_140
    };
    private static final int[] ECHO_DELAYS = {
            ECHO_DELAY_MILLIS[0] / 20,
            ECHO_DELAY_MILLIS[1] / 20,
            ECHO_DELAY_MILLIS[2] / 20,
            ECHO_DELAY_MILLIS[3] / 20,
            ECHO_DELAY_MILLIS[4] / 20,
            ECHO_DELAY_MILLIS[5] / 20,
            ECHO_DELAY_MILLIS[6] / 20,
            ECHO_DELAY_MILLIS[7] / 20,
            ECHO_DELAY_MILLIS[8] / 20,
            ECHO_DELAY_MILLIS[9] / 20,
            ECHO_DELAY_MILLIS[10] / 20,
            ECHO_DELAY_MILLIS[11] / 20
    };
    private static final int[] ECHO_STAGES = {0, 0, 0, 1, 1, 1, 2, 2, 2, 3, 3, 3};
    private static final double[] ECHO_GAINS = {
            0.72, 0.52, 0.38, 0.09, 0.075, 0.06,
            0.008, 0.007, 0.006, 0.004, 0.003, 0.002
    };
    private static final double[] ECHO_WEIGHTS = buildEchoWeights();
    private static final int HISTORY_SIZE = 209;
    private static final double ECHO_MIX_GAIN = 0.28;
    private static final float ECHO_MAX_DISTANCE_BLOCKS = 100.0F;
    private static final double ECHO_DIFFUSION = 0.25;
    private static final double ECHO_SMOOTHING = 0.35;
    private static final int PHRASE_HISTORY_SIZE = 30;
    private static final int MAX_PHRASE_FRAMES = 400;
    private static final int MIN_PHRASE_FRAMES = 6;
    // Simple Voice Chat sends 20 ms frames; keep a natural pause of about five seconds.
    private static final int TRAILING_SILENCE_FRAMES = 100;
    private static final int PHRASE_PREROLL_FRAMES = 10;
    private static final double SPEECH_RMS_THRESHOLD = 280.0;
    private static final long PHRASE_IDLE_TIMEOUT_MILLIS = 5_000L;
    private static final long PHRASE_IDLE_TIMEOUT_NANOS = PHRASE_IDLE_TIMEOUT_MILLIS * 1_000_000L;
    private static final long PHRASE_REPLAY_MIN_TICKS = 2_400L;
    private static final long PHRASE_REPLAY_MAX_TICKS = 6_000L;
    private static final double PHRASE_MIN_DISTANCE = 70.0;
    private static final double PHRASE_MAX_DISTANCE = 95.0;
    private static final float PHRASE_SOUND_DISTANCE = 100.0F;
    private static final long PHRASE_FRAME_INTERVAL_NANOS = 20_000_000L;
    private static final int PHRASE_RENDER_CHUNK_SAMPLES = 24_000;
    private static final int PHRASE_RENDER_GRAIN_SAMPLES = 480;
    private static final int PHRASE_RENDER_HOP_SAMPLES = 240;
    private static final int PHRASE_RENDER_CROSSFADE_SAMPLES = 2_400;
    private static final int BARREL_SHORT_DELAY_SAMPLES = 864;
    private static final int BARREL_LONG_DELAY_SAMPLES = 1_584;
    private static final double BARREL_GAIN = 0.58;
    private static final double BARREL_LOW_PASS_ALPHA = 0.14;
    private static final String PHRASE_DIRECTORY = "phrases";
    private static final int OPUS_FRAME_SAMPLES = 960;

    private static double[] buildEchoWeights() {
        double[] weights = new double[ECHO_GAINS.length];
        for (int i = 0; i < weights.length; i++) {
            double stageDecay = REVERB_STAGE_DECAY_SECONDS[ECHO_STAGES[i]];
            double delaySeconds = ECHO_DELAY_MILLIS[i] / 1_000.0;
            weights[i] = ECHO_GAINS[i]
                    * Math.exp(-delaySeconds / stageDecay)
                    * Math.pow(AIR_ABSORPTION_GAIN, i);
        }
        return weights;
    }

    private final JavaPlugin plugin;
    private final LimboManager limboManager;
    private final LimboEffectClock groupEnforcementClock = new LimboEffectClock(1);
    private final LimboEffectClock phrasePollClock = new LimboEffectClock(1);
    private final LimboEffectClock captureCleanupClock = new LimboEffectClock(1);
    private volatile PhraseMode phraseMode;
    private final Map<UUID, EchoState> echoStates = new ConcurrentHashMap<>();
    private final Map<UUID, Long> echoSequences = new ConcurrentHashMap<>();
    private final Map<UUID, PhraseCaptureState> phraseCaptureStates = new ConcurrentHashMap<>();
    private final Map<UUID, Long> nextPhraseAtNanos = new ConcurrentHashMap<>();
    private final Map<UUID, PhrasePlaybackState> phrasePlaybackTasks = new ConcurrentHashMap<>();
    private final PhraseAudioStore phraseHistory;
    private final ExecutorService phraseStorageExecutor;
    private final java.util.Set<UUID> warnedPlayers = ConcurrentHashMap.newKeySet();
    private volatile VoicechatServerApi api;
    private volatile ScheduledExecutorService phrasePlaybackExecutor;
    private BukkitTask groupEnforcementTask;
    private BukkitTask phrasePlaybackTask;
    private BukkitTask phraseCaptureCleanupTask;

    public LimboVoiceChatIntegration(JavaPlugin plugin, LimboManager limboManager) {
        this.plugin = plugin;
        this.limboManager = limboManager;
        this.phraseMode = PhraseMode.parseOrDefault(
                plugin.getConfig().getString("limbo.phrase-mode", "normal"));
        this.phraseStorageExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "SpaydisCore-LimboPhraseStore");
            thread.setDaemon(true);
            return thread;
        });
        this.phraseHistory = new PhraseAudioStore(plugin, PHRASE_HISTORY_SIZE, phraseStorageExecutor);
    }

    public void start() {
        BukkitVoicechatService service = Bukkit.getServicesManager().load(BukkitVoicechatService.class);
        if (service == null) {
            throw new IllegalStateException("Simple Voice Chat service is unavailable");
        }
        service.registerPlugin(this);
        Bukkit.getPluginManager().registerEvents(this, plugin);
        enforceLimboGroups();
        Bukkit.getScheduler().runTask(plugin, this::enforceLimboGroups);
        if (groupEnforcementTask == null) {
            groupEnforcementTask = Bukkit.getScheduler().runTaskTimer(
                    plugin, this::tickGroupEnforcement, 20L, 20L);
        }
        if (phrasePlaybackExecutor == null || phrasePlaybackExecutor.isShutdown()) {
            phrasePlaybackExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "SpaydisCore-LimboVoice");
                thread.setDaemon(true);
                return thread;
            });
        }
        if (phrasePlaybackTask == null) {
            phrasePlaybackTask = Bukkit.getScheduler().runTaskTimer(
                    plugin, this::tickPhrasePlayback, 100L, 20L);
        }
        if (phraseCaptureCleanupTask == null) {
            phraseCaptureCleanupTask = Bukkit.getScheduler().runTaskTimer(
                    plugin, this::tickPhraseCaptureCleanup, 100L, 20L);
        }
    }

    @Override
    public String getPluginId() {
        return "spaydiscore_limbo_echo";
    }

    public String getPhraseMode() {
        return phraseMode.id;
    }

    public void reloadPhraseMode() {
        phraseMode = PhraseMode.parseOrDefault(
                plugin.getConfig().getString("limbo.phrase-mode", "normal"));
    }

    public boolean setPhraseMode(String value) {
        PhraseMode selected = PhraseMode.parse(value);
        if (selected == null) {
            return false;
        }
        phraseMode = selected;
        try {
            plugin.getConfig().set("limbo.phrase-mode", selected.id);
            plugin.saveConfig();
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("Could not persist Limbo phrase mode: "
                    + exception.getMessage());
        }
        return true;
    }

    @Override
    public void initialize(VoicechatApi api) {
        this.api = api instanceof VoicechatServerApi serverApi ? serverApi : null;
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(MicrophonePacketEvent.class, this::onMicrophonePacket);
        registration.registerEvent(JoinGroupEvent.class, this::onJoinGroup);
        registration.registerEvent(CreateGroupEvent.class, this::onCreateGroup);
        registration.registerEvent(PlayerConnectedEvent.class, this::onPlayerConnected);
        registration.registerEvent(PlayerDisconnectedEvent.class, this::onPlayerDisconnected);
    }

    private void onJoinGroup(JoinGroupEvent event) {
        handleGroupAttempt(event.getConnection(), event);
    }

    private void onCreateGroup(CreateGroupEvent event) {
        handleGroupAttempt(event.getConnection(), event);
    }

    private void handleGroupAttempt(VoicechatConnection connection, Event event) {
        if (connection == null || connection.getPlayer() == null
                || !isLimboPlayer(connection.getPlayer().getUuid())) {
            return;
        }
        enforceGroupPrivacy(true, connection, event);
    }

    private void onPlayerConnected(PlayerConnectedEvent event) {
        VoicechatConnection connection = event.getConnection();
        if (connection == null || connection.getPlayer() == null) {
            return;
        }
        UUID playerUuid = connection.getPlayer().getUuid();
        if (isLimboPlayer(playerUuid)) {
            enforceGroupPrivacy(true, connection, null);
            return;
        }
        // The callback can run before Bukkit's join/limbo state has been published.
        Bukkit.getScheduler().runTask(plugin, () -> enforceLimboPlayerGroup(playerUuid));
    }

    private void onMicrophonePacket(MicrophonePacketEvent event) {
        VoicechatConnection connection = event.getSenderConnection();
        if (connection == null || connection.getPlayer() == null) {
            return;
        }
        UUID senderUuid = connection.getPlayer().getUuid();
        // Group voice is already routed privately by Simple Voice Chat.
        if (connection.isInGroup()) {
            if (isLimboPlayer(senderUuid)) {
                enforceGroupPrivacy(true, connection, event);
            }
            return;
        }
        if (!limboManager.isVoiceEchoEnabled()) {
            return;
        }

        VoicechatServerApi serverApi = api;
        if (serverApi == null) {
            return;
        }
        MicrophonePacket packet = event.getPacket();
        if (packet == null) {
            return;
        }
        byte[] encoded = packet.getOpusEncodedData();
        if (encoded == null || encoded.length == 0) {
            return;
        }
        if (!limboManager.isVoiceEchoParticipant(senderUuid)) {
            if (!limboManager.isVoiceCaptureParticipant(senderUuid)) {
                return;
            }
            capturePhrase(senderUuid, packet, serverApi);
            return;
        }

        EchoState state = null;
        try {
            state = echoStates.computeIfAbsent(senderUuid,
                    ignored -> new EchoState(serverApi.createDecoder(), serverApi.createEncoder()));
            byte[] echoed = state.process(encoded);
            if (echoed != null) {
                sendEchoToLimboPlayers(serverApi, connection, packet, echoed,
                        nextEchoSequence(senderUuid));
            }
        } catch (RuntimeException exception) {
            removeState(senderUuid);
            if (warnedPlayers.add(senderUuid)) {
                plugin.getLogger().warning("Limbo voice echo failed for " + senderUuid
                        + "; the original packet will be used: " + exception.getMessage());
            }
        }
    }

    private void capturePhrase(UUID speakerUuid, MicrophonePacket packet,
                                VoicechatServerApi serverApi) {
        try {
            PhraseCaptureState state = phraseCaptureStates.computeIfAbsent(speakerUuid,
                    ignored -> new PhraseCaptureState(serverApi.createDecoder()));
            state.accept(packet, phraseHistory, speakerUuid);
        } catch (RuntimeException exception) {
            PhraseCaptureState state = phraseCaptureStates.remove(speakerUuid);
            if (state != null) {
                state.close();
            }
            if (warnedPlayers.add(speakerUuid)) {
                plugin.getLogger().warning("Limbo phrase capture failed for " + speakerUuid
                        + ": " + exception.getMessage());
            }
        }
    }

    private void finishPhraseCapture(UUID speakerUuid) {
        PhraseCaptureState state = phraseCaptureStates.remove(speakerUuid);
        if (state != null) {
            state.finish(phraseHistory, speakerUuid);
            state.close();
        }
    }

    private void tickPhraseCaptureCleanup() {
        long now = System.nanoTime();
        if (!captureCleanupClock.advance(now)) return;
        for (Map.Entry<UUID, PhraseCaptureState> entry : phraseCaptureStates.entrySet()) {
            PhraseCaptureState state = entry.getValue();
            if (!state.finishIfIdle(phraseHistory, entry.getKey(), now,
                    PHRASE_IDLE_TIMEOUT_NANOS)) {
                continue;
            }
            if (phraseCaptureStates.remove(entry.getKey(), state)) {
                state.close();
            }
        }
    }

    private void tickPhrasePlayback() {
        if (!limboManager.isVoiceEchoEnabled()) {
            return;
        }
        VoicechatServerApi serverApi = api;
        if (serverApi == null) {
            return;
        }

        long now = System.nanoTime();
        if (!phrasePollClock.advance(now)) return;
        java.util.Set<UUID> limboPlayers = ConcurrentHashMap.newKeySet();
        for (Player target : Bukkit.getOnlinePlayers()) {
            UUID targetUuid = target.getUniqueId();
            if (!limboManager.isVoiceEchoParticipant(targetUuid)) {
                continue;
            }
            limboPlayers.add(targetUuid);
            if (phraseHistory.isEmpty() || phrasePlaybackTasks.containsKey(targetUuid)) {
                continue;
            }
            Long next = nextPhraseAtNanos.get(targetUuid);
            if (next == null) {
                nextPhraseAtNanos.put(targetUuid, now + randomPhraseDelay());
                continue;
            }
            if (now - next < 0L) {
                continue;
            }
            PhraseClip clip = phraseHistory.random();
            if (clip == null) {
                nextPhraseAtNanos.put(targetUuid, now + randomPhraseDelay());
                continue;
            }
            if (startPhrasePlayback(serverApi, target, clip)) {
                nextPhraseAtNanos.put(targetUuid, now + randomPhraseDelay());
            }
        }
        nextPhraseAtNanos.keySet().removeIf(uuid -> !limboPlayers.contains(uuid));
    }

    private long randomPhraseDelay() {
        // Ambient phrases are spaced in real time; sprint must not fast-forward audio.
        return ThreadLocalRandom.current().nextLong(
                PHRASE_REPLAY_MIN_TICKS, PHRASE_REPLAY_MAX_TICKS + 1L) * 50_000_000L;
    }

    /** Replays one captured phrase immediately for an online Limbo listener. */
    public boolean debugPlayLatestPhrase(Player target) {
        VoicechatServerApi serverApi = api;
        if (serverApi == null || target == null || !target.isOnline()
                || !limboManager.isLimbo(target.getUniqueId())
                || phraseHistory.isEmpty()
                || phrasePlaybackTasks.containsKey(target.getUniqueId())) {
            return false;
        }

        PhraseClip clip = phraseHistory.random();
        if (clip == null || !startPhrasePlayback(serverApi, target, clip)) {
            return false;
        }
        nextPhraseAtNanos.put(target.getUniqueId(), System.nanoTime() + randomPhraseDelay());
        return true;
    }

    private boolean startPhrasePlayback(VoicechatServerApi serverApi, Player target,
                                         PhraseClip clip) {
        if (!limboManager.isVoiceEchoEnabled()) {
            return false;
        }
        UUID targetUuid = target.getUniqueId();
        ScheduledExecutorService executor = phrasePlaybackExecutor;
        if (executor == null || executor.isShutdown()) {
            return false;
        }
        VoicechatConnection initialConnection = serverApi.getConnectionOf(targetUuid);
        if (initialConnection == null || !initialConnection.isConnected()
                || initialConnection.isDisabled() || initialConnection.getPlayer() == null) {
            return false;
        }
        VoicechatConnection speakerConnection = serverApi.getConnectionOf(clip.speakerUuid());
        if (speakerConnection != null && speakerConnection.isInGroup()) {
            return false;
        }

        Position listener = initialConnection.getPlayer().getPosition();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        double angle = random.nextDouble(0.0, Math.PI * 2.0);
        double distance = random.nextDouble(PHRASE_MIN_DISTANCE, PHRASE_MAX_DISTANCE);
        Position source = serverApi.createPosition(
                listener.getX() + Math.cos(angle) * distance,
                listener.getY() + random.nextDouble(-1.0, 2.0),
                listener.getZ() + Math.sin(angle) * distance);
        UUID channel = UUID.randomUUID();
        PhraseEffectPlan effectPlan = PhraseEffectPlan.random(clip.frameCount(), phraseMode);
        OpusDecoder decoder = null;
        OpusEncoder encoder = null;
        if (effectPlan.requiresTranscoding()) {
            try {
                decoder = serverApi.createDecoder();
                encoder = serverApi.createEncoder();
            } catch (RuntimeException exception) {
                if (decoder != null) {
                    decoder.close();
                }
                decoder = null;
                encoder = null;
            }
        }
        PhrasePlaybackState state = new PhrasePlaybackState(
                targetUuid, clip, source, channel, effectPlan, decoder, encoder,
                System.nanoTime());
        phrasePlaybackTasks.put(targetUuid, state);
        schedulePhraseFrame(state, 0L);
        return true;
    }

    private void schedulePhraseFrame(PhrasePlaybackState state, long delayNanos) {
        ScheduledExecutorService executor = phrasePlaybackExecutor;
        if (executor == null || executor.isShutdown() || state.finished) {
            finishPhrasePlayback(state);
            return;
        }
        try {
            state.future = executor.schedule(() -> playPhraseFrame(state),
                    Math.max(0L, delayNanos), TimeUnit.NANOSECONDS);
        } catch (RuntimeException exception) {
            finishPhrasePlayback(state);
        }
    }

    private void playPhraseFrame(PhrasePlaybackState state) {
        if (state.finished) {
            return;
        }
        VoicechatServerApi serverApi = api;
        if (serverApi == null || !limboManager.isVoiceEchoEnabled()
                || !limboManager.isLimbo(state.targetUuid)) {
            finishPhrasePlayback(state);
            return;
        }
        VoicechatConnection activeConnection = serverApi.getConnectionOf(state.targetUuid);
        if (activeConnection == null || !activeConnection.isConnected()
                || activeConnection.isDisabled() || activeConnection.getPlayer() == null) {
            finishPhrasePlayback(state);
            return;
        }
        VoicechatConnection speakerConnection = serverApi.getConnectionOf(state.clip.speakerUuid());
        if (speakerConnection != null && speakerConnection.isInGroup()) {
            finishPhrasePlayback(state);
            return;
        }
        try {
            int frameIndex = state.frameIndex;
            byte[] encoded = state.nextEncodedFrame();
            if (encoded == null) {
                finishPhrasePlayback(state);
                return;
            }
            LocationalSoundPacket.Builder<?> builder = serverApi.createPacket()
                    .locationalSoundPacketBuilder();
            LocationalSoundPacket packet = builder
                    .channelId(state.channel)
                    .sender(state.clip.speakerUuid())
                    .position(state.source)
                    .distance(PHRASE_SOUND_DISTANCE)
                    .sequenceNumber(frameIndex)
                    .opusEncodedData(encoded)
                    .build();
            serverApi.sendLocationalSoundPacketTo(activeConnection, packet);
            if (state.playbackFinished()) {
                finishPhrasePlayback(state);
                return;
            }
            long nextDeadlineNanos = state.startNanos
                    + (long) state.frameIndex * PHRASE_FRAME_INTERVAL_NANOS;
            schedulePhraseFrame(state, nextDeadlineNanos - System.nanoTime());
        } catch (RuntimeException exception) {
            finishPhrasePlayback(state);
            if (warnedPlayers.add(state.targetUuid)) {
                plugin.getLogger().warning("Limbo phrase playback failed for " + state.targetUuid
                        + ": " + exception.getMessage());
            }
        }
    }

    private void finishPhrasePlayback(PhrasePlaybackState state) {
        if (state == null || state.finished) {
            return;
        }
        state.finished = true;
        phrasePlaybackTasks.remove(state.targetUuid, state);
        ScheduledFuture<?> future = state.future;
        if (future != null && !future.isDone()) {
            future.cancel(false);
        }
        state.closeCodecs();
    }

    private void sendEchoToLimboPlayers(VoicechatServerApi serverApi,
                                        VoicechatConnection senderConnection,
                                        MicrophonePacket sourcePacket,
                                        byte[] echoed,
                                        long sequenceNumber) {
        if (senderConnection.getPlayer() == null) {
            return;
        }
        Position sourcePosition = senderConnection.getPlayer().getPosition();
        LocationalSoundPacket echoPacket = sourcePacket.locationalSoundPacketBuilder()
                .channelId(echoChannelId(senderConnection.getPlayer().getUuid()))
                .position(sourcePosition)
                .distance(ECHO_MAX_DISTANCE_BLOCKS)
                .sequenceNumber(sequenceNumber)
                .opusEncodedData(echoed)
                .build();
        UUID senderUuid = senderConnection.getPlayer().getUuid();
        for (UUID targetUuid : limboManager.getLimboPlayerIds()) {
            if (targetUuid.equals(senderUuid)) {
                continue;
            }
            VoicechatConnection targetConnection = serverApi.getConnectionOf(targetUuid);
            if (targetConnection == null || !targetConnection.isConnected() || targetConnection.isDisabled()) {
                continue;
            }
            // The client applies distance attenuation from the packet position;
            // sending directly keeps a quiet echo audible beyond the normal voice range.
            serverApi.sendLocationalSoundPacketTo(targetConnection, echoPacket);
        }
    }

    private static UUID echoChannelId(UUID senderUuid) {
        return UUID.nameUUIDFromBytes(("spaydiscore:limbo-echo:" + senderUuid)
                .getBytes(StandardCharsets.UTF_8));
    }

    private long nextEchoSequence(UUID senderUuid) {
        Long sequence = echoSequences.compute(senderUuid, (ignored, current) ->
                current == null || current == Long.MAX_VALUE ? 0L : current + 1L);
        return sequence == null ? 0L : sequence;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        if (event.getTo() == null) {
            return;
        }
        UUID uuid = event.getPlayer().getUniqueId();
        if (limboManager.isLimboWorld(event.getTo().getWorld())) {
            finishPhraseCapture(uuid);
            enforceLimboPlayerGroup(uuid);
        } else if (limboManager.isLimbo(uuid)) {
            removeState(uuid);
            removePhrasePlayback(uuid);
        }
        Bukkit.getScheduler().runTask(plugin, () -> enforceLimboPlayerGroup(uuid));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        Bukkit.getScheduler().runTask(plugin, () -> enforceLimboPlayerGroup(uuid));
    }

    private void onPlayerDisconnected(PlayerDisconnectedEvent event) {
        UUID playerUuid = event.getPlayerUuid();
        removeState(playerUuid);
        removePhrasePlayback(playerUuid);
        finishPhraseCapture(playerUuid);
        warnedPlayers.remove(playerUuid);
    }

    public void shutdown() {
        HandlerList.unregisterAll(this);
        if (groupEnforcementTask != null) {
            groupEnforcementTask.cancel();
            groupEnforcementTask = null;
        }
        if (phrasePlaybackTask != null) {
            phrasePlaybackTask.cancel();
            phrasePlaybackTask = null;
        }
        if (phraseCaptureCleanupTask != null) {
            phraseCaptureCleanupTask.cancel();
            phraseCaptureCleanupTask = null;
        }
        for (PhrasePlaybackState state : phrasePlaybackTasks.values()) {
            finishPhrasePlayback(state);
        }
        phrasePlaybackTasks.clear();
        ScheduledExecutorService playbackExecutor = phrasePlaybackExecutor;
        phrasePlaybackExecutor = null;
        if (playbackExecutor != null) {
            playbackExecutor.shutdownNow();
        }
        for (EchoState state : echoStates.values()) {
            state.close();
        }
        echoStates.clear();
        echoSequences.clear();
        for (UUID uuid : new ArrayList<>(phraseCaptureStates.keySet())) {
            finishPhraseCapture(uuid);
        }
        phraseCaptureStates.clear();
        nextPhraseAtNanos.clear();
        groupEnforcementClock.reset();
        phrasePollClock.reset();
        captureCleanupClock.reset();
        phraseHistory.shutdown();
        warnedPlayers.clear();
        api = null;
    }

    private void tickGroupEnforcement() {
        if (groupEnforcementClock.advance(System.nanoTime())) {
            enforceLimboGroups();
        }
    }

    private void enforceLimboGroups() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!limboManager.isLimbo(player)) {
                continue;
            }
            enforceLimboPlayerGroup(player.getUniqueId());
        }
    }

    private void enforceLimboPlayerGroup(UUID playerUuid) {
        if (playerUuid == null) {
            return;
        }
        if (Bukkit.isPrimaryThread()) {
            Player player = Bukkit.getPlayer(playerUuid);
            if (player == null || !limboManager.isLimbo(player)) {
                return;
            }
        } else if (!limboManager.isLimboParticipant(playerUuid)) {
            return;
        }
        VoicechatServerApi serverApi = api;
        if (serverApi == null) {
            return;
        }
        VoicechatConnection connection = serverApi.getConnectionOf(playerUuid);
        if (connection != null) {
            enforceGroupPrivacy(true, connection, null);
        }
    }

    private boolean isLimboPlayer(UUID playerUuid) {
        if (playerUuid == null) {
            return false;
        }
        if (limboManager.isLimboParticipant(playerUuid)) {
            return true;
        }
        if (!Bukkit.isPrimaryThread()) {
            return false;
        }
        Player player = Bukkit.getPlayer(playerUuid);
        return player != null && limboManager.isLimbo(player);
    }

    static boolean enforceGroupPrivacy(boolean inLimbo, VoicechatConnection connection, Event event) {
        if (!inLimbo) {
            return false;
        }
        if (event != null && event.isCancellable()) {
            event.cancel();
        }
        if (connection != null && connection.isInGroup()) {
            connection.setGroup(null);
        }
        return true;
    }

    private void removeState(UUID playerUuid) {
        EchoState state = echoStates.remove(playerUuid);
        if (state != null) {
            state.close();
        }
    }

    private void removePhrasePlayback(UUID playerUuid) {
        PhrasePlaybackState state = phrasePlaybackTasks.remove(playerUuid);
        if (state != null) {
            finishPhrasePlayback(state);
        }
    }

    private static boolean isSpeechFrame(short[] samples) {
        if (samples == null || samples.length == 0) {
            return false;
        }
        double energy = 0.0;
        int peak = 0;
        for (short sample : samples) {
            int absolute = Math.abs((int) sample);
            peak = Math.max(peak, absolute);
            energy += (double) sample * sample;
        }
        double rms = Math.sqrt(energy / samples.length);
        return rms >= SPEECH_RMS_THRESHOLD || peak >= SPEECH_RMS_THRESHOLD * 3.0;
    }

    private enum PhraseMode {
        NORMAL("normal"),
        BARREL("barrel");

        private final String id;

        PhraseMode(String id) {
            this.id = id;
        }

        private static PhraseMode parse(String value) {
            if (value == null) {
                return null;
            }
            for (PhraseMode mode : values()) {
                if (mode.id.equalsIgnoreCase(value.trim())) {
                    return mode;
                }
            }
            if (value.trim().equalsIgnoreCase("underwater")) {
                return BARREL;
            }
            return null;
        }

        private static PhraseMode parseOrDefault(String value) {
            PhraseMode parsed = parse(value);
            return parsed == null ? NORMAL : parsed;
        }
    }

    private enum PhrasePlaybackEffect {
        NORMAL(new PhraseEffectParameters(1.0, 1.0, 1.0, 1.0, 0.0)),
        SLOW_HIGH(new PhraseEffectParameters(0.72, 1.12, 1.0, 1.0, 0.0)),
        FAST_LOW(new PhraseEffectParameters(1.28, 0.90, 1.0, 1.0, 0.0)),
        PITCH_UP(new PhraseEffectParameters(1.0, 1.14, 1.0, 1.0, 0.0)),
        PITCH_DOWN(new PhraseEffectParameters(1.0, 0.88, 1.0, 1.0, 0.0)),
        DISTANT(new PhraseEffectParameters(1.0, 1.0, 0.28, 0.12, 0.0));

        private final PhraseEffectParameters parameters;

        PhrasePlaybackEffect(PhraseEffectParameters parameters) {
            this.parameters = parameters;
        }

        private static PhrasePlaybackEffect random(PhrasePlaybackEffect previous) {
            PhrasePlaybackEffect effect;
            do {
                effect = values()[1 + ThreadLocalRandom.current().nextInt(values().length - 1)];
            } while (effect == previous);
            return effect;
        }
    }

    private record PhraseEffectParameters(double speed, double pitch, double gain,
                                          double lowPassAlpha, double barrelMix) {
        private boolean requiresTranscoding() {
            return Math.abs(pitch - 1.0) > 0.001
                    || Math.abs(gain - 1.0) > 0.001
                    || lowPassAlpha < 0.999
                    || barrelMix > 0.001;
        }

        private PhraseEffectParameters lerp(PhraseEffectParameters target, double amount) {
            double t = Math.max(0.0, Math.min(1.0, amount));
            return new PhraseEffectParameters(
                    speed + (target.speed - speed) * t,
                    pitch + (target.pitch - pitch) * t,
                    gain + (target.gain - gain) * t,
                    lowPassAlpha + (target.lowPassAlpha - lowPassAlpha) * t,
                    barrelMix + (target.barrelMix - barrelMix) * t);
        }

        private PhraseEffectParameters forMode(PhraseMode mode) {
            if (mode != PhraseMode.BARREL) {
                return this;
            }
            return new PhraseEffectParameters(
                    speed, pitch, gain * BARREL_GAIN,
                    Math.min(lowPassAlpha, BARREL_LOW_PASS_ALPHA), 1.0);
        }
    }

    private record PhraseEffectKeyframe(double progress, PhraseEffectParameters parameters) {
    }

    private static final class PhraseEffectPlan {
        private final int sourceFrameCount;
        private final List<PhraseEffectKeyframe> keyframes;

        private PhraseEffectPlan(int sourceFrameCount, List<PhraseEffectKeyframe> keyframes) {
            this.sourceFrameCount = Math.max(1, sourceFrameCount);
            this.keyframes = List.copyOf(keyframes);
        }

        private static PhraseEffectPlan random(int sourceFrameCount, PhraseMode phraseMode) {
            int safeFrameCount = Math.max(1, sourceFrameCount);
            int effectCount = safeFrameCount < 24
                    ? 1 : ThreadLocalRandom.current().nextInt(2, 4);
            List<PhraseEffectKeyframe> keyframes = new ArrayList<>();
            keyframes.add(new PhraseEffectKeyframe(0.0,
                    PhrasePlaybackEffect.NORMAL.parameters.forMode(phraseMode)));

            PhrasePlaybackEffect previous = PhrasePlaybackEffect.NORMAL;
            double previousProgress = 0.0;
            for (int index = 0; index < effectCount; index++) {
                PhrasePlaybackEffect effect = PhrasePlaybackEffect.random(previous);
                double center = (index + 1.0) / (effectCount + 1.0);
                double jitter = ThreadLocalRandom.current().nextDouble(-0.08, 0.08);
                double minimum = previousProgress + 0.08;
                double maximum = 0.94 - (effectCount - index - 1) * 0.08;
                double progress = Math.max(minimum, Math.min(maximum, center + jitter));
                keyframes.add(new PhraseEffectKeyframe(progress,
                        effect.parameters.forMode(phraseMode)));
                previous = effect;
                previousProgress = progress;
            }
            return new PhraseEffectPlan(safeFrameCount, keyframes);
        }

        private boolean requiresTranscoding() {
            return keyframes.stream()
                    .anyMatch(keyframe -> keyframe.parameters.requiresTranscoding());
        }

        private PhraseEffectParameters parametersAt(double sourceFramePosition) {
            double progress = sourceFrameCount <= 1 ? 1.0
                    : sourceFramePosition / (sourceFrameCount - 1.0);
            progress = Math.max(0.0, Math.min(1.0, progress));
            PhraseEffectKeyframe previous = keyframes.get(0);
            for (int index = 1; index < keyframes.size(); index++) {
                PhraseEffectKeyframe current = keyframes.get(index);
                if (progress <= current.progress) {
                    double span = Math.max(0.0001, current.progress - previous.progress);
                    return previous.parameters.lerp(current.parameters,
                            (progress - previous.progress) / span);
                }
                previous = current;
            }
            return previous.parameters;
        }
    }

    private static final class PhraseAudioRenderer {
        private static byte[][] preparePhrase(PhraseClip clip, PhraseEffectPlan plan,
                                               OpusDecoder decoder, OpusEncoder encoder) {
            short[] source = decodePhrase(clip, decoder);
            if (source == null || source.length == 0) {
                return null;
            }
            short[] rendered = render(source, plan);
            if (rendered == null || rendered.length == 0) {
                return null;
            }
            return encodeFrames(rendered, encoder);
        }

        private static short[] decodePhrase(PhraseClip clip, OpusDecoder decoder) {
            List<short[]> decodedFrames = new ArrayList<>();
            int sampleCount = 0;
            for (int index = 0; index < clip.frameCount(); index++) {
                short[] decoded = decoder.decode(clip.frame(index));
                if (decoded == null || decoded.length == 0
                        || sampleCount > Integer.MAX_VALUE - decoded.length) {
                    return null;
                }
                decodedFrames.add(decoded);
                sampleCount += decoded.length;
            }
            short[] source = new short[sampleCount];
            int offset = 0;
            for (short[] decoded : decodedFrames) {
                System.arraycopy(decoded, 0, source, offset, decoded.length);
                offset += decoded.length;
            }
            return source;
        }

        private static short[] render(short[] source, PhraseEffectPlan plan) {
            List<short[]> segments = new ArrayList<>();
            ToneState tone = new ToneState();
            for (int start = 0; start < source.length;) {
                int end = Math.min(source.length, start + PHRASE_RENDER_CHUNK_SAMPLES);
                double midpoint = (start + (end - start) * 0.5)
                        / Math.max(1.0, source.length - 1.0);
                double sourceFramePosition = midpoint * (plan.sourceFrameCount - 1.0);
                PhraseEffectParameters parameters = plan.parametersAt(sourceFramePosition);
                short[] chunk = Arrays.copyOfRange(source, start, end);
                short[] pitched = resample(chunk, parameters.pitch);
                int targetLength = Math.max(1, (int) Math.round(
                        chunk.length / Math.max(0.35, Math.min(1.5, parameters.speed))));
                short[] stretched = timeStretch(pitched, targetLength);
                applyTone(stretched, parameters, tone);
                segments.add(stretched);
                start = end;
            }
            return crossfade(segments);
        }

        private static short[] resample(short[] input, double pitch) {
            if (input.length == 0) {
                return input;
            }
            double safePitch = Math.max(0.70, Math.min(1.35, pitch));
            int targetLength = Math.max(1, (int) Math.round(input.length / safePitch));
            short[] output = new short[targetLength];
            for (int index = 0; index < targetLength; index++) {
                double position = Math.min(input.length - 1.0, index * safePitch);
                int left = (int) position;
                int right = Math.min(left + 1, input.length - 1);
                double fraction = position - left;
                output[index] = clampToShort(input[left] * (1.0 - fraction)
                        + input[right] * fraction);
            }
            return output;
        }

        private static short[] timeStretch(short[] input, int targetLength) {
            if (input.length == 0 || targetLength <= 0) {
                return new short[0];
            }
            int grain = Math.min(PHRASE_RENDER_GRAIN_SAMPLES, input.length);
            int hop = Math.max(1, Math.min(PHRASE_RENDER_HOP_SAMPLES, grain / 2));
            int lastSourceStart = Math.max(0, input.length - grain);
            double[] sums = new double[targetLength];
            double[] weights = new double[targetLength];
            for (int outputStart = 0; outputStart < targetLength; outputStart += hop) {
                double progress = targetLength <= 1 ? 0.0
                        : outputStart / (targetLength - 1.0);
                int sourceStart = (int) Math.round(progress * lastSourceStart);
                for (int index = 0; index < grain && outputStart + index < targetLength; index++) {
                    double window = 0.5 - 0.5 * Math.cos(
                            2.0 * Math.PI * (index + 0.5) / grain);
                    sums[outputStart + index] += input[sourceStart + index] * window;
                    weights[outputStart + index] += window;
                }
            }
            short[] output = new short[targetLength];
            for (int index = 0; index < targetLength; index++) {
                output[index] = clampToShort(weights[index] <= 0.0001
                        ? 0.0 : sums[index] / weights[index]);
            }
            return output;
        }

        private static void applyTone(short[] samples, PhraseEffectParameters parameters,
                                      ToneState tone) {
            double gain = Math.max(0.05, Math.min(1.0, parameters.gain));
            double filterAlpha = Math.max(0.02, Math.min(1.0, parameters.lowPassAlpha));
            double barrelMix = Math.max(0.0, Math.min(1.0, parameters.barrelMix));
            double filtered = tone.filtered;
            for (int index = 0; index < samples.length; index++) {
                double roomSample = samples[index];
                if (filterAlpha < 0.999) {
                    filtered += (roomSample - filtered) * filterAlpha;
                    roomSample = filtered;
                } else {
                    filtered = roomSample;
                }
                double sample = roomSample;
                int shortIndex = tone.shortIndex;
                int longIndex = tone.longIndex;
                if (barrelMix > 0.001) {
                    sample += (tone.shortDelay[shortIndex] * 0.13
                            + tone.longDelay[longIndex] * 0.08) * barrelMix;
                }
                samples[index] = clampToShort(sample * gain);
                tone.shortDelay[shortIndex] = roomSample * 0.86;
                tone.longDelay[longIndex] = roomSample * 0.78;
                tone.shortIndex = (shortIndex + 1) % tone.shortDelay.length;
                tone.longIndex = (longIndex + 1) % tone.longDelay.length;
            }
            tone.filtered = filtered;
        }

        private static short[] crossfade(List<short[]> segments) {
            if (segments.isEmpty()) {
                return new short[0];
            }
            short[] output = segments.get(0);
            for (int index = 1; index < segments.size(); index++) {
                output = crossfade(output, segments.get(index));
            }
            return output;
        }

        private static short[] crossfade(short[] left, short[] right) {
            int overlap = Math.min(PHRASE_RENDER_CROSSFADE_SAMPLES,
                    Math.min(left.length, right.length));
            short[] output = new short[left.length + right.length - overlap];
            System.arraycopy(left, 0, output, 0, left.length - overlap);
            for (int index = 0; index < overlap; index++) {
                double amount = (index + 1.0) / (overlap + 1.0);
                output[left.length - overlap + index] = clampToShort(
                        left[left.length - overlap + index] * (1.0 - amount)
                                + right[index] * amount);
            }
            System.arraycopy(right, overlap, output, left.length,
                    right.length - overlap);
            return output;
        }

        private static byte[][] encodeFrames(short[] samples, OpusEncoder encoder) {
            int frameCount = (samples.length + OPUS_FRAME_SAMPLES - 1) / OPUS_FRAME_SAMPLES;
            byte[][] frames = new byte[frameCount][];
            for (int index = 0; index < frameCount; index++) {
                short[] frame = new short[OPUS_FRAME_SAMPLES];
                int offset = index * OPUS_FRAME_SAMPLES;
                int length = Math.min(OPUS_FRAME_SAMPLES, samples.length - offset);
                System.arraycopy(samples, offset, frame, 0, length);
                byte[] encoded = encoder.encode(frame);
                if (encoded == null || encoded.length == 0) {
                    return null;
                }
                frames[index] = encoded;
            }
            return frames;
        }

        private static final class ToneState {
            private final double[] shortDelay = new double[BARREL_SHORT_DELAY_SAMPLES];
            private final double[] longDelay = new double[BARREL_LONG_DELAY_SAMPLES];
            private double filtered;
            private int shortIndex;
            private int longIndex;
        }
    }

    private static final class PhrasePlaybackState {
        private final UUID targetUuid;
        private final PhraseClip clip;
        private final Position source;
        private final UUID channel;
        private final PhraseEffectPlan effectPlan;
        private final OpusDecoder decoder;
        private final OpusEncoder encoder;
        private final long startNanos;
        private int frameIndex;
        private byte[][] preparedFrames;
        private boolean prepared;
        private boolean codecsClosed;
        private volatile boolean finished;
        private volatile ScheduledFuture<?> future;

        private PhrasePlaybackState(UUID targetUuid, PhraseClip clip, Position source,
                                    UUID channel, PhraseEffectPlan effectPlan,
                                    OpusDecoder decoder, OpusEncoder encoder, long startNanos) {
            this.targetUuid = targetUuid;
            this.clip = clip;
            this.source = source;
            this.channel = channel;
            this.effectPlan = effectPlan;
            this.decoder = decoder;
            this.encoder = encoder;
            this.startNanos = startNanos;
        }

        private synchronized byte[] nextEncodedFrame() {
            preparePhrase();
            if (preparedFrames != null) {
                if (frameIndex >= preparedFrames.length) {
                    return null;
                }
                return preparedFrames[frameIndex++].clone();
            }
            if (frameIndex >= clip.frameCount()) {
                return null;
            }
            return clip.frame(frameIndex++);
        }

        private synchronized void preparePhrase() {
            if (prepared) {
                return;
            }
            prepared = true;
            if (!effectPlan.requiresTranscoding() || decoder == null || encoder == null) {
                return;
            }
            try {
                preparedFrames = PhraseAudioRenderer.preparePhrase(
                        clip, effectPlan, decoder, encoder);
            } catch (RuntimeException ignored) {
                preparedFrames = null;
            }
        }

        private synchronized boolean playbackFinished() {
            return frameIndex >= (preparedFrames == null
                    ? clip.frameCount() : preparedFrames.length);
        }

        private synchronized void closeCodecs() {
            if (codecsClosed) {
                return;
            }
            codecsClosed = true;
            if (decoder != null && !decoder.isClosed()) {
                decoder.close();
            }
            if (encoder != null && !encoder.isClosed()) {
                encoder.close();
            }
        }
    }

    private static final class PhraseAudioStore {
        private static final Pattern FILE_PATTERN = Pattern.compile("phrase_(\\d{2})\\.ogg");

        private final JavaPlugin plugin;
        private final int capacity;
        private final Path directory;
        private final ExecutorService writer;
        private final Deque<PhraseFile> files = new ArrayDeque<>();

        private PhraseAudioStore(JavaPlugin plugin, int capacity, ExecutorService writer) {
            this.plugin = plugin;
            this.capacity = Math.max(1, capacity);
            this.directory = plugin.getDataFolder().toPath().resolve(PHRASE_DIRECTORY);
            this.writer = writer;
            loadExisting();
            try {
                writer.execute(this::preloadExisting);
            } catch (RuntimeException ignored) {
                // The optional store can still capture new phrases after a rejected preload.
            }
        }

        private void add(PhraseClip phrase) {
            if (phrase == null || phrase.frameCount() == 0 || writer.isShutdown()) {
                return;
            }
            PhraseFile file;
            synchronized (this) {
                Path target = findFreeSlot();
                if (target == null) {
                    if (files.isEmpty()) {
                        target = findOldestExistingSlot();
                        if (target == null) {
                            return;
                        }
                    } else {
                        target = files.removeFirst().path;
                    }
                }
                file = new PhraseFile(target, phrase);
                files.addLast(file);
            }
            try {
                writer.execute(() -> persist(file));
            } catch (RuntimeException exception) {
                synchronized (this) {
                    files.remove(file);
                }
            }
        }

        private PhraseClip random() {
            List<PhraseFile> candidates;
            synchronized (this) {
                if (files.isEmpty()) {
                    return null;
                }
                candidates = new ArrayList<>(files);
            }
            while (!candidates.isEmpty()) {
                PhraseFile file = candidates.remove(ThreadLocalRandom.current().nextInt(candidates.size()));
                PhraseClip phrase = file.clip;
                if (phrase != null && phrase.frameCount() > 0) {
                    return phrase;
                }
            }
            return null;
        }

        private synchronized boolean isEmpty() {
            return files.isEmpty();
        }

        private void shutdown() {
            writer.shutdown();
            try {
                if (!writer.awaitTermination(5L, TimeUnit.SECONDS)) {
                    writer.shutdownNow();
                }
            } catch (InterruptedException exception) {
                writer.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        private void loadExisting() {
            if (!Files.isDirectory(directory)) {
                return;
            }
            List<Path> existing = new ArrayList<>();
            try (var stream = Files.list(directory)) {
                stream.filter(this::isPhraseFile).forEach(existing::add);
            } catch (IOException ignored) {
                return;
            }
            existing.sort(Comparator.comparingLong(this::lastModified)
                    .thenComparing(path -> path.getFileName().toString()));
            int first = Math.max(0, existing.size() - capacity);
            synchronized (this) {
                for (int index = first; index < existing.size(); index++) {
                    files.addLast(new PhraseFile(existing.get(index), null));
                }
            }
        }

        private void preloadExisting() {
            List<PhraseFile> existing;
            synchronized (this) {
                existing = new ArrayList<>(files);
            }
            for (PhraseFile file : existing) {
                if (file.clip != null) {
                    continue;
                }
                try {
                    PhraseClip phrase = OggOpus.read(file.path);
                    if (phrase == null || phrase.frameCount() == 0) {
                        throw new IOException("empty Ogg phrase");
                    }
                    file.clip = phrase;
                } catch (IOException | RuntimeException exception) {
                    synchronized (this) {
                        files.remove(file);
                    }
                    try {
                        Files.deleteIfExists(file.path);
                    } catch (IOException ignored) {
                        // Leave the broken slot for the next startup repair pass.
                    }
                    plugin.getLogger().warning("Ignoring invalid Limbo phrase "
                            + file.path.getFileName() + ": " + exception.getMessage());
                }
            }
        }

        private boolean isPhraseFile(Path path) {
            if (path == null || !Files.isRegularFile(path)) {
                return false;
            }
            Matcher matcher = FILE_PATTERN.matcher(path.getFileName().toString());
            if (!matcher.matches()) {
                return false;
            }
            int slot = Integer.parseInt(matcher.group(1));
            return slot >= 1 && slot <= capacity;
        }

        private long lastModified(Path path) {
            try {
                return Files.getLastModifiedTime(path).toMillis();
            } catch (IOException ignored) {
                return Long.MIN_VALUE;
            }
        }

        private Path findFreeSlot() {
            for (int slot = 1; slot <= capacity; slot++) {
                Path candidate = directory.resolve(String.format(
                        java.util.Locale.ROOT, "phrase_%02d.ogg", slot));
                boolean tracked = false;
                for (PhraseFile file : files) {
                    if (file.path.equals(candidate)) {
                        tracked = true;
                        break;
                    }
                }
                if (!tracked && !Files.exists(candidate)) {
                    return candidate;
                }
            }
            return null;
        }

        private Path findOldestExistingSlot() {
            Path oldest = null;
            long oldestModified = Long.MAX_VALUE;
            try (var stream = Files.list(directory)) {
                for (Path candidate : stream.filter(this::isPhraseFile).toList()) {
                    long modified = lastModified(candidate);
                    if (oldest == null || modified < oldestModified) {
                        oldest = candidate;
                        oldestModified = modified;
                    }
                }
            } catch (IOException ignored) {
                return null;
            }
            return oldest;
        }

        private void persist(PhraseFile file) {
            Path temporary = directory.resolve(file.path.getFileName() + ".tmp-" + UUID.randomUUID());
            try {
                Files.createDirectories(directory);
                OggOpus.write(temporary, file.clip);
                try {
                    Files.move(temporary, file.path,
                            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException exception) {
                    Files.move(temporary, file.path, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException | RuntimeException exception) {
                synchronized (this) {
                    files.remove(file);
                }
                plugin.getLogger().warning("Could not save Limbo phrase "
                        + file.path.getFileName() + ": " + exception.getMessage());
            } finally {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // Keep the successfully written phrase even if temp cleanup is delayed.
                }
            }
        }

        private static final class PhraseFile {
            private final Path path;
            private volatile PhraseClip clip;

            private PhraseFile(Path path, PhraseClip clip) {
                this.path = path;
                this.clip = clip;
            }
        }

        private static final class OggOpus {
            private static final byte[] OGG_CAPTURE = "OggS".getBytes(StandardCharsets.US_ASCII);
            private static final int[] CRC_TABLE = buildCrcTable();

            private static void write(Path path, PhraseClip clip) throws IOException {
                try (OutputStream output = Files.newOutputStream(path)) {
                    int serial = ThreadLocalRandom.current().nextInt();
                    int sequence = 0;
                    writePage(output, opusHead(), 2, serial, sequence++, 0L);
                    writePage(output, opusTags(clip.speakerUuid()), 0, serial, sequence++, 0L);
                    long granule = 0L;
                    for (int index = 0; index < clip.frameCount(); index++) {
                        granule = Math.min(Long.MAX_VALUE, granule + OPUS_FRAME_SAMPLES);
                        int headerType = index + 1 == clip.frameCount() ? 4 : 0;
                        writePage(output, clip.frame(index), headerType, serial, sequence++, granule);
                    }
                }
            }

            private static PhraseClip read(Path path) throws IOException {
                byte[] data = Files.readAllBytes(path);
                List<byte[]> frames = new ArrayList<>();
                ByteArrayOutputStream packet = new ByteArrayOutputStream();
                UUID speakerUuid = null;
                int packetIndex = 0;
                int offset = 0;
                int serial = 0;
                int sequence = -1;
                boolean sawEndOfStream = false;
                while (offset < data.length) {
                    if (sawEndOfStream) {
                        throw new IOException("data found after Ogg end-of-stream page");
                    }
                    if (data.length - offset < 27 || data[offset] != 'O'
                            || data[offset + 1] != 'g' || data[offset + 2] != 'g'
                            || data[offset + 3] != 'S') {
                        throw new IOException("invalid Ogg page");
                    }
                    if (data[offset + 4] != 0) {
                        throw new IOException("unsupported Ogg version");
                    }
                    int headerType = Byte.toUnsignedInt(data[offset + 5]);
                    if ((headerType & ~0x07) != 0) {
                        throw new IOException("invalid Ogg header flags");
                    }
                    int pageSerial = readLittleInt(data, offset + 14);
                    int pageSequence = readLittleInt(data, offset + 18);
                    if (packetIndex == 0) {
                        if ((headerType & 0x02) == 0 || pageSequence != 0) {
                            throw new IOException("invalid Ogg beginning-of-stream page");
                        }
                        serial = pageSerial;
                    } else if (pageSerial != serial || pageSequence != sequence + 1) {
                        throw new IOException("broken Ogg page sequence");
                    }
                    if (((headerType & 0x01) != 0) != (packet.size() != 0)) {
                        throw new IOException("invalid Ogg continuation flag");
                    }
                    int segmentCount = Byte.toUnsignedInt(data[offset + 26]);
                    if (segmentCount > data.length - offset - 27) {
                        throw new IOException("truncated Ogg segment table");
                    }
                    int tableEnd = offset + 27 + segmentCount;
                    int payload = tableEnd;
                    for (int segment = 0; segment < segmentCount; segment++) {
                        int length = Byte.toUnsignedInt(data[offset + 27 + segment]);
                        if (length > data.length - payload) {
                            throw new IOException("truncated Ogg payload");
                        }
                        packet.write(data, payload, length);
                        payload += length;
                        if (length < 255) {
                            byte[] complete = packet.toByteArray();
                            packet.reset();
                            if (packetIndex == 0) {
                                requireHeader(complete, "OpusHead");
                            } else if (packetIndex == 1) {
                                requireHeader(complete, "OpusTags");
                                speakerUuid = readSpeakerUuid(complete);
                            } else if (complete.length > 0) {
                                frames.add(complete);
                            }
                            packetIndex++;
                        }
                    }
                    int expectedCrc = readLittleInt(data, offset + 22);
                    int actualCrc = oggCrc(data, offset, payload - offset);
                    if (expectedCrc != actualCrc) {
                        throw new IOException("invalid Ogg page checksum");
                    }
                    sequence = pageSequence;
                    sawEndOfStream = (headerType & 0x04) != 0;
                    offset = payload;
                }
                if (packet.size() != 0 || packetIndex < 2 || frames.isEmpty() || !sawEndOfStream) {
                    throw new IOException("incomplete Ogg Opus phrase");
                }
                if (speakerUuid == null) {
                    speakerUuid = UUID.nameUUIDFromBytes(
                            path.toString().getBytes(StandardCharsets.UTF_8));
                }
                return new PhraseClip(frames.toArray(new byte[0][]), speakerUuid);
            }

            private static byte[] opusHead() {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                output.writeBytes("OpusHead".getBytes(StandardCharsets.US_ASCII));
                output.write(1);
                output.write(1);
                writeLittleShort(output, 0);
                writeLittleInt(output, 48_000);
                writeLittleShort(output, 0);
                output.write(0);
                return output.toByteArray();
            }

            private static byte[] opusTags(UUID speakerUuid) {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                output.writeBytes("OpusTags".getBytes(StandardCharsets.US_ASCII));
                byte[] vendor = "SpaydisCore".getBytes(StandardCharsets.UTF_8);
                byte[] comment = ("SPAYDISCORE_SPEAKER_UUID=" + speakerUuid)
                        .getBytes(StandardCharsets.UTF_8);
                writeLittleInt(output, vendor.length);
                output.writeBytes(vendor);
                writeLittleInt(output, 1);
                writeLittleInt(output, comment.length);
                output.writeBytes(comment);
                return output.toByteArray();
            }

            private static void writePage(OutputStream output, byte[] packet, int headerType,
                                          int serial, int sequence, long granule) throws IOException {
                int segmentCount = packet.length / 255 + 1;
                if (segmentCount > 255) {
                    throw new IOException("Opus packet is too large for one Ogg page");
                }
                byte[] page = new byte[27 + segmentCount + packet.length];
                System.arraycopy(OGG_CAPTURE, 0, page, 0, OGG_CAPTURE.length);
                page[4] = 0;
                page[5] = (byte) headerType;
                writeLittleLong(page, 6, granule);
                writeLittleInt(page, 14, serial);
                writeLittleInt(page, 18, sequence);
                page[26] = (byte) segmentCount;
                int remaining = packet.length;
                int packetOffset = 0;
                for (int segment = 0; segment < segmentCount; segment++) {
                    int length = Math.min(255, remaining);
                    page[27 + segment] = (byte) length;
                    if (length > 0) {
                        System.arraycopy(packet, packetOffset,
                                page, 27 + segmentCount + packetOffset, length);
                        packetOffset += length;
                        remaining -= length;
                    }
                }
                writeLittleInt(page, 22, oggCrc(page));
                output.write(page);
            }

            private static UUID readSpeakerUuid(byte[] tags) throws IOException {
                int offset = 8;
                int vendorLength = readLittleInt(tags, offset);
                if (vendorLength < 0 || offset + 4 > tags.length - vendorLength) {
                    throw new IOException("invalid OpusTags vendor");
                }
                offset += 4 + vendorLength;
                int commentCount = readLittleInt(tags, offset);
                if (commentCount < 0) {
                    throw new IOException("invalid OpusTags comment count");
                }
                offset += 4;
                for (int index = 0; index < commentCount; index++) {
                    int length = readLittleInt(tags, offset);
                    offset += 4;
                    if (length < 0 || offset > tags.length - length) {
                        throw new IOException("truncated OpusTags comment");
                    }
                    String comment = new String(tags, offset, length, StandardCharsets.UTF_8);
                    offset += length;
                    String prefix = "SPAYDISCORE_SPEAKER_UUID=";
                    if (comment.startsWith(prefix)) {
                        try {
                            return UUID.fromString(comment.substring(prefix.length()));
                        } catch (IllegalArgumentException ignored) {
                            return null;
                        }
                    }
                }
                return null;
            }

            private static void requireHeader(byte[] packet, String header) throws IOException {
                byte[] expected = header.getBytes(StandardCharsets.US_ASCII);
                if (packet.length < expected.length) {
                    throw new IOException("missing " + header);
                }
                for (int index = 0; index < expected.length; index++) {
                    if (packet[index] != expected[index]) {
                        throw new IOException("missing " + header);
                    }
                }
            }

            private static int readLittleInt(byte[] data, int offset) throws IOException {
                if (offset < 0 || offset > data.length - 4) {
                    throw new IOException("truncated little-endian integer");
                }
                return (data[offset] & 0xff)
                        | ((data[offset + 1] & 0xff) << 8)
                        | ((data[offset + 2] & 0xff) << 16)
                        | ((data[offset + 3] & 0xff) << 24);
            }

            private static void writeLittleShort(ByteArrayOutputStream output, int value) {
                output.write(value & 0xff);
                output.write((value >>> 8) & 0xff);
            }

            private static void writeLittleInt(ByteArrayOutputStream output, int value) {
                output.write(value & 0xff);
                output.write((value >>> 8) & 0xff);
                output.write((value >>> 16) & 0xff);
                output.write((value >>> 24) & 0xff);
            }

            private static void writeLittleInt(byte[] output, int offset, int value) {
                output[offset] = (byte) value;
                output[offset + 1] = (byte) (value >>> 8);
                output[offset + 2] = (byte) (value >>> 16);
                output[offset + 3] = (byte) (value >>> 24);
            }

            private static void writeLittleLong(byte[] output, int offset, long value) {
                for (int index = 0; index < 8; index++) {
                    output[offset + index] = (byte) (value >>> (index * 8));
                }
            }

            private static int oggCrc(byte[] page) {
                return oggCrc(page, 0, page.length);
            }

            private static int oggCrc(byte[] data, int offset, int length) {
                int crc = 0;
                for (int index = 0; index < length; index++) {
                    int valueOffset = offset + index;
                    int value = index >= 22 && index < 26 ? 0 : data[valueOffset] & 0xff;
                    crc = (crc << 8) ^ CRC_TABLE[((crc >>> 24) & 0xff) ^ value];
                }
                return crc;
            }

            private static int[] buildCrcTable() {
                int[] table = new int[256];
                for (int index = 0; index < table.length; index++) {
                    int value = index << 24;
                    for (int bit = 0; bit < 8; bit++) {
                        value = (value & 0x80000000) != 0
                                ? (value << 1) ^ 0x04c11db7 : value << 1;
                    }
                    table[index] = value;
                }
                return table;
            }
        }
    }

    private static final class PhraseClip {
        private final byte[][] frames;
        private final UUID speakerUuid;

        private PhraseClip(byte[][] encodedFrames, UUID speakerUuid) {
            this.frames = new byte[encodedFrames.length][];
            for (int index = 0; index < encodedFrames.length; index++) {
                this.frames[index] = encodedFrames[index] == null
                        ? new byte[0] : encodedFrames[index].clone();
            }
            this.speakerUuid = speakerUuid;
        }

        private int frameCount() {
            return frames.length;
        }

        private byte[] frame(int index) {
            return frames[index].clone();
        }

        private UUID speakerUuid() {
            return speakerUuid;
        }
    }

    private static final class PhraseCaptureState {
        private final OpusDecoder decoder;
        private final Deque<byte[]> preRollFrames = new ArrayDeque<>();
        private final List<byte[]> encodedFrames = new ArrayList<>();
        private final List<byte[]> pendingSilenceFrames = new ArrayList<>();
        private boolean speechSeen;
        private int trailingSilence;
        private long lastPacketNanos;
        private boolean closed;

        private PhraseCaptureState(OpusDecoder decoder) {
            this.decoder = decoder;
        }

        private synchronized void accept(MicrophonePacket packet,
                                          PhraseAudioStore history, UUID speakerUuid) {
            if (closed || packet == null) {
                return;
            }
            byte[] encoded = packet.getOpusEncodedData();
            if (encoded == null || encoded.length == 0) {
                return;
            }
            short[] decoded = decoder.decode(encoded);
            if (decoded == null || decoded.length == 0) {
                return;
            }
            lastPacketNanos = System.nanoTime();

            boolean speech = isSpeechFrame(decoded);
            if (!speech && !speechSeen) {
                preRollFrames.addLast(encoded.clone());
                while (preRollFrames.size() > PHRASE_PREROLL_FRAMES) {
                    preRollFrames.removeFirst();
                }
                return;
            }
            if (speech) {
                if (!speechSeen) {
                    encodedFrames.addAll(preRollFrames);
                    preRollFrames.clear();
                    speechSeen = true;
                } else {
                    encodedFrames.addAll(pendingSilenceFrames);
                    pendingSilenceFrames.clear();
                }
                encodedFrames.add(encoded.clone());
                trailingSilence = 0;
            } else {
                pendingSilenceFrames.add(encoded.clone());
                trailingSilence++;
            }

            if (encodedFrames.size() >= MAX_PHRASE_FRAMES
                    || trailingSilence >= TRAILING_SILENCE_FRAMES) {
                finish(history, speakerUuid);
            }
        }

        private synchronized void finish(PhraseAudioStore history, UUID speakerUuid) {
            if (speechSeen && encodedFrames.size() >= MIN_PHRASE_FRAMES) {
                history.add(new PhraseClip(encodedFrames.toArray(new byte[0][]), speakerUuid));
            }
            encodedFrames.clear();
            preRollFrames.clear();
            pendingSilenceFrames.clear();
            speechSeen = false;
            trailingSilence = 0;
            lastPacketNanos = 0L;
        }

        private synchronized boolean finishIfIdle(PhraseAudioStore history, UUID speakerUuid,
                                                   long nowNanos, long timeoutNanos) {
            if (!speechSeen || lastPacketNanos <= 0L
                    || nowNanos - lastPacketNanos < timeoutNanos) {
                return false;
            }
            finish(history, speakerUuid);
            return true;
        }

        private synchronized void close() {
            if (closed) {
                return;
            }
            closed = true;
            decoder.close();
            encodedFrames.clear();
            preRollFrames.clear();
            pendingSilenceFrames.clear();
            lastPacketNanos = 0L;
        }
    }

    private static short clampToShort(double value) {
        return (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(value)));
    }

    private static final class EchoState {
        private final OpusDecoder decoder;
        private final OpusEncoder encoder;
        private final short[][] history = new short[HISTORY_SIZE][];
        private double[] smoothingState;
        private long frameIndex;
        private boolean closed;

        private EchoState(OpusDecoder decoder, OpusEncoder encoder) {
            this.decoder = decoder;
            this.encoder = encoder;
        }

        private synchronized byte[] process(byte[] encoded) {
            if (closed) {
                return null;
            }
            short[] current = decoder.decode(encoded);
            if (current == null || current.length == 0) {
                return null;
            }

            if (smoothingState == null || smoothingState.length != current.length) {
                smoothingState = new double[current.length];
            }
            short[] output = new short[current.length];
            for (int sample = 0; sample < output.length; sample++) {
                double mixed = 0.0;
                for (int i = 0; i < ECHO_DELAYS.length; i++) {
                    int delay = ECHO_DELAYS[i];
                    if (frameIndex < delay) {
                        continue;
                    }
                    short[] delayed = history[(int) ((frameIndex - delay) % HISTORY_SIZE)];
                    if (delayed != null && sample < delayed.length) {
                        double delayedSample = delayed[sample];
                        short[] diffuse = history[(int) ((frameIndex - delay + 1) % HISTORY_SIZE)];
                        if (diffuse != null && sample < diffuse.length) {
                            delayedSample = delayedSample * (1.0 - ECHO_DIFFUSION)
                                    + diffuse[sample] * ECHO_DIFFUSION;
                        }
                        mixed += delayedSample * ECHO_WEIGHTS[i];
                    }
                }
                double smoothed = smoothingState[sample]
                        + (mixed - smoothingState[sample]) * ECHO_SMOOTHING;
                smoothingState[sample] = smoothed;
                output[sample] = clampToShort(smoothed * ECHO_MIX_GAIN);
            }

            history[(int) (frameIndex % HISTORY_SIZE)] = current;
            frameIndex++;
            return encoder.encode(output);
        }

        private synchronized void close() {
            if (closed) {
                return;
            }
            closed = true;
            decoder.close();
            encoder.close();
        }
    }
}
