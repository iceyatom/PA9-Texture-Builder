package com.yourname.texturebuilder.command;

import com.mojang.brigadier.CommandDispatcher;
import com.yourname.texturebuilder.TextureBuilder;
import com.yourname.texturebuilder.TextureBuilderClient;
import com.yourname.texturebuilder.config.ModConfig;
import com.yourname.texturebuilder.gui.TextureBuilderConfigScreen;
import com.yourname.texturebuilder.placement.GradientController;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
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
 * <p>Purely client-side (registered via {@code fabric-command-api-v2}'s {@link ClientCommandManager});
 * sends no packets to the server.
 */
public final class TextureBuilderCommands {

    private TextureBuilderCommands() {
    }

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(
                ClientCommandManager.literal(TextureBuilder.MOD_ID)
                        .then(ClientCommandManager.literal("reload").executes(ctx -> {
                            ModConfig.load();
                            ctx.getSource().sendFeedback(Component.literal(
                                    "[" + TextureBuilder.MOD_NAME + "] Config reloaded."));
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("config").executes(ctx -> {
                            // Deferred to the next client tick: the chat screen is still being
                            // closed as the command runs, and it would overwrite our screen.
                            Minecraft client = Minecraft.getInstance();
                            client.execute(() -> client.setScreen(
                                    new TextureBuilderConfigScreen(null)));
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("status").executes(ctx -> {
                            ctx.getSource().sendFeedback(Component.literal(
                                    "[" + TextureBuilder.MOD_NAME + "] Current config:"));
                            ctx.getSource().sendFeedback(Component.literal("  session = ")
                                    .append(Component.translatable("text.texturebuilder.toggle_state",
                                            Component.translatable(TextureBuilderClient.isOn()
                                                    ? "text.texturebuilder.on" : "text.texturebuilder.off"),
                                            TextureBuilderClient.modeName(ModConfig.get().mode))));
                            for (String line : ModConfig.get().describe()) {
                                ctx.getSource().sendFeedback(Component.literal("  " + line));
                            }
                            // Points and axis need the current dimension, so they come from the
                            // controller rather than ModConfig.describe().
                            ctx.getSource().sendFeedback(Component.literal("  gradient points = ")
                                    .append(GradientController.describePoints(Minecraft.getInstance())));
                            return 1;
                        }))
        );
    }
}
