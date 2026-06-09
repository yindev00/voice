package com.example;

import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.VolumeCategory;
import de.maxhenkel.voicechat.api.VolumeCategoryManager;

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
     * Register the "Music" volume category.
     * It will appear in the SVC "Adjust Volumes" screen, letting players control
     * music volume independently of voice and other sounds.
     */
    @Override
    public void registerVolumeCategories(VolumeCategoryManager manager) {
        VolumeCategory musicCategory = manager.createBuilder()
            .setId("music")
            .setName("Music")
            .setDescription("Volume for music streamed via /play")
            .build();

        manager.registerVolumeCategory(musicCategory);
        SvcMusicChannel.getInstance().setMusicCategory(musicCategory);

        MusicMod.LOGGER.info("[MusicMod] 'Music' volume category registered in SVC.");
    }

    // -----------------------------------------------------------------------
    // Lifecycle hooks — SVC calls these directly on the plugin instance
    // -----------------------------------------------------------------------

    @Override
    public void onServerStarted(VoicechatServerApi api) {
        SvcMusicChannel.getInstance().onServerStarted(api);
        MusicMod.LOGGER.info("[MusicMod] SVC server started.");

        if (LavalinkManager.getInstance().isDebugMode()) {
            MusicMod.LOGGER.info(
                "[MusicMod] [Debug] Lavalink node status: EMBEDDED (Lavaplayer 2.x)");
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
            MusicMod.LOGGER.info(
                "[MusicMod] [Debug] Player {} connected to SVC — channel created.",
                connection.getPlayer().getUuid());
        }
    }

    @Override
    public void onPlayerDisconnected(VoicechatConnection connection) {
        SvcMusicChannel.getInstance().onPlayerDisconnect(connection);
    }
}
