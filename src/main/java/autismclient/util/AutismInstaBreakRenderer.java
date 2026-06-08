package autismclient.util;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

public final class AutismInstaBreakRenderer {
    private static volatile BlockPos targetPos;
    private static volatile int lineColor = 0xFFFF3B3B;
    private static final float LINE_WIDTH = 2.0f;

    private AutismInstaBreakRenderer() {
    }

    public static void initialize() {
        LevelRenderEvents.COLLECT_SUBMITS.register(context -> {
            BlockPos pos = targetPos;
            int renderColor = lineColor;

            AutismSharedState state = AutismSharedState.get();
            if (state.isPlaceCaptureActive()) {
                Minecraft mc = Minecraft.getInstance();
                if (mc != null && mc.level != null && mc.player != null) {
                    HitResult hr = mc.hitResult;
                    if (hr instanceof BlockHitResult bhr) {
                        BlockPos support = bhr.getBlockPos();
                        Direction face = bhr.getDirection() == null ? Direction.UP : bhr.getDirection();
                        BlockPos preview = support.relative(face);
                        if (!mc.level.isOutsideBuildHeight(preview)) {
                            pos = preview;
                            renderColor = 0xFFFF3B3B;
                        }
                    }
                }
            }

            if (pos == null) return;
            Vec3 camera = context.levelState().cameraRenderState.pos;
            double x = pos.getX() - camera.x;
            double y = pos.getY() - camera.y;
            double z = pos.getZ() - camera.z;
            PoseStack poseStack = context.poseStack();
            final int finalColor = renderColor;
            context.submitNodeCollector().submitCustomGeometry(poseStack, RenderTypes.lines(), (pose, buffer) -> renderBox(pose, buffer, x, y, z, finalColor));
        });
    }

    public static void setTarget(BlockPos pos) {
        targetPos = pos == null ? null : pos.immutable();
        lineColor = 0xFFFF3B3B;
    }

    public static void setTarget(BlockPos pos, String shape, int line, int side) {
        setTarget(pos, line);
    }

    public static void setTarget(BlockPos pos, int color) {
        targetPos = pos == null ? null : pos.immutable();
        lineColor = color;
    }

    public static void clearTarget(BlockPos pos) {
        BlockPos current = targetPos;
        if (current == null || pos == null || current.equals(pos)) targetPos = null;
    }

    public static void clear() {
        targetPos = null;
    }

    public static void tickPlacePreview() {

    }

    private static void renderBox(PoseStack.Pose pose, VertexConsumer buffer, double x, double y, double z, int color) {
        double x2 = x + 1.0;
        double y2 = y + 1.0;
        double z2 = z + 1.0;
        line(pose, buffer, x, y, z, x2, y, z, color);
        line(pose, buffer, x2, y, z, x2, y, z2, color);
        line(pose, buffer, x2, y, z2, x, y, z2, color);
        line(pose, buffer, x, y, z2, x, y, z, color);
        line(pose, buffer, x, y2, z, x2, y2, z, color);
        line(pose, buffer, x2, y2, z, x2, y2, z2, color);
        line(pose, buffer, x2, y2, z2, x, y2, z2, color);
        line(pose, buffer, x, y2, z2, x, y2, z, color);
        line(pose, buffer, x, y, z, x, y2, z, color);
        line(pose, buffer, x2, y, z, x2, y2, z, color);
        line(pose, buffer, x2, y, z2, x2, y2, z2, color);
        line(pose, buffer, x, y, z2, x, y2, z2, color);
    }

    private static void line(PoseStack.Pose pose, VertexConsumer buffer, double x1, double y1, double z1, double x2, double y2, double z2, int color) {
        Vector3f normal = new Vector3f((float) (x2 - x1), (float) (y2 - y1), (float) (z2 - z1)).normalize();
        buffer.addVertex(pose, (float) x1, (float) y1, (float) z1).setColor(color).setNormal(pose, normal).setLineWidth(LINE_WIDTH);
        buffer.addVertex(pose, (float) x2, (float) y2, (float) z2).setColor(color).setNormal(pose, normal).setLineWidth(LINE_WIDTH);
    }
}
