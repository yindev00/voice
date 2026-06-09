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
 * {@code fabric.mod.json}.  SVC discovers and instantiates it automatically on load.</p>
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Register the {@code "music"} {@link VolumeCategory} so it appears as its own
 *       row in the SVC "Adjust Volumes" GUI.</li>
 *   <li>Forward all SVC lifecycle events to {@link SvcMusicChannel}.</li>
 * </ul>
 * </p>
 *
 * <p><b>IMPORTANT:</b> Do NOT call {@link LavalinkManager#getInstance()} from any SVC
 * event handler.  SVC events fire before the server is fully up, and constructing
 * {@link LavalinkManager} (which builds {@code DefaultAudioPlayerManager}) at that point
 * can throw {@code ClassNotFoundException} for {@code lava-common} classes.
 * LavalinkManager is initialised lazily on the first {@code /play} command instead.</p>
 */
public class MusicVoicechatPlugin implements VoicechatPlugin {

    @Override
    public String getPluginId() {
        return MusicMod.MOD_ID;
    }

    @Override
    public void initialize(VoicechatApi api) {
        SvcMusicChannel.getInstance().onVoicechatInit(api);
        MusicMod.LOGGER.info("[MusicMod] VoicechatPlugin.initialize() — SVC API ready.");
    }

    @Override
    public void registerEvents(EventRegistration registration) {

        registration.registerEvent(VoicechatServerStartedEvent.class, event -> {
            VoicechatServerApi api = event.getVoicechat();
            SvcMusicChannel.getInstance().onServerStarted(api);

            VolumeCategory musicCategory = api.volumeCategoryBuilder()
                .setId("music")
                .setName("Music")
                .setDescription("Volume for music streamed via /play")
                .build();
            api.registerVolumeCategory(musicCategory);
            SvcMusicChannel.getInstance().setMusicCategory(musicCategory);

            MusicMod.LOGGER.info("[MusicMod] SVC server started + 'Music' category registered.");
            // Do NOT touch LavalinkManager here — it is not initialised yet.
        });

        registration.registerEvent(VoicechatServerStoppedEvent.class, event -> {
            SvcMusicChannel.getInstance().onServerStopped();
            // Only stop the poll loop if LavalinkManager was ever initialised.
            LavalinkManager.stopPollLoopIfInitialised();
        });

        registration.registerEvent(PlayerConnectedEvent.class, event -> {
            SvcMusicChannel.getInstance().onPlayerConnect(event.getConnection());
            MusicMod.LOGGER.info("[MusicMod] Player {} connected to SVC.",
                event.getConnection().getPlayer().getUuid());
            // Do NOT touch LavalinkManager here — it may not be initialised yet.
        });

        registration.registerEvent(PlayerDisconnectedEvent.class, event -> {
            SvcMusicChannel.getInstance().onPlayerDisconnect(event.getPlayerUuid());
        });
    }
}
