package autismclient.mixin;

import autismclient.modules.PackModuleMovementUtil;
import autismclient.modules.PackFreecamState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Entity.class)
public class AutismEntityMovementMixin {
    @ModifyVariable(method = "move", at = @At("HEAD"), argsOnly = true)
    private Vec3 autism$modifyLocalPlayerMovement(Vec3 movement, MoverType type) {
        return PackModuleMovementUtil.onPlayerMove((Entity) (Object) this, type, movement);
    }

    @Inject(method = "turn", at = @At("HEAD"), cancellable = true)
    private void autism$freecamTurn(double deltaYaw, double deltaPitch, CallbackInfo ci) {
        if ((Object) this == net.minecraft.client.Minecraft.getInstance().player && PackFreecamState.isActive()) {
            PackFreecamState.turn(deltaYaw, deltaPitch);
            ci.cancel();
        }
    }
}
