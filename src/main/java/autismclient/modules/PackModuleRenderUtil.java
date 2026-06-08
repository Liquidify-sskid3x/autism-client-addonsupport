package autismclient.modules;

import autismclient.util.AutismOverlayManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.InBedChatScreen;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.BlockAndLightGetter;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class PackModuleRenderUtil {
    private static final Minecraft MC = Minecraft.getInstance();
    private static volatile XraySnapshot xraySnapshot = XraySnapshot.inactive(-1, false);
    private static volatile FullbrightSnapshot fullbrightSnapshot = FullbrightSnapshot.inactive(-1, false);
    private static volatile EntityRenderSnapshot espSnapshot = EntityRenderSnapshot.inactive("esp", -1, false);
    private static volatile ItemEspSnapshot itemEspSnapshot = ItemEspSnapshot.inactive(-1, false);
    private static volatile EntityRenderSnapshot tracerSnapshot = EntityRenderSnapshot.inactive("tracers", -1, false);

    private PackModuleRenderUtil() {
    }

    public static boolean xrayActive() {
        return xraySnapshot().active();
    }

    public static boolean shouldUseFullbrightGamma() {
        return fullbrightSnapshot().gamma();
    }

    public static boolean shouldApplyFullbrightLuminance() {
        return fullbrightSnapshot().luminance();
    }

    public static boolean shouldUseBrightLightmap() {
        return shouldUseFullbrightGamma() || xrayActive();
    }

    public static int fullbrightLuminance(LightLayer lightLayer) {
        FullbrightSnapshot snapshot = fullbrightSnapshot();
        if (!snapshot.luminance() || lightLayer == null) return 0;
        return snapshot.lightType().equals(lightLayer.name()) ? snapshot.minimumLightLevel() : 0;
    }

    public static boolean shouldRenderXrayBlock(BlockAndTintGetter level, BlockPos pos, BlockState state) {
        return !isXrayBlocked(state, pos);
    }

    public static int xrayAlpha(BlockAndTintGetter level, BlockPos pos, BlockState state) {
        return xrayAlpha(state, pos);
    }

    public static int xrayAlpha(BlockState state, BlockPos pos) {
        XraySnapshot snapshot = xraySnapshot();
        if (!snapshot.active() || state == null || state.isAir()) return -1;
        if (!isXrayBlocked(snapshot, state, pos)) return -1;
        return snapshot.irisShaderPackInUse() ? 0 : snapshot.opacity();
    }

    public static boolean modifyXrayFace(BlockAndTintGetter level, BlockState state, Direction direction, BlockPos originalPos, boolean original) {
        XraySnapshot snapshot = xraySnapshot();
        if (!snapshot.active()) return original;
        if (!original && !isXrayBlocked(snapshot, state, originalPos)) {
            BlockPos adjPos = originalPos == null ? null : originalPos.relative(direction);
            BlockState adjState = level == null || adjPos == null ? null : level.getBlockState(adjPos);
            return adjState == null
                || adjState.getFaceOcclusionShape(direction.getOpposite()) != net.minecraft.world.phys.shapes.Shapes.block()
                || adjState.getBlock() != state.getBlock()
                || !adjState.isSolidRender()
                || isXrayBlocked(snapshot, adjState, adjPos);
        }
        return original;
    }

    public static boolean shouldForceXrayFace(BlockState state) {
        XraySnapshot snapshot = xraySnapshot();
        return snapshot.active() && !isXrayBlocked(snapshot, state, null);
    }

    public static boolean isXrayBlocked(BlockState state, BlockPos pos) {
        return isXrayBlocked(xraySnapshot(), state, pos);
    }

    public static int xrayFluidAlpha(BlockAndTintGetter level, BlockPos pos, FluidState fluidState) {
        return xrayFluidAlpha(fluidState, pos);
    }

    public static int xrayFluidAlpha(FluidState fluidState, BlockPos pos) {
        XraySnapshot snapshot = xraySnapshot();
        if (!snapshot.active() || fluidState == null || fluidState.isEmpty()) return -1;
        boolean water = fluidState.is(FluidTags.WATER);
        boolean lava = fluidState.is(FluidTags.LAVA);
        boolean apply = switch (snapshot.fluidOpacityMode()) {
            case "None" -> false;
            case "Water" -> water;
            case "Lava" -> lava;
            default -> water || lava;
        };
        if (!apply) return -1;
        BlockState fluidBlock = fluidState.createLegacyBlock();
        if (!isXrayBlocked(snapshot, fluidBlock, pos)) return -1;
        return snapshot.irisShaderPackInUse() ? 0 : snapshot.opacity();
    }

    public static boolean shouldForceXrayFluidSides() {
        return xrayActive();
    }

    public static boolean xrayUsesShaderCullMode() {
        XraySnapshot snapshot = xraySnapshot();
        return snapshot.active() && snapshot.irisShaderPackInUse();
    }

    public static boolean shouldKeepXrayFluidSide(BlockState neighborState) {
        return isXrayBlocked(xraySnapshot(), neighborState, null);
    }

    public static int sodiumFullLight() {
        return 15 | 15 << 4 | 15 << 8;
    }

    public static int sodiumBlockLight(int current, BlockState state, BlockPos pos) {
        XraySnapshot snapshot = xraySnapshot();
        if (snapshot.active() && !isXrayBlocked(snapshot, state, pos)) return sodiumFullLight();
        FullbrightSnapshot fullbright = fullbrightSnapshot();
        if (!fullbright.luminance() || !"BLOCK".equals(fullbright.lightType())) return current;
        return Math.max(current, fullbright.minimumLightLevel());
    }

    public static void applySodiumQuadAlpha(Object quad, int alpha) {
        if (quad == null || alpha < 0) return;
        try {
            Method baseColor = quad.getClass().getMethod("baseColor", int.class);
            Method setColor = quad.getClass().getMethod("setColor", int.class, int.class);
            for (int i = 0; i < 4; i++) {
                Object colorValue = baseColor.invoke(quad, i);
                if (colorValue instanceof Integer color) {
                    setColor.invoke(quad, i, ((alpha & 0xFF) << 24) | (color & 0x00FFFFFF));
                }
            }
        } catch (Throwable ignored) {
        }
    }

    public static void applySodiumQuadTint(Object quad, int tint) {
        if (quad == null) return;
        try {
            Method baseColor = quad.getClass().getMethod("baseColor", int.class);
            Method setColor = quad.getClass().getMethod("setColor", int.class, int.class);
            for (int i = 0; i < 4; i++) {
                Object colorValue = baseColor.invoke(quad, i);
                if (colorValue instanceof Integer color) {
                    setColor.invoke(quad, i, net.minecraft.util.ARGB.multiply(color, tint));
                }
            }
        } catch (Throwable ignored) {
        }
    }

    public static Object sodiumTranslucentMaterial(Object fallback) {
        try {
            Class<?> defaultMaterials = Class.forName("net.caffeinemc.mods.sodium.client.render.chunk.terrain.material.DefaultMaterials");
            Field translucent = defaultMaterials.getField("TRANSLUCENT");
            Object value = translucent.get(null);
            return value == null ? fallback : value;
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    public static void refreshWorldRenderer() {
        if (MC != null && MC.levelRenderer != null) MC.levelRenderer.allChanged();
    }

    public static int applyFullbrightLuminance(BlockAndLightGetter level, BlockPos pos, int packedBrightness) {
        FullbrightSnapshot snapshot = fullbrightSnapshot();
        if (!snapshot.luminance()) return packedBrightness;
        int sky = "SKY".equals(snapshot.lightType()) ? snapshot.minimumLightLevel() : 0;
        int block = "BLOCK".equals(snapshot.lightType()) ? snapshot.minimumLightLevel() : 0;
        int originalSky = level == null || pos == null ? 0 : level.getBrightness(LightLayer.SKY, pos);
        int originalBlock = level == null || pos == null ? 0 : level.getBrightness(LightLayer.BLOCK, pos);
        return net.minecraft.util.LightCoordsUtil.pack(Math.max(block, originalBlock), Math.max(sky, originalSky));
    }

    public static boolean shouldTrace(Entity entity) {
        return shouldRenderEntity(tracerSnapshot(), entity);
    }

    public static boolean shouldEsp(Entity entity) {
        if (shouldSuppressEspForUi()) return false;

        if (entity instanceof ItemEntity) return false;
        EntityRenderSnapshot snapshot = espSnapshot();
        return shouldRenderEntity(snapshot, entity) && espFadeAlpha(snapshot, entity) > 0.0;
    }

    public static int tracerColor(Entity entity) {
        return entityColor(tracerSnapshot(), entity, 0xCCFFFFFF);
    }

    public static int espColor(Entity entity) {
        EntityRenderSnapshot snapshot = espSnapshot();
        int color = entityColor(snapshot, entity, 0xCCFFFFFF);
        return withAlphaMultiplier(color, espFadeAlpha(snapshot, entity));
    }

    public static int espOutlineColor(Entity entity) {
        return espColor(entity) | 0xFF000000;
    }

    public static boolean shouldItemEsp(Entity entity) {
        if (shouldSuppressEspForUi()) return false;
        if (!(entity instanceof ItemEntity itemEntity)) return false;
        ItemEspSnapshot snapshot = itemEspSnapshot();
        return shouldRenderItem(snapshot, itemEntity) && itemEspFadeAlpha(snapshot, itemEntity) > 0.0;
    }

    public static int itemEspColor(Entity entity) {
        ItemEspSnapshot snapshot = itemEspSnapshot();
        if (!(entity instanceof ItemEntity itemEntity)) return snapshot.color();
        int base = snapshot.dynamicColor()
            ? dynamicItemColor(itemEntity.getItem(), snapshot.color())
            : snapshot.color();
        return withAlphaMultiplier(base, itemEspFadeAlpha(snapshot, itemEntity));
    }

    private static int dynamicItemColor(net.minecraft.world.item.ItemStack stack, int fallbackArgb) {
        int alpha = (fallbackArgb >>> 24) & 0xFF;
        int rgb = computeItemRgb(stack);
        return (alpha << 24) | (rgb & 0xFFFFFF);
    }

    private static int computeItemRgb(net.minecraft.world.item.ItemStack stack) {
        if (stack == null || stack.isEmpty()) return 0xFFD76A;
        Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        String path = id == null ? "" : id.getPath();

        if (path.contains("netherite")) return 0xB7AFA9;
        if (path.contains("diamond"))   return 0x4AE3D6;
        if (path.contains("emerald"))   return 0x2FE05A;
        if (path.contains("lapis"))     return 0x2A55E0;
        if (path.contains("redstone"))  return 0xFF3030;
        if (path.contains("amethyst"))  return 0xB48CF2;
        if (path.contains("copper"))    return 0xE08A5A;
        if (path.contains("gold") || path.contains("golden") || path.contains("raw_gold")) return 0xFCE24B;
        if (path.contains("iron"))      return 0xDADADA;
        if (path.contains("coal"))      return 0x2C2C2C;
        if (path.contains("quartz"))    return 0xF2EAE0;
        if (path.contains("melon"))     return 0x67C84B;
        if (path.contains("pumpkin"))   return 0xE08020;
        if (path.contains("ender"))     return 0x12B79A;
        if (path.contains("blaze"))     return 0xFFB52E;
        if (path.contains("slime"))     return 0x7FD45A;
        if (path.contains("bone"))      return 0xE9E4D0;
        if (path.contains("netherrack") || path.contains("nether_brick")) return 0x7A3B3B;

        if (stack.getItem() instanceof net.minecraft.world.item.BlockItem blockItem) {
            try {
                int col = blockItem.getBlock().defaultMapColor().col;
                if (col != 0) return col & 0xFFFFFF;
            } catch (Throwable ignored) {
            }
        }

        int hash = (id == null ? path.hashCode() : id.toString().hashCode());
        float hue = ((hash & 0x7FFFFFFF) % 360) / 360.0f;
        return java.awt.Color.HSBtoRGB(hue, 0.65f, 0.95f) & 0xFFFFFF;
    }

    public static int itemEspOutlineColor(Entity entity) {
        return itemEspColor(entity) | 0xFF000000;
    }

    public static boolean shouldUseItemOutline() {
        if (shouldSuppressEspForUi()) return false;
        ItemEspSnapshot snapshot = itemEspSnapshot();
        return snapshot.enabled() && "Shader".equals(snapshot.mode());
    }

    public static boolean shouldUseEntityOutline() {
        if (shouldSuppressEspForUi()) return false;
        EntityRenderSnapshot snapshot = espSnapshot();
        return snapshot.enabled() && "Shader".equals(snapshot.mode());
    }

    public static boolean shouldSuppressEspForUi() {
        if (PackHideState.isActive()) return true;
        if (MC == null) return false;
        if (MC.screen != null && !(MC.screen instanceof ChatScreen) && !(MC.screen instanceof InBedChatScreen)) return true;
        return AutismOverlayManager.get().hasVisibleOverlay();
    }

    private static boolean shouldRenderEntity(EntityRenderSnapshot snapshot, Entity entity) {
        if (snapshot == null || !snapshot.enabled()) return false;
        if (MC == null || MC.player == null || entity == null) return false;
        if (entity == MC.player) return false;
        if (entity == MC.getCameraEntity() && MC.options.getCameraType().isFirstPerson()) return false;
        Vec3 camera = MC.gameRenderer.getMainCamera().position();
        if (!entity.shouldRender(camera.x, camera.y, camera.z)) return false;
        double maxDistance = snapshot.maxDistance();
        if (maxDistance > 0.0 && entity.distanceToSqr(MC.player) > maxDistance * maxDistance) return false;
        if (snapshot.entityIds().isEmpty() && snapshot.entityPaths().isEmpty()) return true;
        Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        return snapshot.entityIds().contains(id) || snapshot.entityPaths().contains(id.getPath());
    }

    private static boolean shouldRenderItem(ItemEspSnapshot snapshot, ItemEntity entity) {
        if (snapshot == null || !snapshot.enabled()) return false;
        if (MC == null || MC.player == null || entity == null) return false;
        if (entity.getItem() == null || entity.getItem().isEmpty()) return false;
        Vec3 camera = MC.gameRenderer.getMainCamera().position();
        if (!entity.shouldRender(camera.x, camera.y, camera.z)) return false;
        double maxDistance = snapshot.maxDistance();
        if (maxDistance > 0.0 && entity.distanceToSqr(MC.player) > maxDistance * maxDistance) return false;
        if (!snapshot.someOnly()) return true;
        Identifier id = BuiltInRegistries.ITEM.getKey(entity.getItem().getItem());
        return snapshot.itemIds().contains(id) || snapshot.itemPaths().contains(id.getPath());
    }

    private static EntityRenderSnapshot espSnapshot() {
        EntityRenderSnapshot snapshot = espSnapshot;
        int revision = PackModuleRegistry.revision();
        boolean hidden = PackHideState.isActive();
        if (snapshot.revision() == revision && snapshot.hidden() == hidden) return snapshot;
        synchronized (PackModuleRenderUtil.class) {
            snapshot = espSnapshot;
            if (snapshot.revision() == revision && snapshot.hidden() == hidden) return snapshot;
            snapshot = buildEntityRenderSnapshot("esp", revision, hidden, false);
            espSnapshot = snapshot;
            return snapshot;
        }
    }

    private static EntityRenderSnapshot tracerSnapshot() {
        EntityRenderSnapshot snapshot = tracerSnapshot;
        int revision = PackModuleRegistry.revision();
        boolean hidden = PackHideState.isActive();
        if (snapshot.revision() == revision && snapshot.hidden() == hidden) return snapshot;
        synchronized (PackModuleRenderUtil.class) {
            snapshot = tracerSnapshot;
            if (snapshot.revision() == revision && snapshot.hidden() == hidden) return snapshot;
            snapshot = buildEntityRenderSnapshot("tracers", revision, hidden, true);
            tracerSnapshot = snapshot;
            return snapshot;
        }
    }

    private static ItemEspSnapshot itemEspSnapshot() {
        ItemEspSnapshot snapshot = itemEspSnapshot;
        int revision = PackModuleRegistry.revision();
        boolean hidden = PackHideState.isActive();
        if (snapshot.revision() == revision && snapshot.hidden() == hidden) return snapshot;
        synchronized (PackModuleRenderUtil.class) {
            snapshot = itemEspSnapshot;
            if (snapshot.revision() == revision && snapshot.hidden() == hidden) return snapshot;
            snapshot = buildItemEspSnapshot(revision, hidden);
            itemEspSnapshot = snapshot;
            return snapshot;
        }
    }

    private static ItemEspSnapshot buildItemEspSnapshot(int revision, boolean hidden) {
        if (hidden) return ItemEspSnapshot.inactive(revision, true);
        PackModule module = PackModuleRegistry.get("item-esp");
        if (module == null || !module.isEnabled()) return ItemEspSnapshot.inactive(revision, false);
        Set<Identifier> ids = new HashSet<>();
        Set<String> paths = new HashSet<>();
        for (String entry : module.list("items")) {
            if (entry == null) continue;
            String normalized = entry.trim().toLowerCase(Locale.ROOT);
            if (normalized.isEmpty()) continue;
            Identifier identifier = normalized.contains(":") ? Identifier.tryParse(normalized) : null;
            if (identifier != null) ids.add(identifier);
            else paths.add(normalized.contains(":") ? normalized.substring(normalized.indexOf(':') + 1) : normalized);
        }
        return new ItemEspSnapshot(
            revision,
            false,
            true,
            module.value("mode"),
            "Some".equals(module.value("items-mode")),
            parseDouble(module.value("max-distance"), 64.0),
            parseDouble(module.value("fade-distance"), 3.0),
            Set.copyOf(ids),
            Set.copyOf(paths),
            color(module, "color", 0xCCFFD76A),
            !"Static".equals(module.value("color-mode"))
        );
    }

    private static EntityRenderSnapshot buildEntityRenderSnapshot(String moduleId, int revision, boolean hidden, boolean useMaxDistance) {
        if (hidden) return EntityRenderSnapshot.inactive(moduleId, revision, true);
        PackModule module = PackModuleRegistry.get(moduleId);
        if (module == null || !module.isEnabled()) return EntityRenderSnapshot.inactive(moduleId, revision, false);
        Set<Identifier> ids = new HashSet<>();
        Set<String> paths = new HashSet<>();
        for (String entry : module.list("entities")) {
            if (entry == null) continue;
            String normalized = entry.trim().toLowerCase(Locale.ROOT);
            if (normalized.isEmpty()) continue;
            Identifier identifier = normalized.contains(":") ? Identifier.tryParse(normalized) : null;
            if (identifier != null) ids.add(identifier);
            else paths.add(normalized.contains(":") ? normalized.substring(normalized.indexOf(':') + 1) : normalized);
        }
        return new EntityRenderSnapshot(
            moduleId,
            revision,
            false,
            true,
            module.value("mode"),
            useMaxDistance ? parseDouble(module.value("max-distance"), 256.0) : 0.0,
            parseDouble(module.value("fade-distance"), 3.0),
            Set.copyOf(ids),
            Set.copyOf(paths),
            color(module, "players-color", 0xCCFFFFFF),
            color(module, "monsters-color", 0xCCFF4A4A),
            color(module, "animals-color", 0xCC74FF8F),
            color(module, "water-animals-color", 0xCC66D9FF),
            color(module, "ambient-color", 0xCCB78CFF),
            color(module, "misc-color", 0xCCCCCCCC)
        );
    }

    private static boolean isXrayBlocked(XraySnapshot snapshot, BlockState state, BlockPos pos) {
        if (!snapshot.active() || state == null || state.isAir()) return false;
        return !(matchesBlockList(state, snapshot) && (!snapshot.exposedOnly() || pos == null || isExposed(pos)));
    }

    private static boolean matchesBlockList(BlockState state, XraySnapshot snapshot) {
        if (state == null || state.isAir()) return false;
        Identifier id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return snapshot.blockIds().contains(id) || snapshot.blockPaths().contains(id.getPath());
    }

    private static boolean matchesBlockList(BlockState state, List<String> entries) {
        if (state == null || state.isAir()) return false;
        if (entries == null || entries.isEmpty()) return false;
        String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        for (String entry : entries) {
            String normalized = entry.toLowerCase(Locale.ROOT);
            if (id.equals(normalized) || id.endsWith(":" + normalized)) return true;
        }
        return false;
    }

    private static boolean isExposed(BlockPos pos) {
        if (MC == null || MC.level == null || pos == null) return true;
        for (Direction direction : Direction.values()) {
            BlockState neighbor = MC.level.getBlockState(pos.relative(direction));
            if (neighbor == null || neighbor.isAir() || !neighbor.isSolidRender()) return true;
            FluidState fluid = neighbor.getFluidState();
            if (fluid != null && !fluid.isEmpty()) return true;
        }
        return false;
    }

    private static int entityColor(EntityRenderSnapshot snapshot, Entity entity, int fallback) {
        if (snapshot == null || entity == null) return fallback;
        if (entity.getType() == EntityType.PLAYER) return snapshot.playersColor();
        MobCategory category = entity.getType().getCategory();
        return switch (category) {
            case MONSTER -> snapshot.monstersColor();
            case CREATURE -> snapshot.animalsColor();
            case WATER_CREATURE, WATER_AMBIENT, UNDERGROUND_WATER_CREATURE, AXOLOTLS -> snapshot.waterAnimalsColor();
            case AMBIENT -> snapshot.ambientColor();
            default -> snapshot.miscColor();
        };
    }

    private static double espFadeAlpha(EntityRenderSnapshot snapshot, Entity entity) {
        if (MC == null || MC.gameRenderer == null || entity == null) return 1.0;
        double fadeDistance = snapshot == null ? 3.0 : snapshot.fadeDistance();
        if (fadeDistance <= 0.0) return 1.0;
        Vec3 camera = MC.gameRenderer.getMainCamera().position();
        double dx = entity.getX() - camera.x;
        double dy = entity.getY() + entity.getBbHeight() * 0.5 - camera.y;
        double dz = entity.getZ() - camera.z;
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double alpha = Math.min(1.0, distance / fadeDistance);
        return alpha <= 0.075 ? 0.0 : alpha;
    }

    private static double itemEspFadeAlpha(ItemEspSnapshot snapshot, ItemEntity entity) {
        if (MC == null || MC.gameRenderer == null || entity == null) return 1.0;
        double fadeDistance = snapshot == null ? 3.0 : snapshot.fadeDistance();
        if (fadeDistance <= 0.0) return 1.0;
        Vec3 camera = MC.gameRenderer.getMainCamera().position();
        double dx = entity.getX() - camera.x;
        double dy = entity.getY() + entity.getBbHeight() * 0.5 - camera.y;
        double dz = entity.getZ() - camera.z;
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double alpha = Math.min(1.0, distance / fadeDistance);
        return alpha <= 0.075 ? 0.0 : alpha;
    }

    private static int withAlphaMultiplier(int color, double multiplier) {
        int alpha = Math.max(0, Math.min(255, (int) (((color >>> 24) & 0xFF) * multiplier)));
        return (alpha << 24) | (color & 0x00FFFFFF);
    }

    public static int color(PackModule module, String option, int fallback) {
        try {
            String value = module.value(option).replace("#", "");
            if (value.length() == 6) value = "CC" + value;
            return (int) Long.parseLong(value, 16);
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static double parseDouble(String value, double fallback) {
        try {
            return Double.parseDouble(value);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static boolean isIrisShaderPackInUse() {
        try {
            Class<?> irisApiClass = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            Object instance = irisApiClass.getMethod("getInstance").invoke(null);
            Object result = irisApiClass.getMethod("isShaderPackInUse").invoke(instance);
            return result instanceof Boolean bool && bool;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static XraySnapshot xraySnapshot() {
        int revision = PackModuleRegistry.revision();
        boolean hidden = PackHideState.isActive();
        XraySnapshot snapshot = xraySnapshot;
        if (snapshot.revision() == revision && snapshot.hidden() == hidden) return snapshot;
        synchronized (PackModuleRenderUtil.class) {
            snapshot = xraySnapshot;
            if (snapshot.revision() == revision && snapshot.hidden() == hidden) return snapshot;
            snapshot = buildXraySnapshot(revision, hidden);
            xraySnapshot = snapshot;
            return snapshot;
        }
    }

    private static FullbrightSnapshot fullbrightSnapshot() {
        int revision = PackModuleRegistry.revision();
        boolean hidden = PackHideState.isActive();
        FullbrightSnapshot snapshot = fullbrightSnapshot;
        if (snapshot.revision() == revision && snapshot.hidden() == hidden) return snapshot;
        synchronized (PackModuleRenderUtil.class) {
            snapshot = fullbrightSnapshot;
            if (snapshot.revision() == revision && snapshot.hidden() == hidden) return snapshot;
            snapshot = buildFullbrightSnapshot(revision, hidden);
            fullbrightSnapshot = snapshot;
            return snapshot;
        }
    }

    private static FullbrightSnapshot buildFullbrightSnapshot(int revision, boolean hidden) {
        if (hidden) return FullbrightSnapshot.inactive(revision, true);
        PackModule module = PackModuleRegistry.get("fullbright");
        if (module == null || !module.isEnabled()) return FullbrightSnapshot.inactive(revision, false);
        String mode = module.value("mode");
        boolean gamma = "Gamma".equals(mode);
        boolean luminance = "Luminance".equals(mode);
        return new FullbrightSnapshot(
            revision,
            false,
            gamma,
            luminance,
            module.value("light-type"),
            Math.max(0, Math.min(15, parseInt(module.value("minimum-light-level"), 8)))
        );
    }

    private static XraySnapshot buildXraySnapshot(int revision, boolean hidden) {
        if (hidden) return XraySnapshot.inactive(revision, true);
        PackModule module = PackModuleRegistry.get("xray");
        if (module == null || !module.isEnabled()) return XraySnapshot.inactive(revision, false);
        Set<Identifier> ids = new HashSet<>();
        Set<String> paths = new HashSet<>();
        for (String entry : module.list("whitelist")) {
            if (entry == null) continue;
            String normalized = entry.trim().toLowerCase(Locale.ROOT);
            if (normalized.isEmpty()) continue;
            Identifier identifier = normalized.contains(":") ? Identifier.tryParse(normalized) : null;
            if (identifier != null) ids.add(identifier);
            else paths.add(normalized.contains(":") ? normalized.substring(normalized.indexOf(':') + 1) : normalized);
        }
        return new XraySnapshot(
            revision,
            false,
            true,
            Math.max(0, Math.min(255, parseInt(module.value("opacity"), 25))),
            Boolean.parseBoolean(module.value("exposed-only")),
            isIrisShaderPackInUse(),
            module.value("fluid-opacity"),
            Set.copyOf(ids),
            Set.copyOf(paths)
        );
    }

    private record XraySnapshot(
        int revision,
        boolean hidden,
        boolean active,
        int opacity,
        boolean exposedOnly,
        boolean irisShaderPackInUse,
        String fluidOpacityMode,
        Set<Identifier> blockIds,
        Set<String> blockPaths
    ) {
        static XraySnapshot inactive(int revision, boolean hidden) {
            return new XraySnapshot(revision, hidden, false, -1, false, false, "Both", Set.of(), Set.of());
        }
    }

    private record FullbrightSnapshot(
        int revision,
        boolean hidden,
        boolean gamma,
        boolean luminance,
        String lightType,
        int minimumLightLevel
    ) {
        static FullbrightSnapshot inactive(int revision, boolean hidden) {
            return new FullbrightSnapshot(revision, hidden, false, false, "", 0);
        }
    }

    private record EntityRenderSnapshot(
        String moduleId,
        int revision,
        boolean hidden,
        boolean enabled,
        String mode,
        double maxDistance,
        double fadeDistance,
        Set<Identifier> entityIds,
        Set<String> entityPaths,
        int playersColor,
        int monstersColor,
        int animalsColor,
        int waterAnimalsColor,
        int ambientColor,
        int miscColor
    ) {
        static EntityRenderSnapshot inactive(String moduleId, int revision, boolean hidden) {
            return new EntityRenderSnapshot(
                moduleId,
                revision,
                hidden,
                false,
                "",
                0.0,
                0.0,
                Set.of(),
                Set.of(),
                0xCCFFFFFF,
                0xCCFF4A4A,
                0xCC74FF8F,
                0xCC66D9FF,
                0xCCB78CFF,
                0xCCCCCCCC
            );
        }
    }

    private record ItemEspSnapshot(
        int revision,
        boolean hidden,
        boolean enabled,
        String mode,
        boolean someOnly,
        double maxDistance,
        double fadeDistance,
        Set<Identifier> itemIds,
        Set<String> itemPaths,
        int color,
        boolean dynamicColor
    ) {
        static ItemEspSnapshot inactive(int revision, boolean hidden) {
            return new ItemEspSnapshot(
                revision,
                hidden,
                false,
                "",
                false,
                0.0,
                0.0,
                Set.of(),
                Set.of(),
                0xCCFFD76A,
                true
            );
        }
    }

}
