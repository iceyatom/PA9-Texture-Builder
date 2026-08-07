package com.yourname.texturebuilder.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.yourname.texturebuilder.gui.TextureBuilderConfigScreen;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.InventoryMenu;

/**
 * Adds the TextureBuilder button to the vanilla player inventory screen (FR-05/FR-06).
 *
 * <p><b>What is intercepted and why</b> (NFR-10): all three injection targets are methods declared
 * on {@link InventoryScreen} itself (NFR-11, verified against the 26.2 bytecode), exactly as SRS
 * §7.2 prescribes. No vanilla logic is cancelled or altered — the button is purely additive, and
 * nothing here overlaps ContainerSort's targets, so the two mods cannot conflict (NFR-09).
 *
 * <ul>
 *   <li>{@code init()} at TAIL — creates the button. TAIL is the correct anchor for a second
 *       reason beyond "vanilla widgets are laid out by then": {@code init()} branches, sending
 *       creative-mode players to {@code CreativeModeInventoryScreen} and returning early, so the
 *       final return TAIL binds to is the survival path, immediately after
 *       {@code AbstractRecipeBookScreen.init()} has run.</li>
 *   <li>{@code onRecipeBookButtonClick()} at TAIL — repositions on recipe book toggle (see the
 *       layout note below).</li>
 *   <li>{@code containerTick()} at TAIL — a cheap per-tick safety net that re-asserts the position
 *       in case some future path moves the panel without going through either hook above.</li>
 * </ul>
 *
 * <p><b>Why this mixin declares {@link AbstractContainerScreen} as its superclass:</b> the
 * {@code leftPos}, {@code topPos} and {@code imageWidth} fields it needs are declared on
 * {@code AbstractContainerScreen}, not on {@code InventoryScreen}. Mixin's {@code @Shadow} for
 * <em>fields</em> does not walk the target's superclass hierarchy — it requires the field to be
 * declared on the target class itself, and shadowing them here fails at class-load with
 * {@code InvalidMixinException: @Shadow field leftPos was not located in the target class}.
 * Declaring the superclass instead makes them genuinely inherited members that javac resolves and
 * the JVM looks up normally, so no {@code @Shadow} is needed at all. {@code AbstractContainerScreen}
 * is a valid choice because it is in {@code InventoryScreen}'s hierarchy
 * ({@code InventoryScreen} → {@code AbstractRecipeBookScreen} → {@code AbstractContainerScreen}).
 * This mixin never calls {@code super.*}, so the non-direct superclass is safe.
 *
 * <p><b>Why the position must be re-asserted, not just set once:</b> opening the recipe book
 * shifts the whole inventory panel right, and {@code leftPos} is reassigned in exactly two places,
 * <em>both of which run after our creation hook would have fired if it were anchored higher up the
 * hierarchy</em>:
 * <ol>
 *   <li>{@code AbstractRecipeBookScreen.init()} calls {@code super.init()} <em>first</em> and only
 *       then assigns {@code leftPos = recipeBookComponent.updateScreenPosition(...)}. Injecting at
 *       the TAIL of {@code AbstractContainerScreen.init()} therefore reads a stale, still-centred
 *       {@code leftPos} — the bug that left the button floating over the player model whenever the
 *       recipe book was open.</li>
 *   <li>The recipe book toggle button's own handler re-assigns {@code leftPos} and then calls
 *       {@code onRecipeBookButtonClick()} — it does <em>not</em> re-run {@code init()}, so a
 *       create-time-only placement would go stale again on every toggle.</li>
 * </ol>
 *
 * <p>The button's press handler is the only entry point into the mod's configuration; the mixin
 * contains no configuration logic itself (NFR-12).
 */
@Mixin(InventoryScreen.class)
public abstract class InventoryScreenMixin extends AbstractContainerScreen<InventoryMenu> {

	/** Button size, and its inset from the inventory panel's right and top edges. */
	private static final int TEXTUREBUILDER_WIDTH = 24;
	private static final int TEXTUREBUILDER_HEIGHT = 12;
	private static final int TEXTUREBUILDER_MARGIN = 6;
	private static final int TEXTUREBUILDER_TOP_OFFSET = 64;

	@Unique
	private Button texturebuilder$button;

	/** Never invoked — Mixin discards mixin constructors; present only so this class compiles. */
	private InventoryScreenMixin(InventoryMenu menu, Inventory playerInventory, Component title) {
		super(menu, playerInventory, title);
	}

	@Inject(method = "init", at = @At("TAIL"))
	private void texturebuilder$addConfigButton(CallbackInfo ci) {
		InventoryScreen self = (InventoryScreen) (Object) this;
		texturebuilder$button = this.addRenderableWidget(Button.builder(
						Component.translatable("text.texturebuilder.button"),
						pressed -> this.minecraft.gui.setScreen(new TextureBuilderConfigScreen(self)))
				.bounds(0, 0, TEXTUREBUILDER_WIDTH, TEXTUREBUILDER_HEIGHT)
				.tooltip(Tooltip.create(Component.translatable("text.texturebuilder.button.tooltip")))
				.build());
		texturebuilder$reposition();
	}

	@Inject(method = "onRecipeBookButtonClick", at = @At("TAIL"))
	private void texturebuilder$onRecipeBookToggled(CallbackInfo ci) {
		texturebuilder$reposition();
	}

	@Inject(method = "containerTick", at = @At("TAIL"))
	private void texturebuilder$containerTick(CallbackInfo ci) {
		texturebuilder$reposition();
	}

	/**
	 * Anchors the button in the empty panel space directly beneath the crafting result slot, which
	 * sits at (154, 28)–(170, 44) relative to the panel. Right-aligning to the same 6px margin puts
	 * the button at x 146–170 (flush under the output slot) and y 64–76 — clear of the recipe book
	 * button at (104, 61)–(124, 79), the shield slot at (77, 62), and the player inventory rows,
	 * which start at y 84. Coordinates verified against the 26.2
	 * {@code InventoryMenu}/{@code InventoryScreen} bytecode; see CLAUDE.md.
	 */
	@Unique
	private void texturebuilder$reposition() {
		if (texturebuilder$button != null) {
			texturebuilder$button.setPosition(
					this.leftPos + this.imageWidth - TEXTUREBUILDER_WIDTH - TEXTUREBUILDER_MARGIN,
					this.topPos + TEXTUREBUILDER_TOP_OFFSET);
		}
	}
}
