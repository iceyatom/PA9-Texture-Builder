package com.yourname.texturebuilder.mixin.client;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.yourname.texturebuilder.gui.TextureBuilderConfigScreen;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;

/**
 * Adds the TextureBuilder button to the vanilla player inventory screen (FR-05/FR-06).
 *
 * <p><b>What is intercepted and why</b> (NFR-10): {@code init()} at TAIL, after vanilla widgets are
 * laid out, so the button can be anchored to the GUI's final position without overlapping the
 * crafting grid, armor slots, or recipe book toggle (FR-05). The SRS (§7.2) suggested targeting
 * {@code InventoryScreen.init()}, but in 26.2 the concrete screen subclasses no longer declare
 * {@code init()} at all — {@link AbstractContainerScreen} is the narrowest class that owns the
 * method, so it is the target that satisfies the declared-on-the-target-class rule (NFR-11); an
 * {@code instanceof InventoryScreen} guard keeps every other container screen untouched (creative's
 * screen is a different class, so it is excluded automatically).
 *
 * <p>ContainerSort injects at the same TAIL for its own (container-only) button; both injections
 * are purely additive and neither cancels, so they coexist without conflict (NFR-09). A priority
 * above ContainerSort's 1100 keeps the merge order deterministic. Button placement mirrors
 * ContainerSort's top-right corner precedent (SRS §12); its press handler is the only entry point —
 * the mixin contains no configuration logic itself (NFR-12).
 */
@Mixin(value = AbstractContainerScreen.class, priority = 1200)
public abstract class AbstractContainerScreenMixin extends Screen {
	@Shadow
	protected int leftPos;
	@Shadow
	protected int topPos;
	@Shadow
	@Final
	protected int imageWidth;

	protected AbstractContainerScreenMixin(Component title) {
		super(title);
	}

	@Inject(method = "init", at = @At("TAIL"))
	private void texturebuilder$addConfigButton(CallbackInfo ci) {
		if (!((Object) this instanceof InventoryScreen self)) {
			return;
		}
		int width = 24;
		int height = 12;
		int x = this.leftPos + this.imageWidth - width - 6;
		int y = this.topPos + 4;
		this.addRenderableWidget(Button.builder(
						Component.translatable("text.texturebuilder.button"),
						pressed -> this.minecraft.gui.setScreen(new TextureBuilderConfigScreen(self)))
				.bounds(x, y, width, height)
				.tooltip(Tooltip.create(Component.translatable("text.texturebuilder.button.tooltip")))
				.build());
	}
}
