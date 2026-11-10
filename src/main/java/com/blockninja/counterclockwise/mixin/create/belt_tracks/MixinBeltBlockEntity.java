package com.blockninja.counterclockwise.mixin.create.belt_tracks;

import com.blockninja.counterclockwise.ships.BeltForceInducer;
import com.simibubi.create.content.kinetics.belt.BeltBlockEntity;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BeltBlockEntity.class)
public abstract class MixinBeltBlockEntity {
    @Inject(
            method = "tick",
            at = @At("HEAD"),
            remap = false
    )
    private void tick(CallbackInfo ci) {
        BeltBlockEntity self = (BeltBlockEntity) (Object) this;
        Level level = self.getLevel();

        if (level == null || level.isClientSide) return;

        BeltForceInducer.updateSegment(self);
    }
}
