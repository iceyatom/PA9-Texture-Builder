package com.yourname.texturebuilder.util;

import java.util.List;
import java.util.random.RandomGenerator;

/**
 * Weighted-selection and restock-planning logic, isolated from mixin/screen classes so it can be
 * unit-tested without a running game instance (NFR-12, SRS §7.5). Pure Java — no Minecraft,
 * event-bus, or render dependencies.
 */
public final class TextureBuilderHelper {

    /**
     * One hotbar slot as the selector sees it: pool membership, raw weight, and whether the slot
     * currently holds anything. Empty slots stay in the pool and are chosen at their full weight —
     * a pick that lands on one is a deliberate miss (FR-16).
     */
    public record SlotEntry(int index, boolean included, int weight) {
    }

    private TextureBuilderHelper() {
    }

    /**
     * Chooses one included hotbar slot by weighted random selection (FR-11): each slot's
     * probability is its weight divided by the sum of all included slots' weights — i.e. raw
     * weights are normalized proportionally, so they need not sum to 100 (FR-09).
     *
     * @return the chosen slot index (0–8), or {@code -1} if no included slot has a positive weight
     */
    public static int selectSlot(List<SlotEntry> slots, RandomGenerator random) {
        long total = 0;
        for (SlotEntry slot : slots) {
            if (slot.included() && slot.weight() > 0) {
                total += slot.weight();
            }
        }
        if (total <= 0) {
            return -1;
        }
        long roll = random.nextLong(total);
        for (SlotEntry slot : slots) {
            if (slot.included() && slot.weight() > 0) {
                roll -= slot.weight();
                if (roll < 0) {
                    return slot.index();
                }
            }
        }
        // Unreachable when the two passes see the same list; kept as a safe fallback.
        return -1;
    }

    /** Sum of all included slots' raw weights (the FR-09 running total). */
    public static int includedWeightTotal(List<SlotEntry> slots) {
        int total = 0;
        for (SlotEntry slot : slots) {
            if (slot.included()) {
                total += Math.max(0, slot.weight());
            }
        }
        return total;
    }

    /**
     * The effective (normalized) selection percentage of one slot, for the config screen's
     * {@code auto_normalize_display} column (FR-09). Returns 0 for excluded or zero-weight slots.
     */
    public static double effectivePercent(List<SlotEntry> slots, int index) {
        int total = includedWeightTotal(slots);
        if (total <= 0) {
            return 0.0D;
        }
        for (SlotEntry slot : slots) {
            if (slot.index() == index) {
                return slot.included() && slot.weight() > 0
                        ? slot.weight() * 100.0D / total
                        : 0.0D;
            }
        }
        return 0.0D;
    }
}
