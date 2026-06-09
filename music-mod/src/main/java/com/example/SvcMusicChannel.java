package com.example;

import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.VolumeCategory;
import de.maxhenkel.voicechat.api.audiochannel.AudioPlayer;
import de.maxhenkel.voicechat.api.audiochannel.EntityAudioChannel;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Manages Simple Voice Chat audio channels for the "Music" volume category.
 *
 * <h3>Design</h3>
 * <ul>
 *   <li>One {@link EntityAudioChannel} + {@link AudioPlayer} is created for every
 *       player who connects to SVC.  The channel is attached to the player's entity
 *       so it follows them around the world — no fixed position needed.</li>
 *   <li>All players share a single {@code volatile} {@code currentFrame} reference
 *       (written by the Lavaplayer poll thread every 20 ms).  Each player's
 *       {@link Supplier} reads that same reference, so all listeners are in sync.</li>
 *   <li>The channel is tagged with the "Music" {@link VolumeCategory} so it appears
 *       as a separate row in the SVC "Adjust Volumes" GUI.</li>
 * </ul>
 */
public class SvcMusicChannel {

    private static final SvcMusicChannel INSTANCE = new SvcMusicChannel();

    public static SvcMusicChannel getInstance() { return INSTANCE; }

    // -----------------------------------------------------------------------
    // Fields
    // -----------------------------------------------------------------------

    @SuppressWarnings("unused")
    private VoicechatApi       voicechatApi;   // stored for future use
    private VoicechatServerApi serverApi;
    private VolumeCategory     musicCategory;

    /**
     * The latest decoded mono PCM frame (960 shorts, 48 kHz).
     * Written by the Lavaplayer poll thread; read by every active audio supplier.
     * {@code null} means silence.
     */
    private final AtomicReference<short[]> currentFrame = new AtomicReference<>(null);

    /** Maps SVC player UUID → their active AudioPlayer handle. */
    private final Map<UUID, AudioPlayer> playerHandles = new ConcurrentHashMap<>();

    // -----------------------------------------------------------------------
    // SVC lifecycle callbacks (called by MusicVoicechatPlugin)
    // -----------------------------------------------------------------------

    public void onVoicechatInit(VoicechatApi api) {
        this.voicechatApi = api;
        MusicMod.LOGGER.info("[MusicMod] VoicechatApi stored.");
    }

    public void setMusicCategory(VolumeCategory category) {
        this.musicCategory = category;
    }

    public void onServerStarted(VoicechatServerApi api) {
        this.serverApi = api;
        MusicMod.LOGGER.info("[MusicMod] VoicechatServerApi stored — SVC server ready.");
    }

    public void onServerStopped() {
        currentFrame.set(null);
        playerHandles.values().forEach(ap -> {
            try { ap.stopPlaying(); } catch (Exception ignored) {}
        });
        playerHandles.clear();
        serverApi = null;
        MusicMod.LOGGER.info("[MusicMod] Server stopped — all SVC audio players cleaned up.");
    }

    /**
     * Called when a player opens the SVC connection.
     * Creates an {@link EntityAudioChannel} attached to their Minecraft entity,
     * tagged with the "Music" category, and starts an {@link AudioPlayer} that
     * feeds from the shared {@link #currentFrame}.
     */
    public void onPlayerConnect(VoicechatConnection connection) {
        if (serverApi == null) return;

        UUID playerId = connection.getPlayer().getUuid();

        try {
            // Look up the real Minecraft player so we can pass their entity to SVC.
            // VoicechatConnection.getPlayer() returns SVC's own ServerPlayer abstraction
            // which does NOT expose the Minecraft entity directly — we need the server ref.
            if (MusicMod.server == null) {
                MusicMod.LOGGER.warn(
                    "[MusicMod] Server not ready yet, skipping SVC channel for {}", playerId);
                return;
            }
            ServerPlayer mcPlayer =
                MusicMod.server.getPlayerList().getPlayer(playerId);
            if (mcPlayer == null) {
                MusicMod.LOGGER.warn(
                    "[MusicMod] MC player not found for SVC UUID {}", playerId);
                return;
            }

            // EntityAudioChannel follows the player — plays from their entity position.
            // serverApi.fromEntity() wraps the MC entity in SVC's cross-platform type.
            EntityAudioChannel channel = serverApi.createEntityAudioChannel(
                UUID.randomUUID(),
                serverApi.fromEntity(mcPlayer)
            );

            if (channel == null) {
                MusicMod.LOGGER.warn(
                    "[MusicMod] createEntityAudioChannel returned null for {}", playerId);
                return;
            }

            // Tag channel with the "Music" volume category ID — this is what makes it
            // appear as its own row in the SVC "Adjust Volumes" GUI.
            if (musicCategory != null) {
                channel.setCategory(musicCategory.getId());
            }

            // Supplier<short[]>: return the latest shared frame or null (= silence) if
            // nothing is playing.  Lambda is safe — AtomicReference.get() never throws.
            Supplier<short[]> audioSupplier = currentFrame::get;

            // SVC handles Opus encoding internally via createEncoder()
            AudioPlayer ap = serverApi.createAudioPlayer(
                channel, serverApi.createEncoder(), audioSupplier);
            ap.startPlaying();

            playerHandles.put(playerId, ap);
            MusicMod.LOGGER.info(
                "[MusicMod] SVC EntityAudioChannel + AudioPlayer started for {}", playerId);

        } catch (Exception e) {
            MusicMod.LOGGER.error(
                "[MusicMod] Failed to create SVC channel for {}", playerId, e);
        }
    }

    public void onPlayerDisconnect(UUID playerId) {
        AudioPlayer ap = playerHandles.remove(playerId);
        if (ap != null) {
            try { ap.stopPlaying(); } catch (Exception ignored) {}
            MusicMod.LOGGER.info("[MusicMod] SVC AudioPlayer stopped for {}", playerId);
        }
    }

    // -----------------------------------------------------------------------
    // PCM frame delivery  (called from LavalinkManager poll thread)
    // -----------------------------------------------------------------------

    /**
     * Update the shared frame reference.  Every active audio supplier will
     * return this frame on the next SVC callback.
     *
     * @param mono   960-sample mono 48 kHz 16-bit PCM
     * @param player the /play issuer, for error reporting
     * @return {@code true} on success
     */
    public boolean pushFrame(short[] mono, ServerPlayer player) {
        // It's valid for no one to be on SVC yet — just return true silently.
        if (playerHandles.isEmpty()) return true;

        try {
            currentFrame.set(mono);
            return true;
        } catch (Exception e) {
            MusicMod.sendError(player,
                "SvcMusicChannel.pushFrame failed: " + e.getMessage(), e);
            return false;
        }
    }

    // -----------------------------------------------------------------------
    // /musictest — pure SVC pipeline test, no Lavaplayer involved
    // -----------------------------------------------------------------------

    /**
     * Generates a 1-second 440 Hz sine wave and pushes it through the SVC
     * pipeline in 20 ms chunks — completely bypassing Lavaplayer.
     * Use this to confirm SVC audio delivery works before testing YouTube playback.
     */
    public void playTestTone(ServerPlayer triggerPlayer) {
        if (serverApi == null) {
            MusicMod.sendError(triggerPlayer,
                "/musictest: SVC not ready — is Simple Voice Chat installed?");
            return;
        }
        if (playerHandles.isEmpty()) {
            MusicMod.sendError(triggerPlayer,
                "/musictest: No players connected to SVC. Open voice chat first.");
            return;
        }

        short[] sineWave = PcmUtils.generateSineWave(440.0, 1000); // 1 second

        Thread toneThread = new Thread(() -> {
            int frames = sineWave.length / PcmUtils.FRAME_SAMPLES;
            int sent   = 0;

            for (int f = 0; f < frames; f++) {
                short[] frame = new short[PcmUtils.FRAME_SAMPLES];
                System.arraycopy(sineWave, f * PcmUtils.FRAME_SAMPLES,
                    frame, 0, PcmUtils.FRAME_SAMPLES);

                currentFrame.set(frame);
                sent++;

                MusicMod.LOGGER.info(
                    "[MusicMod] Test frame {} delivered to SVC. Bytes: {}",
                    f + 1, frame.length * 2);

                try {
                    Thread.sleep(20);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            currentFrame.set(null); // silence after tone

            final int finalSent = sent;
            MusicMod.LOGGER.info("[MusicMod] /musictest complete — {} frames sent.", finalSent);
            triggerPlayer.sendSystemMessage(
                Component.literal("§a[MusicMod] Test tone complete — " + finalSent + " frames sent to SVC.")
            );

        }, "MusicMod-TestTone");
        toneThread.setDaemon(true);
        toneThread.start();
    }
}
