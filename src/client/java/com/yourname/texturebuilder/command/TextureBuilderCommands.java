package com.yourname.texturebuilder.command;

import com.mojang.brigadier.CommandDispatcher;
import com.yourname.texturebuilder.TextureBuilder;
import com.yourname.texturebuilder.config.ModConfig;
import com.yourname.texturebuilder.gui.TextureBuilderConfigScreen;

import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Registers the {@code /texturebuilder} client command.
 *
 * <ul>
 *   <li>{@code /texturebuilder reload} — re-reads the TOML config at runtime, no restart (FR-21).</li>
 *   <li>{@code /texturebuilder config} — opens the settings screen (same one the inventory button
 *       and Mod Menu's gear open), so the GUI is reachable from anywhere.</li>
 *   <li>{@code /texturebuilder status} — prints the current effective configuration.</li>
 * </ul>
 *
 * <p>Purely client-side (registered via {@code fabric-command-api-v2}'s {@link ClientCommands});
 * sends no packets to the server.
 */
public final class TextureBuilderCommands {

    private TextureBuilderCommands() {
    }

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(
                ClientCommands.literal(TextureBuilder.MOD_ID)
                        .then(ClientCommands.literal("reload").executes(ctx -> {
                            ModConfig.load();
                            ctx.getSource().sendFeedback(Component.literal(
                                    "[" + TextureBuilder.MOD_NAME + "] Config reloaded."));
                            return 1;
                        }))
                        .then(ClientCommands.literal("config").executes(ctx -> {
                            // Deferred to the next client tick: the chat screen is still being
                            // closed as the command runs, and it would overwrite our screen.
                            Minecraft client = Minecraft.getInstance();
                            client.execute(() -> client.gui.setScreen(
                                    new TextureBuilderConfigScreen(null)));
                            return 1;
                        }))
                        .then(ClientCommands.literal("status").executes(ctx -> {
                            ctx.getSource().sendFeedback(Component.literal(
                                    "[" + TextureBuilder.MOD_NAME + "] Current config:"));
                            for (String line : ModConfig.get().describe()) {
                                ctx.getSource().sendFeedback(Component.literal("  " + line));
                            }
                            return 1;
                        }))
        );
    }
}
