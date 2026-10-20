package com.yourname.texturebuilder.gui;

import java.util.ArrayList;
import java.util.List;

import com.yourname.texturebuilder.TextureBuilder;
import com.yourname.texturebuilder.config.ModConfig;
import com.yourname.texturebuilder.util.TextureBuilderHelper;
import com.yourname.texturebuilder.util.TextureBuilderHelper.SlotEntry;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/**
 * The PA9 TextureBuilder configuration screen (FR-06..FR-10, SRS §7.3): one row per hotbar slot
 * with a live preview of the item currently in that slot, an include/exclude toggle, and a weight
 * slider, plus the running weight total (flagged red when it isn't 100 — the screen can still be
 * closed regardless, since selection normalizes proportionally at runtime, FR-09).
 *
 * <p>Built from vanilla widgets only — no Cloth Config / YACL — mirroring the author's other 26.2
 * config screens. The item preview is the slot's current item <em>name</em>, refreshed every tick
 * (kept from the 26.x release, whose render model exposed no simple item-icon draw call).
 *
 * <p>1.19 predates the {@code StringWidget}/{@code GridLayout}/{@code HeaderAndFooterLayout} widgets
 * the newer releases build this screen from, so the same layout is placed by hand here with the same
 * metrics (36px header and footer, grid 30px below the header, 2px cell spacing, centred labels),
 * and {@link Label} stands in for {@code StringWidget}.
 *
 * <p>Edits apply to the live config immediately (FR-10); the TOML is written when the screen
 * closes, by any route (Done, ESC, or the back control). A parent screen (the inventory) is
 * restored on close (FR-06); a {@code null} parent closes to the game.
 */
public class TextureBuilderConfigScreen extends Screen {

    private static final int ROW_HEIGHT = 16;
    // Column widths total 294px + 8px of grid spacing = 302px, which still fits the 320px minimum
    // scaled GUI width Minecraft guarantees. The weight column is 100px so the slider maps almost
    // exactly one pixel per weight unit across its 0-100 range.
    private static final int COL_SLOT = 30;
    private static final int COL_ITEM = 96;
    private static final int COL_INCLUDE = 34;
    private static final int COL_WEIGHT = 100;
    private static final int COL_EFFECTIVE = 34;
    private static final int[] COLUMNS = {COL_SLOT, COL_ITEM, COL_INCLUDE, COL_WEIGHT, COL_EFFECTIVE};
    private static final int GRID_SPACING = 2;
    /** Same metrics as the newer releases' {@code HeaderAndFooterLayout}. */
    private static final int HEADER_HEIGHT = 36;
    private static final int FOOTER_HEIGHT = 36;
    private static final int CONTENT_PADDING_TOP = 30;
    /** Width {@code Tooltip.create} wraps at in the newer releases. */
    private static final int TOOLTIP_WIDTH = 170;

    private final Screen parent;

    /** Rebuilt from scratch on every {@link #init()} — {@code init()} runs again on resize. */
    private final Label[] itemNames = new Label[TextureBuilder.HOTBAR_SIZE];
    private final Label[] effectivePercents = new Label[TextureBuilder.HOTBAR_SIZE];
    private Label totalWidget;

    public TextureBuilderConfigScreen(Screen parent) {
        super(Component.translatable("text.texturebuilder.config.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        ModConfig config = ModConfig.get();

        // Header: the title, centred in the header band.
        int titleWidth = this.font.width(this.title);
        this.addRenderableWidget(new Label((this.width - titleWidth) / 2, (HEADER_HEIGHT - 9) / 2,
                titleWidth, 9, this.title, this.font));

        // Contents: a 5-column grid, centred horizontally, CONTENT_PADDING_TOP below the header.
        int gridWidth = -GRID_SPACING;
        for (int column : COLUMNS) {
            gridWidth += column + GRID_SPACING;
        }
        int gridX = (this.width - gridWidth) / 2;
        int[] columnX = new int[COLUMNS.length];
        for (int c = 0, x = gridX; c < COLUMNS.length; x += COLUMNS[c] + GRID_SPACING, c++) {
            columnX[c] = x;
        }

        int y = HEADER_HEIGHT + CONTENT_PADDING_TOP;
        String[] headers = {"slot", "item", "included", "weight", "effective"};
        for (int c = 0; c < COLUMNS.length; c++) {
            this.addRenderableWidget(header(headers[c], columnX[c], y, COLUMNS[c]));
        }

        for (int i = 0; i < TextureBuilder.HOTBAR_SIZE; i++) {
            final int slot = i;
            y += ROW_HEIGHT + GRID_SPACING;

            this.addRenderableWidget(new Label(columnX[0], y, COL_SLOT, ROW_HEIGHT,
                    Component.translatable("text.texturebuilder.config.slot", slot + 1), this.font));

            itemNames[i] = this.addRenderableWidget(
                    new Label(columnX[1], y, COL_ITEM, ROW_HEIGHT, Component.empty(), this.font));

            this.addRenderableWidget(includedToggle(config, slot, columnX[2], y));

            this.addRenderableWidget(new WeightSlider(slot, config.weights[slot], columnX[3], y));

            effectivePercents[i] = this.addRenderableWidget(
                    new Label(columnX[4], y, COL_EFFECTIVE, ROW_HEIGHT, Component.empty(), this.font));
        }

        y += ROW_HEIGHT + GRID_SPACING;
        totalWidget = this.addRenderableWidget(
                new Label(gridX, y, gridWidth, ROW_HEIGHT, Component.empty(), this.font));

        // Footer: the Done button, centred in the footer band.
        this.addRenderableWidget(new Button((this.width - 120) / 2,
                this.height - FOOTER_HEIGHT + (FOOTER_HEIGHT - 20) / 2, 120, 20,
                CommonComponents.GUI_DONE, b -> onClose()));

        refreshDerived();
        refreshItemNames();
    }

    /** Draws the hovered slider's tooltip on top of every widget, as the newer releases' tooltips are. */
    @Override
    public void render(PoseStack poseStack, int mouseX, int mouseY, float partialTick) {
        super.render(poseStack, mouseX, mouseY, partialTick);
        for (var child : this.children()) {
            if (child instanceof WeightSlider slider && slider.isHoveredOrFocused()) {
                this.renderTooltip(poseStack, this.font.split(
                        Component.translatable("text.texturebuilder.config.weight.tooltip"), TOOLTIP_WIDTH),
                        mouseX, mouseY);
                break;
            }
        }
    }

    /** FR-07 live preview + FR-09 running total, refreshed every tick. */
    @Override
    public void tick() {
        refreshItemNames();
    }

    /** Any close route persists the (already live) edits to the TOML and returns to the parent (FR-06/FR-10). */
    @Override
    public void onClose() {
        ModConfig.get().save();
        this.minecraft.setScreen(this.parent);
    }

    private void refreshItemNames() {
        if (this.minecraft == null || this.minecraft.player == null) {
            return;
        }
        Inventory inventory = this.minecraft.player.getInventory();
        for (int i = 0; i < TextureBuilder.HOTBAR_SIZE; i++) {
            if (itemNames[i] == null) {
                continue;
            }
            ItemStack stack = inventory.getItem(i);
            itemNames[i].setMessage(stack.isEmpty()
                    ? Component.translatable("text.texturebuilder.config.empty")
                            .withStyle(ChatFormatting.DARK_GRAY)
                    : stack.getHoverName().copy().append(" x" + stack.getCount()));
        }
    }

    /**
     * Recomputes the FR-09 total (red when not 100) and the normalized effective percentages.
     *
     * <p>Null-guarded throughout: widgets are built row by row in {@link #init()}, so a slider can
     * exist before the label widgets this method writes to.
     */
    private void refreshDerived() {
        ModConfig config = ModConfig.get();
        List<SlotEntry> entries = new ArrayList<>(TextureBuilder.HOTBAR_SIZE);
        for (int i = 0; i < TextureBuilder.HOTBAR_SIZE; i++) {
            entries.add(new SlotEntry(i, config.included[i], config.weights[i]));
        }

        int total = TextureBuilderHelper.includedWeightTotal(entries);
        Component totalText = Component.translatable("text.texturebuilder.config.total", total);
        if (totalWidget != null) {
            totalWidget.setMessage(total == 100
                    ? totalText.copy().withStyle(ChatFormatting.GREEN)
                    : totalText.copy().withStyle(ChatFormatting.RED)
                            .append(Component.translatable("text.texturebuilder.config.total.normalized")));
        }

        for (int i = 0; i < TextureBuilder.HOTBAR_SIZE; i++) {
            if (effectivePercents[i] == null) {
                continue;
            }
            if (config.autoNormalizeDisplay) {
                double pct = TextureBuilderHelper.effectivePercent(entries, i);
                effectivePercents[i].setMessage(pct <= 0.0D
                        ? Component.literal("—").withStyle(ChatFormatting.DARK_GRAY)
                        : Component.literal(String.format("%.1f%%", pct)));
            } else {
                effectivePercents[i].setMessage(Component.empty());
            }
        }
    }

    /**
     * The per-slot weight control (FR-07). A slider rather than a text field: weights are
     * relative, so the useful interaction is "a bit more of this one than that one", which a
     * slider expresses directly and cannot put into an invalid state.
     *
     * <p>The slider's normalized 0.0–1.0 position maps onto {@link ModConfig#WEIGHT_MIN}–
     * {@link ModConfig#WEIGHT_MAX}; at the 100px column width that is very close to one weight
     * unit per pixel. Dragging gives coarse adjustment, and vanilla's
     * {@link AbstractSliderButton#keyPressed} lets a focused slider be nudged one step at a time
     * with the arrow keys (after Enter/Space arms it) for exact values.
     *
     * <p>Each change writes straight through to the live config (FR-10) and refreshes the total
     * and effective-percentage columns, since changing any one slot's weight changes every other
     * slot's normalized share.
     */
    private class WeightSlider extends AbstractSliderButton {
        private final int slot;

        WeightSlider(int slot, int weight, int x, int y) {
            super(x, y, COL_WEIGHT, ROW_HEIGHT, Component.empty(), toSliderValue(weight));
            this.slot = slot;
            this.updateMessage();
        }

        private int weight() {
            return ModConfig.WEIGHT_MIN
                    + Mth.floor(this.value * (ModConfig.WEIGHT_MAX - ModConfig.WEIGHT_MIN) + 0.5D);
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.literal(Integer.toString(weight())));
        }

        @Override
        protected void applyValue() {
            ModConfig.get().weights[slot] = weight(); // FR-10: applies immediately.
            refreshDerived();
        }
    }

    private static double toSliderValue(int weight) {
        int span = ModConfig.WEIGHT_MAX - ModConfig.WEIGHT_MIN;
        return Mth.clamp((double) (weight - ModConfig.WEIGHT_MIN) / span, 0.0D, 1.0D);
    }

    /**
     * The per-slot Included/Excluded toggle (FR-08). Built from the generic {@link CycleButton}
     * factory rather than {@link CycleButton#onOffBuilder} so the ON/OFF label can be colored —
     * malilib's SUCCESS/ERROR convention (green {@code §a} / red {@code §c}, confirmed from the
     * installed malilib jar's lang file), matching the toggle hotkey's confirmation message and
     * Litematica's own toggle messages, since Litematica is built on malilib.
     */
    private AbstractWidget includedToggle(ModConfig config, int slot, int x, int y) {
        return CycleButton.<Boolean>builder(
                        value -> Component.translatable(value ? "text.texturebuilder.on" : "text.texturebuilder.off")
                                .withStyle(value ? ChatFormatting.GREEN : ChatFormatting.RED))
                .withValues(true, false)
                .withInitialValue(config.included[slot])
                .displayOnlyValue()
                .create(x, y, COL_INCLUDE, ROW_HEIGHT,
                        Component.translatable("text.texturebuilder.config.included"),
                        (button, value) -> {
                            config.included[slot] = value; // FR-10: applies immediately.
                            refreshDerived();
                        });
    }

    private Label header(String key, int x, int y, int width) {
        return new Label(x, y, width, ROW_HEIGHT,
                Component.translatable("text.texturebuilder.config.header." + key)
                        .withStyle(ChatFormatting.UNDERLINE),
                this.font);
    }

    /**
     * Stand-in for the newer releases' {@code StringWidget}: inactive (never focused or clicked),
     * text centred horizontally and vertically in its box, white with a shadow, no narration.
     */
    private static class Label extends AbstractWidget {
        private final Font font;

        Label(int x, int y, int width, int height, Component message, Font font) {
            super(x, y, width, height, message);
            this.font = font;
            this.active = false;
        }

        @Override
        public void renderButton(PoseStack poseStack, int mouseX, int mouseY, float partialTick) {
            Component message = this.getMessage();
            int textX = this.x + Math.round(0.5F * (this.getWidth() - this.font.width(message)));
            int textY = this.y + (this.getHeight() - 9) / 2;
            this.font.drawShadow(poseStack, message, textX, textY, 0xFFFFFF);
        }

        @Override
        public void updateNarration(NarrationElementOutput output) {
        }
    }
}
