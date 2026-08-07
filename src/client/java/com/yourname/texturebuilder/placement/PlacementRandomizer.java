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

/**
 * The switch-and-restore engine behind each placement (FR-11..FR-19, SRS §1 design note).
 *
 * <p>{@link #beforePlacement} runs at the HEAD of {@code MultiPlayerGameMode.useItemOn}: it draws a
 * weighted random slot from the configured pool, records the player's currently-selected slot, and
 * switches to the pick — sending the same {@code ServerboundSetCarriedItemPacket} pressing a number
 * key would (PKT-01). Vanilla placement then proceeds completely unmodified (PKT-02).
 * {@link #afterPlacement} runs at TAIL of the same call: it fires the restock attempt if the pick
 * emptied the slot (FR-13), then restores the originally-selected slot — all within the same client
 * tick, so nothing ever observes an intermediate hotbar state between placements (FR-12, NFR-03/06).
 *
 * <p>Every entry point is wrapped so that an unexpected exception disables the feature for the rest
 * of the session instead of leaving placement in an inconsistent state (NFR-04); the restore step
 * runs in a {@code finally} so the player's slot comes back even on the failure path.
 */
public final class PlacementRandomizer {

    private static final Random RANDOM = new Random();

    /** In-flight state between the HEAD and TAIL of a single {@code useItemOn} call. */
    private record Pending(int originalSlot, int pickSlot, Item pickedItem, boolean hadItems,
                           BlockHitResult hit) {
    }

    private static Pending pending;

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
     * TAIL of the same {@code useItemOn} call: restock if the pick just emptied its slot
     * (FR-13..FR-15, FR-18/FR-19), then restore the originally-held slot (FR-12).
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
                attemptRestock(client, player, inventory, p);
            }
        } catch (RuntimeException e) {
            TextureBuilderClient.failSession(e);
        } finally {
            // Always restore, even if the restock attempt failed (NFR-04/NFR-06). Sent after the
            // restock pick so a survival server processes the pick against the depleted slot.
            if (inventory.getSelectedSlot() != p.originalSlot()) {
                switchSlot(client, inventory, p.originalSlot());
            }
        }
    }

    /**
     * FR-13: a single pick-block attempt targeting the block that was just placed.
     *
     * <ul>
     *   <li><b>Creative</b> (FR-18) — instantly refill to a full stack, mirroring vanilla creative
     *       pick-block: set the stack locally and send the standard creative slot packet
     *       ({@code handleCreativeModeItemAdd}, hotbar screen-slot id {@code 36 + slot}).</li>
     *   <li><b>Survival</b> (FR-19) — if a matching stack exists in the main inventory, fire
     *       vanilla's own pick-block entry point {@code handlePickItemFromBlock} at the placed
     *       position: the server moves the match into the (still selected, now empty) slot via the
     *       standard {@code ServerboundPickItemFromBlockPacket} — no custom payloads (PKT-03/04).
     *       A match sitting only in another hotbar slot is left alone (vanilla would merely switch
     *       the selection, which the restore would immediately undo).</li>
     *   <li><b>Nothing anywhere</b> (FR-15) — show the disappearing "no more X found" message and
     *       leave the slot empty and still included in the pool.</li>
     * </ul>
     */
    private static void attemptRestock(Minecraft client, LocalPlayer player, Inventory inventory, Pending p) {
        MultiPlayerGameMode gameMode = client.gameMode;
        if (gameMode == null) {
            return;
        }
        Item item = p.pickedItem();

        if (gameMode.getPlayerMode() == GameType.CREATIVE) {
            ItemStack refill = new ItemStack(item);
            refill.setCount(refill.getMaxStackSize());
            inventory.setItem(p.pickSlot(), refill);
            // Same packet vanilla creative pick-block uses; hotbar slot i is screen slot 36 + i.
            gameMode.handleCreativeModeItemAdd(refill, 36 + p.pickSlot());
            return;
        }

        int mainInventoryMatch = findMainInventoryMatch(inventory, item, p.pickSlot());
        if (mainInventoryMatch >= 0) {
            BlockPos placedPos = findPlacedBlock(client, p.hit(), item);
            if (placedPos != null) {
                gameMode.handlePickItemFromBlock(placedPos, false);
            } else if (ModConfig.get().debugLogging) {
                TextureBuilder.LOGGER.info("[TextureBuilder] restock skipped: placed {} not found at hit position",
                        item);
            }
            return;
        }

        if (!hasAnywhere(inventory, item, p.pickSlot())) {
            TextureBuilderHud.showMessage(Component.translatable("message.texturebuilder.no_more",
                    new ItemStack(item).getHoverName()));
        }
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

    /** Whether {@code item} exists anywhere in the 36 player inventory slots besides {@code excludeSlot}. */
    private static boolean hasAnywhere(Inventory inventory, Item item, int excludeSlot) {
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            if (i != excludeSlot && inventory.getItem(i).is(item)) {
                return true;
            }
        }
        return false;
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
