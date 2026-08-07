package com.yourname.texturebuilder.placement;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import com.yourname.texturebuilder.TextureBuilder;
import com.yourname.texturebuilder.TextureBuilderClient;
import com.yourname.texturebuilder.config.ModConfig;
import com.yourname.texturebuilder.hud.TextureBuilderHud;
import com.yourname.texturebuilder.util.TextureBuilderHelper;
import com.yourname.texturebuilder.util.TextureBuilderHelper.SlotEntry;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The slot-switching engine behind each placement (FR-11..FR-19, SRS §1 design note).
 *
 * <p>{@link #beforePlacement} runs at the HEAD of {@code MultiPlayerGameMode.useItemOn}: it draws a
 * weighted random slot from the configured pool, records the player's currently-selected slot, and
 * switches to the pick — sending the same {@code ServerboundSetCarriedItemPacket} pressing a number
 * key would (PKT-01). Vanilla placement then proceeds completely unmodified (PKT-02).
 * {@link #afterPlacement} runs at TAIL of the same call and fires the restock attempt if the pick
 * emptied the slot (FR-13).
 *
 * <h2>Whether the selection is restored afterwards</h2>
 * SRS §1 specified switching only "for the instant of each placement" and restoring the previous
 * slot, so the visible hotbar selection stayed put between placements (FR-12, NFR-06, TC-04) — but
 * it flagged that as "a design assumption made for v1 [that] can be revisited if undesired".
 * <b>Revisited on 2026-08-07 at the user's request: the mod now leaves the chosen slot selected</b>,
 * because keeping the selection frozen while blocks came out of other slots made it visually
 * ambiguous which block was actually being placed. The hotbar indicator now always shows the slot
 * the last block came from. Setting {@code restore_slot_after_placement = true} restores the
 * original SRS §1 behaviour.
 *
 * <p>Not restoring also removes the ordering hazard the restore created: the pick-block restock
 * (FR-13) targets the depleted slot, which simply stays selected, so there is no restore packet
 * racing the server's pick.
 *
 * <p>Every entry point is wrapped so that an unexpected exception disables the feature for the rest
 * of the session instead of leaving placement in an inconsistent state (NFR-04); when restoring is
 * enabled that step runs in a {@code finally} so the player's slot comes back even on failure.
 */
public final class PlacementRandomizer {

    private static final Random RANDOM = new Random();

    /** In-flight state between the HEAD and TAIL of a single {@code useItemOn} call. */
    private record Pending(int originalSlot, int pickSlot, Item pickedItem, boolean hadItems,
                           BlockHitResult hit) {
    }

    private static Pending pending;

    /**
     * How long to wait for a queued Pick Block press to actually refill the slot before declaring
     * it a miss (FR-15). Must cover vanilla consuming the click next tick plus, in survival, a
     * server round-trip — and any work another pick-block mod does — so it is generous at 1s;
     * the message is brief and disappearing, so a late one is unobtrusive, whereas a premature
     * "no more found" would be wrong.
     */
    private static final int RESTOCK_TIMEOUT_TICKS = 20;

    /** Sentinel for {@link RestockWatch#restoreSlot()} meaning "no deferred restore pending". */
    private static final int NO_RESTORE = -1;

    /**
     * A restock in flight: the slot awaiting refill, what it held, the remaining window, and — only
     * when {@code restore_slot_after_placement} is enabled — the slot to restore once the pick has
     * resolved ({@link #NO_RESTORE} otherwise).
     */
    private record RestockWatch(int slot, Item item, int ticksLeft, int restoreSlot) {
    }

    /**
     * In-flight restock attempts, indexed by hotbar slot. Per-slot rather than a single watch so
     * that two depleted slots can recover independently: with one shared watch, alternating misses
     * between two empty slots would keep replacing each other's watch, so neither would ever reach
     * its timeout and no FR-15 message would ever appear.
     */
    private static final RestockWatch[] restockWatches = new RestockWatch[TextureBuilder.HOTBAR_SIZE];

    private PlacementRandomizer() {
    }

    /**
     * HEAD of {@code useItemOn}: weighted-select and switch. Does nothing while toggled OFF
     * (FR-04, NFR-02) or for off-hand interactions.
     */
    public static void beforePlacement(LocalPlayer player, InteractionHand hand, BlockHitResult hit) {
        try {
            if (!TextureBuilderClient.isOn() || hand != InteractionHand.MAIN_HAND
                    || player == null || pending != null) {
                return;
            }
            Minecraft client = Minecraft.getInstance();
            Inventory inventory = player.getInventory();
            int originalSlot = inventory.getSelectedSlot();

            // Only take over interactions that look like block placement: an empty hand or a held
            // block. Tools, food, buckets etc. keep fully vanilla behaviour even while toggled ON.
            ItemStack held = inventory.getItem(originalSlot);
            if (!held.isEmpty() && !(held.getItem() instanceof BlockItem)) {
                return;
            }

            int pick = TextureBuilderHelper.selectSlot(poolEntries(), RANDOM);
            if (pick < 0) {
                return; // nothing included / all weights zero — leave placement alone.
            }

            ItemStack pickStack = inventory.getItem(pick);
            pending = new Pending(originalSlot, pick, pickStack.getItem(), !pickStack.isEmpty(), hit);
            if (pick != originalSlot) {
                switchSlot(client, inventory, pick);
            }
            if (ModConfig.get().debugLogging) {
                TextureBuilder.LOGGER.info("[TextureBuilder] placement draw: slot {} (was {}), stack {}",
                        pick + 1, originalSlot + 1, pickStack);
            }
        } catch (RuntimeException e) {
            pending = null;
            TextureBuilderClient.failSession(e);
        }
    }

    /**
     * TAIL of the same {@code useItemOn} call: restock whenever the drawn slot is empty, then
     * restore the originally-held slot only if {@code restore_slot_after_placement} is enabled
     * (see {@link #beforePlacement}).
     *
     * <p>The restock fires on <em>depletion only</em> — the placement that consumed the slot's last
     * item. A brief experiment also retried on every FR-16 miss, but that was reverted at the user's
     * request: a miss places nothing, so the crosshair is on whatever the player happens to be
     * aiming at rather than on a block of the depleted type, which made retries liable to pull an
     * unrelated block into the pooled slot. Depletion is the one moment the just-placed block is
     * guaranteed to be the item that ran out, which is what {@link #aimPickAtPlacedBlock} relies on.
     */
    public static void afterPlacement() {
        Pending p = pending;
        pending = null;
        if (p == null) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        if (player == null) {
            return;
        }
        Inventory inventory = player.getInventory();
        try {
            if (p.hadItems() && inventory.getItem(p.pickSlot()).isEmpty()) {
                attemptRestock(client, inventory, p, p.pickedItem());
            }
        } catch (RuntimeException e) {
            TextureBuilderClient.failSession(e);
        } finally {
            // Only restore when the player has opted back into the original SRS §1 behaviour, and
            // only when no restock is in flight: vanilla picks into the *selected* slot, so
            // restoring now would divert the queued pick away from the depleted slot. In that case
            // the watch owns the restore and performs it once the pick resolves. Runs even if the
            // restock attempt threw (NFR-04).
            if (ModConfig.get().restoreSlotAfterPlacement && restockWatches[p.pickSlot()] == null
                    && inventory.getSelectedSlot() != p.originalSlot()) {
                switchSlot(client, inventory, p.originalSlot());
            }
        }
    }

    /**
     * FR-13: a single pick-block attempt to restock the slot the last placement just emptied.
     *
     * <p><b>This fires the player's actual Pick Block keybind</b> rather than calling the
     * interaction manager directly, so that <em>other</em> mods which extend pick block take part.
     * Vanilla resolves pick block in the private {@code Minecraft.pickBlockOrEntity()}, reached
     * only through {@code handleKeybinds()}:
     * <pre>while (this.options.keyPickItem.consumeClick()) { this.pickBlockOrEntity(); }</pre>
     * {@link KeyMapping#click} queues exactly that click, so the press is indistinguishable from a
     * real one and every pick-block hook downstream runs. The previous implementation called
     * {@code MultiPlayerGameMode.handlePickItemFromBlock} — a method *inside* that flow — which
     * silently bypassed those hooks. That is why PA9 ShulkerPickBlock (which injects at the TAIL of
     * {@code Minecraft.pickBlockOrEntity}) never restocked from inventory shulker boxes here.
     *
     * <p>Because the click is consumed on the <em>next</em> tick, the crosshair target has been
     * recomputed by then and points at the newly placed block — the same reason pressing pick block
     * by hand right after placing grabs the block you just placed. It also means the outcome is not
     * known synchronously, so success/failure is settled by {@link #tick()} rather than here.
     *
     * <p>Creative (FR-18) and survival (FR-19) both go through this one path; vanilla already
     * implements the mode-specific behaviour (instant full stack vs. inventory search), using only
     * standard packets (PKT-03/04). If Pick Block is unbound there is no key to press, so
     * {@link #attemptDirectRestock} reproduces the old direct behaviour as a fallback.
     */
    private static void attemptRestock(Minecraft client, Inventory inventory, Pending p, Item item) {
        // One attempt at a time per slot: while a press is still unresolved, another would be
        // redundant and would keep resetting the window so the FR-15 message could never fire.
        // Sustained misses therefore produce a steady ~1s attempt/message cadence rather than one
        // pick-block press per placement, which would flood a survival server with pick packets.
        if (restockWatches[p.pickSlot()] != null) {
            return;
        }
        // Armed before the pick so tick() can settle the outcome (success, or the FR-15 message).
        // The pick must land in the depleted slot, which vanilla achieves by picking into the
        // *selected* slot — so when restore-after-placement is on, the restore is handed to the
        // watch and deferred until the pick has resolved (see afterPlacement/tick).
        restockWatches[p.pickSlot()] = new RestockWatch(p.pickSlot(), item, RESTOCK_TIMEOUT_TICKS,
                ModConfig.get().restoreSlotAfterPlacement ? p.originalSlot() : NO_RESTORE);

        KeyMapping pickKey = client.options.keyPickItem;
        if (pickKey.isUnbound()) {
            // KeyMapping.click() dispatches to every mapping bound to the given key, so clicking
            // the "unknown" key would fire every unbound keybind in the game. Never do that.
            if (ModConfig.get().debugLogging) {
                TextureBuilder.LOGGER.info("[TextureBuilder] Pick Block is unbound; restocking {} directly "
                        + "(other pick-block mods will not participate).", item);
            }
            attemptDirectRestock(client, inventory, p, item);
            return;
        }

        aimPickAtPlacedBlock(client, p, item);
        KeyMapping.click(InputConstants.getKey(pickKey.saveString()));
        if (ModConfig.get().debugLogging) {
            TextureBuilder.LOGGER.info("[TextureBuilder] slot {} emptied; fired a Pick Block keypress to restock {}",
                    p.pickSlot() + 1, item);
        }
    }

    /**
     * Points the crosshair target at the block this placement just put down, so the pick resolves
     * the item that actually ran out.
     *
     * <p>Vanilla's pick has no "which item" input — {@code pickBlockOrEntity()} is private, takes no
     * arguments, and reads only {@link Minecraft#hitResult}. That field is recomputed once per tick
     * by {@code gameRenderer.pick()}, which runs <em>before</em> {@code handleKeybinds()}, so at this
     * point it still refers to the block the player aimed at — i.e. the block placed <em>against</em>,
     * not the new one. Left alone, the restock would pick that neighbour's item instead.
     *
     * <p>{@code hitResult} is a public field, and within one {@code handleKeybinds()} pass
     * {@code keyUse} is consumed at offset 581 and {@code keyPickItem} at 601 — so a press queued
     * here is consumed a few instructions later, still inside this tick, and reads whatever is in
     * {@code hitResult} at that moment. Overwriting it now therefore aims that one pick precisely.
     * Because the field is rebuilt from scratch on the next tick, nothing needs restoring, and
     * because other pick-block mods read the same field (ShulkerPickBlock resolves via
     * {@code getCloneItemStack} on {@code client.hitResult}), they are aimed correctly too.
     *
     * <p>No-op if the placed block cannot be located — better to let the pick use the real crosshair
     * target than to aim it at something that is not there.
     */
    private static void aimPickAtPlacedBlock(Minecraft client, Pending p, Item item) {
        BlockPos placedPos = findPlacedBlock(client, p.hit(), item);
        if (placedPos == null) {
            if (ModConfig.get().debugLogging) {
                TextureBuilder.LOGGER.info("[TextureBuilder] could not locate the placed {}; "
                        + "pick will use the live crosshair target", item);
            }
            return;
        }
        client.hitResult = new BlockHitResult(Vec3.atCenterOf(placedPos),
                p.hit().getDirection().getOpposite(), placedPos, false);
    }

    /**
     * Fallback used only when the Pick Block key is unbound: the pre-2026-08-07 behaviour, calling
     * the interaction manager directly. Restocks from vanilla sources only — pick-block hooks added
     * by other mods cannot run, because this does not go through {@code pickBlockOrEntity}.
     */
    private static void attemptDirectRestock(Minecraft client, Inventory inventory, Pending p, Item item) {
        MultiPlayerGameMode gameMode = client.gameMode;
        if (gameMode == null) {
            return;
        }
        if (gameMode.getPlayerMode() == GameType.CREATIVE) {
            ItemStack refill = new ItemStack(item);
            refill.setCount(refill.getMaxStackSize());
            inventory.setItem(p.pickSlot(), refill);
            // Same packet vanilla creative pick-block uses; hotbar slot i is screen slot 36 + i.
            gameMode.handleCreativeModeItemAdd(refill, 36 + p.pickSlot());
            return;
        }
        if (findMainInventoryMatch(inventory, item, p.pickSlot()) >= 0) {
            BlockPos placedPos = findPlacedBlock(client, p.hit(), item);
            if (placedPos != null) {
                gameMode.handlePickItemFromBlock(placedPos, false);
            }
        }
    }

    /**
     * Settles a pending restock (FR-13..FR-15). Called once per client tick.
     *
     * <p>The pick-block keypress is resolved asynchronously — vanilla consumes it on the next tick,
     * and in survival the server then has to move the item — so success cannot be observed inline.
     * This watches the slot until it refills, and only if it is still empty when the window expires
     * does it report the FR-15 "no more X found" message, leaving the slot empty and still in the
     * pool. Waiting the full window matters for correctness as much as for latency: the item may be
     * coming from a source this mod cannot see, such as a shulker box opened by another mod.
     */
    public static void tick() {
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        if (player == null) {
            reset();
            return;
        }
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < TextureBuilder.HOTBAR_SIZE; slot++) {
            RestockWatch watch = restockWatches[slot];
            if (watch == null) {
                continue;
            }
            if (!inventory.getItem(slot).isEmpty()) {
                restockWatches[slot] = null; // Restocked, by vanilla or by another pick-block mod.
                if (ModConfig.get().debugLogging) {
                    TextureBuilder.LOGGER.info("[TextureBuilder] slot {} restocked with {}",
                            slot + 1, inventory.getItem(slot).getItem());
                }
                finishDeferredRestore(client, player, watch);
            } else if (watch.ticksLeft() <= 1) {
                restockWatches[slot] = null;
                TextureBuilderHud.showMessage(Component.translatable("message.texturebuilder.no_more",
                        new ItemStack(watch.item()).getHoverName()));
                finishDeferredRestore(client, player, watch);
            } else {
                restockWatches[slot] = new RestockWatch(slot, watch.item(), watch.ticksLeft() - 1,
                        watch.restoreSlot());
            }
        }
    }

    /**
     * Performs the slot restore that {@link #afterPlacement()} handed off because a pick-block
     * restock was still in flight. No-op unless {@code restore_slot_after_placement} is enabled.
     */
    private static void finishDeferredRestore(Minecraft client, LocalPlayer player, RestockWatch watch) {
        Inventory inventory = player.getInventory();
        if (watch.restoreSlot() != NO_RESTORE && inventory.getSelectedSlot() != watch.restoreSlot()) {
            switchSlot(client, inventory, watch.restoreSlot());
        }
    }

    /** Drops all in-flight restock watches, e.g. when the session is disabled after an error. */
    public static void reset() {
        pending = null;
        java.util.Arrays.fill(restockWatches, null);
    }

    /** First main-inventory (non-hotbar, slots 9–35) slot holding {@code item}, or -1. */
    private static int findMainInventoryMatch(Inventory inventory, Item item, int excludeSlot) {
        for (int i = TextureBuilder.HOTBAR_SIZE; i < Inventory.INVENTORY_SIZE; i++) {
            if (i != excludeSlot && inventory.getItem(i).is(item)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Resolves where the block actually went: usually the face-adjacent position, but a replaceable
     * target (grass, snow layers, water) is replaced in place. Returns {@code null} if neither
     * position now holds the placed block (e.g. the placement failed server-side).
     */
    private static BlockPos findPlacedBlock(Minecraft client, BlockHitResult hit, Item item) {
        if (client.level == null || hit == null) {
            return null;
        }
        BlockPos adjacent = hit.getBlockPos().relative(hit.getDirection());
        if (client.level.getBlockState(adjacent).getBlock().asItem() == item) {
            return adjacent;
        }
        if (client.level.getBlockState(hit.getBlockPos()).getBlock().asItem() == item) {
            return hit.getBlockPos();
        }
        return null;
    }

    /** Snapshot of the configured pool for the selector (FR-08/FR-11). */
    private static List<SlotEntry> poolEntries() {
        ModConfig config = ModConfig.get();
        List<SlotEntry> entries = new ArrayList<>(TextureBuilder.HOTBAR_SIZE);
        for (int i = 0; i < TextureBuilder.HOTBAR_SIZE; i++) {
            entries.add(new SlotEntry(i, config.included[i], config.weights[i]));
        }
        return entries;
    }

    /**
     * Switches the active hotbar slot locally and tells the server with the standard carried-item
     * packet — exactly what pressing a number key sends (PKT-01), and the same idiom
     * ShulkerPickBlock uses.
     */
    private static void switchSlot(Minecraft client, Inventory inventory, int slot) {
        inventory.setSelectedSlot(slot);
        if (client.getConnection() != null) {
            client.getConnection().send(new ServerboundSetCarriedItemPacket(slot));
        }
    }
}
