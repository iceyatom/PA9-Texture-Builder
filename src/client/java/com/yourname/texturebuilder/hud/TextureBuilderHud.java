package com.yourname.texturebuilder.hud;

import com.yourname.texturebuilder.config.ModConfig;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Brief, self-expiring text shown just above the hotbar — the mod's only transient UI surface:
 * the ON/OFF toggle confirmation (FR-02) and the "no more blocks found" restock notice (FR-15).
 * No persistent HUD overlay is ever rendered (FR-02, SRS §10).
 *
 * <p>Mirrors ShulkerPickBlock's {@code PickBlockHud}: drawn from a Fabric {@code HudRenderCallback}
 * (the 1.19.3 equivalent of the 26.x {@code HudElementRegistry} element), so it looks identical to
 * the 26.x release. State is a countdown driven by the client tick; client-thread only, so no
 * synchronisation is needed.
 */
public final class TextureBuilderHud {
    private static Component message;
    private static int remainingTicks;

    private TextureBuilderHud() {
    }

    /** Shows {@code text} above the hotbar for the configured duration, replacing any prior text. */
    public static void showMessage(Component text) {
        message = text;
        remainingTicks = ModConfig.get().restockMessageDurationTicks();
    }

    /** Decrement the countdown; call once per client tick. */
    public static void tick() {
        if (remainingTicks > 0) {
            remainingTicks--;
        }
    }

    /** Draws the notification just above the hotbar while it is active. */
    public static void render(PoseStack poseStack) {
        if (remainingTicks <= 0 || message == null) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.font == null) {
            return;
        }

        int width = client.font.width(message);
        int x = (client.getWindow().getGuiScaledWidth() - width) / 2;
        int y = client.getWindow().getGuiScaledHeight() - 59 - 13;

        // Fade out over the final 10 ticks.
        int alpha = (int) (Math.min(1.0f, remainingTicks / 10.0f) * 255.0f);
        int color = (alpha << 24) | 0xFFFFFF;
        client.font.drawShadow(poseStack, message, x, y, color);
    }
}
