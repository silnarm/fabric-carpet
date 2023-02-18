package carpet.mixins;

import carpet.CarpetSettings;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.level.block.entity.Hopper;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import carpet.helpers.HopperCounter;
import carpet.utils.WoolTool;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * The {@link Mixin} which removes items in a hopper if it points into a wool counter, and calls {@link HopperCounter#add}
 */
@Mixin(HopperBlockEntity.class)
public abstract class HopperBlockEntity_counterMixin extends RandomizableContainerBlockEntity
{
    protected HopperBlockEntity_counterMixin(BlockEntityType<?> blockEntityType, BlockPos blockPos, BlockState blockState) {
        super(blockEntityType, blockPos, blockState);
    }

    @Shadow public abstract int getContainerSize();

    @Shadow public abstract void setItem(int slot, ItemStack stack);

    /**
     * A method to remove items from hoppers pointing into wool and count them via {@link HopperCounter#add} method
     */
    @Inject(method = "ejectItems", at = @At("HEAD"), cancellable = true)
    private static void onInsert(Level world, BlockPos blockPos, BlockState blockState, Container inventory, CallbackInfoReturnable<Boolean> cir)
    {
        if (!CarpetSettings.hopperCounters && !CarpetSettings.hopperCountersLimited) {
            return;
        }
        DyeColor wool_color = WoolTool.getWoolColorAtPosition(world, blockPos.relative(blockState.getValue(HopperBlock.FACING)));
        if (wool_color == null) {
            return;
        }
        for (int i = 0; i < inventory.getContainerSize(); ++i) {
            if (!inventory.getItem(i).isEmpty()) {
                if (CarpetSettings.hopperCountersLimited) {
                    ItemStack pushedItem = inventory.removeItem(i, 1);
                    HopperCounter.COUNTERS.get(wool_color).add(world.getServer(), pushedItem);
                    break;
                } else if (CarpetSettings.hopperCounters) {
                    ItemStack itemstack = inventory.getItem(i);//.copy();
                    HopperCounter.COUNTERS.get(wool_color).add(world.getServer(), itemstack);
                    inventory.setItem(i, ItemStack.EMPTY);
                }
            }
        }
        cir.setReturnValue(true);
    }

    private static final AABB itemFrameCheckVolume = new AABB(0.125, 2.0, 0.125, 0.875, 2.125, 0.875);

    /**
     * A method to provide items for hoppers with wool on top of them to suck in and count them via {@link HopperCounter#add} method
     * Needs to be an item frame holding item type to provide on top of the wool. Hopper is given the opportunity to suck up to max
     * stack size of item type.
     */
    @Inject(method = "suckInItems", at = @At("HEAD"), cancellable = true)
    private static void onSuck(Level level, Hopper hopper, CallbackInfoReturnable<Boolean> cir)
    {
        if (!CarpetSettings.hopperInputCounters) {
            return;
        }
        BlockPos blockPos = new BlockPos(hopper.getLevelX(), hopper.getLevelY(), hopper.getLevelZ());
        DyeColor wool_color = WoolTool.getWoolColorAtPosition(level, blockPos.relative(Direction.UP));
        if (wool_color == null) {
            return;
        }
        List<ItemFrame> frames = level.getEntitiesOfClass(ItemFrame.class, itemFrameCheckVolume.move(blockPos), EntitySelector.ENTITY_STILL_ALIVE);
        if (frames.isEmpty()) {
            return;
        }
        ItemFrame itemFrame = frames.remove(0);
        ItemStack itemStack = itemFrame.getItem().copy();
        if (itemStack.isEmpty()) {
            return;
        }
        itemStack.setCount(itemStack.getItem().getMaxStackSize());
        ItemStack itemStack2 = HopperBlockEntity.addItem((Container)null, hopper, itemStack.copy(), (Direction)null);
        if (itemStack2.getCount() < itemStack.getCount()) {
            var suckCount = itemStack.getCount() - itemStack2.getCount();
            itemStack.setCount(suckCount);
            HopperCounter.COUNTERS.get(wool_color).addInput(level.getServer(), itemStack);
            cir.setReturnValue(true);
        }
    }
}
