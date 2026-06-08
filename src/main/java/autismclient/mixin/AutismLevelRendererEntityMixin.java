package autismclient.mixin;

import autismclient.modules.PackModuleRenderUtil;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockAndLightGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelRenderer.class)
public class AutismLevelRendererEntityMixin {
    @Inject(method = "extractEntity", at = @At("RETURN"))
    private void autism$espEntityOutline(Entity entity, float partialTickTime, CallbackInfoReturnable<EntityRenderState> cir) {
        EntityRenderState state = cir.getReturnValue();
        if (state == null) return;
        if (PackModuleRenderUtil.shouldUseItemOutline() && PackModuleRenderUtil.shouldItemEsp(entity)) {
            state.outlineColor = PackModuleRenderUtil.itemEspOutlineColor(entity);
            return;
        }
        if (PackModuleRenderUtil.shouldUseEntityOutline() && PackModuleRenderUtil.shouldEsp(entity)) {
            state.outlineColor = PackModuleRenderUtil.espOutlineColor(entity);
        }
    }

    @Inject(method = "getLightCoords(Lnet/minecraft/client/renderer/LevelRenderer$BrightnessGetter;Lnet/minecraft/world/level/BlockAndLightGetter;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;)I", at = @At("RETURN"), cancellable = true)
    private static void autism$fullbrightLuminance(LevelRenderer.BrightnessGetter brightnessGetter, BlockAndLightGetter level, BlockState state, BlockPos pos, CallbackInfoReturnable<Integer> cir) {
        if (!PackModuleRenderUtil.shouldApplyFullbrightLuminance()) return;
        cir.setReturnValue(PackModuleRenderUtil.applyFullbrightLuminance(level, pos, cir.getReturnValue()));
    }
}
