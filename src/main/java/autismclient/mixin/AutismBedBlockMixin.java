package autismclient.mixin;

import autismclient.modules.PackModuleMovementUtil;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.BedBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BedBlock.class)
public class AutismBedBlockMixin {
    @Inject(method = "bounceUp", at = @At("HEAD"), cancellable = true)
    private void autism$noFallAntiBounce(Entity entity, CallbackInfo ci) {
        if (PackModuleMovementUtil.shouldCancelNoFallBounce(entity)) ci.cancel();
    }
}
