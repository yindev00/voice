package com.example;

import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.VolumeCategory;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.PlayerConnectedEvent;
import de.maxhenkel.voicechat.api.events.PlayerDisconnectedEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStoppedEvent;

/**
 * Simple Voice Chat plugin entry point.
 *
 * <p>Registration: this class is listed under the {@code "voicechat"} entrypoint key in
 * {@code fabric.mod.json}.  SVC discovers and instantiates it automatically on load.
 * No {@code @VoicechatPlugin} annotation is needed — the Fabric entrypoint system is
 * the canonical registration mechanism for the Fabric edition of SVC.</p>
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Register the {@code "music"} {@link VolumeCategory} so it appears as its own
 *       row in the SVC "Adjust Volumes" GUI — separate from "Other" and "Own voice".</li>
 *   <li>Forward all SVC lifecycle events to {@link SvcMusicChannel}.</li>
 * </ul>
 * </p>
 */
public class MusicVoicechatPlugin implements VoicechatPlugin {

    @Override
    public String getPluginId() {
        return MusicMod.MOD_ID;
    }

    /** Called once when the SVC API object is available (before server start). */
    @Override
    public void initialize(VoicechatApi api) {
        SvcMusicChannel.getInstance().onVoicechatInit(api);
        MusicMod.LOGGER.info("[MusicMod] VoicechatPlugin.initialize() — SVC API ready.");
    }

    /**
     * Register all SVC lifecycle events.
     * Volume category is created and registered on server start so the server API is available.
     */
    @Override
    public void registerEvents(EventRegistration registration) {

        registration.registerEvent(VoicechatServerStartedEvent.class, event -> {
            VoicechatServerApi api = event.getVoicechat();
            SvcMusicChannel.getInstance().onServerStarted(api);

            // Register the "Music" volume category now that the server API is live.
            VolumeCategory musicCategory = api.volumeCategoryBuilder()
                .setId("music")
                .setName("Music")
                .setDescription("Volume for music streamed via /play")
                .build();
            api.registerVolumeCategory(musicCategory);
            SvcMusicChannel.getInstance().setMusicCategory(musicCategory);

            MusicMod.LOGGER.info("[MusicMod] SVC server started + 'Music' category registered.");

            if (LavalinkManager.getInstance().isDebugMode()) {
                MusicMod.LOGGER.info(
                    "[MusicMod] [Debug] Lavalink node status: EMBEDDED (Lavaplayer 2.x)");
            }
        });

        registration.registerEvent(VoicechatServerStoppedEvent.class, event -> {
            SvcMusicChannel.getInstance().onServerStopped();
            LavalinkManager.getInstance().stopPollLoop();
        });

        registration.registerEvent(PlayerConnectedEvent.class, event -> {
            SvcMusicChannel.getInstance().onPlayerConnect(event.getConnection());

            if (LavalinkManager.getInstance().isDebugMode()) {
                MusicMod.LOGGER.info(
                    "[MusicMod] [Debug] Player {} connected to SVC — channel created.",
                    event.getConnection().getPlayer().getUuid());
            }
        });

        registration.registerEvent(PlayerDisconnectedEvent.class, event -> {
            SvcMusicChannel.getInstance().onPlayerDisconnect(event.getPlayerUuid());
        });
    }
}
