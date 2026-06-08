package autismclient.modules;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import autismclient.util.AutismPerf;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.rendertype.AutismRenderTypes;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

final class PackModuleWorldRenderer {
    private static boolean initialized;
    private static StorageSnapshot storageSnapshot = StorageSnapshot.empty();
    private static StorageSnapshot blockSnapshot = StorageSnapshot.empty();

    private PackModuleWorldRenderer() {
    }

    static void initialize() {
        if (initialized) return;
        initialized = true;
        LevelRenderEvents.COLLECT_SUBMITS.register(context -> {
            Minecraft mc = Minecraft.getInstance();
            if (PackHideState.isActive()) return;
            if (mc == null || mc.level == null || mc.player == null || mc.options.hideGui) return;
            if (PackModuleRenderUtil.shouldSuppressEspForUi()) return;
            PackModule tracer = PackModuleRegistry.get("tracers");
            PackModule storage = PackModuleRegistry.get("storage-esp");
            PackModule blockEsp = PackModuleRegistry.get("block-esp");

            boolean drawTracers = tracer != null && tracer.isEnabled();
            boolean storageEnabled = storage != null && storage.isEnabled();
            boolean storageFill = storageEnabled && Boolean.parseBoolean(storage.value("fill"));
            boolean storageWire = storageEnabled;
            boolean storageTrace = storageEnabled && Boolean.parseBoolean(storage.value("tracers"));
            boolean blockEnabled = blockEsp != null && blockEsp.isEnabled();
            boolean blockFill = blockEnabled && Boolean.parseBoolean(blockEsp.value("fill"));
            boolean blockWire = blockEnabled;
            boolean blockTrace = blockEnabled && Boolean.parseBoolean(blockEsp.value("tracers"));
            if (!drawTracers && !storageFill && !storageWire && !storageTrace && !blockFill && !blockWire && !blockTrace) return;
            Vec3 camera = context.levelState().cameraRenderState.pos;
            float tickDelta = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
            Vec3 from = eyeVector(mc);
            float tracerWidth = parseFloat(tracer == null ? null : tracer.value("line-width"), 1.0f, 1.0f, 4.0f);
            StorageSnapshot storageFrame = storageEnabled ? storageSnapshot(storage, mc.level, mc.player, tickDelta) : StorageSnapshot.empty();
            StorageSnapshot blockFrame = blockEnabled ? blockSnapshot(blockEsp, mc.level, mc.player) : StorageSnapshot.empty();
            PoseStack poseStack = context.poseStack();
            context.submitNodeCollector().submitCustomGeometry(poseStack, AutismRenderTypes.storageEspFillSeeThrough(), (pose, buffer) -> {
                if (storageFill && storageFrame != null) {

                    final float fillOpacity = 0.3f;
                    storageFrame.forEachBox((box, color) -> {
                        AABB moved = box.move(-camera.x, -camera.y, -camera.z);
                        fillBox(pose, buffer, moved, withAlpha(color, fillOpacity));
                    });
                }
                if (blockFill && blockFrame != null) {
                    final float fillOpacity = 0.3f;
                    blockFrame.forEachBox((box, color) -> {
                        AABB moved = box.move(-camera.x, -camera.y, -camera.z);
                        fillBox(pose, buffer, moved, withAlpha(color, fillOpacity));
                    });
                }
            });
            context.submitNodeCollector().submitCustomGeometry(poseStack, AutismRenderTypes.storageEspLinesSeeThrough(), (pose, buffer) -> {
                if (drawTracers) {
                    for (Entity entity : mc.level.entitiesForRendering()) {
                        if (!PackModuleRenderUtil.shouldTrace(entity)) continue;

                        Vec3 base = interpolatedPosition(entity, tickDelta).subtract(camera);
                        Vec3 top = base.add(0, entity.getBbHeight(), 0);
                        int color = PackModuleRenderUtil.tracerColor(entity);
                        clippedLine(pose, buffer, from, base, from, color, tracerWidth);
                        clippedLine(pose, buffer, base, top, from, color, tracerWidth);
                    }
                }
            });
            context.submitNodeCollector().submitCustomGeometry(poseStack, AutismRenderTypes.storageEspLinesSeeThrough(), (pose, buffer) -> {
                if (storageWire && storageFrame != null) {

                    final float lineW = 1.5f;
                    storageFrame.forEachBox((box, color) -> {
                        AABB moved = box.move(-camera.x, -camera.y, -camera.z);
                        renderStorageBox(pose, buffer, moved, color, lineW);
                    });
                }
                if (blockWire && blockFrame != null) {
                    final float lineW = 1.5f;
                    blockFrame.forEachBox((box, color) -> {
                        AABB moved = box.move(-camera.x, -camera.y, -camera.z);
                        renderStorageBox(pose, buffer, moved, color, lineW);
                    });
                }
                if (storageTrace && storageFrame != null) {
                    final float traceW = 2.0f;
                    storageFrame.forEachTrace((target, color) -> {
                        Vec3 moved = target.subtract(camera);
                        clippedLine(pose, buffer, from, moved, from, color, traceW);
                    });
                }
                if (blockTrace && blockFrame != null) {
                    final float traceW = 2.0f;
                    blockFrame.forEachTrace((target, color) -> {
                        Vec3 moved = target.subtract(camera);
                        clippedLine(pose, buffer, from, moved, from, color, traceW);
                    });
                }
            });
        });
    }

    private static Vec3 interpolatedPosition(Entity entity, float tickDelta) {
        return new Vec3(
            Mth.lerp(tickDelta, entity.xOld, entity.getX()),
            Mth.lerp(tickDelta, entity.yOld, entity.getY()),
            Mth.lerp(tickDelta, entity.zOld, entity.getZ())
        );
    }

    private static Vec3 eyeVector(Minecraft mc) {
        Camera camera = mc.gameRenderer.getMainCamera();
        if (camera == null) return Vec3.ZERO;
        return Vec3.directionFromRotation(camera.xRot(), camera.yRot());
    }

    private static void renderEntityBox(PoseStack.Pose pose, VertexConsumer buffer, AABB box, int color) {
        renderStorageBox(pose, buffer, box, color, 1.5f);
    }

    private static void renderStorageBox(PoseStack.Pose pose, VertexConsumer buffer, AABB box, int color, float width) {
        double x1 = box.minX;
        double y1 = box.minY;
        double z1 = box.minZ;
        double x2 = box.maxX;
        double y2 = box.maxY;
        double z2 = box.maxZ;
        line(pose, buffer, x1, y1, z1, x2, y1, z1, color, width);
        line(pose, buffer, x2, y1, z1, x2, y1, z2, color, width);
        line(pose, buffer, x2, y1, z2, x1, y1, z2, color, width);
        line(pose, buffer, x1, y1, z2, x1, y1, z1, color, width);
        line(pose, buffer, x1, y2, z1, x2, y2, z1, color, width);
        line(pose, buffer, x2, y2, z1, x2, y2, z2, color, width);
        line(pose, buffer, x2, y2, z2, x1, y2, z2, color, width);
        line(pose, buffer, x1, y2, z2, x1, y2, z1, color, width);
        line(pose, buffer, x1, y1, z1, x1, y2, z1, color, width);
        line(pose, buffer, x2, y1, z1, x2, y2, z1, color, width);
        line(pose, buffer, x2, y1, z2, x2, y2, z2, color, width);
        line(pose, buffer, x1, y1, z2, x1, y2, z2, color, width);
    }

    private static final double NEAR_PLANE = 0.05;

    private static void clippedLine(PoseStack.Pose pose, VertexConsumer buffer,
                                    Vec3 a, Vec3 b, Vec3 forward,
                                    int color, float width) {
        double da = a.x * forward.x + a.y * forward.y + a.z * forward.z;
        double db = b.x * forward.x + b.y * forward.y + b.z * forward.z;
        if (da < NEAR_PLANE && db < NEAR_PLANE) return;
        double ax = a.x, ay = a.y, az = a.z;
        double bx = b.x, by = b.y, bz = b.z;
        if (da < NEAR_PLANE) {
            double t = (NEAR_PLANE - da) / (db - da);
            ax = a.x + (b.x - a.x) * t;
            ay = a.y + (b.y - a.y) * t;
            az = a.z + (b.z - a.z) * t;
        } else if (db < NEAR_PLANE) {
            double t = (NEAR_PLANE - db) / (da - db);
            bx = b.x + (a.x - b.x) * t;
            by = b.y + (a.y - b.y) * t;
            bz = b.z + (a.z - b.z) * t;
        }
        line(pose, buffer, ax, ay, az, bx, by, bz, color, width);
    }

    private static void line(PoseStack.Pose pose, VertexConsumer buffer, double x1, double y1, double z1, double x2, double y2, double z2, int color, float width) {
        Vector3f normal = new Vector3f((float) (x1 - x2), (float) (y1 - y2), (float) (z1 - z2));
        if (normal.lengthSquared() > 0.0f) normal.normalize();
        buffer.addVertex(pose, (float) x1, (float) y1, (float) z1).setColor(color).setNormal(pose, normal).setLineWidth(width);
        normal.negate();
        buffer.addVertex(pose, (float) x2, (float) y2, (float) z2).setColor(color).setNormal(pose, normal).setLineWidth(width);
    }

    private static void fillBox(PoseStack.Pose pose, VertexConsumer buffer, AABB box, int color) {
        if (((color >>> 24) & 0xFF) <= 0) return;
        quad(pose, buffer, box.minX, box.minY, box.minZ, box.maxX, box.minY, box.minZ, box.maxX, box.minY, box.maxZ, box.minX, box.minY, box.maxZ, color);
        quad(pose, buffer, box.minX, box.maxY, box.maxZ, box.maxX, box.maxY, box.maxZ, box.maxX, box.maxY, box.minZ, box.minX, box.maxY, box.minZ, color);
        quad(pose, buffer, box.minX, box.minY, box.maxZ, box.maxX, box.minY, box.maxZ, box.maxX, box.maxY, box.maxZ, box.minX, box.maxY, box.maxZ, color);
        quad(pose, buffer, box.maxX, box.minY, box.minZ, box.minX, box.minY, box.minZ, box.minX, box.maxY, box.minZ, box.maxX, box.maxY, box.minZ, color);
        quad(pose, buffer, box.minX, box.minY, box.minZ, box.minX, box.minY, box.maxZ, box.minX, box.maxY, box.maxZ, box.minX, box.maxY, box.minZ, color);
        quad(pose, buffer, box.maxX, box.minY, box.maxZ, box.maxX, box.minY, box.minZ, box.maxX, box.maxY, box.minZ, box.maxX, box.maxY, box.maxZ, color);
    }

    private static void quad(PoseStack.Pose pose, VertexConsumer buffer, double x1, double y1, double z1, double x2, double y2, double z2, double x3, double y3, double z3, double x4, double y4, double z4, int color) {
        buffer.addVertex(pose, (float) x1, (float) y1, (float) z1).setColor(color);
        buffer.addVertex(pose, (float) x2, (float) y2, (float) z2).setColor(color);
        buffer.addVertex(pose, (float) x3, (float) y3, (float) z3).setColor(color);
        buffer.addVertex(pose, (float) x4, (float) y4, (float) z4).setColor(color);
    }

    private static int withAlpha(int color, float alphaMultiplier) {
        int alpha = Math.max(0, Math.min(255, (int) (((color >>> 24) & 0xFF) * alphaMultiplier)));
        return (alpha << 24) | (color & 0x00FFFFFF);
    }

    private static float parseFloat(String value, float fallback, float min, float max) {
        try {
            return Mth.clamp(Float.parseFloat(value), min, max);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static StorageSnapshot storageSnapshot(PackModule module, ClientLevel level, Player player, float tickDelta) {
        if (module == null || level == null || player == null) return StorageSnapshot.empty();
        long gameTime = level.getGameTime();
        int revision = PackModuleRegistry.revision();
        int chunkX = player.chunkPosition().x();
        int chunkZ = player.chunkPosition().z();
        StorageSnapshot cached = storageSnapshot;
        if (cached.matches(gameTime, revision, chunkX, chunkZ)) return cached;

        long perf = AutismPerf.beginJoin();
        List<StorageBox> boxes = new ArrayList<>();
        List<StorageTrace> traces = new ArrayList<>();
        PackModuleStorageEsp.collectBoth(
            module,
            level,
            player,
            tickDelta,
            (box, color) -> boxes.add(new StorageBox(box, color)),
            (target, color) -> traces.add(new StorageTrace(target, color))
        );
        StorageSnapshot next = new StorageSnapshot(gameTime, revision, chunkX, chunkZ, List.copyOf(boxes), List.copyOf(traces));
        storageSnapshot = next;
        AutismPerf.endJoinSpike("join.storageEsp.scan", perf, 6_000_000L);
        return next;
    }

    private static StorageSnapshot blockSnapshot(PackModule module, ClientLevel level, Player player) {
        if (module == null || level == null || player == null) return StorageSnapshot.empty();
        long gameTime = level.getGameTime();
        int revision = PackModuleRegistry.revision();
        int chunkX = player.chunkPosition().x();
        int chunkZ = player.chunkPosition().z();
        StorageSnapshot cached = blockSnapshot;
        if (cached.matches(gameTime, revision, chunkX, chunkZ)) return cached;

        long perf = AutismPerf.beginJoin();
        List<StorageBox> boxes = new ArrayList<>();
        List<StorageTrace> traces = new ArrayList<>();
        PackModuleBlockEsp.collectBoth(
            module,
            level,
            player,
            (box, color) -> boxes.add(new StorageBox(box, color)),
            (target, color) -> traces.add(new StorageTrace(target, color))
        );
        StorageSnapshot next = new StorageSnapshot(gameTime, revision, chunkX, chunkZ, List.copyOf(boxes), List.copyOf(traces));
        blockSnapshot = next;
        AutismPerf.endJoinSpike("join.blockEsp.scan", perf, 6_000_000L);
        return next;
    }

    private record StorageSnapshot(long gameTime, int revision, int chunkX, int chunkZ,
                                   List<StorageBox> boxes, List<StorageTrace> traces) {
        private static StorageSnapshot empty() {
            return new StorageSnapshot(Long.MIN_VALUE, -1, Integer.MIN_VALUE, Integer.MIN_VALUE, List.of(), List.of());
        }

        private boolean matches(long gameTime, int revision, int chunkX, int chunkZ) {
            return this.gameTime == gameTime && this.revision == revision && this.chunkX == chunkX && this.chunkZ == chunkZ;
        }

        private void forEachBox(java.util.function.BiConsumer<AABB, Integer> consumer) {
            for (StorageBox box : boxes) consumer.accept(box.box(), box.color());
        }

        private void forEachTrace(java.util.function.BiConsumer<Vec3, Integer> consumer) {
            for (StorageTrace trace : traces) consumer.accept(trace.target(), trace.color());
        }
    }

    private record StorageBox(AABB box, int color) {
    }

    private record StorageTrace(Vec3 target, int color) {
    }

}
