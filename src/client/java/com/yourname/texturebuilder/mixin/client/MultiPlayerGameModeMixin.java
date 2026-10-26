package com.yourname.texturebuilder.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.yourname.texturebuilder.placement.PlacementRandomizer;

import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.BlockHitResult;

/**
 * The placement interception point (SRS §7.1, FR-11/FR-12).
 *
 * <p><b>What is intercepted and why</b> (NFR-10): {@code useItemOn} is the client entry point for
 * every right-click block interaction — the 26.2 Mojmap name for the method the SRS calls
 * {@code interactBlock} (there is no Yarn mapping for 26.x; the class the SRS names
 * {@code ClientPlayerInteractionManager} is {@link MultiPlayerGameMode}). Both injections target a
 * method declared on the target class itself (NFR-11, verified via {@code javap} against the 26.2
 * jar), and neither cancels or alters vanilla logic (PKT-02):
 *
 * <ul>
 *   <li>HEAD — {@link PlacementRandomizer#beforePlacement}: weighted-selects a pooled hotbar slot
 *       and switches to it, exactly as pressing that number key would (PKT-01). Vanilla placement
 *       then runs unmodified against the switched slot.</li>
 *   <li>TAIL — {@link PlacementRandomizer#afterPlacement}: fires the single pick-block restock
 *       attempt if the placement emptied the slot (FR-13), then restores the originally-selected
 *       slot — HEAD and TAIL bracket one synchronous call, so the switch-and-restore pair completes
 *       within the same client tick (FR-12, NFR-03).</li>
 * </ul>
 *
 * <p>When the mod is toggled OFF the HEAD call returns after a single boolean check, adding no
 * meaningful overhead to normal placement (FR-04, NFR-02). All business logic lives in
 * {@link PlacementRandomizer}, not here (NFR-12).
 */
@Mixin(MultiPlayerGameMode.class)
public abstract class MultiPlayerGameModeMixin {

    /**
     * Cancellable since v1.1.0: Gradient mode with unusable points (none, start only, another
     * dimension, start and end the same) blocks the placement with a warning. Cancelling here, at
     * HEAD, returns {@code FAIL} before vanilla builds the use-item-on packet, so the server never
     * hears of the click and places nothing; {@code FAIL} also stops {@code startUseItem} trying
     * the off hand. TAIL does not run for a cancelled call, which is fine: nothing was switched.
     * In every other case this still never cancels or alters vanilla logic (PKT-02).
     */
    @Inject(method = "useItemOn", at = @At("HEAD"), cancellable = true)
    private void texturebuilder$beforeUseItemOn(LocalPlayer player, InteractionHand hand,
                                                BlockHitResult hitResult,
                                                CallbackInfoReturnable<InteractionResult> cir) {
        if (PlacementRandomizer.beforePlacement(player, hand, hitResult)) {
            cir.setReturnValue(InteractionResult.FAIL);
        }
    }

    @Inject(method = "useItemOn", at = @At("TAIL"))
    private void texturebuilder$afterUseItemOn(LocalPlayer player, InteractionHand hand,
                                               BlockHitResult hitResult,
                                               CallbackInfoReturnable<InteractionResult> cir) {
        PlacementRandomizer.afterPlacement();
    }
}
