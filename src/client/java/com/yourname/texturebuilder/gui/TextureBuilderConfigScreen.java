package com.yourname.texturebuilder.gui;

import java.util.ArrayList;
import java.util.List;

import com.yourname.texturebuilder.TextureBuilder;
import com.yourname.texturebuilder.TextureBuilderClient;
import com.yourname.texturebuilder.config.ModConfig;
import com.yourname.texturebuilder.placement.GradientController;
import com.yourname.texturebuilder.util.GradientHelper;
import com.yourname.texturebuilder.util.TextureBuilderHelper;
import com.yourname.texturebuilder.util.TextureBuilderHelper.SlotEntry;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
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
 * The PA9 TextureBuilder configuration screen (FR-06..FR-10, SRS §7.3). One screen serves both
 * modes; the Mode button in the footer switches mode and rebuilds the contents in place.
 *
 * <ul>
 *   <li><b>Texture mode</b> (unchanged from v1.0): one row per hotbar slot with a live preview of
 *       the item in it, an include/exclude toggle and a weight slider, plus the running weight
 *       total (red when it isn't 100; the screen still closes, since selection normalizes at
 *       runtime, FR-09).</li>
 *   <li><b>Gradient mode</b>: the same Slot / Item / Included columns (Gradient mode keeps its own
 *       Included flags), without the weight columns. Where the total line was: the multi-handle
 *       {@link GradientDistributionSlider}, the Transition slider with "Clear points", and a
 *       read-only line with the current points and axis. Included slots run from the start point
 *       to the end point in hotbar order.</li>
 * </ul>
 *
 * <p>Built from vanilla widgets only (apart from the distribution slider), mirroring the author's
 * other 26.x config screens. The item preview is the slot's current item <em>name</em>, refreshed
 * every tick.
 *
 * <p>Edits apply to the live config immediately (FR-10); the TOML is written when the screen
 * closes, by any route (Done, ESC, or the back control), and at once when "Clear points" is
 * pressed. A parent screen (the inventory) is restored on close (FR-06); a {@code null} parent
 * closes to the game.
 */
public class TextureBuilderConfigScreen extends Screen {

    private static final int ROW_HEIGHT = 16;
    private static final int BUTTON_HEIGHT = 20;

    // Explicit column widths, shared by both modes so their tables have the same footprint. These
    // reproduce the Texture screen as it has always rendered (~343px): it used to ask for 302px,
    // but its full-width total line sat inside the grid, and GridLayout widens narrow columns to
    // share a spanning child's width (Slot 30 -> 58, Included 46 -> 59). Nothing spans the grid
    // now; full-width rows sit in a vertical layout below it, so these are the real widths.
    // 343px fits any GUI at least 343 wide (1080p at scale 4 is 480), but not the 320px minimum.
    // Eff. % is 40 (was 34 + slack) so the Texture-mode Reset button fits under it.
    private static final int COL_SLOT = 58;
    private static final int COL_ITEM = 84;
    private static final int COL_INCLUDE = 53;
    private static final int INCLUDE_BUTTON_WIDTH = 46;
    private static final int COL_WEIGHT = 100;
    private static final int COL_EFFECTIVE = 40;
    private static final int GRID_SPACING = 2;
    private static final int ROW_WIDTH =
            COL_SLOT + COL_ITEM + COL_INCLUDE + COL_WEIGHT + COL_EFFECTIVE + 4 * GRID_SPACING; // 343

    // Both modes share one header/footer height so the title sits at the same place in each.
    // 28/28 leaves room for Gradient mode's extra rows in a 270px-tall GUI (1080p at GUI scale 4).
    private static final int HEADER_HEIGHT = 28;
    private static final int FOOTER_HEIGHT = 28;

    // Gradient mode: Slot / Item / Included / Eff. % = 58 + 186 + 53 + 40 + 3x2 spacing = 343px, the
    // same width as Texture mode (Item takes the Weight column's space). Rows are 14px so the table,
    // the two slider rows and the points line total 214px of content.
    private static final int GRADIENT_ROW_HEIGHT = 14;
    private static final int GRADIENT_COL_SLOT = COL_SLOT;
    private static final int GRADIENT_COL_ITEM =
            ROW_WIDTH - GRADIENT_COL_SLOT - COL_INCLUDE - COL_EFFECTIVE - 3 * GRID_SPACING;
    private static final int POINTS_LINE_HEIGHT = 10;
    // The Reset and Clear points buttons sit under the Included + Eff. % columns, so the bar and
    // the Transition slider end exactly where the Item column does.
    private static final int SIDE_BUTTON_WIDTH = COL_INCLUDE + GRID_SPACING + COL_EFFECTIVE;
    private static final int BAR_WIDTH = ROW_WIDTH - GRID_SPACING - SIDE_BUTTON_WIDTH;

    private static final int FOOTER_BUTTON_WIDTH = 120;

    private final Screen parent;

    /**
     * Rebuilt from scratch on every {@link #init()} — {@code init()} runs again on resize, and
     * re-adding to a retained layout would stack a second copy of every widget.
     */
    private HeaderAndFooterLayout layout;
    private final StringWidget[] itemNames = new StringWidget[TextureBuilder.HOTBAR_SIZE];
    private final StringWidget[] effectivePercents = new StringWidget[TextureBuilder.HOTBAR_SIZE];
    private StringWidget totalWidget;
    private StringWidget pointsLine;
    /** Whether the current widgets are the Gradient layout (decides what the Eff. % column shows). */
    private boolean gradientLayout;
    /** Set by the Mode button; the rebuild runs on the next tick, outside the click handler. */
    private boolean rebuildPending;

    public TextureBuilderConfigScreen(Screen parent) {
        super(Component.translatable("text.texturebuilder.config.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        java.util.Arrays.fill(itemNames, null);
        java.util.Arrays.fill(effectivePercents, null);
        totalWidget = null;
        pointsLine = null;

        boolean gradient = ModConfig.get().mode == ModConfig.Mode.GRADIENT;
        gradientLayout = gradient;
        this.layout = new HeaderAndFooterLayout(this, HEADER_HEIGHT, FOOTER_HEIGHT);
        this.layout.addTitleHeader(this.title, this.font);

        if (gradient) {
            this.layout.addToContents(buildGradientContents());
        } else {
            this.layout.addToContents(buildTextureContents());
        }

        // Footer: Mode + Done only (120 + 120 + 8 = 248px).
        LinearLayout footer = this.layout.addToFooter(LinearLayout.horizontal().spacing(8));
        footer.addChild(modeButton(FOOTER_BUTTON_WIDTH, BUTTON_HEIGHT));
        footer.addChild(Button.builder(CommonComponents.GUI_DONE, b -> onClose()).width(FOOTER_BUTTON_WIDTH).build());

        this.layout.visitWidgets(this::addRenderableWidget);
        this.repositionElements();
        refreshDerived();
        refreshGradientShares();
        refreshItemNames();
        refreshPoints();
    }

    /**
     * Texture mode: Slot / Item / Included / Weight / Eff. % rows, then the weight total with a
     * Reset button under the Eff. % column (below the grid, so it can't stretch the columns).
     */
    private LinearLayout buildTextureContents() {
        ModConfig config = ModConfig.get();
        LinearLayout contents = LinearLayout.vertical().spacing(GRID_SPACING);
        GridLayout grid = new GridLayout().spacing(GRID_SPACING);
        GridLayout.RowHelper rows = grid.createRowHelper(5);

        rows.addChild(header("slot", COL_SLOT, ROW_HEIGHT));
        rows.addChild(header("item", COL_ITEM, ROW_HEIGHT));
        rows.addChild(header("included", COL_INCLUDE, ROW_HEIGHT));
        rows.addChild(header("weight", COL_WEIGHT, ROW_HEIGHT));
        rows.addChild(header("effective", COL_EFFECTIVE, ROW_HEIGHT));

        for (int i = 0; i < TextureBuilder.HOTBAR_SIZE; i++) {
            final int slot = i;

            rows.addChild(slotLabel(slot, COL_SLOT, ROW_HEIGHT));

            itemNames[i] = new StringWidget(COL_ITEM, ROW_HEIGHT, Component.empty(), this.font);
            rows.addChild(itemNames[i]);

            rows.addChild(includedToggle(config.included[slot], ROW_HEIGHT,
                    "text.texturebuilder.config.included",
                    value -> {
                        config.included[slot] = value; // FR-10: applies immediately.
                        refreshDerived();
                    }));

            rows.addChild(new WeightSlider(slot, config.weights[slot]));

            effectivePercents[i] = new StringWidget(COL_EFFECTIVE, ROW_HEIGHT, Component.empty(), this.font);
            rows.addChild(effectivePercents[i]);
        }

        contents.addChild(grid);

        LinearLayout totalRow = LinearLayout.horizontal().spacing(GRID_SPACING);
        totalWidget = new StringWidget(ROW_WIDTH - GRID_SPACING - COL_EFFECTIVE, ROW_HEIGHT,
                Component.empty(), this.font);
        totalRow.addChild(totalWidget);
        Button reset = Button.builder(Component.translatable("text.texturebuilder.config.reset_weights"), b -> {
            java.util.Arrays.fill(ModConfig.get().weights, ModConfig.DEFAULT_WEIGHT); // Applies immediately.
            rebuildPending = true; // Rebuild next tick so every slider shows the new value.
        }).size(COL_EFFECTIVE, ROW_HEIGHT).build();
        reset.setTooltip(Tooltip.create(Component.translatable("text.texturebuilder.config.reset_weights.tooltip")));
        totalRow.addChild(reset);
        contents.addChild(totalRow);
        return contents;
    }

    /**
     * Gradient mode: Slot / Item / Included / Eff. % rows (Gradient mode's own Included flags;
     * Eff. % is each slot's share of the start→end line), then the distribution bar + Reset,
     * Transition + Clear points, and the points/axis line in place of the weight total.
     *
     * <p>The full-width rows sit in a vertical layout <em>below</em> the grid, not inside it:
     * {@link GridLayout} splits a spanning child's width evenly over the spanned columns and widens
     * narrow columns to match, which pushed the Included column out past the bar's right edge.
     */
    private LinearLayout buildGradientContents() {
        ModConfig config = ModConfig.get();
        LinearLayout contents = LinearLayout.vertical().spacing(GRID_SPACING);

        GridLayout grid = new GridLayout().spacing(GRID_SPACING);
        GridLayout.RowHelper rows = grid.createRowHelper(4);

        rows.addChild(header("slot", GRADIENT_COL_SLOT, GRADIENT_ROW_HEIGHT));
        rows.addChild(header("item", GRADIENT_COL_ITEM, GRADIENT_ROW_HEIGHT));
        rows.addChild(header("included", COL_INCLUDE, GRADIENT_ROW_HEIGHT));
        rows.addChild(header("effective", COL_EFFECTIVE, GRADIENT_ROW_HEIGHT));

        for (int i = 0; i < TextureBuilder.HOTBAR_SIZE; i++) {
            final int slot = i;
            rows.addChild(slotLabel(slot, GRADIENT_COL_SLOT, GRADIENT_ROW_HEIGHT));

            itemNames[i] = new StringWidget(GRADIENT_COL_ITEM, GRADIENT_ROW_HEIGHT, Component.empty(), this.font);
            rows.addChild(itemNames[i]);

            AbstractWidget toggle = includedToggle(config.gradientIncluded[slot], GRADIENT_ROW_HEIGHT,
                    "text.texturebuilder.config.included",
                    value -> {
                        // Applies immediately; the handles redistribute because the count changed.
                        ModConfig.get().setGradientIncluded(slot, value);
                        refreshGradientShares();
                    });
            toggle.setTooltip(Tooltip.create(Component.translatable("text.texturebuilder.gradient.included.tooltip")));
            rows.addChild(toggle);

            effectivePercents[i] = new StringWidget(COL_EFFECTIVE, GRADIENT_ROW_HEIGHT, Component.empty(), this.font);
            rows.addChild(effectivePercents[i]);
        }
        contents.addChild(grid);

        LinearLayout barRow = LinearLayout.horizontal().spacing(GRID_SPACING);
        GradientDistributionSlider distribution = new GradientDistributionSlider(BAR_WIDTH, BUTTON_HEIGHT,
                this.font, this::gradientEntryName, this::refreshGradientShares);
        distribution.setTooltip(Tooltip.create(
                Component.translatable("text.texturebuilder.gradient.distribution.tooltip")));
        barRow.addChild(distribution);
        Button reset = Button.builder(Component.translatable("text.texturebuilder.gradient.reset"), b -> {
            ModConfig cfg = ModConfig.get();
            int entries = cfg.gradientPalette().length;
            if (entries >= 2) {
                cfg.gradientBoundaries = GradientHelper.evenBoundaries(entries); // Applies immediately.
            }
            refreshGradientShares();
        }).size(SIDE_BUTTON_WIDTH, BUTTON_HEIGHT).build();
        reset.setTooltip(Tooltip.create(Component.translatable("text.texturebuilder.gradient.reset.tooltip")));
        barRow.addChild(reset);
        contents.addChild(barRow);

        LinearLayout transitionRow = LinearLayout.horizontal().spacing(GRID_SPACING);
        transitionRow.addChild(new TransitionSlider(config.gradientTransition));
        transitionRow.addChild(Button.builder(Component.translatable("text.texturebuilder.gradient.clear_points"),
                b -> {
                    ModConfig.get().clearGradientPoints();
                    ModConfig.get().save(); // Points always save when they change.
                    refreshPoints();
                }).size(SIDE_BUTTON_WIDTH, BUTTON_HEIGHT).build());
        contents.addChild(transitionRow);

        pointsLine = new StringWidget(ROW_WIDTH, POINTS_LINE_HEIGHT, Component.empty(), this.font)
                .setMaxWidth(ROW_WIDTH, StringWidget.TextOverflow.SCROLLING);
        contents.addChild(pointsLine);
        return contents;
    }

    @Override
    protected void repositionElements() {
        if (this.layout != null) {
            this.layout.arrangeElements();
        }
    }

    /** FR-07 live preview, plus the gradient points line; refreshed every tick. */
    @Override
    public void tick() {
        if (rebuildPending) {
            rebuildPending = false;
            this.rebuildWidgets();
        }
        refreshItemNames();
        refreshPoints();
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

    private void refreshPoints() {
        if (pointsLine != null && this.minecraft != null) {
            pointsLine.setMessage(GradientController.describePoints(this.minecraft));
        }
    }

    /** Segment label for gradient entry {@code entry}: the live name of its slot's item. */
    private Component gradientEntryName(int entry) {
        int[] palette = ModConfig.get().gradientPalette();
        if (entry >= palette.length) {
            return Component.empty();
        }
        if (this.minecraft == null || this.minecraft.player == null) {
            return Component.translatable("text.texturebuilder.config.slot", palette[entry] + 1);
        }
        ItemStack stack = this.minecraft.player.getInventory().getItem(palette[entry]);
        return stack.isEmpty()
                ? Component.translatable("text.texturebuilder.config.empty")
                : stack.getHoverName();
    }

    /**
     * Recomputes the FR-09 total (red when not 100) and the normalized effective percentages.
     * Texture mode only; a no-op in Gradient mode, where those widgets don't exist.
     *
     * <p>Null-guarded throughout: widgets are built row by row in {@link #init()}, so a slider can
     * exist before the label widgets this method writes to.
     */
    private void refreshDerived() {
        if (gradientLayout) {
            return; // Gradient mode's Eff. % column is filled by refreshGradientShares().
        }
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
     * Gradient mode's Eff. % column: each included slot's share of the start→end line, from the
     * distribution handles. Blend zones are symmetric around each boundary, so this is also the
     * expected share of placed blocks along the line. "—" for excluded slots, and for every slot
     * while fewer than 2 are included (no gradient). Blank when {@code auto_normalize_display} is
     * off, like Texture mode's column.
     */
    private void refreshGradientShares() {
        if (!gradientLayout) {
            return;
        }
        ModConfig config = ModConfig.get();
        int[] palette = config.gradientPalette();
        boolean usable = palette.length >= GradientHelper.MIN_ENTRIES
                && config.gradientBoundaries.length == palette.length - 1;
        Component blank = config.autoNormalizeDisplay
                ? Component.literal("—").withStyle(ChatFormatting.DARK_GRAY)
                : Component.empty();
        for (StringWidget widget : effectivePercents) {
            if (widget != null) {
                widget.setMessage(blank);
            }
        }
        if (!config.autoNormalizeDisplay || !usable) {
            return;
        }
        for (int entry = 0; entry < palette.length; entry++) {
            StringWidget widget = effectivePercents[palette[entry]];
            if (widget != null) {
                double pct = GradientHelper.segmentShare(config.gradientBoundaries, entry) * 100.0D;
                widget.setMessage(Component.literal(String.format("%.1f%%", pct)));
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

    /** Gradient mode's global blend width, 0-100%, written straight to the live config. */
    private static class TransitionSlider extends AbstractSliderButton {
        TransitionSlider(int transition) {
            super(0, 0, BAR_WIDTH, BUTTON_HEIGHT, Component.empty(),
                    Mth.clamp(transition / (double) ModConfig.TRANSITION_MAX, 0.0D, 1.0D));
            this.setTooltip(Tooltip.create(Component.translatable("text.texturebuilder.gradient.transition.tooltip")));
            this.updateMessage();
        }

        private int percent() {
            return Mth.floor(this.value * ModConfig.TRANSITION_MAX + 0.5D);
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.translatable("text.texturebuilder.gradient.transition", percent()));
        }

        @Override
        protected void applyValue() {
            ModConfig.get().gradientTransition = percent();
        }
    }

    /**
     * A per-slot Included/Excluded toggle (FR-08), used by both modes with their own flags. Built
     * from the generic {@link CycleButton} factory rather than {@link CycleButton#onOffBuilder} so
     * the ON/OFF label can be colored — malilib's SUCCESS/ERROR convention (green {@code §a} / red
     * {@code §c}, confirmed from the installed malilib jar's lang file), matching the toggle
     * hotkey's confirmation message and Litematica's own toggle messages, since Litematica is built
     * on malilib.
     */
    private static AbstractWidget includedToggle(boolean initial, int height, String narrationKey,
                                                 java.util.function.Consumer<Boolean> onChange) {
        return CycleButton.<Boolean>builder(
                        value -> Component.translatable(value ? "text.texturebuilder.on" : "text.texturebuilder.off")
                                .withStyle(value ? ChatFormatting.GREEN : ChatFormatting.RED),
                        initial)
                .withValues(true, false)
                .displayOnlyValue()
                .create(0, 0, INCLUDE_BUTTON_WIDTH, height, Component.translatable(narrationKey),
                        (button, value) -> onChange.accept(value));
    }

    /**
     * The Texture/Gradient mode button in the footer, the only way to change the mode. A change
     * applies to the live config at once and the screen swaps its contents on the next tick.
     */
    private CycleButton<ModConfig.Mode> modeButton(int width, int height) {
        CycleButton<ModConfig.Mode> button = CycleButton.<ModConfig.Mode>builder(
                        TextureBuilderClient::modeName, ModConfig.get().mode)
                .withValues(ModConfig.Mode.values())
                .create(0, 0, width, height, Component.translatable("text.texturebuilder.config.mode"),
                        (b, mode) -> {
                            ModConfig.get().mode = mode; // Applies immediately.
                            rebuildPending = true;
                        });
        button.setTooltip(Tooltip.create(Component.translatable("text.texturebuilder.config.mode.tooltip")));
        return button;
    }

    private StringWidget slotLabel(int slot, int width, int height) {
        return new StringWidget(width, height,
                Component.translatable("text.texturebuilder.config.slot", slot + 1), this.font);
    }

    private StringWidget header(String key, int width, int height) {
        return new StringWidget(width, height,
                Component.translatable("text.texturebuilder.config.header." + key)
                        .withStyle(ChatFormatting.UNDERLINE),
                this.font);
    }
}
