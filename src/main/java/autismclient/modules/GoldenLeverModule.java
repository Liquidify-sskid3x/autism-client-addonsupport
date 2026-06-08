package autismclient.modules;

import autismclient.mixin.accessor.AutismItemStackRenderStateAccessor;
import it.unimi.dsi.fastutil.ints.IntList;
import net.minecraft.ChatFormatting;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

public final class GoldenLeverModule extends PackModule {
    public static final int GOLD_TINT = 0xFFFFD24A;
    private static volatile boolean active;

    public GoldenLeverModule() {
        super("golden-lever", "Golden Lever", PackModuleCategory.MISC, "Renames and recolors vanilla levers.");
        active = isEnabled();
    }

    @Override
    public void onEnable() {
        active = true;
        PackModuleRenderUtil.refreshWorldRenderer();
    }

    @Override
    public void onDisable() {
        active = false;
        PackModuleRenderUtil.refreshWorldRenderer();
    }

    public static boolean isStylingActive() {
        return active;
    }

    public static boolean shouldStyle(ItemStack stack) {
        return isActive() && stack != null && stack.is(Items.LEVER);
    }

    public static boolean shouldStyle(BlockState state) {
        return isActive() && state != null && state.is(Blocks.LEVER);
    }

    public static Component leverName() {
        return Component.literal("Golden Lever").withStyle(ChatFormatting.GOLD);
    }

    public static void tintItemStackRenderState(ItemStackRenderState output) {
        if (output == null) return;
        AutismItemStackRenderStateAccessor accessor = (AutismItemStackRenderStateAccessor) output;
        ItemStackRenderState.LayerRenderState[] layers = accessor.autism$getLayers();
        int count = Math.min(accessor.autism$getActiveLayerCount(), layers.length);
        for (int i = 0; i < count; i++) {
            tintLayer(layers[i]);
        }
    }

    private static void tintLayer(ItemStackRenderState.LayerRenderState layer) {
        if (layer == null) return;
        List<BakedQuad> quads = layer.prepareQuadList();
        for (int i = 0; i < quads.size(); i++) {
            quads.set(i, withTintIndex(quads.get(i)));
        }
        IntList tints = layer.tintLayers();
        tints.clear();
        tints.add(GOLD_TINT);
    }

    private static BakedQuad withTintIndex(BakedQuad quad) {
        BakedQuad.MaterialInfo materialInfo = quad.materialInfo();
        if (materialInfo.tintIndex() == 0) return quad;
        BakedQuad.MaterialInfo tintedInfo = new BakedQuad.MaterialInfo(
            materialInfo.sprite(),
            materialInfo.layer(),
            materialInfo.itemRenderType(),
            0,
            materialInfo.shade(),
            materialInfo.lightEmission()
        );
        return new BakedQuad(
            quad.position0(),
            quad.position1(),
            quad.position2(),
            quad.position3(),
            quad.packedUV0(),
            quad.packedUV1(),
            quad.packedUV2(),
            quad.packedUV3(),
            quad.direction(),
            tintedInfo
        );
    }

    private static boolean isActive() {
        return active;
    }
}
