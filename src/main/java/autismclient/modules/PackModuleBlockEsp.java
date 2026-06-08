package autismclient.modules;

import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.function.BiConsumer;

final class PackModuleBlockEsp {
    private static volatile Selection cachedSelection = new Selection("", Set.of());

    private PackModuleBlockEsp() {
    }

    static void collectBoth(PackModule module, ClientLevel level, Player player,
                            BiConsumer<AABB, Integer> boxEmit,
                            BiConsumer<Vec3, Integer> traceEmit) {
        if (module == null || level == null || player == null || (boxEmit == null && traceEmit == null)) return;
        Selection selection = selection(module.value("blocks"));
        if (selection.blocks().isEmpty()) return;

        double maxDist = parseDouble(module.value("max-distance"), 64.0);
        double effectiveMax = maxDist <= 0 ? 4096.0 : maxDist;
        double maxDistSq = effectiveMax * effectiveMax;
        int chunkRadius = Math.max(1, (int) Math.ceil(effectiveMax / 16.0));
        int maxTargets = parseInt(module.value("max-targets"), 1024, 64, 8192);
        int color = PackModuleRenderUtil.color(module, "color", 0xCCFF3B3B);

        ClientChunkCache chunks = level.getChunkSource();
        Vec3 playerPos = player.position();
        int playerChunkX = player.chunkPosition().x();
        int playerChunkZ = player.chunkPosition().z();
        int emitted = 0;

        for (int radius = 0; radius <= chunkRadius && emitted < maxTargets; radius++) {
            for (int dx = -radius; dx <= radius && emitted < maxTargets; dx++) {
                for (int dz = -radius; dz <= radius && emitted < maxTargets; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                    LevelChunk chunk = chunks.getChunk(playerChunkX + dx, playerChunkZ + dz, ChunkStatus.FULL, false);
                    if (chunk == null) continue;
                    emitted += collectChunk(level, chunk, selection.blocks(), playerPos, maxDistSq, color, maxTargets - emitted, boxEmit, traceEmit);
                }
            }
        }
    }

    private static int collectChunk(ClientLevel level, LevelChunk chunk, Set<Block> targets, Vec3 playerPos, double maxDistSq,
                                    int color, int remaining, BiConsumer<AABB, Integer> boxEmit, BiConsumer<Vec3, Integer> traceEmit) {
        int emitted = 0;
        LevelChunkSection[] sections = chunk.getSections();
        int minX = chunk.getPos().getMinBlockX();
        int minZ = chunk.getPos().getMinBlockZ();
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();

        for (int sectionIndex = 0; sectionIndex < sections.length && emitted < remaining; sectionIndex++) {
            LevelChunkSection section = sections[sectionIndex];
            if (section == null || section.hasOnlyAir()) continue;
            if (!section.maybeHas(state -> targets.contains(state.getBlock()))) continue;

            int baseY = chunk.getSectionYFromSectionIndex(sectionIndex) << 4;
            for (int sy = 0; sy < 16 && emitted < remaining; sy++) {
                int y = baseY + sy;
                if (level.isOutsideBuildHeight(y)) continue;
                for (int sx = 0; sx < 16 && emitted < remaining; sx++) {
                    int x = minX + sx;
                    for (int sz = 0; sz < 16 && emitted < remaining; sz++) {
                        int z = minZ + sz;
                        BlockState state = section.getBlockState(sx, sy, sz);
                        if (!targets.contains(state.getBlock())) continue;
                        double cx = x + 0.5;
                        double cy = y + 0.5;
                        double cz = z + 0.5;
                        if (sqDist(playerPos, cx, cy, cz) > maxDistSq) continue;
                        mutable.set(x, y, z);
                        if (boxEmit != null) boxEmit.accept(blockShape(level, mutable, state), color);
                        if (traceEmit != null) traceEmit.accept(new Vec3(cx, cy, cz), color);
                        emitted++;
                    }
                }
            }
        }
        return emitted;
    }

    private static AABB blockShape(ClientLevel level, BlockPos pos, BlockState state) {
        VoxelShape shape = state.getShape(level, pos);
        if (shape == null || shape.isEmpty()) return new AABB(pos);
        return shape.bounds().move(pos.getX(), pos.getY(), pos.getZ());
    }

    private static Selection selection(String value) {
        String safe = value == null ? "" : value;
        Selection cached = cachedSelection;
        if (cached.value().equals(safe)) return cached;
        Set<Block> blocks = new LinkedHashSet<>();
        for (String raw : safe.split("\\|")) {
            String id = normalizeId(raw);
            if (id.isEmpty()) continue;
            Identifier parsed = Identifier.tryParse(id);
            if (parsed == null) continue;
            Block block = BuiltInRegistries.BLOCK.getOptional(parsed).orElse(Blocks.AIR);
            if (block != Blocks.AIR) blocks.add(block);
        }
        Selection next = new Selection(safe, Set.copyOf(blocks));
        cachedSelection = next;
        return next;
    }

    private static String normalizeId(String raw) {
        if (raw == null) return "";
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty()) return "";
        return value.contains(":") ? value : "minecraft:" + value;
    }

    private static double sqDist(Vec3 from, double x, double y, double z) {
        double dx = from.x - x;
        double dy = from.y - y;
        double dz = from.z - z;
        return dx * dx + dy * dy + dz * dz;
    }

    private static double parseDouble(String value, double fallback) {
        try {
            return Double.parseDouble(value);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static int parseInt(String value, int fallback, int min, int max) {
        try {
            return Math.max(min, Math.min(max, Integer.parseInt(value)));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private record Selection(String value, Set<Block> blocks) {
    }
}
