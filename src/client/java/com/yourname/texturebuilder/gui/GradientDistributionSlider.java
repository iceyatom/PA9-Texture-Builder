package com.yourname.texturebuilder.gui;

import java.util.function.IntFunction;

import com.mojang.blaze3d.platform.cursor.CursorTypes;
import com.yourname.texturebuilder.config.ModConfig;
import com.yourname.texturebuilder.util.GradientHelper;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;

/**
 * One full-width slider with N-1 handles: the boundaries between consecutive gradient palette
 * entries. Vanilla has no multi-handle slider, so this extends {@link AbstractWidget} directly and
 * draws itself with vanilla's own slider sprites.
 *
 * <p>The segment between two handles is the share of the start→end line its entry owns, labelled
 * with that entry's block name. Moving a handle goes through {@link GradientHelper#moveHandle}, so
 * it stays between its neighbours (with {@link GradientHelper#MIN_GAP}) and only the two adjacent
 * segments change. A thin bar along the bottom marks each boundary's blend zone for the current
 * Transition value.
 *
 * <p>Mouse: clicking grabs the nearest handle and moves it to the cursor; dragging moves it.
 * Keyboard (when focused): Enter/Space selects the next handle, Left/Right steps the selected
 * handle by 1% (5% with Shift).
 *
 * <p>Reads and writes {@code ModConfig.get().gradientBoundaries} live.
 */
public class GradientDistributionSlider extends AbstractWidget {
    private static final Identifier SLIDER_SPRITE = Identifier.withDefaultNamespace("widget/slider");
    private static final Identifier SLIDER_HIGHLIGHTED_SPRITE = Identifier.withDefaultNamespace("widget/slider_highlighted");
    private static final Identifier HANDLE_SPRITE = Identifier.withDefaultNamespace("widget/slider_handle");
    private static final Identifier HANDLE_HIGHLIGHTED_SPRITE = Identifier.withDefaultNamespace("widget/slider_handle_highlighted");

    private static final int HANDLE_WIDTH = 8;
    private static final double KEY_STEP = 0.01D;
    private static final double KEY_STEP_SHIFT = 0.05D;

    private static final int SEGMENT_TINT_A = 0x30FFFFFF;
    private static final int SEGMENT_TINT_B = 0x30000000;
    private static final int BLEND_ZONE_COLOR = 0xC0FFD24A;
    private static final int LABEL_COLOR = 0xFFFFFFFF;
    private static final int NEED_TWO_COLOR = 0xFFA0A0A0;
    private static final Component NEED_TWO = Component.translatable("text.texturebuilder.gradient.need_two");

    private final Font font;
    private final IntFunction<Component> segmentLabel;
    private final Runnable onChange;
    private int selected;
    private boolean dragging;

    /**
     * @param segmentLabel the label for palette entry {@code i}
     * @param onChange     called after any handle moves
     */
    public GradientDistributionSlider(int width, int height, Font font, IntFunction<Component> segmentLabel,
                                      Runnable onChange) {
        super(0, 0, width, height, Component.translatable("text.texturebuilder.gradient.distribution"));
        this.font = font;
        this.segmentLabel = segmentLabel;
        this.onChange = onChange;
    }

    private static double[] boundaries() {
        return ModConfig.get().gradientBoundaries;
    }

    private int trackLeft() {
        return getX() + HANDLE_WIDTH / 2;
    }

    private int trackWidth() {
        return Math.max(1, getWidth() - HANDLE_WIDTH);
    }

    private int toPixel(double value) {
        return trackLeft() + (int) Math.round(value * trackWidth());
    }

    private double toValue(double mouseX) {
        return (mouseX - trackLeft()) / trackWidth();
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        double[] b = boundaries();
        int entries = b.length + 1;
        int color = ARGB.white(this.alpha);
        int top = getY();
        int bottom = getBottom();

        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, isFocused() ? SLIDER_HIGHLIGHTED_SPRITE : SLIDER_SPRITE,
                getX(), top, getWidth(), getHeight(), color);

        if (b.length == 0) {
            // Fewer than 2 slots included: nothing to distribute yet.
            graphics.centeredText(this.font, NEED_TWO, getX() + getWidth() / 2,
                    top + (getHeight() - this.font.lineHeight) / 2 + 1, NEED_TWO_COLOR);
            return;
        }

        // Alternate tints so neighbouring segments are easy to tell apart.
        for (int i = 0; i < entries; i++) {
            int x0 = i == 0 ? getX() + 1 : toPixel(b[i - 1]);
            int x1 = i == entries - 1 ? getRight() - 1 : toPixel(b[i]);
            graphics.fill(x0, top + 1, x1, bottom - 1, i % 2 == 0 ? SEGMENT_TINT_A : SEGMENT_TINT_B);
        }

        // Blend zones for the current Transition value.
        double transition = ModConfig.get().gradientTransition / 100.0D;
        for (int k = 0; k < b.length; k++) {
            double hw = GradientHelper.blendHalfWidth(b, k, transition);
            if (hw > 0.0D) {
                graphics.fill(toPixel(b[k] - hw), bottom - 4, toPixel(b[k] + hw), bottom - 2, BLEND_ZONE_COLOR);
            }
        }

        // Segment labels: each entry's block name, cut to fit between its handles.
        int textY = top + (getHeight() - this.font.lineHeight) / 2 + 1;
        for (int i = 0; i < entries; i++) {
            int x0 = (i == 0 ? getX() : toPixel(b[i - 1])) + HANDLE_WIDTH / 2 + 1;
            int x1 = (i == entries - 1 ? getRight() : toPixel(b[i])) - HANDLE_WIDTH / 2 - 1;
            int room = x1 - x0;
            if (room <= 4) {
                continue;
            }
            String text = this.font.plainSubstrByWidth(segmentLabel.apply(i).getString(), room);
            if (!text.isEmpty()) {
                int x = x0 + (room - this.font.width(text)) / 2;
                graphics.text(this.font, text, x, textY, LABEL_COLOR, true);
            }
        }

        boolean showSelection = isFocused() || dragging;
        for (int k = 0; k < b.length; k++) {
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED,
                    showSelection && k == selected ? HANDLE_HIGHLIGHTED_SPRITE : HANDLE_SPRITE,
                    toPixel(b[k]) - HANDLE_WIDTH / 2, top, HANDLE_WIDTH, getHeight(), color);
        }

        if (isHovered()) {
            graphics.requestCursor(CursorTypes.RESIZE_EW);
        }
    }

    /** The handle nearest {@code mouseX}; on a tie, the one on the side the cursor is on. */
    private int nearestHandle(double mouseX) {
        double[] b = boundaries();
        int best = 0;
        double bestDist = Double.MAX_VALUE;
        for (int k = 0; k < b.length; k++) {
            int px = toPixel(b[k]);
            double d = Math.abs(px - mouseX);
            if (d < bestDist || (d == bestDist && mouseX > px)) {
                best = k;
                bestDist = d;
            }
        }
        return best;
    }

    private void moveSelected(double value) {
        double[] b = boundaries();
        if (b.length == 0) {
            return;
        }
        selected = Math.max(0, Math.min(b.length - 1, selected));
        double before = b[selected];
        GradientHelper.moveHandle(b, selected, value);
        if (b[selected] != before) {
            onChange.run();
        }
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
        if (boundaries().length == 0) {
            return;
        }
        selected = nearestHandle(event.x());
        dragging = true;
        moveSelected(toValue(event.x()));
    }

    @Override
    protected void onDrag(MouseButtonEvent event, double dragX, double dragY) {
        moveSelected(toValue(event.x()));
    }

    @Override
    public void onRelease(MouseButtonEvent event) {
        if (dragging) {
            dragging = false;
            AbstractWidget.playButtonClickSound(Minecraft.getInstance().getSoundManager());
        }
    }

    /** Like vanilla sliders, the click sound plays on release rather than on press. */
    @Override
    public void playDownSound(SoundManager soundManager) {
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        double[] b = boundaries();
        if (!this.active || !this.visible || b.length == 0) {
            return false;
        }
        if (event.isSelection()) {
            selected = (selected + 1) % b.length;
            return true;
        }
        if (event.isLeft() || event.isRight()) {
            double step = event.hasShiftDown() ? KEY_STEP_SHIFT : KEY_STEP;
            selected = Math.max(0, Math.min(b.length - 1, selected));
            moveSelected(b[selected] + (event.isLeft() ? -step : step));
            return true;
        }
        return false;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        double[] b = boundaries();
        if (b.length == 0) {
            output.add(NarratedElementType.TITLE, getMessage());
            return;
        }
        int k = Math.max(0, Math.min(b.length - 1, selected));
        output.add(NarratedElementType.TITLE, Component.translatable(
                "text.texturebuilder.gradient.distribution.narration",
                k + 1, b.length, Math.round(b[k] * 100.0D)));
    }
}
