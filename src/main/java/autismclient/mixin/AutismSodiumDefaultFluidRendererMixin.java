package autismclient.mixin;

import autismclient.modules.PackModuleRenderUtil;
import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Method;

@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.DefaultFluidRenderer", remap = false)
public abstract class AutismSodiumDefaultFluidRendererMixin {
    @Unique private int autism$xrayAlpha = -1;
    @Unique private boolean autism$rewritingXrayFluidQuad;

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void autism$xraySodiumFluidStart(@Coerce Object level, BlockState blockState, FluidState fluidState, BlockPos blockPos, BlockPos offset, @Coerce Object collector, @Coerce Object buffers, @Coerce Object material, @Coerce Object colorProvider, FluidModel fluidModel, CallbackInfo ci) {
        autism$xrayAlpha = PackModuleRenderUtil.xrayFluidAlpha(fluidState, blockPos);
        if (autism$xrayAlpha == 0) ci.cancel();
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void autism$xraySodiumFluidEnd(@Coerce Object level, BlockState blockState, FluidState fluidState, BlockPos blockPos, BlockPos offset, @Coerce Object collector, @Coerce Object buffers, @Coerce Object material, @Coerce Object colorProvider, FluidModel fluidModel, CallbackInfo ci) {
        autism$xrayAlpha = -1;
    }

    @Inject(method = "writeQuad", at = @At("HEAD"), cancellable = true)
    private void autism$xraySodiumFluidMaterial(@Coerce Object buffers, @Coerce Object collector, @Coerce Object material, BlockPos offset, @Coerce Object quad, @Coerce Object facing, boolean flip, CallbackInfo ci) {
        int alpha = autism$xrayAlpha;
        if (autism$rewritingXrayFluidQuad || alpha < 0) return;

        PackModuleRenderUtil.applySodiumQuadAlpha(quad, alpha);
        if (alpha >= 255) return;

        Object translucent = PackModuleRenderUtil.sodiumTranslucentMaterial(material);
        if (translucent == null || translucent == material) return;

        autism$rewritingXrayFluidQuad = true;
        try {
            autism$invokeSodiumWriteQuad(buffers, collector, translucent, offset, quad, facing, flip);
        } finally {
            autism$rewritingXrayFluidQuad = false;
        }
        ci.cancel();
    }

    @Unique
    private void autism$invokeSodiumWriteQuad(Object buffers, Object collector, Object material, BlockPos offset, Object quad, Object facing, boolean flip) {
        for (Class<?> type = getClass(); type != null; type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) {
                if (!method.getName().equals("writeQuad") || method.getParameterCount() != 7) continue;
                try {
                    method.setAccessible(true);
                    method.invoke(this, buffers, collector, material, offset, quad, facing, flip);
                    return;
                } catch (Throwable ignored) {
                    return;
                }
            }
        }
    }
}
