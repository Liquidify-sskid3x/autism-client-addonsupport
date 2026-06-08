package autismclient.mixin;

import autismclient.modules.GoldenLeverModule;
import autismclient.modules.PackModuleRenderUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Method;

@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderer", remap = false)
public abstract class AutismSodiumBlockRendererMixin {
    @Unique private int autism$xrayAlpha = -1;
    @Unique private boolean autism$goldenLever;
    @Unique private boolean autism$rebufferingXrayQuad;

    @Inject(method = "renderModel", at = @At("HEAD"), cancellable = true)
    private void autism$xraySodiumBlockStart(@Coerce Object model, BlockState state, BlockPos pos, BlockPos origin, CallbackInfo ci) {
        boolean xrayActive = PackModuleRenderUtil.xrayActive();
        boolean goldenLeverActive = GoldenLeverModule.isStylingActive();
        if (!xrayActive && !goldenLeverActive) {
            autism$xrayAlpha = -1;
            autism$goldenLever = false;
            return;
        }
        autism$xrayAlpha = xrayActive ? PackModuleRenderUtil.xrayAlpha(state, pos) : -1;
        autism$goldenLever = goldenLeverActive && GoldenLeverModule.shouldStyle(state);
        if (autism$xrayAlpha == 0) ci.cancel();
    }

    @Inject(method = "renderModel", at = @At("RETURN"))
    private void autism$xraySodiumBlockEnd(@Coerce Object model, BlockState state, BlockPos pos, BlockPos origin, CallbackInfo ci) {
        autism$xrayAlpha = -1;
        autism$goldenLever = false;
    }

    @Inject(method = "bufferQuad", at = @At("HEAD"), cancellable = true)
    private void autism$xraySodiumBlockMaterial(@Coerce Object quad, float[] brightnesses, @Coerce Object material, CallbackInfo ci) {
        int alpha = autism$xrayAlpha;
        if (autism$rebufferingXrayQuad) return;

        if (autism$goldenLever) PackModuleRenderUtil.applySodiumQuadTint(quad, GoldenLeverModule.GOLD_TINT);
        if (alpha < 0) return;
        PackModuleRenderUtil.applySodiumQuadAlpha(quad, alpha);
        if (alpha >= 255) return;

        Object translucent = PackModuleRenderUtil.sodiumTranslucentMaterial(material);
        if (translucent == null || translucent == material) return;

        autism$rebufferingXrayQuad = true;
        try {
            autism$invokeSodiumBufferQuad(quad, brightnesses, translucent);
        } finally {
            autism$rebufferingXrayQuad = false;
        }
        ci.cancel();
    }

    @Unique
    private void autism$invokeSodiumBufferQuad(Object quad, float[] brightnesses, Object material) {
        for (Class<?> type = getClass(); type != null; type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) {
                if (!method.getName().equals("bufferQuad") || method.getParameterCount() != 3) continue;
                try {
                    method.setAccessible(true);
                    method.invoke(this, quad, brightnesses, material);
                    return;
                } catch (Throwable ignored) {
                    return;
                }
            }
        }
    }
}
