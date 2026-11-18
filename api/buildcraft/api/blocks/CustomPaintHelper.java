package buildcraft.api.blocks;

import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.Vec3;

import buildcraft.api.core.BCDebugging;
import buildcraft.api.core.BCLog;

/** Provides a simple way to paint a single block, iterating through all {@link ICustomPaintHandler}'s that are
 * registered for the block. */
public enum CustomPaintHelper {
    INSTANCE;

    /* If you want to test your class-based rotation registration then add the system property
     * "-Dbuildcraft.api.rotation.debug.class=true" to your launch. */
    private static final boolean DEBUG = BCDebugging.shouldDebugLog("api.painting");

    private final Map<Block, List<ICustomPaintHandler>> handlers = Maps.newIdentityHashMap();
    private final List<ICustomPaintHandler> allHandlers = Lists.newArrayList();

    /** Registers a handler that will be called LAST for ALL blocks, if all other paint handlers have returned PASS or
     * none are registered for that block. */
    public void registerHandlerForAll(ICustomPaintHandler handler) {
        if (DEBUG) {
            BCLog.logger.info("[api.painting] Adding a paint handler for ALL blocks (" + handler.getClass() + ")");
        }
        allHandlers.add(handler);
    }

    /** Register's a paint handler for every class of a given block. */
    public void registerHandlerForAll(Class<? extends Block> blockClass, ICustomPaintHandler handler) {
        for (Block block : BuiltInRegistries.BLOCK) {
            Class<? extends Block> foundClass = block.getClass();
            if (blockClass.isAssignableFrom(foundClass)) {
                if (DEBUG) {
                    BCLog.logger.info("[api.painting] Found an assignable block " + BuiltInRegistries.BLOCK.getKey(block) + " (" + foundClass + ") for " + blockClass);
                }
                registerHandlerInternal(block, handler);
            }
        }
    }

    public void registerHandler(Block block, ICustomPaintHandler handler) {
        if (registerHandlerInternal(block, handler)) {
            if (DEBUG) {
                BCLog.logger.info("[api.painting] Setting a paint handler for block " + BuiltInRegistries.BLOCK.getKey(block) + "(" + handler.getClass() + ")");
            }
        } else if (DEBUG) {
            BCLog.logger.info("[api.painting] Adding another paint handler for block " + BuiltInRegistries.BLOCK.getKey(block) + "(" + handler.getClass() + ")");
        }
    }

    private boolean registerHandlerInternal(Block block, ICustomPaintHandler handler) {
        if (!handlers.containsKey(block)) {
            List<ICustomPaintHandler> forBlock = Lists.newArrayList();
            forBlock.add(handler);
            handlers.put(block, forBlock);
            return true;
        } else {
            handlers.get(block).add(handler);
            return false;
        }
    }

    /** Attempts to paint a block at the given position. Basically iterates through all registered paint handlers. */
    public InteractionResult attemptPaintBlock(Level world, BlockPos pos, BlockState state, Vec3 hitPos, @Nullable Direction hitSide, @Nullable DyeColor paint) {
        Block block = state.getBlock();
        if (block instanceof ICustomPaintHandler) {
            return ((ICustomPaintHandler) block).attemptPaint(world, pos, state, hitPos, hitSide, paint);
        }
        List<ICustomPaintHandler> custom = handlers.get(block);
        if (custom == null || custom.isEmpty()) {
            return defaultAttemptPaint(world, pos, state, hitPos, hitSide, paint);
        }
        for (ICustomPaintHandler handler : custom) {
            InteractionResult result = handler.attemptPaint(world, pos, state, hitPos, hitSide, paint);
            if (result != InteractionResult.PASS) {
                return result;
            }
        }
        return defaultAttemptPaint(world, pos, state, hitPos, hitSide, paint);
    }

    private InteractionResult defaultAttemptPaint(Level world, BlockPos pos, BlockState state, Vec3 hitPos, Direction hitSide, @Nullable DyeColor paint) {
        for (ICustomPaintHandler handler : allHandlers) {
            InteractionResult result = handler.attemptPaint(world, pos, state, hitPos, hitSide, paint);
            if (result != InteractionResult.PASS) {
                return result;
            }
        }
        if (paint == null) {
            return InteractionResult.FAIL;
        }
        if (recolorBlock(world, pos, state, paint)) {
            return InteractionResult.SUCCESS;
        } else {
            return InteractionResult.FAIL;
        }
    }

    /** Replacement for the removed Forge Block#recolorBlock. */
    private static boolean recolorBlock(Level world, BlockPos pos, BlockState state, DyeColor paint) {
        // 1. Blocks that store their color in a DyeColor block state property (mostly modded blocks)
        for (Property<?> prop : state.getProperties()) {
            if (prop.getValueClass() == DyeColor.class) {
                @SuppressWarnings("unchecked")
                Property<DyeColor> colorProp = (Property<DyeColor>) prop;
                if (state.getValue(colorProp) == paint || !colorProp.getPossibleValues().contains(paint)) {
                    return false;
                }
                return world.setBlock(pos, state.setValue(colorProp, paint), Block.UPDATE_ALL);
            }
        }

        // 2. Blocks where each color is its own block (white_wool -> red_wool, etc.)
        // Skip block entities (beds, banners, shulker boxes) so their data isn't lost and two-block beds aren't split.
        if (state.hasBlockEntity()) {
            return false;
        }
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        String path = id.getPath();

        // Longest match first so "light_blue_" wins over "blue_"
        DyeColor current = null;
        for (DyeColor color : DyeColor.values()) {
            String prefix = color.getSerializedName() + "_";
            if (path.startsWith(prefix) && (current == null || prefix.length() > current.getSerializedName().length() + 1)) {
                current = color;
            }
        }
        if (current == null || current == paint) {
            return false;
        }

        String newPath = paint.getSerializedName() + path.substring(current.getSerializedName().length());
        ResourceLocation newId = ResourceLocation.fromNamespaceAndPath(id.getNamespace(), newPath);
        Block newBlock = BuiltInRegistries.BLOCK.get(newId);
        if (newBlock == Blocks.AIR) { // registry falls back to air for unknown ids
            return false;
        }
        return world.setBlock(pos, newBlock.withPropertiesOf(state), Block.UPDATE_ALL);
    }
}
