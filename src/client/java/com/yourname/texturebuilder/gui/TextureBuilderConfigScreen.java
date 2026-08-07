package com.yourname.texturebuilder.gui;

import java.util.ArrayList;
import java.util.List;

import com.yourname.texturebuilder.TextureBuilder;
import com.yourname.texturebuilder.config.ModConfig;
import com.yourname.texturebuilder.util.TextureBuilderHelper;
import com.yourname.texturebuilder.util.TextureBuilderHelper.SlotEntry;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
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
 * config screens. The item preview is the slot's current item <em>name</em>, refreshed every tick:
 * the 26.2 {@code GuiGraphicsExtractor} render model exposes no simple item-icon draw call, so the
 * name is the verified widget-safe way to show live slot contents.
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

    private final Screen parent;

    /**
     * Rebuilt from scratch on every {@link #init()} — {@code init()} runs again on resize, and
     * re-adding to a retained layout would stack a second copy of every widget.
     */
    private HeaderAndFooterLayout layout;
    private final StringWidget[] itemNames = new StringWidget[TextureBuilder.HOTBAR_SIZE];
    private final StringWidget[] effectivePercents = new StringWidget[TextureBuilder.HOTBAR_SIZE];
    private StringWidget totalWidget;

    public TextureBuilderConfigScreen(Screen parent) {
        super(Component.translatable("text.texturebuilder.config.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        ModConfig config = ModConfig.get();
        this.layout = new HeaderAndFooterLayout(this);
        this.layout.addTitleHeader(this.title, this.font);

        GridLayout grid = new GridLayout().spacing(2);
        GridLayout.RowHelper rows = grid.createRowHelper(5);

        rows.addChild(header("slot", COL_SLOT));
        rows.addChild(header("item", COL_ITEM));
        rows.addChild(header("included", COL_INCLUDE));
        rows.addChild(header("weight", COL_WEIGHT));
        rows.addChild(header("effective", COL_EFFECTIVE));

        for (int i = 0; i < TextureBuilder.HOTBAR_SIZE; i++) {
            final int slot = i;

            rows.addChild(new StringWidget(COL_SLOT, ROW_HEIGHT,
                    Component.translatable("text.texturebuilder.config.slot", slot + 1), this.font));

            itemNames[i] = new StringWidget(COL_ITEM, ROW_HEIGHT, Component.empty(), this.font);
            rows.addChild(itemNames[i]);

            rows.addChild(CycleButton.onOffBuilder(config.included[slot])
                    .displayOnlyValue()
                    .create(0, 0, COL_INCLUDE, ROW_HEIGHT,
                            Component.translatable("text.texturebuilder.config.included"),
                            (button, value) -> {
                                config.included[slot] = value; // FR-10: applies immediately.
                                refreshDerived();
                            }));

            rows.addChild(new WeightSlider(slot, config.weights[slot]));

            effectivePercents[i] = new StringWidget(COL_EFFECTIVE, ROW_HEIGHT, Component.empty(), this.font);
            rows.addChild(effectivePercents[i]);
        }

        totalWidget = new StringWidget(
                COL_SLOT + COL_ITEM + COL_INCLUDE + COL_WEIGHT + COL_EFFECTIVE + 8, ROW_HEIGHT,
                Component.empty(), this.font);
        rows.addChild(totalWidget, 5);

        this.layout.addToContents(grid);

        LinearLayout footer = this.layout.addToFooter(LinearLayout.horizontal().spacing(8));
        footer.addChild(Button.builder(CommonComponents.GUI_DONE, b -> onClose()).width(120).build());

        this.layout.visitWidgets(this::addRenderableWidget);
        this.repositionElements();
        refreshDerived();
        refreshItemNames();
    }

    @Override
    protected void repositionElements() {
        if (this.layout != null) {
            this.layout.arrangeElements();
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
        this.minecraft.gui.setScreen(this.parent);
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

        WeightSlider(int slot, int weight) {
            super(0, 0, COL_WEIGHT, ROW_HEIGHT, Component.empty(), toSliderValue(weight));
            this.slot = slot;
            this.setTooltip(Tooltip.create(Component.translatable("text.texturebuilder.config.weight.tooltip")));
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

    private StringWidget header(String key, int width) {
        return new StringWidget(width, ROW_HEIGHT,
                Component.translatable("text.texturebuilder.config.header." + key)
                        .withStyle(ChatFormatting.UNDERLINE),
                this.font);
    }
}
