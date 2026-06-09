package com.example;

import com.sedmelluq.discord.lavaplayer.format.StandardAudioDataFormats;
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.playback.AudioFrame;
import dev.lavalink.youtube.YoutubeAudioSourceManager;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Manages Lavaplayer for audio loading and PCM extraction.
 *
 * <p><b>Why Lavaplayer instead of a remote Lavalink WebSocket?</b><br>
 * Lavalink's wire protocol streams Opus audio to Discord via UDP — there is no
 * mechanism for a client to intercept raw PCM frames across the network.
 * Lavaplayer is the exact same engine that runs <em>inside</em> Lavalink.
 * Embedding it directly is the only way to obtain per-frame PCM for SVC injection.</p>
 *
 * <p>Lavaplayer is configured with {@link StandardAudioDataFormats#COMMON_PCM_S16_LE}
 * so {@link AudioPlayer#provide()} returns raw 48 kHz stereo 16-bit LE PCM instead
 * of Opus, which we downmix to mono and push to Simple Voice Chat.</p>
 */
public class LavalinkManager {

    private static LavalinkManager INSTANCE;

    public static LavalinkManager getInstance() {
        if (INSTANCE == null) INSTANCE = new LavalinkManager();
        return INSTANCE;
    }

    // -----------------------------------------------------------------------
    // Fields
    // -----------------------------------------------------------------------

    private final AudioPlayerManager      playerManager;
    private final AudioPlayer             audioPlayer;
    private final ScheduledExecutorService scheduler;

    private ScheduledFuture<?> pollTask;
    private ServerPlayer commandPlayer;   // player who triggered /play
    private boolean      debugMode = false;

    private final AtomicLong totalFramesSent = new AtomicLong(0);

    // -----------------------------------------------------------------------
    // Construction
    // -----------------------------------------------------------------------

    private LavalinkManager() {
        playerManager = new DefaultAudioPlayerManager();

        // Tell Lavaplayer to produce raw 16-bit signed LE stereo PCM (48 kHz).
        // Each 20 ms frame = 960 samples/channel × 2 channels × 2 bytes = 3840 bytes.
        playerManager.getConfiguration()
                     .setOutputFormat(StandardAudioDataFormats.COMMON_PCM_S16_LE);

        // YouTube source provided by dev.lavalink.youtube:common
        playerManager.registerSourceManager(new YoutubeAudioSourceManager());

        MusicMod.LOGGER.info("[MusicMod] Lavaplayer initialized — output: PCM S16 LE, 48 kHz stereo.");

        audioPlayer = playerManager.createPlayer();

        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "MusicMod-PCM-Poll");
            t.setDaemon(true);
            return t;
        });
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    public boolean isDebugMode() { return debugMode; }
    public void    setDebugMode(boolean v) { debugMode = v; }

    /**
     * Load a track and start playback.
     *
     * @param query  a YouTube URL, or a plain-text search query
     * @param player the player who typed /play (receives status + error messages)
     */
    public void loadAndPlay(String query, ServerPlayer player) {
        this.commandPlayer = player;

        // Bare text → ytsearch:, URL → use as-is
        String identifier = isUrl(query) ? query : "ytsearch:" + query;

        playerManager.loadItem(identifier, new AudioLoadResultHandler() {

            @Override
            public void trackLoaded(AudioTrack track) {
                // AudioTrackInfo uses public *fields*, not getters
                String title    = track.getInfo().title;
                long   duration = track.getDuration();          // ms
                String uri      = track.getInfo().uri;

                MusicMod.LOGGER.info("[MusicMod] Track loaded: {} ({}ms)", title, duration);

                // §a▶ Now Playing: §f<Actual Track Title>  — from real metadata
                player.sendSystemMessage(Component.literal("§a▶ Now Playing: §f" + title));

                if (debugMode) {
                    player.sendSystemMessage(Component.literal(
                        "§e[Debug] Track loaded: " + title +
                        " | Duration: " + duration + "ms | URL: " + uri
                    ));
                }

                audioPlayer.playTrack(track);
                startPollLoop();
            }

            @Override
            public void playlistLoaded(AudioPlaylist playlist) {
                // For search results the first/selected track is what we want
                AudioTrack first = playlist.getSelectedTrack() != null
                    ? playlist.getSelectedTrack()
                    : playlist.getTracks().get(0);
                trackLoaded(first);
            }

            @Override
            public void noMatches() {
                MusicMod.sendError(player, "No track found for: " + query);
            }

            @Override
            public void loadFailed(FriendlyException exception) {
                MusicMod.sendError(player,
                    "Track load failed: " + exception.getMessage(), exception);
            }
        });
    }

    // -----------------------------------------------------------------------
    // PCM poll loop
    // -----------------------------------------------------------------------

    private synchronized void startPollLoop() {
        stopPollLoop();
        totalFramesSent.set(0);
        pollTask = scheduler.scheduleAtFixedRate(
            this::pollFrame, 0L, 20L, TimeUnit.MILLISECONDS);
        MusicMod.LOGGER.info("[MusicMod] PCM poll loop started (20 ms cadence).");
    }

    public synchronized void stopPollLoop() {
        if (pollTask != null && !pollTask.isCancelled()) {
            pollTask.cancel(false);
            pollTask = null;
            MusicMod.LOGGER.info("[MusicMod] PCM poll loop stopped.");
        }
    }

    /**
     * Called every 20 ms by the scheduler thread.
     * Fetches one PCM frame from Lavaplayer, downmixes stereo→mono, and
     * pushes to {@link SvcMusicChannel}.
     */
    private void pollFrame() {
        try {
            // AudioPlayer.provide() returns null when the buffer is empty or track ended
            AudioFrame frame = audioPlayer.provide();
            if (frame == null || frame.isTerminator()) {
                return;
            }

            // getData() = interleaved stereo S16 LE bytes
            byte[] stereoBytes = frame.getData();

            if (debugMode && commandPlayer != null) {
                commandPlayer.sendSystemMessage(Component.literal(
                    "§e[Debug] PCM frame received: " + stereoBytes.length + " bytes"
                ));
            }

            // Downmix stereo 48 kHz 16-bit → mono 48 kHz 16-bit (960 shorts)
            short[] mono = PcmUtils.stereoToMono(stereoBytes);

            if (debugMode && commandPlayer != null) {
                commandPlayer.sendSystemMessage(Component.literal(
                    "§e[Debug] Downmix: " + stereoBytes.length +
                    " stereo bytes → " + (mono.length * 2) + " mono bytes"
                ));
            }

            // Push the mono frame to all SVC-connected players
            boolean sent = SvcMusicChannel.getInstance().pushFrame(mono, commandPlayer);

            if (!sent) {
                MusicMod.LOGGER.error(
                    "[MusicMod] SVC rejected audio frame (pushFrame returned false).");
                if (commandPlayer != null) {
                    commandPlayer.sendSystemMessage(
                        Component.literal("§cMusic Error: SVC rejected audio frame. See server log.")
                    );
                }
                return;
            }

            long count = totalFramesSent.incrementAndGet();

            if (count == 1) {
                MusicMod.LOGGER.info(
                    "[MusicMod] First PCM frame delivered to SVC. Bytes: {}", mono.length * 2);
            }
            if (count % 100 == 0) {
                MusicMod.LOGGER.info(
                    "[MusicMod] Heartbeat — total frames sent: {}", count);
            }

            if (debugMode && commandPlayer != null) {
                commandPlayer.sendSystemMessage(Component.literal(
                    "§e[Debug] Frame sent to SVC: " + (mono.length * 2) + " bytes"
                ));
            }

        } catch (Exception e) {
            MusicMod.LOGGER.error("[MusicMod] Exception in PCM poll loop", e);
            MusicMod.sendError(commandPlayer, "PCM poll error: " + e.getMessage(), e);
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static boolean isUrl(String s) {
        return s.startsWith("http://")
            || s.startsWith("https://")
            || s.startsWith("ytsearch:");
    }
}
