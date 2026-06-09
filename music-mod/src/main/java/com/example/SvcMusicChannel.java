package com.example;

import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.VolumeCategory;
import de.maxhenkel.voicechat.api.audiochannel.AudioPlayer;
import de.maxhenkel.voicechat.api.audiochannel.AudioProvider;
import de.maxhenkel.voicechat.api.audiochannel.StaticAudioChannel;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Manages Simple Voice Chat audio channels for the "Music" volume category.
 *
 * <p>Design: one {@link StaticAudioChannel} + {@link AudioPlayer} per connected SVC player.
 * All players share a single volatile {@code currentFrame} reference that gets updated by
 * the Lavaplayer poll thread every 20 ms.  Each player's {@link AudioProvider} reads that
 * same reference so all listeners are in sync.</p>
 */
public class SvcMusicChannel {

    private static final SvcMusicChannel INSTANCE = new SvcMusicChannel();

    public static SvcMusicChannel getInstance() { return INSTANCE; }

    // -----------------------------------------------------------------------
    // Fields
    // -----------------------------------------------------------------------

    private VoicechatApi       voicechatApi;
    private VoicechatServerApi serverApi;
    private VolumeCategory     musicCategory;

    // The most recent decoded PCM frame available for all players to consume.
    // Written by the Lavaplayer thread, read by each player's AudioProvider thread.
    private final AtomicReference<short[]> currentFrame = new AtomicReference<>(null);

    // Map from SVC player UUID → their active AudioPlayer handle
    private final Map<UUID, AudioPlayer> playerHandles = new ConcurrentHashMap<>();

    // -----------------------------------------------------------------------
    // SVC lifecycle callbacks (invoked by MusicVoicechatPlugin)
    // -----------------------------------------------------------------------

    public void onVoicechatInit(VoicechatApi api) {
        this.voicechatApi = api;
        MusicMod.LOGGER.info("[MusicMod] VoicechatApi reference stored.");
    }

    public void setMusicCategory(VolumeCategory category) {
        this.musicCategory = category;
    }

    public void onServerStarted(VoicechatServerApi api) {
        this.serverApi = api;
        MusicMod.LOGGER.info("[MusicMod] VoicechatServerApi reference stored — server started.");
        if (musicCategory != null) {
            MusicMod.LOGGER.info("[MusicMod] 'Music' category is ready.");
        }
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

    public void onPlayerConnect(VoicechatConnection connection) {
        if (serverApi == null || musicCategory == null) return;
        UUID playerId = connection.getPlayer().getUuid();

        try {
            // Create a static audio channel for this player at their current position.
            // The channel is associated with the "Music" volume category so the player
            // can adjust its volume independently in the SVC "Adjust Volumes" screen.
            StaticAudioChannel channel = serverApi.createStaticAudioChannel(
                UUID.randomUUID(),
                serverApi.fromServerLevel(
                    (net.minecraft.server.world.ServerWorld) connection.getPlayer().getWorld()
                ),
                connection
            );

            if (channel == null) {
                MusicMod.LOGGER.warn("[MusicMod] createStaticAudioChannel returned null for {}", playerId);
                return;
            }

            // Attach the Music volume category so SVC GUI shows it under "Music"
            channel.setCategory(musicCategory);

            // AudioProvider reads the latest frame that the Lavaplayer thread wrote.
            AudioProvider provider = () -> currentFrame.get();

            // Encoder provided by SVC (Opus, 48 kHz mono)
            AudioPlayer ap = serverApi.createAudioPlayer(channel, serverApi.createEncoder(), provider);
            ap.startPlaying();

            playerHandles.put(playerId, ap);
            MusicMod.LOGGER.info("[MusicMod] SVC audio player started for player {}", playerId);

        } catch (Exception e) {
            MusicMod.LOGGER.error("[MusicMod] Failed to create SVC channel for {}", playerId, e);
        }
    }

    public void onPlayerDisconnect(VoicechatConnection connection) {
        UUID playerId = connection.getPlayer().getUuid();
        AudioPlayer ap = playerHandles.remove(playerId);
        if (ap != null) {
            try { ap.stopPlaying(); } catch (Exception ignored) {}
            MusicMod.LOGGER.info("[MusicMod] SVC audio player stopped for {}", playerId);
        }
    }

    // -----------------------------------------------------------------------
    // PCM frame delivery (called from LavalinkManager poll thread)
    // -----------------------------------------------------------------------

    /**
     * Push a mono PCM frame so all connected SVC players receive it.
     *
     * @param mono   960-sample mono 16-bit 48 kHz PCM
     * @param player player who issued /play (for error reporting)
     * @return true if at least one SVC AudioPlayer is active
     */
    public boolean pushFrame(short[] mono, ServerPlayerEntity player) {
        if (playerHandles.isEmpty()) {
            // Nobody connected to SVC yet — not an error, just silence
            return true;
        }

        try {
            currentFrame.set(mono);
            return true;
        } catch (Exception e) {
            MusicMod.sendError(player, "SvcMusicChannel.pushFrame failed: " + e.getMessage(), e);
            return false;
        }
    }

    // -----------------------------------------------------------------------
    // /musictest — 440 Hz sine wave, no Lavalink, pure SVC pipeline test
    // -----------------------------------------------------------------------

    /**
     * Synthesise a 1-second 440 Hz sine wave and play it through each connected
     * player's SVC channel.  No Lavalink involved — used to verify the SVC
     * pipeline in isolation.
     */
    public void playTestTone(ServerPlayerEntity triggerPlayer) {
        if (serverApi == null) {
            MusicMod.sendError(triggerPlayer, "/musictest: SVC serverApi not ready yet. Is Simple Voice Chat installed?");
            return;
        }
        if (playerHandles.isEmpty()) {
            MusicMod.sendError(triggerPlayer, "/musictest: No players connected to SVC. Join a voice chat first.");
            return;
        }

        // Generate 1 second of 440 Hz sine wave (48 000 samples)
        short[] sineWave = PcmUtils.generateSineWave(440.0, 1000);

        // Chunk it into 960-sample frames and push them sequentially with 20 ms gaps
        Thread toneThread = new Thread(() -> {
            int frames = sineWave.length / PcmUtils.FRAME_SAMPLES;
            int sent   = 0;

            for (int f = 0; f < frames; f++) {
                short[] frame = new short[PcmUtils.FRAME_SAMPLES];
                System.arraycopy(sineWave, f * PcmUtils.FRAME_SAMPLES, frame, 0, PcmUtils.FRAME_SAMPLES);

                currentFrame.set(frame);
                sent++;

                MusicMod.LOGGER.info("[MusicMod] Test frame {} delivered to SVC. Bytes: {}",
                    f + 1, frame.length * 2);

                try {
                    Thread.sleep(20);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            // Clear frame after tone finishes
            currentFrame.set(null);

            MusicMod.LOGGER.info("[MusicMod] /musictest complete — {} frames sent.", sent);
            triggerPlayer.sendMessage(Text.literal(
                "§a[MusicMod] Test tone complete — " + sent + " frames sent to SVC."
            ));

        }, "MusicMod-TestTone");
        toneThread.setDaemon(true);
        toneThread.start();
    }
}
