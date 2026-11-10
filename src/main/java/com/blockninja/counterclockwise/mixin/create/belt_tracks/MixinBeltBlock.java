package com.blockninja.counterclockwise.mixin.create.belt_tracks;

import com.blockninja.counterclockwise.ships.BeltForceInducer;
import com.simibubi.create.content.kinetics.belt.BeltBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BeltBlock.class)
public abstract class MixinBeltBlock {
    @Inject(
            method = "onRemove",
            at = @At("HEAD")
    )
    private void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving, CallbackInfo ci) {
        if (level == null || level.isClientSide) return;

        BeltForceInducer.removeSegment(level, pos);
    }
}
