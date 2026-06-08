package autismclient.mixin;

import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public class AutismLevelRendererGuardMixin {
    @Inject(method = "update", at = @At("HEAD"), cancellable = true)
    private void autism$skipUpdateWithoutPlayer(Camera camera, CallbackInfo ci) {
        if (Minecraft.getInstance().player == null) ci.cancel();
    }

    @Inject(method = "extractLevel", at = @At("HEAD"), cancellable = true)
    private void autism$skipExtractWithoutPlayer(DeltaTracker deltaTracker, Camera camera, float deltaPartialTick, CallbackInfo ci) {
        if (Minecraft.getInstance().player == null) ci.cancel();
    }
}
