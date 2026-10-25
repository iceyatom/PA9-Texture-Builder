package com.yourname.texturebuilder.placement;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.random.RandomGenerator;

import com.yourname.texturebuilder.TextureBuilder;
import com.yourname.texturebuilder.config.ModConfig;
import com.yourname.texturebuilder.hud.TextureBuilderHud;
import com.yourname.texturebuilder.util.GradientHelper;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * Gradient mode's game-facing side: the "Set Gradient Point" keybind cycle, which saved points
 * apply in the current dimension, and the slot pick for one placement. The math itself lives in
 * {@link GradientHelper}.
 *
 * <p>Points are stored in {@link ModConfig} with the dimension they were set in, and the TOML is
 * written every time they change. Points saved in another dimension are ignored (not applied) but
 * kept, until the player starts a new start point in the current dimension.
 */
public final class GradientController {

    /** What the stored points mean in the current dimension. */
    public enum State {
        /** No start point. */
        NO_POINTS,
        /** Points exist but belong to a different dimension, so they are ignored here. */
        OTHER_DIMENSION,
        /** A start point but no end point yet. */
        START_ONLY,
        /** Both points set, but no single longest axis (a tie, or identical points). */
        NO_AXIS,
        /** Both points set with a clear axis: gradient placement is active. */
        VALID
    }

    private GradientController() {
    }

    /** The current level's dimension id, e.g. {@code minecraft:overworld}, or "" with no level. */
    public static String currentDimension(Minecraft client) {
        return client.level == null ? "" : client.level.dimension().identifier().toString();
    }

    /**
     * The stored points' state here. With no level loaded (e.g. the config screen opened from the
     * title screen through Mod Menu) the dimension check is skipped.
     */
    public static State state(Minecraft client) {
        ModConfig cfg = ModConfig.get();
        if (cfg.gradientStart == null) {
            return State.NO_POINTS;
        }
        if (client.level != null && !currentDimension(client).equals(cfg.gradientDimension)) {
            return State.OTHER_DIMENSION;
        }
        if (cfg.gradientEnd == null) {
            return State.START_ONLY;
        }
        return axis(cfg) == GradientHelper.AXIS_NONE ? State.NO_AXIS : State.VALID;
    }

    /** The axis of the stored points, or {@link GradientHelper#AXIS_NONE} if either is unset. */
    public static int axis(ModConfig cfg) {
        int[] s = cfg.gradientStart;
        int[] e = cfg.gradientEnd;
        if (s == null || e == null) {
            return GradientHelper.AXIS_NONE;
        }
        return GradientHelper.selectAxis(s[0], s[1], s[2], e[0], e[1], e[2]);
    }

    /**
     * "Set Gradient Point" keybind: 1st press sets the start, 2nd the end, 3rd clears both, then
     * the cycle repeats. The cycle position comes from the stored points, so it survives restarts.
     * If the stored points belong to another dimension, the press starts a fresh cycle here: it
     * sets a new start and drops the old end.
     */
    public static void onSetPointPressed(Minecraft client) {
        if (client.level == null || client.player == null) {
            return;
        }
        ModConfig cfg = ModConfig.get();
        String dimension = currentDimension(client);
        boolean pointsHere = cfg.gradientStart != null && dimension.equals(cfg.gradientDimension);

        if (pointsHere && cfg.gradientEnd != null) {
            cfg.clearGradientPoints();
            cfg.save();
            TextureBuilderHud.showMessage(Component.translatable("message.texturebuilder.gradient.cleared"));
            return;
        }

        BlockPos target = lookedAtBlock(client);
        if (target == null) {
            TextureBuilderHud.showMessage(Component.translatable("message.texturebuilder.gradient.no_target"));
            return;
        }
        int[] point = {target.getX(), target.getY(), target.getZ()};

        if (!pointsHere) {
            cfg.gradientStart = point;
            cfg.gradientEnd = null;
            cfg.gradientDimension = dimension;
            cfg.save();
            TextureBuilderHud.showMessage(Component.translatable("message.texturebuilder.gradient.start_set",
                    point[0], point[1], point[2]));
            return;
        }

        cfg.gradientEnd = point;
        cfg.save();
        int axis = axis(cfg);
        if (axis == GradientHelper.AXIS_NONE) {
            TextureBuilderHud.showMessage(noAxisMessage(cfg));
        } else {
            // Green confirmation, the counterpart of the gold refusal warnings: the gradient is
            // ready, along this axis, spanning this many blocks (both endpoints included).
            int[] s = cfg.gradientStart;
            long length = GradientHelper.largestDifference(s[0], s[1], s[2], point[0], point[1], point[2]) + 1;
            TextureBuilderHud.showMessage(Component.translatable("message.texturebuilder.gradient.end_set",
                            point[0], point[1], point[2], GradientHelper.axisName(axis), length)
                    .withStyle(ChatFormatting.GREEN));
        }
    }

    /**
     * The hotbar slot gradient mode picks for this placement, or -1 if it can't pick. Callers check
     * {@link #blockedReason} first, which blocks every case that returns -1 here, so the caller's
     * Texture-pool fallback is only a safety net.
     */
    public static int selectSlot(Minecraft client, BlockHitResult hit, RandomGenerator random) {
        if (state(client) != State.VALID || hit == null) {
            return -1;
        }
        ModConfig cfg = ModConfig.get();
        int[] palette = cfg.gradientPalette();
        if (palette.length < GradientHelper.MIN_ENTRIES || cfg.gradientBoundaries.length != palette.length - 1) {
            return -1;
        }
        BlockPos placed = predictPlacedPos(client, hit);
        if (placed == null) {
            return -1;
        }
        int axis = axis(cfg);
        int p = coordinate(placed, axis);
        double t = GradientHelper.positionT(p, cfg.gradientStart[axis], cfg.gradientEnd[axis]);
        int index = GradientHelper.selectIndex(t, cfg.gradientBoundaries,
                cfg.gradientTransition / 100.0D, random.nextDouble());
        int slot = palette[index];
        if (cfg.debugLogging) {
            TextureBuilder.LOGGER.info("[TextureBuilder] gradient draw at {}: axis {}, t={}, entry {} -> slot {}",
                    placed.toShortString(), GradientHelper.axisName(axis), String.format("%.3f", t), index + 1, slot + 1);
        }
        return slot;
    }

    /**
     * Why Gradient mode must not place right now, as a gold warning for the HUD, or {@code null}
     * when it can. Every point problem, and fewer than 2 included slots, blocks placement rather
     * than falling back to Texture mode's mix (user's choice, 2026-10-25). Point problems are
     * reported first.
     */
    public static Component blockedReason(Minecraft client) {
        ModConfig cfg = ModConfig.get();
        MutableComponent reason = switch (state(client)) {
            case NO_POINTS -> Component.translatable("message.texturebuilder.gradient.blocked.no_points");
            case START_ONLY -> Component.translatable("message.texturebuilder.gradient.blocked.no_end");
            case OTHER_DIMENSION -> Component.translatable("message.texturebuilder.gradient.blocked.other_dimension",
                    cfg.gradientDimension);
            case NO_AXIS -> noAxisMessage(cfg);
            case VALID -> cfg.gradientPalette().length < GradientHelper.MIN_ENTRIES
                    ? Component.translatable("message.texturebuilder.gradient.blocked.need_two")
                    : null;
        };
        return reason == null ? null : reason.withStyle(ChatFormatting.GOLD);
    }

    /** Per block class: whether it overrides {@code useWithoutItem}, i.e. has its own right-click. */
    private static final Map<Class<?>, Boolean> USE_ACTION_CACHE = new ConcurrentHashMap<>();

    /**
     * Whether right-clicking this block does something of its own (open a chest, toggle a door,
     * press a button), judged by whether its class overrides {@code BlockBehaviour.useWithoutItem}.
     * Vanilla runs that before placing the held item (unless the player is sneaking with an item),
     * so when it acts, nothing is placed and the click can safely go through while placement is
     * blocked.
     *
     * <p>Name-based reflection is safe because 26.x runs with Mojmap names in both dev and
     * production. A few blocks override it but sometimes pass (e.g. redstone ore lighting up); a
     * click on those still places the held block, since the client can't know in advance.
     */
    public static boolean hasUseAction(Block block) {
        return USE_ACTION_CACHE.computeIfAbsent(block.getClass(), cls -> {
            for (Class<?> c = cls; c != null && c != BlockBehaviour.class; c = c.getSuperclass()) {
                for (Method m : c.getDeclaredMethods()) {
                    if (m.getName().equals("useWithoutItem") && m.getParameterCount() == 5) {
                        return true;
                    }
                }
            }
            return false;
        });
    }

    /**
     * Where this placement will put the block. This runs before vanilla places it, so it uses the
     * same rule the restock code applies afterwards: the face-adjacent position, or the clicked
     * position itself when that block is replaceable (grass, snow layers, water).
     */
    static BlockPos predictPlacedPos(Minecraft client, BlockHitResult hit) {
        if (client.level == null) {
            return null;
        }
        BlockPos clicked = hit.getBlockPos();
        if (client.level.getBlockState(clicked).canBeReplaced()) {
            return clicked;
        }
        return clicked.relative(hit.getDirection());
    }

    private static int coordinate(BlockPos pos, int axis) {
        return switch (axis) {
            case GradientHelper.AXIS_X -> pos.getX();
            case GradientHelper.AXIS_Y -> pos.getY();
            default -> pos.getZ();
        };
    }

    private static BlockPos lookedAtBlock(Minecraft client) {
        HitResult hit = client.hitResult;
        if (hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK) {
            return blockHit.getBlockPos();
        }
        return null;
    }

    /**
     * One-line summary of the points and axis, for the gradient screen and
     * {@code /texturebuilder status}.
     */
    public static Component describePoints(Minecraft client) {
        ModConfig cfg = ModConfig.get();
        return switch (state(client)) {
            case NO_POINTS -> Component.translatable("text.texturebuilder.gradient.points.none")
                    .withStyle(ChatFormatting.GRAY);
            case OTHER_DIMENSION -> Component.translatable("text.texturebuilder.gradient.points.other_dimension",
                    cfg.gradientDimension).withStyle(ChatFormatting.GOLD);
            case START_ONLY -> Component.translatable("text.texturebuilder.gradient.points.start_only",
                    point(cfg.gradientStart));
            case NO_AXIS -> {
                MutableComponent tie = tieDetail(cfg);
                yield (tie == null
                        ? Component.translatable("text.texturebuilder.gradient.points.same",
                                point(cfg.gradientStart), point(cfg.gradientEnd))
                        : Component.translatable("text.texturebuilder.gradient.points.unclear",
                                point(cfg.gradientStart), point(cfg.gradientEnd), tie))
                        .withStyle(ChatFormatting.RED);
            }
            case VALID -> Component.translatable("text.texturebuilder.gradient.points.valid",
                    point(cfg.gradientStart), point(cfg.gradientEnd),
                    GradientHelper.axisName(axis(cfg))).withStyle(ChatFormatting.GREEN);
        };
    }

    private static String point(int[] p) {
        return p[0] + " " + p[1] + " " + p[2];
    }

    /**
     * HUD message for points with no single longest axis: "Gradient axis unclear: X and Z both
     * differ by 12 blocks" for a tie, or "Gradient start and end are the same" when the same block
     * was picked twice (the only case where that wording is accurate).
     */
    private static MutableComponent noAxisMessage(ModConfig cfg) {
        MutableComponent tie = tieDetail(cfg);
        return tie == null
                ? Component.translatable("message.texturebuilder.gradient.same")
                : Component.translatable("message.texturebuilder.gradient.unclear", tie);
    }

    /**
     * "X and Z both differ by 12 blocks" or "X, Y and Z all differ by 12 blocks" for tied points,
     * or {@code null} when the points are identical (or either is unset).
     */
    private static MutableComponent tieDetail(ModConfig cfg) {
        int[] s = cfg.gradientStart;
        int[] e = cfg.gradientEnd;
        if (s == null || e == null) {
            return null;
        }
        int mask = GradientHelper.tiedAxes(s[0], s[1], s[2], e[0], e[1], e[2]);
        if (mask == 0) {
            return null;
        }
        long blocks = GradientHelper.largestDifference(s[0], s[1], s[2], e[0], e[1], e[2]);
        Component distance = blocks == 1
                ? Component.translatable("text.texturebuilder.gradient.distance.one")
                : Component.translatable("text.texturebuilder.gradient.distance.many", blocks);
        if (Integer.bitCount(mask) == 3) {
            return Component.translatable("text.texturebuilder.gradient.tie.all", distance);
        }
        String first = (mask & GradientHelper.MASK_X) != 0 ? "X" : "Y";
        String second = (mask & GradientHelper.MASK_Z) != 0 ? "Z" : "Y";
        return Component.translatable("text.texturebuilder.gradient.tie.two", first, second, distance);
    }
}
