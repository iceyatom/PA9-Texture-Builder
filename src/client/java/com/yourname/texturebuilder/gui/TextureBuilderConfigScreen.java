package com.yourname.texturebuilder.gui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.yourname.texturebuilder.TextureBuilder;
import com.yourname.texturebuilder.TextureBuilderClient;
import com.yourname.texturebuilder.config.ModConfig;
import com.yourname.texturebuilder.placement.GradientController;
import com.yourname.texturebuilder.util.GradientHelper;
import com.yourname.texturebuilder.util.TextureBuilderHelper;
import com.yourname.texturebuilder.util.TextureBuilderHelper.SlotEntry;

import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
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
 * <p>1.19 predates the {@code StringWidget}/{@code GridLayout}/{@code LinearLayout}/
 * {@code HeaderAndFooterLayout} widgets and the {@code Tooltip} class the newer releases build this
 * screen from, so the same layout is placed by hand here with the same metrics (28px header and
 * footer, contents 30px below the header or higher if they would reach the footer, explicit column
 * widths, 2px spacing), {@link Label} stands in for {@code StringWidget}, and tooltips are drawn in
 * {@link #render}.
 *
 * <p>Edits apply to the live config immediately (FR-10); the TOML is written when the screen
 * closes, by any route (Done, ESC, or the back control), and at once when "Clear points" is
 * pressed. A parent screen (the inventory) is restored on close (FR-06); a {@code null} parent
 * closes to the game.
 */
public class TextureBuilderConfigScreen extends Screen {

    private static final int ROW_HEIGHT = 16;
    private static final int BUTTON_HEIGHT = 20;

    // Explicit column widths, shared by both modes so their tables have the same footprint (~343px,
    // what the Texture screen has always rendered at on the layout-based releases).
    // 343px fits any GUI at least 343 wide (1080p at scale 4 is 480), but not the 320px minimum.
    // Eff. % is 40 so the Texture-mode Reset button fits under it.
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
    /** The newer releases' {@code HeaderAndFooterLayout} puts the contents this far below the header. */
    private static final int CONTENT_MARGIN_TOP = 30;

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
    // Transition row: [Transition 162][Gradient: 1D/2D/3D 82][Clear points 95] = 343 with spacing.
    private static final int SHAPE_BUTTON_WIDTH = 82;
    private static final int TRANSITION_WIDTH = BAR_WIDTH - GRID_SPACING - SHAPE_BUTTON_WIDTH;

    /** Content heights: 10 table rows, then the total line (Texture) or bar/Transition/points (Gradient). */
    private static final int TEXTURE_CONTENT_HEIGHT = 11 * ROW_HEIGHT + 10 * GRID_SPACING; // 196
    private static final int GRADIENT_CONTENT_HEIGHT = 10 * GRADIENT_ROW_HEIGHT + 9 * GRID_SPACING
            + 2 * (GRID_SPACING + BUTTON_HEIGHT) + GRID_SPACING + POINTS_LINE_HEIGHT; // 214

    private static final int FOOTER_BUTTON_WIDTH = 120;
    private static final int FOOTER_SPACING = 8;
    /** Width {@code Tooltip.create} wraps at in the newer releases. */
    private static final int TOOLTIP_WIDTH = 170;

    private final Screen parent;

    /**
     * Rebuilt from scratch on every {@link #init()} — {@code init()} runs again on resize, and
     * re-adding would stack a second copy of every widget.
     */
    private final Label[] itemNames = new Label[TextureBuilder.HOTBAR_SIZE];
    private final Label[] effectivePercents = new Label[TextureBuilder.HOTBAR_SIZE];
    private Label totalWidget;
    private Label pointsLine;
    /** Tooltip text per widget; no {@code Tooltip} class on this version, see {@link #render}. */
    private final Map<AbstractWidget, Component> tooltips = new LinkedHashMap<>();
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
        tooltips.clear();

        boolean gradient = ModConfig.get().mode == ModConfig.Mode.GRADIENT;
        gradientLayout = gradient;

        // Header: the title, centred in the header band.
        int titleWidth = this.font.width(this.title);
        this.addRenderableWidget(new Label((this.width - titleWidth) / 2, (HEADER_HEIGHT - 9) / 2,
                titleWidth, 9, this.title, this.font));

        // Contents: centred horizontally, CONTENT_MARGIN_TOP below the header, or higher when they
        // would otherwise reach the footer (the newer releases' HeaderAndFooterLayout rule).
        int contentHeight = gradient ? GRADIENT_CONTENT_HEIGHT : TEXTURE_CONTENT_HEIGHT;
        int x = (this.width - ROW_WIDTH) / 2;
        int y = Math.min(HEADER_HEIGHT + CONTENT_MARGIN_TOP, this.height - FOOTER_HEIGHT - contentHeight);
        if (gradient) {
            buildGradientContents(x, y);
        } else {
            buildTextureContents(x, y);
        }

        // Footer: Mode + Done only (120 + 120 + 8 = 248px), centred in the footer band.
        int footerX = (this.width - (2 * FOOTER_BUTTON_WIDTH + FOOTER_SPACING)) / 2;
        int footerY = this.height - FOOTER_HEIGHT + (FOOTER_HEIGHT - BUTTON_HEIGHT) / 2;
        this.addRenderableWidget(withTooltip(modeButton(footerX, footerY, FOOTER_BUTTON_WIDTH, BUTTON_HEIGHT),
                "text.texturebuilder.config.mode.tooltip"));
        this.addRenderableWidget(new Button(footerX + FOOTER_BUTTON_WIDTH + FOOTER_SPACING, footerY,
                FOOTER_BUTTON_WIDTH, BUTTON_HEIGHT, CommonComponents.GUI_DONE, b -> onClose()));

        refreshDerived();
        refreshGradientShares();
        refreshItemNames();
        refreshPoints();
    }

    /** The x of each column, left to right, starting at {@code x} with {@link #GRID_SPACING} between. */
    private static int[] columnX(int x, int[] widths) {
        int[] columns = new int[widths.length];
        for (int c = 0; c < widths.length; c++) {
            columns[c] = x;
            x += widths[c] + GRID_SPACING;
        }
        return columns;
    }

    /**
     * Texture mode: Slot / Item / Included / Weight / Eff. % rows, then the weight total with a
     * Reset button under the Eff. % column.
     */
    private void buildTextureContents(int x, int y) {
        ModConfig config = ModConfig.get();
        int[] widths = {COL_SLOT, COL_ITEM, COL_INCLUDE, COL_WEIGHT, COL_EFFECTIVE};
        int[] cx = columnX(x, widths);
        String[] headers = {"slot", "item", "included", "weight", "effective"};
        for (int c = 0; c < widths.length; c++) {
            this.addRenderableWidget(header(headers[c], cx[c], y, widths[c], ROW_HEIGHT));
        }

        for (int i = 0; i < TextureBuilder.HOTBAR_SIZE; i++) {
            final int slot = i;
            y += ROW_HEIGHT + GRID_SPACING;

            this.addRenderableWidget(slotLabel(slot, cx[0], y, COL_SLOT, ROW_HEIGHT));

            itemNames[i] = this.addRenderableWidget(
                    new Label(cx[1], y, COL_ITEM, ROW_HEIGHT, Component.empty(), this.font));

            this.addRenderableWidget(includedToggle(config.included[slot], cx[2], y, ROW_HEIGHT,
                    "text.texturebuilder.config.included",
                    value -> {
                        config.included[slot] = value; // FR-10: applies immediately.
                        refreshDerived();
                    }));

            this.addRenderableWidget(withTooltip(new WeightSlider(slot, config.weights[slot], cx[3], y),
                    "text.texturebuilder.config.weight.tooltip"));

            effectivePercents[i] = this.addRenderableWidget(
                    new Label(cx[4], y, COL_EFFECTIVE, ROW_HEIGHT, Component.empty(), this.font));
        }

        y += ROW_HEIGHT + GRID_SPACING;
        totalWidget = this.addRenderableWidget(new Label(x, y, ROW_WIDTH - GRID_SPACING - COL_EFFECTIVE,
                ROW_HEIGHT, Component.empty(), this.font));
        Button reset = new Button(x + ROW_WIDTH - COL_EFFECTIVE, y, COL_EFFECTIVE, ROW_HEIGHT,
                Component.translatable("text.texturebuilder.config.reset_weights"), b -> {
            java.util.Arrays.fill(ModConfig.get().weights, ModConfig.DEFAULT_WEIGHT); // Applies immediately.
            rebuildPending = true; // Rebuild next tick so every slider shows the new value.
        });
        this.addRenderableWidget(withTooltip(reset, "text.texturebuilder.config.reset_weights.tooltip"));
    }

    /**
     * Gradient mode: Slot / Item / Included / Eff. % rows (Gradient mode's own Included flags;
     * Eff. % is each slot's share of the start→end line), then the distribution bar + Reset,
     * Transition + Clear points, and the points/axis line in place of the weight total.
     */
    private void buildGradientContents(int x, int y) {
        ModConfig config = ModConfig.get();
        int[] widths = {GRADIENT_COL_SLOT, GRADIENT_COL_ITEM, COL_INCLUDE, COL_EFFECTIVE};
        int[] cx = columnX(x, widths);
        String[] headers = {"slot", "item", "included", "effective"};
        for (int c = 0; c < widths.length; c++) {
            this.addRenderableWidget(header(headers[c], cx[c], y, widths[c], GRADIENT_ROW_HEIGHT));
        }

        for (int i = 0; i < TextureBuilder.HOTBAR_SIZE; i++) {
            final int slot = i;
            y += GRADIENT_ROW_HEIGHT + GRID_SPACING;
            this.addRenderableWidget(slotLabel(slot, cx[0], y, GRADIENT_COL_SLOT, GRADIENT_ROW_HEIGHT));

            itemNames[i] = this.addRenderableWidget(
                    new Label(cx[1], y, GRADIENT_COL_ITEM, GRADIENT_ROW_HEIGHT, Component.empty(), this.font));

            AbstractWidget toggle = includedToggle(config.gradientIncluded[slot], cx[2], y, GRADIENT_ROW_HEIGHT,
                    "text.texturebuilder.config.included",
                    value -> {
                        // Applies immediately; the handles redistribute because the count changed.
                        ModConfig.get().setGradientIncluded(slot, value);
                        refreshGradientShares();
                    });
            this.addRenderableWidget(withTooltip(toggle, "text.texturebuilder.gradient.included.tooltip"));

            effectivePercents[i] = this.addRenderableWidget(
                    new Label(cx[3], y, COL_EFFECTIVE, GRADIENT_ROW_HEIGHT, Component.empty(), this.font));
        }

        y += GRADIENT_ROW_HEIGHT + GRID_SPACING;
        GradientDistributionSlider distribution = new GradientDistributionSlider(BAR_WIDTH, BUTTON_HEIGHT,
                this.font, this::gradientEntryName, this::refreshGradientShares);
        place(distribution, x, y);
        this.addRenderableWidget(withTooltip(distribution, "text.texturebuilder.gradient.distribution.tooltip"));
        Button reset = new Button(x + BAR_WIDTH + GRID_SPACING, y, SIDE_BUTTON_WIDTH, BUTTON_HEIGHT,
                Component.translatable("text.texturebuilder.gradient.reset"), b -> {
            ModConfig cfg = ModConfig.get();
            int entries = cfg.gradientPalette().length;
            if (entries >= 2) {
                cfg.gradientBoundaries = GradientHelper.evenBoundaries(entries); // Applies immediately.
            }
            refreshGradientShares();
        });
        this.addRenderableWidget(withTooltip(reset, "text.texturebuilder.gradient.reset.tooltip"));

        y += BUTTON_HEIGHT + GRID_SPACING;
        this.addRenderableWidget(withTooltip(new TransitionSlider(config.gradientTransition, x, y),
                "text.texturebuilder.gradient.transition.tooltip"));
        this.addRenderableWidget(withTooltip(shapeButton(config.gradientShape, x + TRANSITION_WIDTH + GRID_SPACING, y),
                "text.texturebuilder.gradient.shape.tooltip"));
        this.addRenderableWidget(new Button(x + BAR_WIDTH + GRID_SPACING, y, SIDE_BUTTON_WIDTH, BUTTON_HEIGHT,
                Component.translatable("text.texturebuilder.gradient.clear_points"),
                b -> {
                    ModConfig.get().clearGradientPoints();
                    ModConfig.get().save(); // Points always save when they change.
                    refreshPoints();
                }));

        y += BUTTON_HEIGHT + GRID_SPACING;
        pointsLine = this.addRenderableWidget(
                new ScrollingLabel(x, y, ROW_WIDTH, POINTS_LINE_HEIGHT, Component.empty(), this.font));
    }

    /**
     * Draws the dimmed background first (1.19's {@code Screen.render} doesn't, unlike 1.20.2+), then
     * the hovered widget's tooltip on top of every widget, as the newer releases' tooltips are.
     */
    @Override
    public void render(PoseStack poseStack, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(poseStack);
        super.render(poseStack, mouseX, mouseY, partialTick);
        for (Map.Entry<AbstractWidget, Component> entry : tooltips.entrySet()) {
            if (entry.getKey().isHoveredOrFocused()) {
                this.renderTooltip(poseStack, this.font.split(entry.getValue(), TOOLTIP_WIDTH), mouseX, mouseY);
                break;
            }
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
        for (Label widget : effectivePercents) {
            if (widget != null) {
                widget.setMessage(blank);
            }
        }
        if (!config.autoNormalizeDisplay || !usable) {
            return;
        }
        for (int entry = 0; entry < palette.length; entry++) {
            Label widget = effectivePercents[palette[entry]];
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

    /** Gradient mode's global blend width, 0-100%, written straight to the live config. */
    private static class TransitionSlider extends AbstractSliderButton {
        TransitionSlider(int transition, int x, int y) {
            super(x, y, TRANSITION_WIDTH, BUTTON_HEIGHT, Component.empty(),
                    Mth.clamp(transition / (double) ModConfig.TRANSITION_MAX, 0.0D, 1.0D));
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
    private static AbstractWidget includedToggle(boolean initial, int x, int y, int height, String narrationKey,
                                                 java.util.function.Consumer<Boolean> onChange) {
        return CycleButton.<Boolean>builder(
                        value -> Component.translatable(value ? "text.texturebuilder.on" : "text.texturebuilder.off")
                                .withStyle(value ? ChatFormatting.GREEN : ChatFormatting.RED))
                .withValues(true, false)
                .withInitialValue(initial)
                .displayOnlyValue()
                .create(x, y, INCLUDE_BUTTON_WIDTH, height, Component.translatable(narrationKey),
                        (button, value) -> onChange.accept(value));
    }

    /**
     * The 1D / 2D / 3D gradient shape button: which axes the fade runs across. Applies to the live
     * config at once; the points line below refreshes every tick, so it re-checks the points for
     * the new shape straight away.
     */
    private static CycleButton<Integer> shapeButton(int shape, int x, int y) {
        return CycleButton.<Integer>builder(
                        value -> Component.translatable("text.texturebuilder.gradient.shape." + value))
                .withValues(1, 2, 3)
                .withInitialValue(shape)
                .create(x, y, SHAPE_BUTTON_WIDTH, BUTTON_HEIGHT,
                        Component.translatable("text.texturebuilder.gradient.shape"),
                        (b, value) -> ModConfig.get().gradientShape = value);
    }

    /**
     * The Texture/Gradient mode button in the footer, the only way to change the mode. A change
     * applies to the live config at once and the screen swaps its contents on the next tick.
     */
    private CycleButton<ModConfig.Mode> modeButton(int x, int y, int width, int height) {
        return CycleButton.<ModConfig.Mode>builder(TextureBuilderClient::modeName)
                .withValues(ModConfig.Mode.values())
                .withInitialValue(ModConfig.get().mode)
                .create(x, y, width, height, Component.translatable("text.texturebuilder.config.mode"),
                        (b, mode) -> {
                            ModConfig.get().mode = mode; // Applies immediately.
                            rebuildPending = true;
                        });
    }

    /** Registers {@code widget}'s tooltip (drawn in {@link #render}) and returns the widget. */
    private <T extends AbstractWidget> T withTooltip(T widget, String key) {
        tooltips.put(widget, Component.translatable(key));
        return widget;
    }

    private static void place(AbstractWidget widget, int x, int y) {
        widget.x = x;
        widget.y = y;
    }

    private Label slotLabel(int slot, int x, int y, int width, int height) {
        return new Label(x, y, width, height,
                Component.translatable("text.texturebuilder.config.slot", slot + 1), this.font);
    }

    private Label header(String key, int x, int y, int width, int height) {
        return new Label(x, y, width, height,
                Component.translatable("text.texturebuilder.config.header." + key)
                        .withStyle(ChatFormatting.UNDERLINE),
                this.font);
    }

    /**
     * Stand-in for the newer releases' {@code StringWidget}: inactive (never focused or clicked),
     * text centred horizontally and vertically in its box, white with a shadow, no narration.
     */
    private static class Label extends AbstractWidget {
        protected final Font font;

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

    /**
     * The points line: left-aligned text that scrolls when it is wider than the line, as the newer
     * releases' {@code StringWidget.setMaxWidth(width, TextOverflow.SCROLLING)} does (2px margin,
     * the same back-and-forth motion as vanilla's {@code renderScrollingString}, ported here).
     */
    private static class ScrollingLabel extends Label {
        ScrollingLabel(int x, int y, int width, int height, Component message, Font font) {
            super(x, y, width, height, message, font);
        }

        @Override
        public void renderButton(PoseStack poseStack, int mouseX, int mouseY, float partialTick) {
            Component message = this.getMessage();
            int textWidth = this.font.width(message);
            if (textWidth <= this.getWidth()) {
                this.font.drawShadow(poseStack, message, this.x, this.y + (this.getHeight() - 9) / 2, 0xFFFFFF);
                return;
            }
            int minX = this.x + 2;
            int maxX = this.x + this.getWidth() - 2;
            int minY = this.y;
            int maxY = this.y + this.getHeight();
            int textY = (minY + maxY - 9) / 2 + 1;
            int overflow = textWidth - (maxX - minX);
            double seconds = (double) Util.getMillis() / 1000.0D;
            double period = Math.max((double) overflow * 0.5D, 3.0D);
            double phase = Math.sin((Math.PI / 2) * Math.cos((Math.PI * 2) * seconds / period)) / 2.0D + 0.5D;
            double offset = Mth.lerp(phase, 0.0D, (double) overflow);
            enableScissor(minX, minY, maxX, maxY);
            drawString(poseStack, this.font, message, minX - (int) offset, textY, 0xFFFFFF);
            RenderSystem.disableScissor();
        }

        /** GUI-coordinate scissor, as {@code GuiComponent.enableScissor} does from 1.19.3 on. */
        private static void enableScissor(int minX, int minY, int maxX, int maxY) {
            Window window = Minecraft.getInstance().getWindow();
            int windowHeight = window.getHeight();
            double scale = window.getGuiScale();
            double x = minX * scale;
            double y = windowHeight - maxY * scale;
            double width = (maxX - minX) * scale;
            double height = (maxY - minY) * scale;
            RenderSystem.enableScissor((int) x, (int) y, Math.max(0, (int) width), Math.max(0, (int) height));
        }
    }
}
