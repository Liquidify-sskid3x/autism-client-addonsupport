package autismclient.util;

import autismclient.gui.vanillaui.components.UiText;
import autismclient.gui.vanillaui.components.CompactTheme;
import autismclient.gui.vanillaui.components.UiTone;
import autismclient.mixin.accessor.AutismMultiPlayerGameModeAccessor;
import autismclient.modules.PackModule;
import autismclient.modules.PackHideState;
import autismclient.modules.PackModuleRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class AutismHudManager {
    public static final String ACTIVE_MODULES = "active_modules";
    public static final String TPS = "tps";
    public static final String COORDINATES = "coordinates";
    public static final String NETHER_COORDS = "nether_coords";
    public static final String FPS = "fps";
    public static final String PING = "ping";
    public static final String SPEED = "speed";
    public static final String GAME_MODE = "game_mode";
    public static final String DURABILITY = "durability";
    public static final String LOOKING_AT = "looking_at";
    public static final String BREAKING_PROGRESS = "breaking_progress";
    public static final String SERVER = "server";
    public static final String WEATHER = "weather";
    public static final String BIOME = "biome";
    public static final String WORLD_TIME = "world_time";
    public static final String REAL_TIME = "real_time";
    public static final String ROTATION = "rotation";
    public static final String WATERMARK = "watermark";
    public static final String ARMOR = "armor";
    public static final String INVENTORY = "inventory";
    public static final String ITEM_COUNTER = "item_counter";
    public static final String POTION_TIMERS = "potion_timers";
    public static final String COMPASS = "compass";

    private static final Minecraft MC = Minecraft.getInstance();
    private static final CompactTheme THEME = new CompactTheme();
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT);
    private static final Identifier HUD_LOGO = Identifier.fromNamespaceAndPath("autismclient", "textures/gui/hud/autismclient_hud.png");
    private static final int HUD_LOGO_TEXTURE_WIDTH = 552;
    private static final int HUD_LOGO_TEXTURE_HEIGHT = 52;
    private static final int HUD_LOGO_DISPLAY_WIDTH = 552;
    private static final int HUD_LOGO_DISPLAY_HEIGHT = 52;
    private static final int HUD_OUTLINE_MERGE_TOLERANCE = 1;
    private static final int HUD_SAFE_ZONE_X = 1;
    private static final int HUD_SAFE_ZONE_Y = 2;
    private static final int HUD_OUTLINE_CHROME_PADDING = 2;
    private static final List<String> ORDER = List.of(
        ACTIVE_MODULES, TPS, COORDINATES, NETHER_COORDS,
        FPS, PING, SPEED, GAME_MODE, DURABILITY, LOOKING_AT, BREAKING_PROGRESS,
        SERVER, WEATHER, BIOME, WORLD_TIME, REAL_TIME, ROTATION, WATERMARK,
        ARMOR, INVENTORY, ITEM_COUNTER, POTION_TIMERS, COMPASS
    );

    private static double lastX;
    private static double lastZ;
    private static long lastSpeedGameTime = Long.MIN_VALUE;
    private static double blocksPerSecond;
    private static float rainbowHue;
    private static final Map<String, CachedHudElement> HUD_CACHE = new HashMap<>();
    private static final Map<String, Map<String, String>> DEFAULT_SETTINGS_CACHE = new HashMap<>();
    private static long hudRenderFrame;
    private static int metricsStableWidth;
    private static long metricsWidthHoldUntil;

    private static final Map<String, int[]> GROUP_RECT_OVERRIDE = new HashMap<>();
    private static List<ElementBounds> HUD_OCCLUDERS = List.of();
    private static boolean defaultsEnsured;

    private AutismHudManager() {
    }

    public static void ensureDefaults() {
        AutismConfig config = AutismConfig.getGlobal();
        if (defaultsEnsured && config.hudLayoutMigrated) return;
        if (!config.hudLayoutMigrated) migrateOldHud(config);
        for (String id : ORDER) state(id);
        normalizeDefaultHudStack(config);
        defaultsEnsured = true;
    }

    public static List<String> elementIds() {
        ensureDefaults();
        return ORDER;
    }

    public static String label(String id) {
        return switch (id) {
            case ACTIVE_MODULES -> "Active Modules";
            case TPS -> "TPS";
            case COORDINATES -> "Coordinates";
            case NETHER_COORDS -> "Nether Coords";
            case FPS -> "FPS";
            case PING -> "Ping";
            case SPEED -> "Speed";
            case GAME_MODE -> "Game Mode";
            case DURABILITY -> "Durability";
            case LOOKING_AT -> "Looking At";
            case BREAKING_PROGRESS -> "Breaking Progress";
            case SERVER -> "Server";
            case WEATHER -> "Weather";
            case BIOME -> "Biome";
            case WORLD_TIME -> "World Time";
            case REAL_TIME -> "Real Time";
            case ROTATION -> "Rotation";
            case WATERMARK -> "Logo";
            case ARMOR -> "Armor";
            case INVENTORY -> "Inventory";
            case ITEM_COUNTER -> "Item Counter";
            case POTION_TIMERS -> "Potion Timers";
            case COMPASS -> "Compass";
            default -> id;
        };
    }

    public static String description(String id) {
        return switch (id) {
            case ACTIVE_MODULES -> "Enabled Autism modules with optional info and keybinds.";
            case TPS -> "Estimated server TPS from Autism's tick tracker.";
            case COORDINATES -> "Current player coordinates in this dimension.";
            case NETHER_COORDS -> "Overworld/Nether converted coordinates using the 8:1 ratio.";
            case FPS -> "Current client FPS.";
            case PING -> "Current player latency.";
            case SPEED -> "Horizontal player speed in blocks per second.";
            case GAME_MODE -> "Current game mode.";
            case DURABILITY -> "Main hand durability.";
            case LOOKING_AT -> "Block or entity currently under the crosshair.";
            case BREAKING_PROGRESS -> "Current vanilla block-breaking progress.";
            case SERVER -> "Current server or world.";
            case WEATHER -> "Current world weather.";
            case BIOME -> "Current biome.";
            case WORLD_TIME -> "Current world day time.";
            case REAL_TIME -> "Local system time.";
            case ROTATION -> "Camera direction, yaw, and pitch.";
            case WATERMARK -> "Movable AUTISM Client logo image.";
            case ARMOR -> "Equipped armor with vanilla item overlays.";
            case INVENTORY -> "Main inventory grid.";
            case ITEM_COUNTER -> "Count of a configured item id in inventory.";
            case POTION_TIMERS -> "Active status effects and durations.";
            case COMPASS -> "Compact facing compass.";
            default -> "";
        };
    }

    public static AutismConfig.HudElementState state(String id) {
        AutismConfig config = AutismConfig.getGlobal();
        ensureStateMap(config);
        AutismConfig.HudElementState state = config.hudElements.computeIfAbsent(id, key -> defaultState(key));
        if (state.settings == null) state.settings = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : defaultSettings(id).entrySet()) {
            state.settings.putIfAbsent(entry.getKey(), entry.getValue());
        }
        return state;
    }

    public static void save() {
        AutismConfig.getGlobal().save();
    }

    public static boolean shouldRenderInGame(Screen screen, PackModule hud) {
        if (PackHideState.isActive()) return false;
        if (hud == null || !hud.isEnabled() || MC.player == null || MC.options.hideGui) return false;
        if (screen == null) return true;
        if (screen instanceof autismclient.gui.screen.AutismHudEditorScreen) return false;
        if (screen instanceof ChatScreen) return bool(hud, "show-in-chat", false);
        if (screen.isPauseScreen()) return bool(hud, "show-in-pause", false);
        if (bool(hud, "hide-in-guis", true)) return false;
        return !(screen instanceof AbstractContainerScreen<?>) || !bool(hud, "hide-in-guis", true);
    }

    public static void tick() {
        if (MC.player == null) {
            lastSpeedGameTime = Long.MIN_VALUE;
            blocksPerSecond = 0.0;
            return;
        }
        if (MC.level == null) return;
        long gameTime = MC.level.getGameTime();
        if (gameTime == lastSpeedGameTime) return;
        double x = MC.player.getX();
        double z = MC.player.getZ();
        if (lastSpeedGameTime != Long.MIN_VALUE) {
            long ticks = Math.max(1L, gameTime - lastSpeedGameTime);
            double dx = x - lastX;
            double dz = z - lastZ;
            double distance = Math.sqrt(dx * dx + dz * dz);
            double instant = distance * (20.0 / ticks);
            if (instant > 80.0) instant = horizontalVelocityBps();
            if (instant < 0.01) instant = 0.0;
            blocksPerSecond = (blocksPerSecond * 0.70) + (instant * 0.30);
            if (blocksPerSecond < 0.03) blocksPerSecond = 0.0;
        }
        lastX = x;
        lastZ = z;
        lastSpeedGameTime = gameTime;
    }

    private static double horizontalVelocityBps() {
        if (MC.player == null) return 0.0;
        double vx = MC.player.getDeltaMovement().x;
        double vz = MC.player.getDeltaMovement().z;
        return Math.sqrt(vx * vx + vz * vz) * 20.0;
    }

    public static void render(GuiGraphicsExtractor context, Font font, boolean editor, String selectedId, int mouseX, int mouseY) {
        render(context, font, editor, selectedId, mouseX, mouseY, List.of());
    }

    public static void render(GuiGraphicsExtractor context, Font font, boolean editor, String selectedId, int mouseX, int mouseY, List<ElementBounds> occluders) {
        long perf = AutismPerf.begin();
        ensureDefaults();
        HUD_OCCLUDERS = editor || occluders == null || occluders.isEmpty() ? List.of() : occluders;
        try {
            hudRenderFrame++;
            tick();
            rainbowHue += 0.000875f * (float) doubleSetting(ACTIVE_MODULES, "rainbow-speed", 1.0);
            if (rainbowHue > 1.0f) rainbowHue -= 1.0f;
            computeConnectedGroups(font);
            for (String id : ORDER) {
                AutismConfig.HudElementState state = state(id);
                if (!state.enabled) continue;
                if (combinedMetricsRowOwns(id)) continue;
                CachedHudElement cached = cached(id, font);
                ElementBounds bounds = cached.layout().bounds();
                if (bounds.width <= 0 || bounds.height <= 0) continue;
                if (!ACTIVE_MODULES.equals(id) && occluded(bounds)) continue;
                int[] ov = GROUP_RECT_OVERRIDE.get(id);
                boolean hovered = ov != null
                    ? hover(mouseX, mouseY, ov[0], bounds.y, ov[1], bounds.height)
                    : hover(mouseX, mouseY, bounds);
                renderElement(context, font, id, state, cached, editor, selectedId != null && selectedId.equals(id), hovered);
            }
        } finally {
            HUD_OCCLUDERS = List.of();
            AutismPerf.end("hud.render", perf);
        }
    }

    public static HudLayout layout(String id, Font font) {
        return cached(id, font).layout();
    }

    private static HudLayout computeLayout(String id, Font font, AutismConfig.HudElementState state, List<HudLine> lines, List<Integer> widths) {
        int pad = padding(id);
        int width;
        int height;
        if (WATERMARK.equals(id)) {
            int logoW = logoWidth(id);
            width = logoW + pad * 2 + logoRightPadding(id);
            height = logoHeight(logoW) + pad * 2;
        } else if (ARMOR.equals(id)) {
            width = pad * 2 + 4 * 18;
            height = pad * 2 + 18;
        } else if (INVENTORY.equals(id)) {
            width = pad * 2 + 9 * 18;
            height = pad * 2 + 3 * 18;
        } else if (COMPASS.equals(id)) {
            width = pad * 2 + compassWidth(id);
            height = pad * 2 + 18;
        } else if (ACTIVE_MODULES.equals(id)) {
            int maxW = 0;
            for (Integer lineWidth : widths) maxW = Math.max(maxW, lineWidth);
            int rowH = activeModuleRowHeight(id);
            int gap = lineGap(id);
            width = Math.max(32, maxW + pad * 2);
            height = lines.isEmpty() ? 0 : lines.size() * rowH + Math.max(0, lines.size() - 1) * gap;
        } else if (FPS.equals(id)) {
            int maxW = 0;
            for (Integer lineWidth : widths) maxW = Math.max(maxW, lineWidth);
            width = stableMetricsWidth(maxW) + pad * 2;
            height = THEME.fontHeight(UiTone.BODY) + verticalPadding(id) * 2;
        } else {
            int lineH = lineHeight(id);
            int maxW = 0;
            for (Integer lineWidth : widths) maxW = Math.max(maxW, lineWidth);
            width = Math.max(32, maxW + pad * 2);
            int vpad = verticalPadding(id);
            height = Math.max(THEME.fontHeight(UiTone.BODY) + vpad * 2, lines.size() * lineH - lineGap(id) + vpad * 2);
        }
        int renderX = safeContentX(id, anchorX(state.anchor, state.x, width), width);
        int renderY = safeContentY(id, anchorY(state.anchor, state.y, height), height);
        return new HudLayout(id, renderX, renderY, width, height, 1.0);
    }

    public static ElementBounds bounds(String id, Font font) {
        return layout(id, font).bounds();
    }

    private static ElementBounds visualBounds(String id, Font font) {
        CachedHudElement cached = cached(id, font);
        if (ACTIVE_MODULES.equals(id)) {
            List<VisualRect> rects = activeModuleVisualRects(id, cached);
            if (rects.isEmpty()) return cached.layout().bounds();
            int left = Integer.MAX_VALUE;
            int top = Integer.MAX_VALUE;
            int right = Integer.MIN_VALUE;
            int bottom = Integer.MIN_VALUE;
            for (VisualRect rect : rects) {
                left = Math.min(left, rect.x());
                top = Math.min(top, rect.y());
                right = Math.max(right, rect.right());
                bottom = Math.max(bottom, rect.bottom());
            }
            return new ElementBounds(id, left, top, Math.max(0, right - left), Math.max(0, bottom - top));
        }
        ElementBounds bounds = cached.layout().bounds();
        int[] ov = GROUP_RECT_OVERRIDE.get(id);
        int rx = ov != null ? ov[0] : bounds.x();
        int rw = ov != null ? ov[1] : bounds.width();
        VisualRect rect = visualChromeRect(new VisualRect(id, rx, bounds.y(), rw, bounds.height()));
        return new ElementBounds(id, rect.x(), rect.y(), rect.width(), rect.height());
    }

    public static String hit(Font font, int mouseX, int mouseY) {
        ensureDefaults();
        for (int i = ORDER.size() - 1; i >= 0; i--) {
            String id = ORDER.get(i);
            if (!state(id).enabled) continue;
            ElementBounds bounds = visualBounds(id, font);
            if (hover(mouseX, mouseY, bounds)) return id;
        }
        return null;
    }

    public static void move(String id, int x, int y, int screenW, int screenH) {
        AutismConfig.HudElementState state = state(id);
        HudLayout layout = layout(id, MC.font);
        int w = layout.scaledWidth();
        int h = layout.scaledHeight();
        int px = clamp(x, safeZoneXFor(id), maxSafeX(id, screenW, w));
        int py = clamp(y, safeZoneYFor(id), maxSafeY(id, screenH, h));

        boolean right = px > screenW / 2 || px + w >= screenW - 2 || (px + w / 2) >= screenW / 2;
        boolean bottom = py > screenH / 2 || py + h >= screenH - 2 || (py + h / 2) >= screenH / 2;
        state.anchor = (bottom ? "BOTTOM_" : "TOP_") + (right ? "RIGHT" : "LEFT");

        state.x = right ? (px + w - screenW) : px;
        state.y = bottom ? (py + h - screenH) : py;
        HUD_CACHE.remove(id);
        GROUP_RECT_OVERRIDE.remove(id);
        save();
    }

    public static void setEnabled(String id, boolean enabled) {
        state(id).enabled = enabled;
        save();
    }

    public static void toggle(String id) {
        AutismConfig.HudElementState state = state(id);
        state.enabled = !state.enabled;
        save();
    }

    public static String setting(String id, String key) {
        return state(id).settings.getOrDefault(key, defaultSettings(id).getOrDefault(key, ""));
    }

    public static String defaultSetting(String id, String key) {
        return defaultSettings(id).getOrDefault(key, "");
    }

    public static void setSetting(String id, String key, String value) {
        state(id).settings.put(key, value == null ? "" : value);
        save();
    }

    public static boolean boolSetting(String id, String key) {
        return Boolean.parseBoolean(setting(id, key));
    }

    public static int intSetting(String id, String key, int fallback) {
        try {
            return Integer.parseInt(setting(id, key));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    public static double doubleSetting(String id, String key, double fallback) {
        try {
            return Double.parseDouble(setting(id, key));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    public static void resetElement(String id) {
        AutismConfig config = AutismConfig.getGlobal();
        config.hudElements.put(id, defaultState(id));
        save();
    }

    public static void resetAllElements() {
        AutismConfig config = AutismConfig.getGlobal();
        ensureStateMap(config);
        config.hudElements.clear();
        for (String id : ORDER) config.hudElements.put(id, defaultState(id));
        defaultsEnsured = false;
        HUD_CACHE.clear();
        GROUP_RECT_OVERRIDE.clear();
        save();
    }

    public static List<String> lines(String id) {
        List<String> lines = new ArrayList<>();
        for (HudLine line : cached(id, MC.font).lines()) lines.add(line.plainText());
        if (lines.isEmpty()) lines.add(label(id));
        return lines;
    }

    private static CachedHudElement cached(String id, Font font) {
        AutismConfig.HudElementState state = state(id);
        HudCacheKey signature = cacheSignature(id, state, font);
        CachedHudElement cached = HUD_CACHE.get(id);
        if (cached != null && cached.signature().equals(signature)) return cached;
        List<HudLine> lines = ARMOR.equals(id) || INVENTORY.equals(id) || COMPASS.equals(id) || WATERMARK.equals(id)
            ? List.of()
            : buildLines(id);
        List<Integer> widths = new ArrayList<>(lines.size());
        for (HudLine line : lines) widths.add(lineWidth(font, line));
        HudLayout layout = computeLayout(id, font, state, lines, widths);
        CachedHudElement next = new CachedHudElement(signature, lines, widths, layout);
        HUD_CACHE.put(id, next);
        return next;
    }

    private static HudCacheKey cacheSignature(String id, AutismConfig.HudElementState state, Font font) {
        long nowBucket = System.currentTimeMillis() / cacheIntervalMillis(id);
        int screenW = MC.getWindow() == null ? 854 : AutismUiScale.getVirtualScreenWidth();
        int screenH = MC.getWindow() == null ? 480 : AutismUiScale.getVirtualScreenHeight();
        int playerId = MC.player == null ? 0 : MC.player.getId();
        int activeRevision = ACTIVE_MODULES.equals(id) ? PackModuleRegistry.activeRevision() : 0;
        int moduleRevision = ACTIVE_MODULES.equals(id) ? PackModuleRegistry.revision() : 0;
        return new HudCacheKey(
            state.enabled,
            state.anchor,
            state.x,
            state.y,
            state.settings.hashCode(),
            nowBucket,
            screenW,
            screenH,
            playerId,
            activeRevision,
            moduleRevision,
            System.identityHashCode(font)
        );
    }

    private static long cacheIntervalMillis(String id) {
        return switch (id) {
            case ACTIVE_MODULES, COORDINATES, NETHER_COORDS, SPEED, LOOKING_AT, BREAKING_PROGRESS, ROTATION, COMPASS -> 50L;
            case FPS, TPS, PING, REAL_TIME, WORLD_TIME, POTION_TIMERS, ITEM_COUNTER -> 250L;
            default -> 500L;
        };
    }

    private static List<HudLine> buildLines(String id) {
        List<HudLine> lines = new ArrayList<>();
        if (MC.player == null) {
            lines.add(row(id, label(id), "Preview"));
            return lines;
        }
        switch (id) {
            case ACTIVE_MODULES -> activeModuleLines(lines);
            case TPS -> lines.add(row(id, "TPS", String.format(Locale.ROOT, "%.1f", autismclient.util.macro.ServerTickTracker.getEstimatedTps())));
            case COORDINATES -> lines.add(row(id, "Pos", blockPositionText(MC.player.getX(), MC.player.getY(), MC.player.getZ())));
            case NETHER_COORDS -> lines.add(oppositeCoordsLine());
            case FPS -> lines.add(metricsLine());
            case PING -> lines.add(row(id, "Ping", autismclient.util.macro.ServerTickTracker.getPingMs() + " ms"));
            case SPEED -> lines.add(row(id, "Speed", String.format(Locale.ROOT, "%.2f b/s", blocksPerSecond)));
            case GAME_MODE -> lines.add(row(id, "Game", gameMode()));
            case DURABILITY -> lines.add(row(id, "Durability", durability()));
            case LOOKING_AT -> lines.add(row(id, "Looking", lookingAt()));
            case BREAKING_PROGRESS -> lines.add(row(id, "Breaking", breakingProgress()));
            case SERVER -> lines.add(row(id, "Server", serverName()));
            case WEATHER -> lines.add(row(id, "Weather", weather()));
            case BIOME -> lines.add(row(id, "Biome", biome()));
            case WORLD_TIME -> lines.add(row(id, "Time", worldTime()));
            case REAL_TIME -> lines.add(row(id, "Time", LocalTime.now().format(TIME_FORMAT)));
            case ROTATION -> lines.add(row(id, "Rot", String.format(Locale.ROOT, "%.1f yaw, %.1f pitch", MC.player.getYRot(), MC.player.getXRot())));
            case WATERMARK -> {
            }
            case ITEM_COUNTER -> lines.add(row(id, itemCounterLabel(), Integer.toString(itemCounter())));
            case POTION_TIMERS -> potionLines(lines);
            case COMPASS -> lines.add(row(id, "Compass", directionName()));
            default -> lines.add(row(id, label(id), ""));
        }
        if (ACTIVE_MODULES.equals(id) && lines.isEmpty()) return lines;
        if (lines.isEmpty()) lines.add(row(id, label(id), ""));
        return lines;
    }

    private static void activeModuleLines(List<HudLine> lines) {
        boolean showInfo = boolSetting(ACTIVE_MODULES, "module-info");
        boolean showKeybind = boolSetting(ACTIVE_MODULES, "show-keybind");
        String hidden = "|" + setting(ACTIVE_MODULES, "hidden-modules").toLowerCase(Locale.ROOT) + "|";
        List<PackModule> modules = new ArrayList<>(PackModuleRegistry.activeModules());
        modules.removeIf(module -> hidden.contains("|" + module.id().toLowerCase(Locale.ROOT) + "|"));
        String sort = setting(ACTIVE_MODULES, "sort");
        if ("Name".equals(sort)) modules.sort(Comparator.comparing(PackModule::name, String.CASE_INSENSITIVE_ORDER));
        else if ("Category".equals(sort)) modules.sort(Comparator.comparing((PackModule m) -> m.category().label()).thenComparing(PackModule::name));
        else modules.sort((a, b) -> {
            int widthCompare = Integer.compare(modulePlainWidth(b, showInfo, showKeybind), modulePlainWidth(a, showInfo, showKeybind));
            return widthCompare != 0 ? widthCompare : a.name().compareToIgnoreCase(b.name());
        });

        String colorMode = setting(ACTIVE_MODULES, "color-mode");
        int moduleCount = modules.size();
        for (int i = 0; i < modules.size(); i++) {
            PackModule module = modules.get(i);
            int moduleColor = activeModuleColor(module, i, colorMode, moduleCount);
            HudLine line = new HudLine();
            line.add(module.name(), moduleColor);
            String info = module.info();
            if (showInfo && info != null && !info.isBlank()) line.add(" " + info, color("module-info-color", 0xFFB79E9E));
            if (showKeybind && module.keybind() != -1) line.add(" [" + AutismBindUtil.getBindName(module.keybind()) + "]", color("module-info-color", 0xFFB79E9E));
            lines.add(line);
        }
    }

    private static int activeModuleColor(PackModule module, int index, String colorMode, int moduleCount) {
        if ("Flat".equals(colorMode)) return color(ACTIVE_MODULES, "flat-color", 0xFFFF3B3B);
        if ("Random".equals(colorMode)) return 0xFF000000 | (module.id().hashCode() & 0x00FFFFFF);
        if ("Gradient".equals(colorMode)) {
            int start = color(ACTIVE_MODULES, "gradient-start-color", 0xFFFF3B3B);
            int end = color(ACTIVE_MODULES, "gradient-end-color", 0xFFFFD6D6);
            double rows = Math.max(1.0, moduleCount - 1.0);
            double spread = doubleSetting(ACTIVE_MODULES, "rainbow-spread", 0.035) * 3.0;
            double phase = (rainbowHue + index * spread + index / rows) % 1.0;
            double t = phase < 0.5 ? phase * 2.0 : (1.0 - phase) * 2.0;
            return lerpColor(start, end, t);
        }
        float hue = (rainbowHue + index * (float) doubleSetting(ACTIVE_MODULES, "rainbow-spread", 0.035)) % 1.0f;
        float saturation = Math.min(0.35f, (float) doubleSetting(ACTIVE_MODULES, "rainbow-saturation", 0.35));
        float brightness = (float) doubleSetting(ACTIVE_MODULES, "rainbow-brightness", 1.0);
        int rgb = java.awt.Color.HSBtoRGB(hue, saturation, brightness);
        return softenColor(0xE8000000 | (rgb & 0x00FFFFFF), color(ACTIVE_MODULES, "value-color", 0xFFF3ECE7), 0.55);
    }

    private static String moduleLinePlain(PackModule module, boolean showInfo, boolean showKeybind) {
        StringBuilder line = new StringBuilder(module.name());
        String info = module.info();
        if (showInfo && info != null && !info.isBlank()) line.append(' ').append(info);
        if (showKeybind && module.keybind() != -1) line.append(" [").append(AutismBindUtil.getBindName(module.keybind())).append(']');
        return line.toString();
    }

    private static int modulePlainWidth(PackModule module, boolean showInfo, boolean showKeybind) {
        return UiText.width(MC.font, moduleLinePlain(module, showInfo, showKeybind),
            THEME.fontFor(UiTone.BODY), color(ACTIVE_MODULES, "text-color", 0xFFF3ECE7));
    }

    private static HudLine oppositeCoordsLine() {
        if (MC.level == null || MC.player == null) return row(NETHER_COORDS, "Opposite", "N/A");
        Identifier dimension = MC.level.dimension().identifier();
        String path = dimension.getPath();
        double x = MC.player.getX();
        double y = MC.player.getY();
        double z = MC.player.getZ();
        if ("overworld".equals(path)) return row(NETHER_COORDS, "Nether", blockPositionText(x / 8.0, y, z / 8.0));
        if ("the_nether".equals(path)) return row(NETHER_COORDS, "Overworld", blockPositionText(x * 8.0, y, z * 8.0));
        return row(NETHER_COORDS, "Opposite", "N/A");
    }

    private static void renderElement(GuiGraphicsExtractor context, Font font, String id, AutismConfig.HudElementState state, CachedHudElement cached, boolean editor, boolean selected, boolean hovered) {
        float alpha = 1.0f;
        HudLayout layout = cached.layout();
        int x = layout.x();
        int y = layout.y();
        int unscaledW = layout.unscaledWidth();
        int unscaledH = layout.unscaledHeight();

        int[] groupOverride = GROUP_RECT_OVERRIDE.get(id);
        if (groupOverride != null) {
            x = groupOverride[0];
            unscaledW = groupOverride[1];
        }

        if (ACTIVE_MODULES.equals(id)) {
            renderActiveModuleRows(context, font, id, cached, editor, selected, hovered, alpha);
            return;
        }
        boolean background = boolSetting(id, "background");
        VisualRect contentRect = new VisualRect(id, x, y, unscaledW, unscaledH);
        VisualRect rect = visualChromeRect(contentRect);
        if (background) {
            if (COMPASS.equals(id)) drawCompassBackground(context, rect.x(), rect.y(), rect.width(), rect.height(), alpha);
            else drawMergedBackground(context, font, id, rect, alphaColor(color(id, "background-color", 0x32191919), alpha));
        }
        else if (editor) UiText.fill(context, rect.x(), rect.y(), rect.right(), rect.bottom(), selected ? 0x382A1116 : 0x22131418);

        if (boolSetting(id, "outline") || editor || selected || hovered) {
            int border = selected ? color(id, "accent-color", 0xFFFF3B3B) : color(id, "outline-color", editor ? 0xAAFF3B3B : 0xFF750000);
            int outlineWidth = Math.max(1, intSetting(id, "outline-width", 1));
            int outlineColor = alphaColor(border, alpha);
            if (!editor && !selected && !hovered) outlineMergedRect(context, rect, mergeBlockers(font, id, rect), outlineColor, outlineWidth);
            else outline(context, rect.x(), rect.y(), rect.width(), rect.height(), outlineColor, outlineWidth);
        }

        if (WATERMARK.equals(id)) renderLogo(context, x + padding(id), y + padding(id), logoWidth(id), logoHeight(logoWidth(id)), alpha);
        else if (ARMOR.equals(id)) renderArmor(context, x + padding(id), y + padding(id));
        else if (INVENTORY.equals(id)) renderInventory(context, x + padding(id), y + padding(id));
        else if (COMPASS.equals(id)) renderCompass(context, font, id, x + padding(id), y + padding(id), unscaledW - padding(id) * 2, unscaledH - padding(id) * 2, alpha);
        else renderTextLines(context, font, id, state, cached, x, y, unscaledW, alpha);
    }

    private static void renderActiveModuleRows(GuiGraphicsExtractor context, Font font, String id, CachedHudElement cached, boolean editor, boolean selected, boolean hovered, float alpha) {
        List<HudLine> lines = cached.lines();
        List<VisualRect> rowRects = activeModuleContentRects(id, cached);
        int pad = padding(id);
        boolean background = boolSetting(id, "background");
        boolean drawOutline = boolSetting(id, "outline") || editor || selected || hovered;
        boolean shadow = boolSetting(id, "shadow");
        int backgroundColor = alphaColor(color(id, "background-color", 0x32191919), alpha);
        int editorColor = selected ? 0x382A1116 : 0x22131418;
        int border = selected ? color(id, "accent-color", 0xFFFF3B3B) : color(id, "outline-color", editor ? 0xAAFF3B3B : 0xFF8F1F24);
        int outlineColor = alphaColor(border, alpha);
        int outlineWidth = Math.max(1, intSetting(id, "outline-width", 1));

        for (int i = 0; i < lines.size() && i < rowRects.size(); i++) {
            VisualRect contentRect = rowRects.get(i);
            VisualRect rect = visualChromeRect(contentRect);
            if (occluded(rect)) continue;
            if (background) drawMergedBackground(context, font, id, rect, backgroundColor);
            else if (editor) UiText.fill(context, rect.x(), rect.y(), rect.right(), rect.bottom(), editorColor);

            if (drawOutline) {
                List<VisualRect> blockers = mergeBlockers(font, id, rect);
                outlineMergedRect(context, rect, blockers, outlineColor, outlineWidth);
            }

            renderLineSegments(context, font, lines.get(i), contentRect.x() + pad, contentRect.y() + verticalPadding(id), Math.max(1, contentRect.width() - pad * 2), alpha, shadow);
        }
    }

    private static void renderLogo(GuiGraphicsExtractor context, int x, int y, int width, int height, float alpha) {
        if (AutismSvgHudLogo.render(context, x, y, width, height, alpha)) return;
        context.blit(RenderPipelines.GUI_TEXTURED, HUD_LOGO, x, y, 0.0F, 0.0F, width, height,
            HUD_LOGO_TEXTURE_WIDTH, HUD_LOGO_TEXTURE_HEIGHT, HUD_LOGO_TEXTURE_WIDTH, HUD_LOGO_TEXTURE_HEIGHT, ARGB.white(alpha));
    }

    private static void renderTextLines(GuiGraphicsExtractor context, Font font, String id, AutismConfig.HudElementState state, CachedHudElement cached, int renderX, int renderY, int unscaledW, float alpha) {
        List<HudLine> lines = cached.lines();
        int pad = padding(id);
        int y = renderY + verticalPadding(id);
        int lineH = lineHeight(id);
        String alignment = setting(id, "alignment");
        boolean shadow = boolSetting(id, "shadow");
        int maxW = Math.max(1, unscaledW - pad * 2);
        for (int i = 0; i < lines.size(); i++) {
            HudLine line = lines.get(i);
            int lineW = i < cached.widths().size() ? cached.widths().get(i) : lineWidth(font, line);
            int tx = renderX + pad;
            if ("Center".equals(alignment)) tx = renderX + Math.max(pad, (unscaledW - lineW) / 2);
            else if ("Right".equals(alignment)) tx = renderX + Math.max(pad, unscaledW - pad - lineW);
            renderLineSegments(context, font, line, tx, y, maxW, alpha, shadow);
            y += lineH;
        }
    }

    private static void renderLineSegments(GuiGraphicsExtractor context, Font font, HudLine line, int x, int y, int maxW, float alpha, boolean shadow) {
        int drawX = x;
        int used = 0;
        for (HudSegment segment : line.segments) {
            int remaining = Math.max(1, maxW - used);
            String text = UiText.trimToWidth(font, segment.text, remaining, THEME.fontFor(UiTone.BODY), segment.color);
            UiText.draw(context, font, text, THEME.fontFor(UiTone.BODY), alphaColor(segment.color, alpha), drawX, y, shadow);
            int width = UiText.width(font, text, THEME.fontFor(UiTone.BODY), segment.color);
            drawX += width;
            used += width;
            if (used >= maxW) break;
        }
    }

    private static void renderArmor(GuiGraphicsExtractor context, int x, int y) {
        if (MC.player == null) return;
        EquipmentSlot[] slots = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
        for (int i = 0; i < slots.length; i++) {
            int sx = x + i * 18;
            drawHudSlot(context, sx, y);
            ItemStack stack = MC.player.getItemBySlot(slots[i]);
            if (!stack.isEmpty()) context.item(stack, sx + 1, y + 1);
        }
    }

    private static void renderInventory(GuiGraphicsExtractor context, int x, int y) {
        if (MC.player == null) return;
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                int sx = x + col * 18;
                int sy = y + row * 18;
                drawHudSlot(context, sx, sy);
                ItemStack stack = MC.player.getInventory().getItem(9 + row * 9 + col);
                if (!stack.isEmpty()) context.item(stack, sx + 1, sy + 1);
            }
        }
    }

    private static void drawHudSlot(GuiGraphicsExtractor context, int x, int y) {
        UiText.fill(context, x, y, x + 18, y + 18, 0x201A1215);
        UiText.fill(context, x, y, x + 18, y + 1, 0x55912E35);
        UiText.fill(context, x, y + 17, x + 18, y + 18, 0x551B0B0E);
        UiText.fill(context, x, y, x + 1, y + 18, 0x55912E35);
        UiText.fill(context, x + 17, y, x + 18, y + 18, 0x551B0B0E);
    }

    private static void renderCompass(GuiGraphicsExtractor context, Font font, String id, int x, int y, int width, int height, float alpha) {
        int stripH = Math.max(16, height);
        int centerX = x + width / 2;
        UiText.fill(context, centerX, y + 2, centerX + 1, y + stripH - 2, alphaColor(color(id, "accent-color", 0xFFFF3B3B), alpha * 0.88f));
        String[] labels = {"S", "SW", "W", "NW", "N", "NE", "E", "SE"};
        float yaw = MC.player == null ? 180.0f : MC.player.getYRot();
        double normalized = ((yaw % 360.0) + 360.0) % 360.0;
        int yText = y + Math.max(2, (stripH - THEME.fontHeight(UiTone.BODY)) / 2);
        double visibleDegrees = 118.0;
        boolean fullHeightHighlight = boolSetting(id, "background");
        for (int i = 0; i < labels.length; i++) {
            double centerAngle = i * 45.0;
            double delta = wrappedDegrees(centerAngle - normalized);
            double distance = Math.abs(delta);
            if (distance > visibleDegrees) continue;
            double presence = 1.0 - (distance / visibleDegrees);
            presence = presence * presence * (3.0 - 2.0 * presence);
            int tx = centerX + (int) Math.round(delta / visibleDegrees * (width / 2.0 - 8.0));
            double face = Math.max(0.0, 1.0 - distance / 24.0);
            int baseColor = lerpColor(color(id, "label-color", 0xFFB79E9E), color(id, "accent-color", 0xFFFF3B3B), face);
            int textColor = alphaColor(baseColor, alpha * (0.18f + (float) presence * 0.82f));
            int tw = UiText.width(font, labels[i], THEME.fontFor(UiTone.BODY), textColor);
            int halo = alphaColor(0x38280D12, alpha * (float) face);
            if (face > 0.0) {
                int haloTop = fullHeightHighlight ? y : y + 2;
                int haloBottom = fullHeightHighlight ? y + stripH : y + stripH - 2;
                UiText.fill(context, tx - tw / 2 - 3, haloTop, tx + tw / 2 + 3, haloBottom, halo);
            }
            UiText.draw(context, font, labels[i], THEME.fontFor(UiTone.BODY), alphaColor(0xCC000000, alpha * (0.24f + (float) presence * 0.36f)), tx - tw / 2 + 1, yText + 1, false);
            UiText.draw(context, font, labels[i], THEME.fontFor(UiTone.BODY), textColor, tx - tw / 2, yText, boolSetting(id, "shadow"));
        }
    }

    private static void drawCompassBackground(GuiGraphicsExtractor context, int x, int y, int width, int height, float alpha) {
        if (width <= 0 || height <= 0 || alpha <= 0.001f) return;
        int base = color(COMPASS, "background-color", 0x32191919);
        int maxAlpha = (int) (((base >>> 24) & 0xFF) * Math.max(0.0f, Math.min(1.0f, alpha)));
        int rgb = base & 0x00FFFFFF;
        int center = Math.max(1, width / 2);
        for (int i = 0; i < width; i++) {
            double edgeDistance = Math.abs(i + 0.5 - (width / 2.0)) / center;
            double presence = 1.0 - Math.min(1.0, edgeDistance);
            presence = presence * presence * (3.0 - 2.0 * presence);
            int a = (int) Math.round(maxAlpha * presence);
            if (a <= 0) continue;
            UiText.fill(context, x + i, y, x + i + 1, y + height, (a << 24) | rgb);
        }
    }

    private static double wrappedDegrees(double value) {
        double wrapped = value % 360.0;
        if (wrapped >= 180.0) wrapped -= 360.0;
        if (wrapped < -180.0) wrapped += 360.0;
        return wrapped;
    }

    private static HudLine row(String elementId, String label, String value) {
        HudLine line = new HudLine();
        line.add(label + ": ", color(elementId, "label-color", 0xFFB79E9E));
        line.add(value == null || value.isBlank() ? "N/A" : value, color(elementId, "value-color", 0xFFF3ECE7));
        return line;
    }

    private static HudLine metricsLine() {
        HudLine line = new HudLine();
        int labelColor = color(FPS, "label-color", 0xFFB79E9E);
        int valueColor = color(FPS, "value-color", 0xFFF3ECE7);
        int muted = alphaColor(labelColor, 0.72f);
        line.add("FPS: ", labelColor);
        line.add(Integer.toString(MC.getFps()), valueColor);
        line.add(" ", muted);
        line.add("TPS: ", labelColor);
        line.add(String.format(Locale.ROOT, "%.1f", autismclient.util.macro.ServerTickTracker.getEstimatedTps()), valueColor);
        line.add(" ", muted);
        line.add("Ping: ", labelColor);
        line.add(autismclient.util.macro.ServerTickTracker.getPingMs() + " ms", valueColor);
        return line;
    }

    private static String gameMode() {
        if (MC.gameMode == null) return "N/A";
        try {
            String name = MC.gameMode.getPlayerMode().getName();
            return name == null || name.isBlank() ? "N/A" : AutismRegistryLabels.identifier(name);
        } catch (Throwable ignored) {
            return MC.gameMode.toString();
        }
    }

    private static String durability() {
        ItemStack stack = MC.player == null ? ItemStack.EMPTY : MC.player.getMainHandItem();
        if (stack.isEmpty()) return "Empty";
        if (!stack.isDamageableItem()) return "No durability";
        int left = Math.max(0, stack.getMaxDamage() - stack.getDamageValue());
        int max = Math.max(1, stack.getMaxDamage());
        return String.format(Locale.ROOT, "%d / %d (%d%%)", left, max, Math.round(left * 100.0f / max));
    }

    private static String lookingAt() {
        HitResult hit = MC.hitResult;
        if (hit == null || hit.getType() == HitResult.Type.MISS) return "Nothing";
        if (hit instanceof EntityHitResult entityHit) return entityHit.getEntity().getName().getString();
        if (hit instanceof BlockHitResult blockHit && MC.level != null) {
            BlockPos pos = blockHit.getBlockPos();
            Identifier id = BuiltInRegistries.BLOCK.getKey(MC.level.getBlockState(pos).getBlock());
            return AutismRegistryLabels.block(id.toString()) + " " + blockPositionText(pos.getX(), pos.getY(), pos.getZ());
        }
        return AutismRegistryLabels.identifier(hit.getType().name());
    }

    private static String breakingProgress() {
        if (!(MC.hitResult instanceof BlockHitResult) || MC.gameMode == null) return "0%";
        try {
            AutismMultiPlayerGameModeAccessor accessor = (AutismMultiPlayerGameModeAccessor) MC.gameMode;
            if (!accessor.autism$isDestroying()) return "0%";
            return Math.round(clamp(accessor.autism$getDestroyProgress(), 0.0, 1.0) * 100.0) + "%";
        } catch (Throwable ignored) {
            return "0%";
        }
    }

    private static String serverName() {
        if (MC.getCurrentServer() != null && MC.getCurrentServer().ip != null && !MC.getCurrentServer().ip.isBlank()) return MC.getCurrentServer().ip;
        return MC.hasSingleplayerServer() ? "Singleplayer" : "N/A";
    }

    private static String weather() {
        if (MC.level == null) return "N/A";
        if (MC.level.isThundering()) return "Thunder";
        if (MC.level.isRaining()) return "Rain";
        return "Clear";
    }

    private static String biome() {
        if (MC.level == null || MC.player == null) return "N/A";
        try {
            return MC.level.getBiome(MC.player.blockPosition()).unwrapKey()
                .map(key -> AutismRegistryLabels.identifier(key.identifier().toString()))
                .orElse("Unknown");
        } catch (Throwable ignored) {
            return "Unknown";
        }
    }

    private static String worldTime() {
        if (MC.level == null) return "N/A";
        long time = currentDayTime() % 24000L;
        long hours = (time / 1000L + 6L) % 24L;
        long minutes = (time % 1000L) * 60L / 1000L;
        return String.format(Locale.ROOT, "%02d:%02d", hours, minutes);
    }

    private static long currentDayTime() {
        if (MC.level == null) return 0L;
        Long reflected = invokeLong(MC.level, "getDayTime");
        if (reflected != null) return reflected;
        reflected = invokeLong(MC.level, "dayTime");
        if (reflected != null) return reflected;
        try {
            Method method = MC.level.getClass().getMethod("getLevelData");
            Object levelData = method.invoke(MC.level);
            reflected = invokeLong(levelData, "getDayTime");
            if (reflected != null) return reflected;
            reflected = invokeLong(levelData, "dayTime");
            if (reflected != null) return reflected;
        } catch (Throwable ignored) {
        }
        return MC.level.getGameTime();
    }

    private static Long invokeLong(Object target, String methodName) {
        if (target == null) return null;
        try {
            Method method = target.getClass().getMethod(methodName);
            Object value = method.invoke(target);
            return value instanceof Number number ? number.longValue() : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String directionName() {
        if (MC.player == null) return "N";
        return switch (MC.player.getDirection()) {
            case NORTH -> "North";
            case SOUTH -> "South";
            case EAST -> "East";
            case WEST -> "West";
            case UP -> "Up";
            case DOWN -> "Down";
        };
    }

    private static String itemCounterLabel() {
        String raw = setting(ITEM_COUNTER, "item-id");
        if (raw == null || raw.isBlank()) return "Items";
        return AutismRegistryLabels.item(raw.contains(":") ? raw : "minecraft:" + raw);
    }

    private static int itemCounter() {
        if (MC.player == null) return 0;
        String raw = setting(ITEM_COUNTER, "item-id");
        if (raw == null || raw.isBlank()) return 0;
        Identifier id;
        try {
            id = Identifier.parse(raw.contains(":") ? raw : "minecraft:" + raw);
        } catch (Exception ignored) {
            return 0;
        }
        int count = 0;
        for (int i = 0; i < MC.player.getInventory().getContainerSize(); i++) {
            ItemStack stack = MC.player.getInventory().getItem(i);
            if (!stack.isEmpty() && BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(id)) count += stack.getCount();
        }
        return count;
    }

    private static String blockPositionText(double x, double y, double z) {
        return String.format(Locale.ROOT, "%d, %d, %d", (int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
    }

    private static void potionLines(List<HudLine> lines) {
        if (MC.player == null || MC.player.getActiveEffects().isEmpty()) {
            lines.add(row(POTION_TIMERS, "Effects", "None"));
            return;
        }
        for (MobEffectInstance effect : MC.player.getActiveEffects()) {
            String name = Component.translatable(effect.getDescriptionId()).getString();
            int amplifier = effect.getAmplifier() + 1;
            if (amplifier > 1) name += " " + roman(amplifier);
            int seconds = Math.max(0, effect.getDuration() / 20);
            lines.add(row(POTION_TIMERS, name, String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)));
        }
    }

    private static String roman(int value) {
        return switch (Math.max(1, Math.min(10, value))) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            case 5 -> "V";
            case 6 -> "VI";
            case 7 -> "VII";
            case 8 -> "VIII";
            case 9 -> "IX";
            default -> "X";
        };
    }

    private static int lineWidth(Font font, HudLine line) {
        int width = 0;
        for (HudSegment segment : line.segments) {
            width += UiText.width(font, segment.text, THEME.fontFor(UiTone.BODY), segment.color);
        }
        return width;
    }

    private static int lineHeight(String id) {
        return THEME.fontHeight(UiTone.BODY) + lineGap(id);
    }

    private static int activeModuleRowHeight(String id) {
        return THEME.fontHeight(UiTone.BODY) + verticalPadding(id) * 2;
    }

    private static int activeModuleStairSnap(String id) {
        return clamp(intSetting(id, "stair-snap", 2), 0, 24);
    }

    private static int lineGap(String id) {
        return clamp(intSetting(id, "line-gap", 0), 0, 10);
    }

    private static void migrateOldHud(AutismConfig config) {
        ensureStateMap(config);
        AutismConfig.ModuleState old = config.modules == null ? null : config.modules.get("hud");
        Map<String, String> settings = old == null || old.settings == null ? Map.of() : old.settings;
        int x = parseInt(settings.get("x"), 8);
        int y = parseInt(settings.get("y"), 8);
        boolean modules = parseBool(settings.get("modules"), true);
        boolean metrics = parseBool(settings.get("metrics"), true);
        boolean coords = parseBool(settings.get("coords"), true);
        putMigrated(config, ACTIVE_MODULES, modules, x, y);
        putMigrated(config, TPS, metrics, x, y + 24);
        putMigrated(config, COORDINATES, coords, x, y + 44);
        putMigrated(config, NETHER_COORDS, coords, x, y + 64);
        config.hudLayoutMigrated = true;
        config.save();
    }

    private static void putMigrated(AutismConfig config, String id, boolean enabled, int x, int y) {
        AutismConfig.HudElementState state = config.hudElements.computeIfAbsent(id, ignored -> defaultState(id));
        state.enabled = enabled;
        state.x = x;
        state.y = y;
        if (state.settings == null) state.settings = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : defaultSettings(id).entrySet()) state.settings.putIfAbsent(entry.getKey(), entry.getValue());
    }

    private static void normalizeDefaultHudStack(AutismConfig config) {
        if (config == null || config.hudElements == null) return;
        int rowH = defaultHudRowStep();
        int oldRowH = THEME.fontHeight(UiTone.BODY) + 2;
        int oldLogoBottom = logoHeight(180) + 1;
        int oldSpacedLogoBottom = logoHeight(180) + 6;
        int newLogoBottom = defaultLogoElementHeight();
        boolean changed = false;
        AutismConfig.HudElementState fps = config.hudElements.get(FPS);
        AutismConfig.HudElementState tps = config.hudElements.get(TPS);
        AutismConfig.HudElementState ping = config.hudElements.get(PING);
        AutismConfig.HudElementState speed = config.hudElements.get(SPEED);
        AutismConfig.HudElementState compass = config.hudElements.get(COMPASS);
        AutismConfig.HudElementState coordinates = config.hudElements.get(COORDINATES);
        AutismConfig.HudElementState nether = config.hudElements.get(NETHER_COORDS);
        AutismConfig.HudElementState rotation = config.hudElements.get(ROTATION);
        AutismConfig.HudElementState logo = config.hudElements.get(WATERMARK);
        if (isTopLeftAt(fps, 0, oldLogoBottom) || isTopLeftAt(fps, 0, oldSpacedLogoBottom)) {
            fps.y = newLogoBottom;
            changed = true;
        }
        if (isTopLeftAt(tps, 0, oldLogoBottom + rowH) || isTopLeftAt(tps, 0, oldSpacedLogoBottom + rowH)) {
            tps.enabled = false;
            tps.y = newLogoBottom;
            changed = true;
        }
        if (isTopLeftAt(ping, 0, oldLogoBottom + rowH * 2) || isTopLeftAt(ping, 0, oldSpacedLogoBottom + rowH * 2)) {
            ping.enabled = false;
            ping.y = newLogoBottom;
            changed = true;
        }
        if (isTopLeftAt(speed, 0, oldLogoBottom + rowH * 3) || isTopLeftAt(speed, 0, oldSpacedLogoBottom + rowH)) {
            speed.y = newLogoBottom + rowH;
            changed = true;
        }
        if (isTopLeftAt(compass, 0, oldLogoBottom + rowH * 5) || isTopLeftAt(compass, 0, oldSpacedLogoBottom + rowH * 2)) {
            compass.y = newLogoBottom + rowH * 2;
            changed = true;
        }
        int rightStackX = -HUD_SAFE_ZONE_X;
        int rightStackBottom = -HUD_SAFE_ZONE_Y;
        if (isLegacyRightCornerStack(coordinates, nether, rotation)) {
            changed |= snapHudState(coordinates, "BOTTOM_RIGHT", rightStackX, rightStackBottom - rowH * 2);
            changed |= snapHudState(nether, "BOTTOM_RIGHT", rightStackX, rightStackBottom - rowH);
            changed |= snapHudState(rotation, "BOTTOM_RIGHT", rightStackX, rightStackBottom);
        }
        if (isBottomRightStackAt(coordinates, nether, rotation, 0, oldRowH)
            || isBottomRightStackAt(coordinates, nether, rotation, 0, rowH)) {
            changed |= snapHudState(coordinates, "BOTTOM_RIGHT", rightStackX, rightStackBottom - rowH * 2);
            changed |= snapHudState(nether, "BOTTOM_RIGHT", rightStackX, rightStackBottom - rowH);
            changed |= snapHudState(rotation, "BOTTOM_RIGHT", rightStackX, rightStackBottom);
        }
        if (isLegacyCompass(compass, oldLogoBottom, oldSpacedLogoBottom, newLogoBottom, rowH)) {
            changed |= snapHudState(compass, "TOP_CENTER", 0, 2);
        }
        if (compass != null && compass.settings != null && "3".equals(compass.settings.get("padding"))) {
            compass.settings.put("padding", "1");
            changed = true;
        }
        if (compass != null && compass.settings != null && "86".equals(compass.settings.get("compass-width"))) {
            compass.settings.put("compass-width", "112");
            changed = true;
        }
        if (compass != null && compass.settings != null && !compass.settings.containsKey("compass-style-migrated")) {
            if ("true".equalsIgnoreCase(compass.settings.get("outline"))) {
                compass.settings.put("outline", "false");
                changed = true;
            }
            compass.settings.put("compass-style-migrated", "true");
            changed = true;
        }
        if (logo != null && logo.settings != null) {
            if ("false".equalsIgnoreCase(logo.settings.get("background"))) {
                logo.settings.put("background", "true");
                changed = true;
            }
            if (!"1".equals(logo.settings.get("padding"))) {
                logo.settings.put("padding", "1");
                changed = true;
            }
        }
        for (AutismConfig.HudElementState element : config.hudElements.values()) {
            if (element == null || element.settings == null) continue;
            String outlineColor = element.settings.get("outline-color");
            if ("FF8F1F24".equalsIgnoreCase(outlineColor) || "FFD3424D".equalsIgnoreCase(outlineColor)) {
                element.settings.put("outline-color", "FF750000");
                changed = true;
            }

            if ("5".equals(element.settings.get("stair-snap"))) {
                element.settings.put("stair-snap", "2");
                changed = true;
            }
            if (!element.settings.containsKey("vertical-padding")) {
                element.settings.put("vertical-padding", "0");
                changed = true;
            }
        }
        if (changed) config.save();
    }

    private static boolean isTopLeftAt(AutismConfig.HudElementState state, int x, int y) {
        return state != null && "TOP_LEFT".equals(state.anchor) && state.x == x && state.y == y;
    }

    private static boolean isLegacyRightCornerStack(
        AutismConfig.HudElementState coordinates,
        AutismConfig.HudElementState nether,
        AutismConfig.HudElementState rotation
    ) {
        int legacy = 0;
        if (isLegacyRightCornerState(coordinates)) legacy++;
        if (isLegacyRightCornerState(nether)) legacy++;
        if (isLegacyRightCornerState(rotation)) legacy++;
        return legacy >= 2;
    }

    private static boolean isLegacyRightCornerState(AutismConfig.HudElementState state) {
        if (state == null) return false;
        String anchor = state.anchor == null ? "" : state.anchor.toUpperCase(Locale.ROOT);
        if (anchor.contains("RIGHT") && (state.x > 0 || state.y > 0)) return true;
        return "TOP_LEFT".equals(anchor) && state.x >= 0 && state.x <= 16 && state.y >= 0 && state.y <= 140;
    }

    private static boolean isBottomRightStackAt(
        AutismConfig.HudElementState coordinates,
        AutismConfig.HudElementState nether,
        AutismConfig.HudElementState rotation,
        int bottomOffset,
        int rowH
    ) {
        return isAnchoredAt(coordinates, "BOTTOM_RIGHT", bottomOffset, -rowH * 2)
            && isAnchoredAt(nether, "BOTTOM_RIGHT", bottomOffset, -rowH)
            && isAnchoredAt(rotation, "BOTTOM_RIGHT", bottomOffset, 0);
    }

    private static boolean isAnchoredAt(AutismConfig.HudElementState state, String anchor, int x, int y) {
        return state != null && anchor.equals(state.anchor) && state.x == x && state.y == y;
    }

    private static boolean isLegacyCompass(AutismConfig.HudElementState state, int oldLogoBottom, int oldSpacedLogoBottom, int newLogoBottom, int rowH) {
        if (state == null) return false;
        String anchor = state.anchor == null ? "" : state.anchor.toUpperCase(Locale.ROOT);
        if ("TOP_CENTER".equals(anchor) && state.x == 0 && state.y == 2) return false;
        if (state.x != 0 && Math.abs(state.x) > 16) return false;
        return "TOP_LEFT".equals(anchor)
            || "TOP_CENTER".equals(anchor) && (state.y == newLogoBottom + rowH * 2
                || state.y == oldLogoBottom + rowH * 5
                || state.y == oldSpacedLogoBottom + rowH * 2);
    }

    private static boolean snapHudState(AutismConfig.HudElementState state, String anchor, int x, int y) {
        if (state == null) return false;
        boolean changed = !anchor.equals(state.anchor) || state.x != x || state.y != y;
        if (!changed) return false;
        state.anchor = anchor;
        state.x = x;
        state.y = y;
        return true;
    }

    private static AutismConfig.HudElementState defaultState(String id) {
        AutismConfig.HudElementState state = new AutismConfig.HudElementState();
        state.enabled = defaultEnabled(id);
        state.scale = 1.0;
        state.anchor = defaultAnchor(id);
        int[] position = defaultPosition(id);
        state.x = position[0];
        state.y = position[1];
        state.settings = new LinkedHashMap<>(defaultSettings(id));
        return state;
    }

    private static String defaultAnchor(String id) {
        return switch (id) {
            case ACTIVE_MODULES -> "TOP_RIGHT";
            case COORDINATES, NETHER_COORDS, ROTATION -> "BOTTOM_RIGHT";
            case COMPASS -> "TOP_CENTER";
            default -> "TOP_LEFT";
        };
    }

    private static int[] defaultPosition(String id) {
        int rowH = defaultHudRowStep();
        int logoBottom = defaultLogoElementHeight();
        return switch (id) {
            case WATERMARK -> new int[] {0, 0};
            case FPS -> new int[] {0, logoBottom};
            case TPS, PING -> new int[] {0, logoBottom};
            case SPEED -> new int[] {0, logoBottom + rowH};
            case ACTIVE_MODULES -> new int[] {0, 0};

            case ROTATION -> new int[] {-HUD_SAFE_ZONE_X, -HUD_SAFE_ZONE_Y};
            case NETHER_COORDS -> new int[] {-HUD_SAFE_ZONE_X, -HUD_SAFE_ZONE_Y - rowH};
            case COORDINATES -> new int[] {-HUD_SAFE_ZONE_X, -HUD_SAFE_ZONE_Y - rowH * 2};
            case ARMOR -> new int[] {0, logoBottom + rowH * 2};
            case COMPASS -> new int[] {0, 2};
            case POTION_TIMERS -> new int[] {0, logoBottom + rowH * 4};
            default -> new int[] {0, 0};
        };
    }

    private static boolean defaultEnabled(String id) {
        return ACTIVE_MODULES.equals(id)
            || WATERMARK.equals(id)
            || FPS.equals(id)
            || SPEED.equals(id)
            || COORDINATES.equals(id)
            || NETHER_COORDS.equals(id)
            || ROTATION.equals(id)
            || COMPASS.equals(id);
    }

    private static Map<String, String> defaultSettings(String id) {
        Map<String, String> cached = DEFAULT_SETTINGS_CACHE.get(id);
        if (cached != null) return cached;
        Map<String, String> settings = new LinkedHashMap<>();
        settings.put("shadow", "true");
        settings.put("background", "true");
        settings.put("background-color", "32191919");
        settings.put("text-color", "FFF3ECE7");
        settings.put("label-color", "FFB79E9E");
        settings.put("value-color", "FFF3ECE7");
        settings.put("accent-color", "FFFF3B3B");
        settings.put("alignment", "Left");
        settings.put("outline", "false");
        settings.put("outline-color", "FF750000");
        settings.put("outline-width", "1");
        settings.put("padding", "1");
        settings.put("vertical-padding", "0");
        settings.put("line-gap", "0");
        if (WATERMARK.equals(id)) {
            settings.put("shadow", "false");
            settings.put("background", "true");
            settings.put("outline", "false");
            settings.put("padding", "1");
            settings.put("logo-right-padding", "3");
            settings.put("logo-width", "180");
        }
        if (COMPASS.equals(id)) {
            settings.put("padding", "1");
            settings.put("compass-width", "112");
            settings.put("outline", "false");
        }
        if (ACTIVE_MODULES.equals(id) || COORDINATES.equals(id) || NETHER_COORDS.equals(id) || ROTATION.equals(id)) {
            settings.put("alignment", "Right");
        }
        if (ACTIVE_MODULES.equals(id)) {
            settings.put("module-info", "true");
            settings.put("show-keybind", "false");
            settings.put("sort", "Width");
            settings.put("hidden-modules", "hud");
            settings.put("color-mode", "Rainbow");
            settings.put("flat-color", "FFFF3B3B");
            settings.put("gradient-start-color", "FFFF3B3B");
            settings.put("gradient-end-color", "FFFFD6D6");
            settings.put("module-info-color", "FFB79E9E");
            settings.put("rainbow-speed", "1.0");
            settings.put("rainbow-spread", "0.035");
            settings.put("rainbow-saturation", "0.35");
            settings.put("rainbow-brightness", "1.0");
            settings.put("stair-snap", "2");
        }
        if (ITEM_COUNTER.equals(id)) settings.put("item-id", "minecraft:totem_of_undying");
        Map<String, String> immutable = Collections.unmodifiableMap(new LinkedHashMap<>(settings));
        DEFAULT_SETTINGS_CACHE.put(id, immutable);
        return immutable;
    }

    private static int padding(String id) {
        return clamp(intSetting(id, "padding", 0), 0, 16);
    }

    private static int verticalPadding(String id) {
        return clamp(intSetting(id, "vertical-padding", 0), 0, 16);
    }

    private static int defaultHudRowStep() {
        return THEME.fontHeight(UiTone.BODY);
    }

    private static int logoWidth(String id) {
        return clamp(intSetting(id, "logo-width", 180), 48, 420);
    }

    private static int logoHeight(int width) {
        return Math.max(1, Math.round(width * (HUD_LOGO_DISPLAY_HEIGHT / (float) HUD_LOGO_DISPLAY_WIDTH)));
    }

    private static int defaultLogoElementHeight() {
        return logoHeight(180) + 2;
    }

    private static int logoRightPadding(String id) {
        return clamp(intSetting(id, "logo-right-padding", 3), 0, 16);
    }

    private static int compassWidth(String id) {
        return clamp(intSetting(id, "compass-width", 112), 72, 200);
    }

    private static int anchorX(String anchor, int x, int width) {
        String normalized = anchor == null ? "" : anchor.toUpperCase(Locale.ROOT);
        if (normalized.contains("RIGHT")) {
            int sw = MC.getWindow() == null ? 854 : AutismUiScale.getVirtualScreenWidth();
            return sw + x - width;
        }
        if (normalized.contains("CENTER")) {
            int sw = MC.getWindow() == null ? 854 : AutismUiScale.getVirtualScreenWidth();
            return (sw - width) / 2 + x;
        }
        return x;
    }

    private static int anchorY(String anchor, int y, int height) {
        String normalized = anchor == null ? "" : anchor.toUpperCase(Locale.ROOT);
        if (normalized.contains("BOTTOM")) {
            int sh = MC.getWindow() == null ? 480 : AutismUiScale.getVirtualScreenHeight();
            return sh + y - height;
        }
        return y;
    }

    private static int safeContentX(String id, int x, int width) {
        int screenW = MC.getWindow() == null ? 854 : AutismUiScale.getVirtualScreenWidth();
        return clamp(x, safeZoneXFor(id), maxSafeX(id, screenW, width));
    }

    private static int safeContentY(String id, int y, int height) {
        int screenH = MC.getWindow() == null ? 480 : AutismUiScale.getVirtualScreenHeight();
        return clamp(y, safeZoneYFor(id), maxSafeY(id, screenH, height));
    }

    private static int safeZoneXFor(String id) {
        if (WATERMARK.equals(id)) return 0;
        return HUD_SAFE_ZONE_X;
    }

    private static int safeZoneYFor(String id) {
        if (WATERMARK.equals(id)) return 0;
        return HUD_SAFE_ZONE_Y;
    }

    private static int maxSafeX(String id, int screenW, int width) {
        int safe = safeZoneXFor(id);
        return Math.max(safe, screenW - Math.max(0, width) - safe);
    }

    private static int maxSafeY(String id, int screenH, int height) {
        int safe = safeZoneYFor(id);
        return Math.max(safe, screenH - Math.max(0, height) - safe);
    }

    private static int color(String key, int fallback) {
        return parseColor(setting(ACTIVE_MODULES, key), fallback);
    }

    private static int color(String id, String key, int fallback) {
        return parseColor(setting(id, key), fallback);
    }

    private static int alphaColor(int color, float alpha) {
        if (alpha >= 0.999f) return color;
        int a = (int) (((color >>> 24) & 0xFF) * Math.max(0.0f, Math.min(1.0f, alpha)));
        return (color & 0x00FFFFFF) | (a << 24);
    }

    private static int lerpColor(int from, int to, double t) {
        double clamped = Math.max(0.0, Math.min(1.0, t));
        int a0 = (from >>> 24) & 255;
        int r0 = (from >>> 16) & 255;
        int g0 = (from >>> 8) & 255;
        int b0 = from & 255;
        int a = (int) Math.round(a0 + (((to >>> 24) & 255) - a0) * clamped);
        int r = (int) Math.round(r0 + (((to >>> 16) & 255) - r0) * clamped);
        int g = (int) Math.round(g0 + (((to >>> 8) & 255) - g0) * clamped);
        int b = (int) Math.round(b0 + ((to & 255) - b0) * clamped);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static int softenColor(int color, int base, double amount) {
        return lerpColor(color, base, amount);
    }

    private static void ensureStateMap(AutismConfig config) {
        if (config.hudElements == null) config.hudElements = new LinkedHashMap<>();
    }

    private static boolean bool(PackModule module, String id, boolean fallback) {
        PackModuleOptionAccess option = new PackModuleOptionAccess(module, id);
        if (!option.exists) return fallback;
        return Boolean.parseBoolean(option.value);
    }

    private static boolean parseBool(String value, boolean fallback) {
        return value == null ? fallback : Boolean.parseBoolean(value);
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    public static int parseColor(String value, int fallback) {
        if (value == null || value.isBlank()) return fallback;
        String normalized = value.startsWith("#") ? value.substring(1) : value;
        try {
            long parsed = Long.parseLong(normalized, 16);
            if (normalized.length() == 6) parsed |= 0xFF000000L;
            return (int) parsed;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static boolean hover(int mouseX, int mouseY, ElementBounds bounds) {
        return hover(mouseX, mouseY, bounds.x, bounds.y, bounds.width, bounds.height);
    }

    private static boolean hover(int mouseX, int mouseY, int x, int y, int w, int h) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }

    private static boolean isRightAnchor(String anchor) {
        return anchor != null && anchor.toUpperCase(Locale.ROOT).contains("RIGHT");
    }

    private static void computeConnectedGroups(Font font) {
        GROUP_RECT_OVERRIDE.clear();
    }

    private static boolean combinedMetricsRowOwns(String id) {
        return (TPS.equals(id) || PING.equals(id)) && state(FPS).enabled;
    }

    private static int stableMetricsWidth(int measuredWidth) {
        long now = System.currentTimeMillis();
        int width = Math.max(1, measuredWidth);
        if (width >= metricsStableWidth) {
            metricsStableWidth = width;
            metricsWidthHoldUntil = now + 350L;
            return metricsStableWidth;
        }
        if (now < metricsWidthHoldUntil) return metricsStableWidth;
        metricsStableWidth = Math.max(width, metricsStableWidth - 6);
        return metricsStableWidth;
    }

    private static void drawMergedBackground(GuiGraphicsExtractor context, Font font, String id, VisualRect rect, int color) {
        List<VisualRect> occluders = backgroundOccluders(font, id, rect);
        drawRectWithoutOverlaps(context, rect, occluders, color);
        drawBackgroundBridges(context, rect, mergeBlockers(font, id, rect), color);
    }

    private static VisualRect bleedToScreenEdge(VisualRect rect) {
        if (rect == null) return null;
        int screenW = MC.getWindow() == null ? 854 : AutismUiScale.getVirtualScreenWidth();
        int screenH = MC.getWindow() == null ? 480 : AutismUiScale.getVirtualScreenHeight();
        int safeX = safeZoneXFor(rect.id());
        int safeY = safeZoneYFor(rect.id());
        int x = rect.x();
        int y = rect.y();
        int right = rect.right();
        int bottom = rect.bottom();
        if (x == safeX) x = 0;
        if (y == safeY) y = 0;
        if (right == screenW - safeX) right = screenW;
        if (bottom == screenH - safeY) bottom = screenH;
        x = clamp(x, 0, screenW);
        y = clamp(y, 0, screenH);
        right = clamp(right, x, screenW);
        bottom = clamp(bottom, y, screenH);
        return new VisualRect(rect.id(), x, y, Math.max(0, right - x), Math.max(0, bottom - y));
    }

    private static VisualRect visualChromeRect(VisualRect contentRect) {
        if (contentRect == null) return null;
        VisualRect rect = contentRect;
        if (boolSetting(contentRect.id(), "outline")) {
            int pad = HUD_OUTLINE_CHROME_PADDING;
            rect = new VisualRect(
                contentRect.id(),
                contentRect.x() - pad,
                contentRect.y() - pad,
                contentRect.width() + pad * 2,
                contentRect.height() + pad * 2
            );
        }
        return bleedToScreenEdge(rect);
    }

    private static List<VisualRect> backgroundOccluders(Font font, String id, VisualRect self) {
        List<VisualRect> occluders = new ArrayList<>();
        int selfOrder = ORDER.indexOf(id);
        for (String other : ORDER) {
            if (combinedMetricsRowOwns(other) || !state(other).enabled || !boolSetting(other, "background")) continue;
            int otherOrder = ORDER.indexOf(other);
            if (otherOrder > selfOrder) continue;
            for (VisualRect rect : visualRects(other, font)) {
                if (rect.sameBounds(self)) continue;
                if (otherOrder == selfOrder && !drawsBefore(rect, self)) continue;
                if (rect.intersects(self)) occluders.add(rect);
            }
        }
        return occluders;
    }

    private static boolean drawsBefore(VisualRect rect, VisualRect self) {
        if (rect.y() != self.y()) return rect.y() < self.y();
        if (rect.x() != self.x()) return rect.x() < self.x();
        if (rect.width() != self.width()) return rect.width() < self.width();
        return rect.height() < self.height();
    }

    private static void drawRectWithoutOverlaps(GuiGraphicsExtractor context, VisualRect rect, List<VisualRect> occluders, int color) {
        if (occluders.isEmpty()) {
            UiText.fill(context, rect.x(), rect.y(), rect.right(), rect.bottom(), color);
            return;
        }
        List<Integer> cuts = new ArrayList<>();
        cuts.add(rect.y());
        cuts.add(rect.bottom());
        for (VisualRect other : occluders) {
            int top = clamp(other.y(), rect.y(), rect.bottom());
            int bottom = clamp(other.bottom(), rect.y(), rect.bottom());
            if (top < bottom) {
                cuts.add(top);
                cuts.add(bottom);
            }
        }
        cuts.sort(Integer::compareTo);
        for (int i = 0; i < cuts.size() - 1; i++) {
            int bandY = cuts.get(i);
            int bandBottom = cuts.get(i + 1);
            if (bandY >= bandBottom) continue;
            List<int[]> blocked = new ArrayList<>();
            for (VisualRect other : occluders) {
                if (other.y() >= bandBottom || other.bottom() <= bandY) continue;
                int left = clamp(other.x(), rect.x(), rect.right());
                int right = clamp(other.right(), rect.x(), rect.right());
                if (left < right) blocked.add(new int[] { left, right });
            }
            drawLineSegments(context, rect.x(), rect.right(), blocked, 0, (a, b) -> UiText.fill(context, a, bandY, b, bandBottom, color));
        }
    }

    private static void drawBackgroundBridges(GuiGraphicsExtractor context, VisualRect rect, List<VisualRect> blockers, int color) {
        for (VisualRect other : blockers) {
            if (rect.right() <= other.x() && other.x() - rect.right() <= HUD_OUTLINE_MERGE_TOLERANCE) {
                int y0 = Math.max(rect.y(), other.y());
                int y1 = Math.min(rect.bottom(), other.bottom());
                if (y0 < y1 && rect.right() < other.x()) UiText.fill(context, rect.right(), y0, other.x(), y1, color);
            } else if (other.right() <= rect.x() && rect.x() - other.right() <= HUD_OUTLINE_MERGE_TOLERANCE) {
                int y0 = Math.max(rect.y(), other.y());
                int y1 = Math.min(rect.bottom(), other.bottom());
                if (y0 < y1 && other.right() < rect.x()) UiText.fill(context, other.right(), y0, rect.x(), y1, color);
            }
            if (rect.bottom() <= other.y() && other.y() - rect.bottom() <= HUD_OUTLINE_MERGE_TOLERANCE) {
                int x0 = Math.max(rect.x(), other.x());
                int x1 = Math.min(rect.right(), other.right());
                if (x0 < x1 && rect.bottom() < other.y()) UiText.fill(context, x0, rect.bottom(), x1, other.y(), color);
            } else if (other.bottom() <= rect.y() && rect.y() - other.bottom() <= HUD_OUTLINE_MERGE_TOLERANCE) {
                int x0 = Math.max(rect.x(), other.x());
                int x1 = Math.min(rect.right(), other.right());
                if (x0 < x1 && other.bottom() < rect.y()) UiText.fill(context, x0, other.bottom(), x1, rect.y(), color);
            }
        }
    }

    private static void outlineMerged(GuiGraphicsExtractor context, Font font, String id, int x, int y, int w, int h, int color, int width) {
        VisualRect rect = new VisualRect(id, x, y, w, h);
        outlineMergedRect(context, rect, mergeBlockers(font, id, rect), color, width);
    }

    private static void outlineMergedRect(GuiGraphicsExtractor context, VisualRect rect, List<VisualRect> blockers, int color, int width) {
        int size = Math.max(1, width);
        int screenW = MC.getWindow() == null ? 854 : AutismUiScale.getVirtualScreenWidth();
        int screenH = MC.getWindow() == null ? 480 : AutismUiScale.getVirtualScreenHeight();
        int x0 = clamp(rect.x(), 0, screenW);
        int x1 = clamp(rect.right(), 0, screenW);
        int y0 = clamp(rect.y(), 0, screenH);
        int y1 = clamp(rect.bottom(), 0, screenH);
        if (x0 >= x1 || y0 >= y1) return;
        if (y0 > 0) drawMergedHorizontalEdge(context, blockers, x0, x1, y0, y0, size, color, true);
        if (y1 < screenH) drawMergedHorizontalEdge(context, blockers, x0, x1, Math.max(y0, y1 - size), y1, size, color, false);
        if (x0 > 0) drawMergedVerticalEdge(context, blockers, y0, y1, x0, x0, size, color, true);
        if (x1 < screenW) drawMergedVerticalEdge(context, blockers, y0, y1, Math.max(x0, x1 - size), x1, size, color, false);
    }

    private static void drawMergedHorizontalEdge(GuiGraphicsExtractor context, List<VisualRect> blockers, int fromX, int toX, int drawY, int edgeY, int size, int color, boolean top) {
        List<int[]> blocked = new ArrayList<>();
        for (VisualRect other : blockers) {
            boolean connected = top
                ? other.y() < edgeY && other.bottom() >= edgeY - HUD_OUTLINE_MERGE_TOLERANCE
                : other.y() <= edgeY + HUD_OUTLINE_MERGE_TOLERANCE && other.bottom() > edgeY;
            if (!connected) continue;
            int overlapStart = Math.max(fromX, other.x());
            int overlapEnd = Math.min(toX, other.right());
            if (overlapStart < overlapEnd) blocked.add(new int[] { overlapStart, overlapEnd });
        }
        drawLineSegments(context, fromX, toX, blocked, size, (a, b) -> UiText.fill(context, a, drawY, b, drawY + size, color));
    }

    private static void drawMergedVerticalEdge(GuiGraphicsExtractor context, List<VisualRect> blockers, int fromY, int toY, int drawX, int edgeX, int size, int color, boolean left) {
        List<int[]> blocked = new ArrayList<>();
        for (VisualRect other : blockers) {
            boolean connected = left
                ? other.x() < edgeX && other.right() >= edgeX - HUD_OUTLINE_MERGE_TOLERANCE
                : other.x() <= edgeX + HUD_OUTLINE_MERGE_TOLERANCE && other.right() > edgeX;
            if (!connected) continue;
            int overlapStart = Math.max(fromY, other.y());
            int overlapEnd = Math.min(toY, other.bottom());
            if (overlapStart < overlapEnd) blocked.add(new int[] { overlapStart, overlapEnd });
        }
        drawLineSegments(context, fromY, toY, blocked, size, (a, b) -> UiText.fill(context, drawX, a, drawX + size, b, color));
    }

    private static List<VisualRect> mergeBlockers(Font font, String id, VisualRect self) {
        List<VisualRect> blockers = new ArrayList<>();
        for (String other : ORDER) {
            if (combinedMetricsRowOwns(other) || !state(other).enabled || !mergesWithHudOutline(other)) continue;
            if (other.equals(id) && !ACTIVE_MODULES.equals(other)) continue;
            for (VisualRect rect : visualRects(other, font)) {
                if (self != null && rect.sameBounds(self)) continue;
                blockers.add(rect);
            }
        }
        return blockers;
    }

    private static List<VisualRect> visualRects(String id, Font font) {
        CachedHudElement cached = cached(id, font);
        if (ACTIVE_MODULES.equals(id)) {
            List<VisualRect> rects = activeModuleVisualRects(id, cached);
            if (HUD_OCCLUDERS.isEmpty() || rects.isEmpty()) return rects;
            List<VisualRect> visible = new ArrayList<>(rects.size());
            for (VisualRect rect : rects) {
                if (!occluded(rect)) visible.add(rect);
            }
            return visible;
        }
        ElementBounds bounds = cached.layout().bounds();
        if (occluded(bounds)) return List.of();
        int[] ov = GROUP_RECT_OVERRIDE.get(id);
        int rx = ov != null ? ov[0] : bounds.x();
        int rw = ov != null ? ov[1] : bounds.width();
        return List.of(visualChromeRect(new VisualRect(id, rx, bounds.y(), rw, bounds.height())));
    }

    private static List<VisualRect> activeModuleVisualRects(String id, CachedHudElement cached) {
        List<VisualRect> content = activeModuleContentRects(id, cached);
        if (content.isEmpty()) return content;
        List<VisualRect> visual = new ArrayList<>(content.size());
        for (VisualRect rect : content) visual.add(visualChromeRect(rect));
        return visual;
    }

    private static List<VisualRect> activeModuleContentRects(String id, CachedHudElement cached) {
        List<VisualRect> rects = new ArrayList<>();
        HudLayout layout = cached.layout();
        int rowH = activeModuleRowHeight(id);
        int gap = lineGap(id);
        int stairSnap = activeModuleStairSnap(id);
        int groupWidth = -1;
        for (int i = 0; i < cached.lines().size(); i++) {
            int lineW = i < cached.widths().size() ? cached.widths().get(i) : lineWidth(MC.font, cached.lines().get(i));
            int rowW = Math.max(1, lineW + padding(id) * 2);
            if (groupWidth < 0 || Math.abs(groupWidth - rowW) > stairSnap) groupWidth = rowW;
            else rowW = groupWidth;
            int rowX = alignedRowX(id, layout.x(), layout.unscaledWidth(), rowW);
            int rowY = layout.y() + i * (rowH + gap);
            rects.add(new VisualRect(id, rowX, rowY, rowW, rowH));
        }
        return rects;
    }

    private static int alignedRowX(String id, int renderX, int unscaledW, int rowW) {
        String alignment = setting(id, "alignment");
        if ("Center".equals(alignment)) return renderX + Math.max(0, (unscaledW - rowW) / 2);
        if ("Right".equals(alignment)) return renderX + Math.max(0, unscaledW - rowW);
        return renderX;
    }

    private static boolean mergesWithHudOutline(String id) {
        return boolSetting(id, "background") || boolSetting(id, "outline");
    }

    private static boolean occluded(ElementBounds bounds) {
        if (bounds == null || HUD_OCCLUDERS.isEmpty()) return false;
        for (ElementBounds occluder : HUD_OCCLUDERS) {
            if (bounds.intersects(occluder)) return true;
        }
        return false;
    }

    private static boolean occluded(VisualRect rect) {
        if (rect == null || HUD_OCCLUDERS.isEmpty()) return false;
        for (ElementBounds occluder : HUD_OCCLUDERS) {
            if (rect.x() < occluder.right() && rect.right() > occluder.x()
                && rect.y() < occluder.bottom() && rect.bottom() > occluder.y()) {
                return true;
            }
        }
        return false;
    }

    private static void drawLineSegments(GuiGraphicsExtractor context, int from, int to, List<int[]> blocked, int capOverlap, SegmentDrawer drawer) {
        if (from >= to) return;
        if (blocked.isEmpty()) {
            drawer.draw(from, to);
            return;
        }
        blocked.sort(Comparator.comparingInt(a -> a[0]));
        int cursor = from;
        for (int[] block : blocked) {
            int start = clamp(block[0], from, to);
            int end = clamp(block[1], from, to);
            if (cursor < start) drawer.draw(cursor, Math.min(to, start + capOverlap));
            cursor = Math.max(cursor, end);
            if (cursor >= to) return;
        }
        if (cursor < to) drawer.draw(Math.max(from, cursor - capOverlap), to);
    }

    @FunctionalInterface
    private interface SegmentDrawer {
        void draw(int from, int to);
    }

    private static void outline(GuiGraphicsExtractor context, int x, int y, int w, int h, int color, int width) {
        int size = Math.max(1, width);
        int screenW = MC.getWindow() == null ? 854 : AutismUiScale.getVirtualScreenWidth();
        int screenH = MC.getWindow() == null ? 480 : AutismUiScale.getVirtualScreenHeight();
        int x0 = clamp(x, 0, screenW);
        int y0 = clamp(y, 0, screenH);
        int x1 = clamp(x + w, x0, screenW);
        int y1 = clamp(y + h, y0, screenH);
        if (x0 >= x1 || y0 >= y1) return;
        if (y0 > 0) UiText.fill(context, x0, y0, x1, Math.min(y1, y0 + size), color);
        if (y1 < screenH) UiText.fill(context, x0, Math.max(y0, y1 - size), x1, y1, color);
        if (x0 > 0) UiText.fill(context, x0, y0, Math.min(x1, x0 + size), y1, color);
        if (x1 < screenW) UiText.fill(context, Math.max(x0, x1 - size), y0, x1, y1, color);
    }

    public record ElementBounds(String id, int x, int y, int width, int height) {
        public int right() {
            return x + width;
        }

        public int bottom() {
            return y + height;
        }

        public boolean intersects(ElementBounds other) {
            return other != null && x < other.right() && right() > other.x()
                && y < other.bottom() && bottom() > other.y();
        }
    }

    public record HudLayout(String id, int x, int y, int unscaledWidth, int unscaledHeight, double scale) {
        public int scaledWidth() {
            return (int) Math.ceil(unscaledWidth * scale);
        }

        public int scaledHeight() {
            return (int) Math.ceil(unscaledHeight * scale);
        }

        public ElementBounds bounds() {
            return new ElementBounds(id, x, y, scaledWidth(), scaledHeight());
        }
    }

    private record HudCacheKey(
        boolean enabled,
        String anchor,
        int x,
        int y,
        int settingsHash,
        long timeBucket,
        int screenWidth,
        int screenHeight,
        int playerId,
        int activeRevision,
        int moduleRevision,
        int fontIdentity
    ) {
    }

    private record CachedHudElement(HudCacheKey signature, List<HudLine> lines, List<Integer> widths, HudLayout layout) {
    }

    private record VisualRect(String id, int x, int y, int width, int height) {
        int right() {
            return x + width;
        }

        int bottom() {
            return y + height;
        }

        boolean sameBounds(VisualRect other) {
            return other != null && x == other.x && y == other.y && width == other.width && height == other.height;
        }

        boolean intersects(VisualRect other) {
            return other != null && x < other.right() && right() > other.x() && y < other.bottom() && bottom() > other.y();
        }
    }

    private static final class HudLine {
        private final List<HudSegment> segments = new ArrayList<>();

        void add(String text, int color) {
            if (text != null && !text.isEmpty()) segments.add(new HudSegment(text, color));
        }

        String plainText() {
            StringBuilder builder = new StringBuilder();
            for (HudSegment segment : segments) builder.append(segment.text);
            return builder.toString();
        }
    }

    private record HudSegment(String text, int color) {
    }

    private record PackModuleOptionAccess(boolean exists, String value) {
        PackModuleOptionAccess(PackModule module, String id) {
            this(module != null && module.option(id) != null, module != null && module.option(id) != null ? module.value(id) : "");
        }
    }
}
