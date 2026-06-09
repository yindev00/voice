package com.example;

import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.VolumeCategory;
import de.maxhenkel.voicechat.api.VolumeCategoryManager;

/**
 * SVC plugin entry point.
 *
 * <p>Registered under the {@code "voicechat"} entrypoint key in {@code fabric.mod.json}.
 * SVC discovers and instantiates this class automatically when the mod is loaded alongside
 * Simple Voice Chat.</p>
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Register the "Music" volume category so it appears as its own row in the
 *       SVC "Adjust Volumes" GUI — separate from "Other" and "Own voice".</li>
 *   <li>Forward SVC lifecycle events (server start/stop, player connect/disconnect)
 *       to {@link SvcMusicChannel}.</li>
 * </ul>
 * </p>
 */
@de.maxhenkel.voicechat.api.VoicechatPlugin
public class MusicVoicechatPlugin implements VoicechatPlugin {

    @Override
    public String getPluginId() {
        return MusicMod.MOD_ID;
    }

    // Called once when the SVC API is ready (before the server fully starts).
    @Override
    public void initialize(VoicechatApi api) {
        SvcMusicChannel.getInstance().onVoicechatInit(api);
        MusicMod.LOGGER.info("[MusicMod] SVC VoicechatPlugin.initialize() called.");
    }

    // Register our custom "Music" volume category.
    // It will appear in the SVC Adjust Volumes screen between/below the built-in categories.
    @Override
    public void registerVolumeCategories(VolumeCategoryManager manager) {
        VolumeCategory musicCategory = manager.createBuilder()
            .setId("music")
            .setName("Music")
            .setDescription("Volume for streamed music played via /play")
            .build();

        manager.registerVolumeCategory(musicCategory);
        SvcMusicChannel.getInstance().setMusicCategory(musicCategory);

        MusicMod.LOGGER.info("[MusicMod] 'Music' volume category registered in Simple Voice Chat.");
    }

    // -----------------------------------------------------------------------
    // Lifecycle hooks — SVC calls these directly on the plugin instance.
    // -----------------------------------------------------------------------

    @Override
    public void onServerStarted(VoicechatServerApi api) {
        SvcMusicChannel.getInstance().onServerStarted(api);

        MusicMod.LOGGER.info("[MusicMod] SVC server started.");

        // Debug: log the Lavalink (Lavaplayer) node status
        if (LavalinkManager.getInstance().isDebugMode()) {
            MusicMod.LOGGER.info("[MusicMod] §e[Debug] Lavalink node status: EMBEDDED (Lavaplayer)");
        }
    }

    @Override
    public void onServerStopped() {
        SvcMusicChannel.getInstance().onServerStopped();
        LavalinkManager.getInstance().stopPollLoop();
    }

    @Override
    public void onPlayerConnected(VoicechatConnection connection) {
        SvcMusicChannel.getInstance().onPlayerConnect(connection);

        if (LavalinkManager.getInstance().isDebugMode()) {
            MusicMod.LOGGER.info("[MusicMod] §e[Debug] Lavalink node status: EMBEDDED (Lavaplayer) — player {} connected to SVC",
                connection.getPlayer().getUuid());
        }
    }

    @Override
    public void onPlayerDisconnected(VoicechatConnection connection) {
        SvcMusicChannel.getInstance().onPlayerDisconnect(connection);
    }
}
