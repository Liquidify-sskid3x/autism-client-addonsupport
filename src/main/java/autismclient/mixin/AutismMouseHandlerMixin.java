package autismclient.mixin;

import autismclient.modules.PackModuleRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
public abstract class AutismMouseHandlerMixin {
    @Shadow @Final private Minecraft minecraft;
    @Unique private float autism$turnStartYaw;
    @Unique private float autism$turnStartPitch;
    @Unique private boolean autism$turnHadPlayer;

    @Inject(method = "turnPlayer", at = @At("HEAD"))
    private void autism$beforeTurnPlayer(double deltaTime, CallbackInfo ci) {
        autism$turnHadPlayer = minecraft != null && minecraft.player != null;
        if (autism$turnHadPlayer) {
            autism$turnStartYaw = minecraft.player.getYRot();
            autism$turnStartPitch = minecraft.player.getXRot();
        }
    }

    @Inject(method = "turnPlayer", at = @At("TAIL"))
    private void autism$afterTurnPlayer(double deltaTime, CallbackInfo ci) {
        if (!autism$turnHadPlayer || minecraft == null || minecraft.player == null) return;
        double deltaYaw = minecraft.player.getYRot() - autism$turnStartYaw;
        double deltaPitch = minecraft.player.getXRot() - autism$turnStartPitch;
        if (Math.abs(deltaYaw) > 1.0E-6 || Math.abs(deltaPitch) > 1.0E-6) {
            PackModuleRegistry.onMouseRotation(deltaYaw, deltaPitch);
        }
    }
}
