package com.yourname.texturebuilder;

import com.mojang.blaze3d.platform.InputConstants;
import com.yourname.texturebuilder.command.TextureBuilderCommands;
import com.yourname.texturebuilder.config.ModConfig;
import com.yourname.texturebuilder.hud.TextureBuilderHud;
import com.yourname.texturebuilder.placement.GradientController;
import com.yourname.texturebuilder.placement.PlacementRandomizer;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.chat.Component;

/**
 * Client entrypoint (declared under {@code entrypoints.client} in {@code fabric.mod.json}).
 *
 * <p>Thin bootstrap only — behaviour lives in the dedicated classes:
 * <ol>
 *   <li>loads the TOML config (FR-20),</li>
 *   <li>registers the toggle keybind (FR-01) and drives it + the HUD from the client tick,</li>
 *   <li>registers the {@code /texturebuilder} client commands (FR-21),</li>
 *   <li>attaches the disappearing-message HUD element (FR-02/FR-15).</li>
 * </ol>
 * The placement hook itself is a mixin ({@code MultiPlayerGameModeMixin}) and needs no
 * registration here.
 */
public class TextureBuilderClient implements ClientModInitializer {

    private static KeyMapping toggleKey;
    private static KeyMapping gradientPointKey;
    /** Session ON/OFF state (FR-03: session-only; seeded from the config's `enabled` at launch). */
    private static boolean active;
    /** NFR-04 kill switch: set after an unexpected exception; stays set for the session. */
    private static boolean sessionFailed;
    private static boolean keybindApplied;

    /** Whether placements should be randomized right now (FR-04). */
    public static boolean isOn() {
        return active && !sessionFailed;
    }

    /**
     * Flips the session toggle and confirms via a disappearing message (FR-01/FR-02), with the
     * ON/OFF word colored to malilib's SUCCESS/ERROR convention (green {@code §a} / red {@code §c}
     * — confirmed from the installed malilib jar's {@code assets/malilib/lang/en_us.json}), which
     * is what Litematica's own toggle messages use, since Litematica is built on malilib.
     */
    public static void toggle() {
        if (sessionFailed) {
            TextureBuilderHud.showMessage(Component.translatable("message.texturebuilder.failed"));
            return;
        }
        active = !active;
        // "ON (Gradient)" / "OFF (Texture)": the state and the active mode go into the toggle
        // message's %s as one colored Component, so the whole phrase is green or red.
        TextureBuilderHud.showMessage(Component.translatable("message.texturebuilder.toggle",
                Component.translatable("text.texturebuilder.toggle_state",
                                Component.translatable(active ? "text.texturebuilder.on" : "text.texturebuilder.off"),
                                modeName(ModConfig.get().mode))
                        .withStyle(active ? ChatFormatting.GREEN : ChatFormatting.RED)));
    }

    /** "Texture" / "Gradient", for the toggle message, status and the config screen's mode button. */
    public static Component modeName(ModConfig.Mode mode) {
        return Component.translatable("text.texturebuilder.mode." + mode.id());
    }

    /**
     * NFR-04: catch-all failure path — log a warning, disable texture building for the remainder
     * of the session, and notify the player, rather than leaving placement inconsistent.
     */
    public static void failSession(RuntimeException e) {
        TextureBuilder.LOGGER.warn("[TextureBuilder] Unexpected error; disabling for this session.", e);
        sessionFailed = true;
        active = false;
        PlacementRandomizer.reset(); // Drop any in-flight placement/restock state.
        TextureBuilderHud.showMessage(Component.translatable("message.texturebuilder.failed"));
    }

    @Override
    public void onInitializeClient() {
        ModConfig.load();
        active = ModConfig.get().enabled; // FR-03: OFF unless explicitly configured otherwise.

        // FR-01: unbound by default; bindable via the vanilla Controls screen or the TOML.
        // Use Minecraft's own "unbound" sentinel (GLFW -1 on 1.21.2) rather than a literal.
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.texturebuilder.toggle", InputConstants.UNKNOWN.getValue(), "key.categories.gameplay"));
        // Gradient mode: start / end / clear cycle on the block under the crosshair. Unbound by
        // default, like the toggle. Mode switching has no keybind (config screen button only).
        gradientPointKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.texturebuilder.gradient_point", InputConstants.UNKNOWN.getValue(), "key.categories.gameplay"));

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                TextureBuilderCommands.register(dispatcher));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (!keybindApplied) {
                keybindApplied = true;
                applyConfiguredKeybind();
            }
            while (toggleKey.consumeClick()) {
                toggle(); // FR-01: works in-game regardless of any GUI being open.
            }
            while (gradientPointKey.consumeClick()) {
                GradientController.onSetPointPressed(client);
            }
            // Settles any in-flight pick-block restock (FR-13..FR-15), which resolves a tick or
            // more after the keypress is queued.
            PlacementRandomizer.tick();
            TextureBuilderHud.tick();
        });

        HudRenderCallback.EVENT.register((context, tickCounter) -> TextureBuilderHud.render(context));

        TextureBuilder.LOGGER.info("{} initialised (client-side only; {}).",TextureBuilder.MOD_NAME,
                active ? "enabled at launch via config" : "toggled off");
    }

    /**
     * Applies the TOML {@code toggle_keybind} once, on the first tick — after vanilla has loaded
     * options.txt — and only if the mapping is still unbound, so a rebind made in the vanilla
     * Controls screen always wins over the TOML value (SRS §6).
     */
    private static void applyConfiguredKeybind() {
        String configured = ModConfig.get().toggleKeybind;
        if (configured == null || configured.isEmpty() || "unbound".equalsIgnoreCase(configured)) {
            return;
        }
        try {
            if (toggleKey.isUnbound()) {
                toggleKey.setKey(InputConstants.getKey(configured));
                KeyMapping.resetMapping();
                TextureBuilder.LOGGER.info("[TextureBuilder] Toggle keybind set from config: {}", configured);
            }
        } catch (RuntimeException e) {
            TextureBuilder.LOGGER.warn("[TextureBuilder] Invalid toggle_keybind '{}' in config; leaving unbound.",
                    configured, e);
        }
    }
}
