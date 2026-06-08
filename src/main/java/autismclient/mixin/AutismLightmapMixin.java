package autismclient.mixin;

import autismclient.modules.PackModuleRenderUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import net.minecraft.client.renderer.Lightmap;
import net.minecraft.client.renderer.state.LightmapRenderState;
import net.minecraft.util.profiling.Profiler;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Lightmap.class)
public abstract class AutismLightmapMixin {
    @Shadow
    @Final
    private GpuTexture texture;

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void autism$fullbrightLightmap(LightmapRenderState renderState, CallbackInfo ci) {
        if (!PackModuleRenderUtil.shouldUseBrightLightmap()) return;
        var profiler = Profiler.get();
        profiler.push("autism_lightmap");
        RenderSystem.getDevice().createCommandEncoder().clearColorTexture(texture, -1);
        profiler.pop();
        ci.cancel();
    }
}
