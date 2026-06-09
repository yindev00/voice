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
import com.sedmelluq.discord.lavaplayer.track.playback.MutableAudioFrame;
import dev.lavalink.youtube.YoutubeAudioSourceManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.nio.ByteBuffer;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Manages Lavaplayer: connects to YouTube (directly, no Lavalink WS needed for PCM),
 * loads tracks, polls raw PCM frames every 20 ms, and pushes them to {@link SvcMusicChannel}.
 *
 * <p>NOTE — the user's prompt requests a connection to a Lavalink WebSocket server at
 * ws://localhost:2333 for control, but PCM frame interception is impossible through the
 * standard Lavalink wire protocol (it only streams Opus to Discord).  Instead, we embed
 * Lavaplayer directly (it is the same engine Lavalink uses internally) which gives us
 * full PCM access via the AudioFrame API.  This is the only correct approach for
 * real PCM delivery to Simple Voice Chat.</p>
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

    private final AudioPlayerManager playerManager;
    private final AudioPlayer         audioPlayer;
    private final ScheduledExecutorService scheduler;

    private ScheduledFuture<?>  pollTask;
    private ServerPlayerEntity  commandPlayer;   // player who issued /play
    private boolean             debugMode = false;

    private final AtomicLong totalFramesSent = new AtomicLong(0);

    // Reusable MutableAudioFrame to avoid allocations in the hot loop
    private final MutableAudioFrame mutableFrame = new MutableAudioFrame();
    private final ByteBuffer        frameBuffer;

    // -----------------------------------------------------------------------
    // Construction
    // -----------------------------------------------------------------------

    private LavalinkManager() {
        playerManager = new DefaultAudioPlayerManager();

        // Output raw 16-bit signed LE stereo PCM instead of Opus, so we can
        // downmix to mono and push directly to SVC.
        playerManager.getConfiguration()
                     .setOutputFormat(StandardAudioDataFormats.COMMON_PCM_S16_LE);

        // Register YouTube source (dev.lavalink.youtube:youtube-source)
        YoutubeAudioSourceManager ytSource = new YoutubeAudioSourceManager();
        playerManager.registerSourceManager(ytSource);

        MusicMod.LOGGER.info("[MusicMod] Lavaplayer initialized with YouTube source.");

        audioPlayer = playerManager.createPlayer();

        // Pre-allocate frame buffer: 20 ms stereo 48 kHz 16-bit = 3840 bytes
        frameBuffer = ByteBuffer.allocate(3840);
        mutableFrame.setBuffer(frameBuffer);

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
     * Load a track and begin playback.
     *
     * @param query  YouTube URL or search string (e.g. "never gonna give you up")
     * @param player the player who ran /play — receives status messages
     */
    public void loadAndPlay(String query, ServerPlayerEntity player) {
        this.commandPlayer = player;

        // If the query is not a URL, prefix with ytsearch: for Lavaplayer
        String identifier = isUrl(query) ? query : "ytsearch:" + query;

        playerManager.loadItem(identifier, new AudioLoadResultHandler() {

            @Override
            public void trackLoaded(AudioTrack track) {
                String title    = track.getInfo().getTitle();
                long   duration = track.getDuration();
                String uri      = track.getInfo().getUri();

                MusicMod.LOGGER.info("[MusicMod] Track loaded: {} | {}ms", title, duration);

                // In-game now-playing message
                player.sendMessage(Text.literal("§a▶ Now Playing: §f" + title));

                if (debugMode) {
                    player.sendMessage(Text.literal(
                        "§e[Debug] Track loaded: " + title +
                        " | Duration: " + duration + "ms | URL: " + uri
                    ));
                }

                audioPlayer.playTrack(track);
                startPollLoop();
            }

            @Override
            public void playlistLoaded(AudioPlaylist playlist) {
                // Play the first track from the playlist / search result
                AudioTrack first = playlist.getSelectedTrack() != null
                    ? playlist.getSelectedTrack()
                    : playlist.getTracks().get(0);
                trackLoaded(first);
            }

            @Override
            public void noMatches() {
                String msg = "No track found for: " + query;
                MusicMod.sendError(player, msg);
            }

            @Override
            public void loadFailed(FriendlyException exception) {
                MusicMod.sendError(player, "Track load failed: " + exception.getMessage(), exception);
            }
        });
    }

    // -----------------------------------------------------------------------
    // PCM poll loop — runs every 20 ms on the MusicMod-PCM-Poll thread
    // -----------------------------------------------------------------------

    private synchronized void startPollLoop() {
        stopPollLoop();
        totalFramesSent.set(0);

        pollTask = scheduler.scheduleAtFixedRate(this::pollFrame, 0L, 20L, TimeUnit.MILLISECONDS);
        MusicMod.LOGGER.info("[MusicMod] PCM poll loop started.");
    }

    public synchronized void stopPollLoop() {
        if (pollTask != null && !pollTask.isCancelled()) {
            pollTask.cancel(false);
            pollTask = null;
        }
    }

    private void pollFrame() {
        try {
            frameBuffer.clear();
            boolean provided = audioPlayer.provide(mutableFrame);

            if (!provided) {
                // Lavaplayer has no frame right now (buffering or track ended)
                return;
            }

            // getData() on MutableAudioFrame returns the populated buffer's array
            int dataLength = mutableFrame.getDataLength();
            byte[] stereoBytes = new byte[dataLength];
            frameBuffer.rewind();
            frameBuffer.get(stereoBytes, 0, dataLength);

            if (debugMode && commandPlayer != null) {
                commandPlayer.sendMessage(Text.literal(
                    "§e[Debug] PCM frame received: " + dataLength + " bytes"
                ));
            }

            // Downmix stereo → mono
            short[] mono = PcmUtils.stereoToMono(stereoBytes);

            if (debugMode && commandPlayer != null) {
                commandPlayer.sendMessage(Text.literal(
                    "§e[Debug] Downmix: " + dataLength + " stereo bytes → " + (mono.length * 2) + " mono bytes"
                ));
            }

            // Push to SVC
            boolean sent = SvcMusicChannel.getInstance().pushFrame(mono, commandPlayer);

            if (!sent) {
                MusicMod.LOGGER.error("[MusicMod] SVC rejected audio frame (pushFrame returned false).");
                if (commandPlayer != null) {
                    commandPlayer.sendMessage(Text.literal(
                        "§cMusic Error: SVC rejected audio frame. See server log."
                    ));
                }
                return;
            }

            long count = totalFramesSent.incrementAndGet();

            // First-frame confirmation
            if (count == 1) {
                MusicMod.LOGGER.info("[MusicMod] First PCM frame delivered to SVC. Bytes: {}", mono.length * 2);
            }

            // Heartbeat every 100 frames
            if (count % 100 == 0) {
                MusicMod.LOGGER.info("[MusicMod] Heartbeat — total frames sent: {}", count);
            }

            if (debugMode && commandPlayer != null) {
                commandPlayer.sendMessage(Text.literal(
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
        return s.startsWith("http://") || s.startsWith("https://") || s.startsWith("ytsearch:");
    }
}
