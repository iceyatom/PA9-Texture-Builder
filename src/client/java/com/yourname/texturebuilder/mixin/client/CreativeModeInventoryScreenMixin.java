package com.yourname.texturebuilder.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.yourname.texturebuilder.gui.TextureBuilderConfigScreen;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.CreativeModeTab;

/**
 * Adds the TextureBuilder button to the creative inventory's "Survival Inventory" tab — the
 * creative-mode counterpart of {@link InventoryScreenMixin} (FR-05/FR-06).
 *
 * <p><b>Why a second mixin is needed:</b> {@code InventoryScreen.init()} swaps creative players to
 * {@link CreativeModeInventoryScreen} and returns early, so the survival button never exists there.
 *
 * <p><b>What is intercepted and why</b> (NFR-10): all three injection targets are methods declared
 * on {@code CreativeModeInventoryScreen} itself (NFR-11, verified against the 26.3 bytecode). No
 * vanilla logic is cancelled or altered — the button is purely additive.
 *
 * <ul>
 *   <li>{@code init()} at TAIL — creates the button. {@code init()} has a single {@code return},
 *       reached by both its creative branch and its "not creative any more, swap to
 *       {@code InventoryScreen}" branch; in the latter the button lands on a screen that is already
 *       being replaced, which is harmless.</li>
 *   <li>{@code selectTab(CreativeModeTab)} at TAIL — tab switches do not re-run {@code init()}, so
 *       the button is shown/hidden here: it only belongs on the inventory tab, where the area it
 *       occupies is empty panel; every other tab draws its item grid there. {@code init()} itself
 *       calls {@code selectTab} before the button exists, hence the null guard.</li>
 *   <li>{@code containerTick()} at TAIL — the same cheap per-tick safety net as the survival mixin,
 *       re-asserting position and visibility for any path not enumerated above.</li>
 * </ul>
 *
 * <p><b>Why this mixin declares {@link AbstractContainerScreen} as its superclass:</b> same reason
 * as {@link InventoryScreenMixin} — {@code leftPos}/{@code topPos}/{@code imageWidth} are declared
 * there, and {@code @Shadow} cannot resolve inherited fields. This mixin never calls
 * {@code super.*}. {@code getSelectedTab()} (1.19's way to ask whether the inventory tab is open;
 * {@code isInventoryOpen()} came later) is public on the target, so it is reached through a cast
 * rather than a shadow.
 */
@Mixin(CreativeModeInventoryScreen.class)
public abstract class CreativeModeInventoryScreenMixin
		extends AbstractContainerScreen<CreativeModeInventoryScreen.ItemPickerMenu> {

	/** Button size (same as the survival button), and its inset from the panel's right and top edges. */
	private static final int TEXTUREBUILDER_WIDTH = 24;
	private static final int TEXTUREBUILDER_HEIGHT = 12;
	/** Width the newer releases' {@code Tooltip.create} wraps the tooltip at. */
	private static final int TEXTUREBUILDER_TOOLTIP_WIDTH = 170;
	private static final int TEXTUREBUILDER_RIGHT_INSET = 25;
	private static final int TEXTUREBUILDER_TOP_OFFSET = 28;

	@Unique
	private Button texturebuilder$button;

	/** Never invoked — Mixin discards mixin constructors; present only so this class compiles. */
	private CreativeModeInventoryScreenMixin(CreativeModeInventoryScreen.ItemPickerMenu menu,
			Inventory playerInventory, Component title) {
		super(menu, playerInventory, title);
	}

	@Inject(method = "init", at = @At("TAIL"))
	private void texturebuilder$addConfigButton(CallbackInfo ci) {
		CreativeModeInventoryScreen self = (CreativeModeInventoryScreen) (Object) this;
		texturebuilder$button = this.addRenderableWidget(new Button(
				0, 0, TEXTUREBUILDER_WIDTH, TEXTUREBUILDER_HEIGHT,
				Component.translatable("text.texturebuilder.button"),
				pressed -> this.minecraft.setScreen(new TextureBuilderConfigScreen(self)),
				(button, poseStack, mouseX, mouseY) -> this.renderTooltip(poseStack, this.font.split(
						Component.translatable("text.texturebuilder.button.tooltip"), TEXTUREBUILDER_TOOLTIP_WIDTH),
						mouseX, mouseY)));
		texturebuilder$refresh();
	}

	@Inject(method = "selectTab", at = @At("TAIL"))
	private void texturebuilder$onTabSelected(CallbackInfo ci) {
		texturebuilder$refresh();
	}

	@Inject(method = "containerTick", at = @At("TAIL"))
	private void texturebuilder$containerTick(CallbackInfo ci) {
		texturebuilder$refresh();
	}

	/**
	 * Anchors the button in the empty panel space to the right of the leggings/boots slots, above
	 * the main inventory rows: GUI-relative x 146–170, y 28–40 on the 195-wide creative panel. That
	 * right edge lines up with the last inventory column (slots at x 9 + 18i, so the ninth ends at
	 * 170), and the area is clear of everything the inventory tab lays out — armor slots at x 54/108,
	 * y 6/33 (the right-hand pair ends at x 124), the player model between them, the offhand slot at
	 * (35, 20), the inventory rows from y 54, and the destroy-item slot at (173, 112). Coordinates
	 * verified against the 26.3 {@code selectTab} bytecode; see CLAUDE.md.
	 */
	@Unique
	private void texturebuilder$refresh() {
		if (texturebuilder$button != null) {
			texturebuilder$button.visible = ((CreativeModeInventoryScreen) (Object) this).getSelectedTab()
					== CreativeModeTab.TAB_INVENTORY.getId();
			texturebuilder$button.x = this.leftPos + this.imageWidth - TEXTUREBUILDER_WIDTH - TEXTUREBUILDER_RIGHT_INSET;
			texturebuilder$button.y = this.topPos + TEXTUREBUILDER_TOP_OFFSET;
		}
	}
}
