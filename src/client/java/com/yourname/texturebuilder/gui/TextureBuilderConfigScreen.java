package com.yourname.texturebuilder.gui;

import java.util.ArrayList;
import java.util.List;

import com.yourname.texturebuilder.TextureBuilder;
import com.yourname.texturebuilder.config.ModConfig;
import com.yourname.texturebuilder.util.TextureBuilderHelper;
import com.yourname.texturebuilder.util.TextureBuilderHelper.SlotEntry;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/**
 * The PA9 TextureBuilder configuration screen (FR-06..FR-10, SRS §7.3): one row per hotbar slot
 * with a live preview of the item currently in that slot, an include/exclude toggle, and an
 * editable weight field, plus the running weight total (flagged red when it isn't 100 — the screen
 * can still be closed regardless, since selection normalizes proportionally at runtime, FR-09).
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
    private static final int COL_SLOT = 34;
    private static final int COL_ITEM = 112;
    private static final int COL_INCLUDE = 58;
    private static final int COL_WEIGHT = 44;
    private static final int COL_EFFECTIVE = 44;

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

            EditBox weightBox = new EditBox(this.font, 0, 0, COL_WEIGHT, ROW_HEIGHT,
                    Component.translatable("text.texturebuilder.config.weight"));
            weightBox.setMaxLength(4);
            weightBox.setValue(Integer.toString(config.weights[slot]));
            // 26.2 removed EditBox.setFilter, so non-digits are stripped in the responder
            // instead (the re-entrant setValue call settles immediately once the text is clean).
            weightBox.setResponder(s -> {
                String digits = s.replaceAll("\\D", "");
                if (!digits.equals(s)) {
                    weightBox.setValue(digits);
                    return;
                }
                config.weights[slot] = digits.isEmpty() ? 0 : Integer.parseInt(digits); // FR-10
                refreshDerived();
            });
            rows.addChild(weightBox);

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
            ItemStack stack = inventory.getItem(i);
            itemNames[i].setMessage(stack.isEmpty()
                    ? Component.translatable("text.texturebuilder.config.empty")
                            .withStyle(ChatFormatting.DARK_GRAY)
                    : stack.getHoverName().copy().append(" x" + stack.getCount()));
        }
    }

    /** Recomputes the FR-09 total (red when not 100) and the normalized effective percentages. */
    private void refreshDerived() {
        ModConfig config = ModConfig.get();
        List<SlotEntry> entries = new ArrayList<>(TextureBuilder.HOTBAR_SIZE);
        for (int i = 0; i < TextureBuilder.HOTBAR_SIZE; i++) {
            entries.add(new SlotEntry(i, config.included[i], config.weights[i]));
        }

        int total = TextureBuilderHelper.includedWeightTotal(entries);
        Component totalText = Component.translatable("text.texturebuilder.config.total", total);
        totalWidget.setMessage(total == 100
                ? totalText.copy().withStyle(ChatFormatting.GREEN)
                : totalText.copy().withStyle(ChatFormatting.RED)
                        .append(Component.translatable("text.texturebuilder.config.total.normalized")));

        for (int i = 0; i < TextureBuilder.HOTBAR_SIZE; i++) {
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

    private StringWidget header(String key, int width) {
        return new StringWidget(width, ROW_HEIGHT,
                Component.translatable("text.texturebuilder.config.header." + key)
                        .withStyle(ChatFormatting.UNDERLINE),
                this.font);
    }
}
