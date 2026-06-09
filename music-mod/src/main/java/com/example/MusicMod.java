package com.example;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MusicMod implements ModInitializer {

    public static final String MOD_ID = "musicmod";
    public static final Logger LOGGER = LoggerFactory.getLogger("[MusicMod]");

    @Override
    public void onInitialize() {
        LOGGER.info("[MusicMod] Initializing Music Mod...");

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {

            // /play <query or URL>   — also handles /play debug
            dispatcher.register(
                CommandManager.literal("play")
                    .then(CommandManager.argument("query", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            String query = StringArgumentType.getString(ctx, "query");
                            ServerPlayerEntity player;
                            try {
                                player = ctx.getSource().getPlayerOrThrow();
                            } catch (Exception e) {
                                return 0;
                            }

                            if (query.equalsIgnoreCase("debug")) {
                                boolean next = !LavalinkManager.getInstance().isDebugMode();
                                LavalinkManager.getInstance().setDebugMode(next);
                                String onOff = next ? "§aON" : "§cOFF";
                                player.sendMessage(Text.literal("§e[MusicMod] Debug mode: " + onOff));
                                return 1;
                            }

                            player.sendMessage(Text.literal("§7[MusicMod] Loading track..."));
                            LavalinkManager.getInstance().loadAndPlay(query, player);
                            return 1;
                        }))
            );

            // /musictest — 440 Hz sine wave directly into SVC, no Lavalink
            dispatcher.register(
                CommandManager.literal("musictest")
                    .executes(ctx -> {
                        ServerPlayerEntity player;
                        try {
                            player = ctx.getSource().getPlayerOrThrow();
                        } catch (Exception e) {
                            return 0;
                        }
                        player.sendMessage(Text.literal("§7[MusicMod] Sending 440 Hz sine-wave test tone via SVC..."));
                        SvcMusicChannel.getInstance().playTestTone(player);
                        return 1;
                    })
            );
        });

        LOGGER.info("[MusicMod] Commands registered: /play <query|debug>, /musictest");
    }

    // -----------------------------------------------------------------------
    // Utility — send an error to both the server log and the player's chat.
    // -----------------------------------------------------------------------

    public static void sendError(ServerPlayerEntity player, String message) {
        sendError(player, message, null);
    }

    public static void sendError(ServerPlayerEntity player, String message, Throwable t) {
        LOGGER.error("[MusicMod] {}", message, t);

        if (player == null || !player.isAlive()) return;

        StringBuilder sb = new StringBuilder("§c[MusicMod Error] ").append(message);
        if (t != null) {
            sb.append("\n§c  ").append(t.getClass().getSimpleName());
            if (t.getMessage() != null) {
                sb.append(": ").append(t.getMessage());
            }
            StackTraceElement[] st = t.getStackTrace();
            for (int i = 0; i < Math.min(3, st.length); i++) {
                sb.append("\n§c  at ").append(st[i]);
            }
        }
        player.sendMessage(Text.literal(sb.toString()));
    }
}
