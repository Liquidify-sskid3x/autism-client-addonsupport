package autismclient.util;

import autismclient.gui.vanillaui.assets.UiAssets;
import autismclient.gui.vanillaui.components.CompactControlGlyphs;
import autismclient.gui.vanillaui.direct.DirectLayout;
import autismclient.gui.vanillaui.components.CompactOverlayButton;
import autismclient.gui.vanillaui.components.CompactOverlayControls;
import autismclient.gui.vanillaui.components.CompactSurfaces;
import autismclient.gui.vanillaui.components.CompactScrollbar;
import autismclient.gui.vanillaui.components.ScrollState;
import autismclient.gui.vanillaui.components.UiSizing;
import autismclient.gui.vanillaui.components.UiText;
import autismclient.gui.vanillaui.components.CompactTheme;
import autismclient.gui.vanillaui.components.UiTone;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

public class AutismPacketLoggerOverlay extends AutismOverlayBase {
    private static final Minecraft MC = Minecraft.getInstance();
    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

    private static final int HEADER_H = HEADER_HEIGHT;
    private static final int GROUP_THRESHOLD = 10;
    private static final int DEFAULT_PANEL_WIDTH = 323;
    private static final int DEFAULT_PANEL_HEIGHT = 250;

    private static final int CAP_ALL = 800;
    private static final int CAP_INVENTORY = 400;
    private static final int CAP_MOVEMENT = 200;
    private static final int CAP_PAYLOAD = 200;
    private static final long UI_FLUSH_INTERVAL_MS = 500L;
    private static final int BLOCKED_DEFAULTS_VERSION = 2;

    private static final Set<String> DEFAULT_BLOCKED_NAMES_V2_ADDITIONS = createIgnoredPacketKeyAdditionsV2();
    private static final Set<String> DEFAULT_BLOCKED_NAMES = createIgnoredPacketKeys();

    public enum Category {
        ALL("All"), INVENTORY("INV"), MOVEMENT("Move"), PAYLOAD("Payload");
        public final String label;
        Category(String l) { this.label = l; }
    }

    private static final Set<String> INVENTORY_NAMES = new HashSet<>(Arrays.asList(
        "ClickSlot", "CloseAbstractContainerScreen", "OpenScreen", "AbstractContainerMenu",
        "CreativeInventoryAction", "PickFromInventory", "PlayerAction",
        "InventoryS2C", "AbstractContainerMenuSlotUpdate", "AbstractContainerMenuProperty",
        "SetTradeOffers", "OpenHorseScreen", "CraftRequest",
        "ButtonClick", "RecipeBookData", "UpdateSelectedSlot",
        "HandSwing", "PlayerInteractBlock", "PlayerInteractItem",
        "PlayerInteractEntity", "ItemPickupAnimation",
        "ContainerClick", "ContainerClose", "ContainerSetContent", "ContainerSetData",
        "ContainerSetSlot", "ContainerButtonClick", "SetCreativeModeSlot",
        "SetCarriedItem", "Swing", "UseItem", "UseItemOn", "Interact",
        "SetCursorItem", "SetPlayerInventory", "TakeItemEntity", "MerchantOffers"
    ));

    private static final Set<String> MOVEMENT_NAMES = new HashSet<>(Arrays.asList(
        "PlayerMove", "PlayerMoveFull", "PlayerMovePositionAndOnGround",
        "PlayerMoveLookAndOnGround", "PlayerMoveOnGroundOnly",
        "EntityPosition", "EntityPositionSync", "EntitySetHead",
        "EntityVelocityUpdate", "VehicleMove",
        "MoveRelative", "PacketMoveRelative", "RotateRelative",
        "PacketRotateRelative", "EntityPacketRotate",
        "EntityMoveRelative", "EntityRotate", "TeleportConfirm",
        "ClientTickEnd",
        "ServerboundMovePlayer", "ServerboundMoveVehicle", "ServerboundPlayerInput",
        "ServerboundAcceptTeleportation", "ClientboundMoveEntity", "ClientboundMoveVehicle",
        "ClientboundMoveMinecart", "ClientboundPlayerPosition", "ClientboundPlayerRotation",
        "ClientboundEntityPositionSync", "ClientboundSetEntityMotion", "ClientboundRotateHead",
        "ClientboundTeleportEntity"
    ));

    private static final Set<String> PAYLOAD_NAMES = new HashSet<>(Arrays.asList(
        "CustomPacketPayload",
        "CustomPacketPayloadC2S",
        "CustomPacketPayloadS2C",
        "BrandPayload",
        "PluginMessage",
        "CustomPayload",
        "S2CPayload",
        "DiscardedPayload"
    ));

    private final Font textRenderer;
    private final AutismPacketInspectOverlay inspectOverlay;
    private final BlockedPacketListOverlay blockedListOverlay;
    private final CompactTheme theme = new CompactTheme();
    private final AutismContextMenu<LogEntry> ctxMenu;
    private int PANEL_WIDTH = DEFAULT_PANEL_WIDTH;
    private int PANEL_HEIGHT = DEFAULT_PANEL_HEIGHT;
    private int currentPanelHeight = DEFAULT_PANEL_HEIGHT;
    private boolean paused = true;
    private boolean isDragging;
    private double dragOffX, dragOffY;
    private double headerPressMouseX;
    private double headerPressMouseY;
    private int headerPressPanelX;
    private int headerPressPanelY;
    private boolean headerDragMoved;
    private int scrollOffset;
    private final ScrollState contentScrollState = new ScrollState();
    private boolean scrollbarDragging;
    private int scrollbarGrabOffset;

    private String searchFilter = "";
    private final AutismChatField searchField;
    private Category activeTab = Category.ALL;
    private boolean groupingEnabled = true;
    private int dirFilter = 0;

    private final Set<String> blockedNames = new LinkedHashSet<>();
    private final Set<String> blockedNormalized = new HashSet<>();
    private final Map<Class<?>, Boolean> blockedClassCache = new HashMap<>();
    private boolean blockedExpanded;

    private final List<LogEntry> bufAll = new CopyOnWriteArrayList<>();
    private final List<LogEntry> bufInventory = new CopyOnWriteArrayList<>();
    private final List<LogEntry> bufMovement = new CopyOnWriteArrayList<>();
    private final List<LogEntry> bufPayload = new CopyOnWriteArrayList<>();
    private final List<LogEntry> pendingEntries = new ArrayList<>();

    private int gameTick;
    private long lastUiFlushMs;

    private List<DisplayRow> displayRows = new ArrayList<>();
    private boolean dirty = true;

    private final Set<String> expandedGroups = new HashSet<>();

    public AutismPacketLoggerOverlay(Font textRenderer) {
        super("AutismPacketLoggerOverlay", DEFAULT_PANEL_WIDTH, DEFAULT_PANEL_HEIGHT);
        this.textRenderer = textRenderer;
        this.PANEL_WIDTH = defaultPanelWidth();
        this.PANEL_HEIGHT = defaultPanelHeight();
        this.currentPanelHeight = this.PANEL_HEIGHT;
        this.panelX = 200;
        this.panelY = 40;
        this.searchField = new AutismChatField(MC, textRenderer, 0, 0, searchFieldWidth(), filterRowHeight(), false);
        this.searchField.setPlaceholder(Component.literal("Search..."));
        this.searchField.setMaxLength(160);
        this.searchField.setChangedListener(value -> {
            searchFilter = value == null ? "" : value;
            dirty = true;
        });
        this.inspectOverlay = new AutismPacketInspectOverlay(textRenderer);
        this.blockedListOverlay = new BlockedPacketListOverlay();
        this.ctxMenu = new AutismContextMenu<>(theme, textRenderer, this::getCtxItems, lineHeight());
        setContextMenu(ctxMenu);
        AutismOverlayManager.get().register(this.inspectOverlay);
        AutismOverlayManager.get().register(this.blockedListOverlay);
        loadBlockedFromConfig();

        this.paused = !AutismConfig.getGlobal().packetLoggerCapturing;
    }

    @Override
    public int getMinWidth() {
        return defaultPanelWidth();
    }

    @Override
    public int getMinHeight() {
        return defaultPanelHeight();
    }

    @Override
    public AutismWindowLayout getBounds() {
        return new AutismWindowLayout(panelX, panelY, PANEL_WIDTH, PANEL_HEIGHT, visible, collapsed);
    }

    @Override
    public void setBounds(AutismWindowLayout bounds) {
        if (bounds == null) return;
        AutismWindowLayout clamped = clampToScreen(this, bounds);
        panelX = clamped.x;
        panelY = clamped.y;
        PANEL_WIDTH = clamped.width;
        PANEL_HEIGHT = clamped.height;
        visible = clamped.visible;
        collapsed = clamped.collapsed;
    }

    public void setGameTick(int t) { gameTick = t; }
    public boolean isPaused() { return paused; }
    public synchronized void setPaused(boolean paused) {
        if (this.paused == paused) return;
        if (paused) {
            flushPendingLocked();
        } else {

            lastUiFlushMs = System.currentTimeMillis();
        }
        this.paused = paused;
        dirty = true;

        AutismConfig config = AutismConfig.getGlobal();
        if (config.packetLoggerCapturing != !paused) {
            config.packetLoggerCapturing = !paused;
            config.save();
        }
    }

    public synchronized void logPacket(Packet<?> packet, String direction) {
        logPacket(packet, direction, false, false);
    }

    public synchronized void logPayloadPacketSilently(Packet<?> packet, String direction) {
        logPacket(packet, direction, true, true);
    }

    public synchronized void logPayloadSnapshotSilently(long timestampMs, int tick, String direction, Class<?> packetClass,
                                                        AutismPayloadSupport.PayloadSnapshot payloadSnapshot) {
        if (payloadSnapshot == null) return;
        String name = packetClass != null && Packet.class.isAssignableFrom(packetClass)
            ? friendlyNameForClass(packetClass)
            : "Custom Payload";
        boolean isInventory = matchesAny(name, INVENTORY_NAMES);
        boolean isMovement = matchesAny(name, MOVEMENT_NAMES);
        LogEntry e = new LogEntry(timestampMs, tick, direction, name, packetClass, null, isInventory, isMovement, true,
            null, null, payloadSnapshot);
        pendingEntries.add(e);
        maybeFlushPendingLocked(true);
    }

    @SuppressWarnings("unchecked")
    private static String friendlyNameForClass(Class<?> packetClass) {
        try {
            return AutismPacketNamer.getFriendlyName((Class<? extends Packet<?>>) packetClass);
        } catch (Throwable ignored) {
            return packetClass == null ? "Custom Payload" : packetClass.getSimpleName();
        }
    }

    private void logPacket(Packet<?> packet, String direction, boolean ignorePaused, boolean payloadOnly) {
        if (packet == null) return;
        if (!ignorePaused && paused) return;
        Class<?> cls = packet.getClass();
        String name = AutismPacketNamer.getFriendlyName(packet, direction);
        if (isBlockedName(cls, name)) return;
        boolean isInventory = matchesAny(name, INVENTORY_NAMES);
        boolean isMovement = matchesAny(name, MOVEMENT_NAMES);
        boolean isPayload = isPayloadPacket(cls, name);
        if (payloadOnly && !isPayload) return;
        LogCaptureContext captureContext = captureLogContext(packet);
        AutismPayloadSupport.PayloadSnapshot payloadSnapshot = isPayload ? AutismPayloadSupport.snapshot(packet, direction) : null;

        LogEntry e = new LogEntry(System.currentTimeMillis(), gameTick, direction, name, cls, packet, isInventory, isMovement, isPayload,
            captureContext.blockStateSummary(), captureContext.screenSummary(), payloadSnapshot);
        pendingEntries.add(e);
        maybeFlushPendingLocked(false);
    }

    private static LogCaptureContext captureLogContext(Packet<?> packet) {
        if (packet == null || MC == null || !MC.isSameThread()) return LogCaptureContext.EMPTY;
        try {
            if (packet instanceof ServerboundPlayerActionPacket actionPacket) {
                return new LogCaptureContext(snapshotBlockState(actionPacket.getPos()), null);
            }
            if (packet instanceof ServerboundUseItemOnPacket interactBlockPacket) {
                return new LogCaptureContext(snapshotBlockState(interactBlockPacket.getHitResult().getBlockPos()), null);
            }
            if (packet instanceof ServerboundContainerClosePacket) {
                return new LogCaptureContext(null, snapshotCurrentScreen());
            }
        } catch (Throwable ignored) {
        }
        return LogCaptureContext.EMPTY;
    }

    private static String snapshotBlockState(BlockPos pos) {
        if (pos == null || MC.level == null) return null;
        try {
            BlockState state = MC.level.getBlockState(pos);
            if (state == null) return null;
            return BuiltInRegistries.BLOCK.getKey(state.getBlock()) + " " + state;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String snapshotCurrentScreen() {
        if (MC.screen == null) return null;
        try {
            String title = MC.screen.getTitle() == null ? "" : MC.screen.getTitle().getString().trim();
            String className = MC.screen.getClass().getSimpleName();
            if (title.isEmpty()) return className;
            if (className == null || className.isBlank()) return title;
            return title + " [" + className + "]";
        } catch (Throwable ignored) {
            return null;
        }
    }

    public boolean isPacketBlocked(Class<?> cls) { return isBlockedClass(cls); }

    private static boolean matchesAny(String name, Set<String> set) {
        for (String s : set) { if (name.contains(s)) return true; }
        return false;
    }

    private static boolean isPayloadPacket(Class<?> cls, String name) {
        if (matchesAny(name, PAYLOAD_NAMES)) return true;
        if (cls == null) return false;
        String simpleName = cls.getSimpleName();
        if (matchesAny(simpleName, PAYLOAD_NAMES)) return true;
        String className = cls.getName();
        return matchesAny(className, PAYLOAD_NAMES);
    }

    private static Set<String> createIgnoredPacketKeys() {
        Set<String> keys = new LinkedHashSet<>();

        registerIgnoredPackets(keys,

            "ServerboundMovePlayerPacket.Pos",
            "ServerboundMovePlayerPacket.PosRot",
            "ServerboundMovePlayerPacket.Rot",
            "ServerboundMovePlayerPacket.StatusOnly",
            "ServerboundClientTickEndPacket",
            "ServerboundPlayerInputPacket",
            "ServerboundAcceptTeleportationPacket",
            "ServerboundChunkBatchReceivedPacket",
            "ServerboundPlayerCommandPacket",
            "ServerboundKeepAlivePacket",
            "ServerboundPongPacket",

            "ClientboundBundlePacket",
            "ClientboundBundleDelimiterPacket",
            "ClientboundKeepAlivePacket",
            "ClientboundPingPacket",

            "ClientboundMoveEntityPacket.Pos",
            "ClientboundMoveEntityPacket.PosRot",
            "ClientboundMoveEntityPacket.Rot",
            "ClientboundEntityPositionSyncPacket",
            "ClientboundSetEntityMotionPacket",
            "ClientboundRotateHeadPacket",
            "ClientboundTeleportEntityPacket",
            "ClientboundMoveMinecartPacket",
            "ClientboundSetEntityDataPacket",
            "ClientboundUpdateAttributesPacket",
            "ClientboundEntityEventPacket",

            "ClientboundLightUpdatePacket",
            "ClientboundLevelChunkWithLightPacket",
            "ClientboundForgetLevelChunkPacket",
            "ClientboundChunkBatchStartPacket",
            "ClientboundChunkBatchFinishedPacket",
            "ClientboundSetChunkCacheCenterPacket",
            "ClientboundChunksBiomesPacket",
            "ClientboundSectionBlocksUpdatePacket",
            "ClientboundBlockUpdatePacket",
            "ClientboundBlockEntityDataPacket",

            "ClientboundSetTimePacket",
            "ClientboundLevelParticlesPacket",
            "ClientboundLevelEventPacket",
            "ClientboundSoundPacket",
            "ClientboundSoundEntityPacket",

            "ClientboundPlayerInfoUpdatePacket",
            "ClientboundSetObjectivePacket",
            "ClientboundTickingStatePacket"
        );
        keys.addAll(DEFAULT_BLOCKED_NAMES_V2_ADDITIONS);
        return Collections.unmodifiableSet(keys);
    }

    private static Set<String> createIgnoredPacketKeyAdditionsV2() {
        Set<String> keys = new LinkedHashSet<>();
        registerIgnoredPackets(keys,
            "ClientboundSetScorePacket",
            "ClientboundSetEquipmentPacket",
            "ClientboundAnimatePacket",
            "ClientboundTabListPacket",
            "ClientboundBlockDestructionPacket",
            "ClientboundPlayerPositionPacket",
            "ClientboundExplodePacket",
            "ClientboundRemoveEntitiesPacket",
            "ClientboundBlockEventPacket",
            "ClientboundSetPlayerTeamPacket",
            "ClientboundSystemChatPacket"
        );
        return Collections.unmodifiableSet(keys);
    }

    private static void registerIgnoredPackets(Set<String> keys, String... names) {
        for (String name : names) {
            registerIgnoredPacket(keys, name);
        }
    }

    private static void registerIgnoredPacket(Set<String> keys, String name) {
        if (name == null || name.isBlank()) return;
        keys.add(name.trim());
    }

    private static String normalizePacketKey(String value) {
        if (value == null || value.isEmpty()) return "";
        String lower = value.toLowerCase(Locale.ROOT);
        StringBuilder normalized = new StringBuilder(lower.length());
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                normalized.append(c);
            }
        }
        return normalized.toString();
    }

    private void recomputeBlockedNormalized() {
        blockedNormalized.clear();
        blockedClassCache.clear();
        for (String name : blockedNames) {
            if (name == null || name.isBlank()) continue;
            blockedNormalized.add(normalizePacketKey(name));
            if (name.endsWith("Packet")) {
                blockedNormalized.add(normalizePacketKey(name.substring(0, name.length() - 6)));
            }
        }
    }

    private boolean isBlockedName(Class<?> cls, String friendlyName) {
        if (blockedNormalized.isEmpty()) return false;
        if (blockedNormalized.contains(normalizePacketKey(friendlyName))) return true;
        return isBlockedClass(cls);
    }

    private boolean isBlockedClass(Class<?> cls) {
        if (cls == null || blockedNormalized.isEmpty()) return false;
        Boolean cached = blockedClassCache.get(cls);
        if (cached != null) return cached;
        boolean blocked = computeBlockedClass(cls);
        blockedClassCache.put(cls, blocked);
        return blocked;
    }

    private boolean computeBlockedClass(Class<?> cls) {
        if (blockedNormalized.contains(normalizePacketKey(cls.getSimpleName()))) return true;
        if (blockedNormalized.contains(normalizePacketKey(cls.getName()))) return true;
        if (Packet.class.isAssignableFrom(cls)) {
            @SuppressWarnings("unchecked")
            Class<? extends Packet<?>> packetClass = (Class<? extends Packet<?>>) cls;
            if (blockedNormalized.contains(normalizePacketKey(AutismPacketNamer.getFriendlyName(packetClass)))) return true;
        }
        return false;
    }

    private void loadBlockedFromConfig() {
        AutismConfig config = AutismConfig.getGlobal();
        blockedNames.clear();
        if (config.packetLoggerBlockedInit) {
            if (config.packetLoggerBlocked != null) blockedNames.addAll(config.packetLoggerBlocked);
            if (config.packetLoggerBlockedDefaultsVersion < BLOCKED_DEFAULTS_VERSION) {
                blockedNames.addAll(DEFAULT_BLOCKED_NAMES_V2_ADDITIONS);
                config.packetLoggerBlocked = new ArrayList<>(blockedNames);
                config.packetLoggerBlockedDefaultsVersion = BLOCKED_DEFAULTS_VERSION;
                config.save();
            }
        } else {
            blockedNames.addAll(DEFAULT_BLOCKED_NAMES);
            config.packetLoggerBlocked = new ArrayList<>(blockedNames);
            config.packetLoggerBlockedInit = true;
            config.packetLoggerBlockedDefaultsVersion = BLOCKED_DEFAULTS_VERSION;
            config.save();
        }
        recomputeBlockedNormalized();
    }

    private void saveBlocked() {
        AutismConfig config = AutismConfig.getGlobal();
        config.packetLoggerBlocked = new ArrayList<>(blockedNames);
        config.packetLoggerBlockedInit = true;
        config.packetLoggerBlockedDefaultsVersion = BLOCKED_DEFAULTS_VERSION;
        config.save();
    }

    public synchronized void blockPacketName(String name) {
        if (name == null || name.isBlank()) return;
        if (blockedNames.add(name.trim())) {
            recomputeBlockedNormalized();
            saveBlocked();
        }
        purgeBlockedEntries();
    }

    private synchronized void unblockName(String name) {
        if (blockedNames.remove(name)) {
            recomputeBlockedNormalized();
            saveBlocked();
        }
    }

    private synchronized void resetBlockedToDefault() {
        blockedNames.clear();
        blockedNames.addAll(DEFAULT_BLOCKED_NAMES);
        recomputeBlockedNormalized();
        saveBlocked();
        purgeBlockedEntries();
    }

    private synchronized void clearBlocked() {
        blockedNames.clear();
        recomputeBlockedNormalized();
        saveBlocked();
    }

    private void purgeBlockedEntries() {
        bufAll.removeIf(e -> isBlockedName(e.packetClass, e.shortName));
        bufInventory.removeIf(e -> isBlockedName(e.packetClass, e.shortName));
        bufMovement.removeIf(e -> isBlockedName(e.packetClass, e.shortName));
        bufPayload.removeIf(e -> isBlockedName(e.packetClass, e.shortName));
        dirty = true;
    }

    public static boolean isPayloadPacket(Packet<?> packet) {
        if (packet == null) return false;
        if (AutismPayloadSupport.extractPayload(packet) != null) return true;
        Class<?> cls = packet.getClass();
        return isPayloadPacket(cls, AutismPacketNamer.getFriendlyName(packet, ""));
    }

    private static void addCapped(List<LogEntry> buf, LogEntry e, int cap) {
        buf.add(e);
        int excess = buf.size() - cap;
        if (excess > 0) {

            buf.subList(0, excess).clear();
        }
    }

    private void maybeFlushPending() {
        synchronized (this) {
            maybeFlushPendingLocked(false);
        }
    }

    private void maybeFlushPendingLocked(boolean force) {
        long now = System.currentTimeMillis();
        if (!force) {
            if (pendingEntries.isEmpty()) return;
            if (lastUiFlushMs != 0L && now - lastUiFlushMs < UI_FLUSH_INTERVAL_MS) return;
        }
        flushPendingLocked();
        lastUiFlushMs = now;
    }

    private void flushPending() {
        synchronized (this) {
            flushPendingLocked();
            lastUiFlushMs = System.currentTimeMillis();
        }
    }

    private void flushPendingLocked() {
        if (pendingEntries.isEmpty()) return;

        for (LogEntry e : pendingEntries) {
            addCapped(bufAll, e, CAP_ALL);
            if (e.isInventory) addCapped(bufInventory, e, CAP_INVENTORY);
            if (e.isMovement) addCapped(bufMovement, e, CAP_MOVEMENT);
            if (e.isPayload) addCapped(bufPayload, e, CAP_PAYLOAD);
        }

        pendingEntries.clear();
        dirty = true;
    }

    @Override public void setVisible(boolean v) {
        visible = v;
        if (v) { scrollOffset = 0; contentScrollState.jumpTo(0, 0); dirty = true; ctxMenu.close(); AutismOverlayManager.get().bringToFront(this); }
        else inspectOverlay.close();
        saveLayout();
    }
    public void toggle() { setVisible(!visible); }
    @Override public boolean isVisible() { return visible; }
    @Override public boolean isCollapsed() { return collapsed; }
    @Override public void setCollapsed(boolean c) {
        if (collapsed == c) return;
        collapsed = c;
        isDragging = false;
        headerDragMoved = false;
        scrollbarDragging = false;
        if (c) {
            clearHiddenInteractionState();
            ctxMenu.close();
        }
        saveLayout();
    }
    @Override public boolean usesSharedHeaderClickCollapse() { return true; }
    @Override public boolean hasTextFieldFocused() { return searchField != null && searchField.isFocused(); }
    @Override public void clearTextFieldFocus() { if (searchField != null) searchField.setFocused(false); }
    @Override public int getZLevel() { return 10; }

    private void drawUiText(GuiGraphicsExtractor context, String text, UiTone tone, int color, int x, int y) {
        UiText.draw(context, textRenderer, text, theme.fontFor(tone), color, x, y, false);
    }

    @Override public boolean isMouseOver(double mx, double my) {
        if (!visible) return false;
        int panelHeight = collapsed ? HEADER_H : currentPanelHeight;
        AutismWindowLayout bounds = new AutismWindowLayout(panelX, panelY, PANEL_WIDTH, panelHeight, visible, collapsed);
        int h = getRenderedFrameHeight(bounds, collapsed);
        boolean overPanel = mx >= panelX && mx <= panelX + PANEL_WIDTH && my >= panelY && my <= panelY + h;
        overPanel |= ctxMenu.isMouseOver(mx, my);
        return overPanel;
    }

    @Override public boolean isOverDragBar(double mx, double my) {
        if (!visible) return false;
        return mx >= panelX && mx <= panelX + PANEL_WIDTH
            && my >= panelY && my <= panelY + HEADER_H
            && !isOverWindowControl(mx, my, getBounds());
    }

    @Override
    public void render(GuiGraphicsExtractor ctx, int mx, int my, float delta) {
        if (!visible) return;
        maybeFlushPending();
        if (dirty) { rebuildDisplay(); dirty = false; }

        AutismWindowLayout clamped = clampToScreen(this, new AutismWindowLayout(panelX, panelY, PANEL_WIDTH, calcPanelH(), visible, collapsed));
        panelX = clamped.x;
        panelY = clamped.y;
        PANEL_WIDTH = clamped.width;
        currentPanelHeight = clamped.height;
        int ph = currentPanelHeight;

        int total = 0;
        for (DisplayRow r : displayRows) total += (r.type == RowType.GROUP) ? r.groupCount : 1;

        int bodyMx = mx;
        int bodyMy = my;
        if (ctxMenu.isMouseOver(mx, my)) {
            bodyMx = AutismOverlayManager.HOVER_BLOCKED_MOUSE;
            bodyMy = AutismOverlayManager.HOVER_BLOCKED_MOUSE;
        }

        String title = "Packet Logger";
        AutismWindowLayout bounds = new AutismWindowLayout(panelX, panelY, PANEL_WIDTH, ph, visible, collapsed);
        renderWindowFrame(ctx, bodyMx, bodyMy, bounds, title, collapsed, isDragging);
        boolean clipBody = beginWindowBodyClip(ctx, bounds, collapsed);
        if (!clipBody) {
            renderWindowInactiveOverlay(ctx, bounds, collapsed, isDragging);
            return;
        }

        try {

            int tabY = panelY + HEADER_H + 2;
            renderTabs(ctx, bodyMx, bodyMy, tabY, total);

            int filterY = tabY + tabHeight() + 2;
            renderFilterBar(ctx, bodyMx, bodyMy, filterY);

            int contentY = filterY + filterHeight() + 2;
            int contentEndY = contentY + contentAreaHeight();

            if (displayRows.isEmpty()) {
                drawUiText(ctx, "No packets matching filters", UiTone.MUTED, AutismColors.textDim(), panelX + 10, contentY + 6);
            } else {
                int contentHeight = displayRows.size() * lineHeight();
                int viewHeight = contentAreaHeight();
                int maxScroll = Math.max(0, contentHeight - viewHeight);
                scrollOffset = quantizeScrollOffset(scrollOffset, lineHeight(), maxScroll);
                contentScrollState.setTarget(scrollOffset, maxScroll);
                int drawScroll = contentScrollState.tick(delta, maxScroll);
                autismclient.gui.vanillaui.UiScissorStack.global().push(ctx,
                    autismclient.gui.vanillaui.UiBounds.of(panelX, contentY, PANEL_WIDTH, contentEndY - contentY));
                int drawBase = contentY - drawScroll;
                for (int i = 0; i < displayRows.size(); i++) {
                    int ey = drawBase + i * lineHeight();
                    if (ey + lineHeight() > contentY && ey < contentEndY) {
                        DisplayRow row = displayRows.get(i);
                        if (row.type == RowType.GROUP) renderGroup(ctx, row, ey, bodyMx, bodyMy);
                        else renderEntry(ctx, row.entry, ey, bodyMx, bodyMy);
                    }
                }
                autismclient.gui.vanillaui.UiScissorStack.global().pop(ctx);
                CompactScrollbar.Metrics scrollbarMetrics = getContentScrollbarMetrics();
                CompactScrollbar.draw(ctx, scrollbarMetrics, scrollbarMetrics.contains(bodyMx, bodyMy), scrollbarDragging);
            }

        } finally {
            endWindowBodyClip(ctx, clipBody);
            renderWindowInactiveOverlay(ctx, bounds, collapsed, isDragging);
        }

        if (ctxMenu.isOpen()) {

            ctx.nextStratum();
            ctxMenu.render(ctx, mx, my);
        }
    }

    private void renderTabs(GuiGraphicsExtractor ctx, int mx, int my, int y, int total) {
        int x = panelX + 4;
        for (Category cat : Category.values()) {
            int w = DirectLayout.fitOverlayButtonWidth(textRenderer, theme, UiTone.BODY, cat.label, 5, 32, 64);
            CompactOverlayControls.tab(ctx, textRenderer, x, y, w, tabHeight(), cat.label, activeTab == cat, mx, my);
            x += w + 2;
        }

        String summary = paused ? ("Paused  " + total) : Integer.toString(total);
        int summaryColor = paused ? theme.color(UiTone.MUTED) : AutismColors.textSecondary();
        int summaryWidth = UiText.width(textRenderer, summary, theme.fontFor(UiTone.MUTED), summaryColor);
        int summaryX = panelX + PANEL_WIDTH - 6 - summaryWidth;
        int minSummaryX = x + 4;
        if (summaryX >= minSummaryX) {
            int summaryY = UiSizing.alignTextY(y, tabHeight(), theme.fontHeight(UiTone.MUTED), theme.bodyTextNudge());
            drawUiText(ctx, summary, UiTone.MUTED, summaryColor, summaryX, summaryY);
        }
    }

    private int contentAreaY() {
        return panelY + HEADER_H + 2 + tabHeight() + 2 + filterHeight() + 2;
    }

    private int contentAreaHeight() {
        int rawHeight = Math.max(0, currentPanelHeight - HEADER_H - tabHeight() - filterHeight() - 8 - blockedH());
        return alignViewportHeight(rawHeight, lineHeight());
    }

    private CompactScrollbar.Metrics getContentScrollbarMetrics() {
        int contentHeight = displayRows.size() * lineHeight();
        int viewHeight = contentAreaHeight();
        int maxScroll = Math.max(0, contentHeight - viewHeight);
        return CompactScrollbar.compute(contentHeight, viewHeight, panelX + PANEL_WIDTH - 5, contentAreaY(), 3, viewHeight, contentScrollState.tick(0.0f, maxScroll));
    }

    private void renderFilterBar(GuiGraphicsExtractor ctx, int mx, int my, int y) {
        int gap = 2;
        int row1Y = y;
        int row2Y = y + filterRowHeight() + filterRowGap();

        int x = panelX + 4;
        String captureLabel = paused ? "Start" : "Stop";
        int captureW = DirectLayout.fitOverlayButtonWidth(textRenderer, theme, UiTone.BODY, captureLabel, 5, 38, 54);
        if (paused) {
            drawOverlayToggleButton(ctx, x, row1Y, captureW, filterRowHeight(), captureLabel, false, "packet-logger:capture", mx, my);
        } else {
            drawOverlayToggleButton(ctx, x, row1Y, captureW, filterRowHeight(), captureLabel, true, "packet-logger:capture", mx, my);
        }
        x += captureW + gap;

        int clearW = DirectLayout.fitOverlayButtonWidth(textRenderer, theme, UiTone.BODY, "Clear", 5, 34, 54);
        drawOverlayButton(ctx, x, row1Y, clearW, filterRowHeight(), "Clear", CompactOverlayButton.Variant.GHOST, true, mx, my);
        x += clearW + gap;
        int copyW = DirectLayout.fitOverlayButtonWidth(textRenderer, theme, UiTone.BODY, "Copy", 5, 34, 52);
        drawOverlayButton(ctx, x, row1Y, copyW, filterRowHeight(), "Copy", CompactOverlayButton.Variant.GHOST, true, mx, my);
        x += copyW + gap;
        String grpLabel = groupingEnabled ? "Group" : "Ungrp";
        int groupW = DirectLayout.fitOverlayButtonWidth(textRenderer, theme, UiTone.BODY, grpLabel, 5, 38, 58);
        drawOverlayToggleButton(ctx, x, row1Y, groupW, filterRowHeight(), grpLabel, groupingEnabled, "packet-logger:grouping", mx, my);
        x += groupW + gap;
        String bt = "Blocked";
        int blockedW = DirectLayout.fitOverlayButtonWidth(textRenderer, theme, UiTone.BODY, bt, 5, 54, 72);
        drawOverlayButton(ctx, x, row1Y, blockedW, filterRowHeight(), bt, CompactOverlayButton.Variant.GHOST, true, mx, my);

        x = panelX + 4;
        int sw = searchFieldWidth();
        searchField.setX(x);
        searchField.setY(row2Y);
        searchField.setWidth(sw);
        searchField.setHeight(filterRowHeight());
        if (!Objects.equals(searchField.getText(), searchFilter)) {
            searchField.setText(searchFilter);
        }
        searchField.render(ctx, mx, my, 0.0f);
        x += sw + 3;

        String[] dirLabels = {"Both", "C2S", "S2C"};
        for (int d = 0; d < 3; d++) {
            int bw = DirectLayout.fitOverlayButtonWidth(textRenderer, theme, UiTone.BODY, dirLabels[d], 4, 30, 48);
            CompactOverlayControls.tab(ctx, textRenderer, x, row2Y, bw, filterRowHeight(), dirLabels[d], dirFilter == d, mx, my);
            x += bw + gap;
        }
    }

    private void drawOverlayButton(GuiGraphicsExtractor ctx, int x, int y, int w, int h, String label, CompactOverlayButton.Variant variant, boolean active, int mx, int my) {
        CompactOverlayControls.action(ctx, textRenderer, x, y, w, h, label, variant, active, mx, my);
    }

    private void drawOverlayToggleButton(GuiGraphicsExtractor ctx, int x, int y, int w, int h, String label, boolean enabled, String animationKey, int mx, int my) {
        CompactOverlayControls.toggle(ctx, textRenderer, x, y, w, h, label, enabled, animationKey, mx, my);
    }

    private void renderGroup(GuiGraphicsExtractor ctx, DisplayRow row, int y, int mx, int my) {
        int x = panelX + 4;
        boolean exp = expandedGroups.contains(row.groupKey);
        boolean hov = mx >= panelX && mx <= panelX + PANEL_WIDTH && my >= y && my < y + lineHeight();
        if (hov) CompactSurfaces.row(ctx, panelX + 2, y, PANEL_WIDTH - 4, lineHeight(), true, false);

        CompactControlGlyphs.drawChevron(
            ctx,
            x,
            y + 2,
            8,
            exp ? CompactControlGlyphs.ChevronDirection.DOWN : CompactControlGlyphs.ChevronDirection.RIGHT,
            hov ? 0xFFF5EEEE : 0xFFE7DADA,
            0xB83A1418,
            1.0f
        );
        x += 10;
        String arrow = row.direction.equals("C2S") ? ">" : "<";

        int color = row.direction.equals("C2S") ? 0xFF44CCFF : 0xFFFFAA44;
        drawUiText(ctx, arrow, UiTone.BODY, color, x, y + 1);
        x += 12;
        String displayName = row.groupKey.contains(":") ? row.groupKey.substring(row.groupKey.indexOf(':') + 1) : row.groupKey;
        String summary = row.payloadSnapshot == null ? "" : AutismPayloadSupport.summarizeForLogger(row.payloadSnapshot, true);
        String line = displayName + " (x" + row.groupCount + ")" + (summary.isBlank() ? "" : " " + summary);
        int maxW = PANEL_WIDTH - (x - panelX) - 32;
        if (UiText.width(textRenderer, line, theme.fontFor(UiTone.BODY), color) > maxW) {
            line = UiText.trimToWidth(textRenderer, line, Math.max(1, maxW), theme.fontFor(UiTone.BODY), color);
        }
        drawUiText(ctx, line, UiTone.BODY, color, x, y + 1);

        int bx = panelX + PANEL_WIDTH - 28;
        boolean hb = mx >= bx && mx <= bx + 24 && my >= y && my < y + lineHeight();
        drawUiText(ctx, "BLK", UiTone.MUTED, hb ? 0xFFFF4444 : AutismColors.textDim(), bx, y + 1);
    }

    private void renderEntry(GuiGraphicsExtractor ctx, LogEntry e, int y, int mx, int my) {
        int x = panelX + 4;
        boolean hov = mx >= panelX && mx <= panelX + PANEL_WIDTH && my >= y && my < y + lineHeight();
        if (hov) CompactSurfaces.row(ctx, panelX + 2, y, PANEL_WIDTH - 4, lineHeight(), true, false);

        int color = e.direction.equals("C2S") ? 0xFF44CCFF : 0xFFFFAA44;
        drawUiText(ctx, e.direction.equals("C2S") ? ">" : "<", UiTone.BODY, color, x, y + 1);
        x += 12;
        String time = TIME_FMT.format(Instant.ofEpochMilli(e.timestampMs));
        drawUiText(ctx, time, UiTone.MUTED, AutismColors.textDim(), x, y + 1);
        x += UiText.width(textRenderer, time, theme.fontFor(UiTone.MUTED), AutismColors.textDim()) + 4;
        drawUiText(ctx, "T" + e.gameTick, UiTone.MUTED, AutismColors.textDim(), x, y + 1);
        x += UiText.width(textRenderer, "T" + e.gameTick, theme.fontFor(UiTone.MUTED), AutismColors.textDim()) + 4;

        int maxW = PANEL_WIDTH - (x - panelX) - 30;
        String name = e.shortName;
        if (e.payloadSnapshot != null) {
            String summary = AutismPayloadSupport.summarizeForLogger(e.payloadSnapshot, true);
            if (!summary.isBlank()) {
                name = name + " " + summary;
            }
        }
        if (UiText.width(textRenderer, name, theme.fontFor(UiTone.BODY), AutismColors.textPrimary()) > maxW) {
            name = UiText.trimToWidth(textRenderer, name, Math.max(1, maxW), theme.fontFor(UiTone.BODY), AutismColors.textPrimary());
        }
        drawUiText(ctx, name, UiTone.BODY, color, x, y + 1);

        int bx = panelX + PANEL_WIDTH - 16;
        boolean hb = mx >= bx && mx <= bx + 12 && my >= y && my < y + lineHeight();
        drawUiText(ctx, "=", UiTone.MUTED, hb ? 0xFFFFDD55 : AutismColors.textDim(), bx, y + 1);
    }

    private void renderBlocked(GuiGraphicsExtractor ctx, int mx, int my, int startY, int endY) {
        CompactSurfaces.divider(ctx, panelX + 4, startY, PANEL_WIDTH - 8);
        int y = startY + 3;
        boolean hh = mx >= panelX + 4 && mx <= panelX + 150 && my >= y && my < y + lineHeight();
        drawUiText(ctx, (blockedExpanded ? "v" : ">") + " Blocked (" + blockedNames.size() + ")", UiTone.LABEL, hh ? 0xFFFF8888 : 0xFFFF6666, panelX + 6, y + 1);
        int ubX = panelX + PANEL_WIDTH - 58;
        boolean hua = mx >= ubX && mx <= ubX + 54 && my >= y && my < y + lineHeight();
        drawUiText(ctx, "CLR ALL", UiTone.MUTED, hua ? 0xFF44FF44 : AutismColors.textSecondary(), ubX, y + 1);
        int rstX = ubX - 44;
        boolean hRst = mx >= rstX && mx <= rstX + 40 && my >= y && my < y + lineHeight();
        drawUiText(ctx, "RESET", UiTone.MUTED, hRst ? 0xFFFFD555 : AutismColors.textSecondary(), rstX, y + 1);

        if (!blockedExpanded) return;
        y += lineHeight();
        for (String name : blockedNames) {
            if (y + lineHeight() > endY) break;
            drawUiText(ctx, "  " + UiText.trimToWidth(textRenderer, name, Math.max(1, PANEL_WIDTH - 50), theme.fontFor(UiTone.BODY), 0xFFAA6666), UiTone.BODY, 0xFFAA6666, panelX + 8, y + 1);
            int ux = panelX + PANEL_WIDTH - 28;
            boolean hu = mx >= ux && mx <= ux + 24 && my >= y && my < y + lineHeight();
            drawUiText(ctx, "UB", UiTone.MUTED, hu ? 0xFF44FF44 : AutismColors.textSecondary(), ux, y + 1);
            y += lineHeight();
        }
    }

    private String[] getCtxItems(LogEntry e) {
        boolean isC2S = e != null && "C2S".equalsIgnoreCase(e.direction);
        boolean isPayload = e != null && e.isPayload;
        if (isC2S && isPayload) {
            return new String[]{"Block", "Queue", "Replay", "Send", "Edit Payload", "+PAYLOAD", "+SEND", "+WAIT", "+ Filter", "- Filter", "Copy", "Inspect"};
        }
        return isC2S
                ? new String[]{"Block", "Queue", "Replay", "Send", "+SEND", "+WAIT", "+ Filter", "- Filter", "Copy", "Inspect"}
                : new String[]{"Block", "+WAIT", "+ Filter", "- Filter", "Copy", "Inspect"};
    }

    private void openCtxMenu(LogEntry entry, int mouseX, int mouseY) {
        ctxMenu.open(mouseX, mouseY, entry);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!visible) return false;

        if (ctxMenu.handleClick(mouseX, mouseY, button, (entry, action, index) -> executeCtxAction(action, entry))) return true;

        if (button != 0 && button != 1) return false;

        if (mouseY >= panelY && mouseY <= panelY + HEADER_H && mouseX >= panelX && mouseX <= panelX + PANEL_WIDTH) {
            AutismWindowLayout bounds = new AutismWindowLayout(panelX, panelY, PANEL_WIDTH, calcPanelH(), visible, collapsed);
            if (isOverCloseButton(mouseX, mouseY, bounds)) { setVisible(false); return true; }
            if (button == 0) {
                isDragging = true;
                headerDragMoved = false;
                dragOffX = mouseX - panelX;
                dragOffY = mouseY - panelY;
                headerPressMouseX = mouseX;
                headerPressMouseY = mouseY;
                headerPressPanelX = panelX;
                headerPressPanelY = panelY;
            }
            return true;
        }
        if (collapsed) return false;

        int tabY = panelY + HEADER_H + 2;
        int filterY = tabY + tabHeight() + 2;
        int contentY = filterY + filterHeight() + 2;

        if (mouseY >= tabY && mouseY < tabY + tabHeight() && button == 0) {
            int x = panelX + 4;
            for (Category cat : Category.values()) {
                int w = DirectLayout.fitOverlayButtonWidth(textRenderer, theme, UiTone.BODY, cat.label, 5, 32, 64);
                if (mouseX >= x && mouseX < x + w) { activeTab = cat; dirty = true; scrollOffset = 0; return true; }
                x += w + 2;
            }
            return true;
        }

        if (mouseY >= filterY && mouseY < filterY + filterHeight() && button == 0) {
            return handleFilterClick(mouseX, mouseY, filterY);
        }

        if (searchField.isFocused()) searchField.setFocused(false);

        int contentEndY = contentY + contentAreaHeight();
        if (mouseY >= contentY && mouseY < contentEndY) {
            if (button == 0) {
                CompactScrollbar.Metrics scrollbarMetrics = getContentScrollbarMetrics();
                if (scrollbarMetrics.hasScroll() && scrollbarMetrics.contains((int) mouseX, (int) mouseY)) {
                    scrollbarDragging = true;
                    scrollbarGrabOffset = Math.max(0, (int) mouseY - scrollbarMetrics.thumbY());
                    scrollOffset = quantizeScrollOffset(CompactScrollbar.scrollFromThumb(scrollbarMetrics, (int) mouseY, scrollbarGrabOffset), lineHeight(), scrollbarMetrics.maxScroll());
                    contentScrollState.jumpTo(scrollOffset, scrollbarMetrics.maxScroll());
                    return true;
                }
            }
            return handleContentClick(mouseX, mouseY, contentY, button);
        }

        return false;
    }

    private boolean handleFilterClick(double mouseX, double mouseY, int y) {
        int gap = 2;
        int row1Y = y;
        int row2Y = y + filterRowHeight() + filterRowGap();
        int x = panelX + 4;
        String captureLabel = paused ? "Start" : "Stop";
        int captureW = DirectLayout.fitOverlayButtonWidth(textRenderer, theme, UiTone.BODY, captureLabel, 5, 38, 54);
        if (mouseY >= row1Y && mouseY < row1Y + filterRowHeight() && mouseX >= x && mouseX < x + captureW) {
            setPaused(!paused);
            return true;
        }
        x += captureW + gap;

        int clrW = DirectLayout.fitOverlayButtonWidth(textRenderer, theme, UiTone.BODY, "Clear", 5, 34, 54);
        if (mouseY >= row1Y && mouseY < row1Y + filterRowHeight() && mouseX >= x && mouseX < x + clrW) { clearAll(); return true; }
        x += clrW + gap;
        int cpW = DirectLayout.fitOverlayButtonWidth(textRenderer, theme, UiTone.BODY, "Copy", 5, 34, 52);
        if (mouseY >= row1Y && mouseY < row1Y + filterRowHeight() && mouseX >= x && mouseX < x + cpW) { copyToClipboard(); return true; }
        x += cpW + gap;
        int grpW = DirectLayout.fitOverlayButtonWidth(textRenderer, theme, UiTone.BODY, groupingEnabled ? "Group" : "Ungrp", 5, 38, 58);
        if (mouseY >= row1Y && mouseY < row1Y + filterRowHeight() && mouseX >= x && mouseX < x + grpW) { groupingEnabled = !groupingEnabled; dirty = true; return true; }
        x += grpW + gap;
        String bt = "Blocked";
        int bw = DirectLayout.fitOverlayButtonWidth(textRenderer, theme, UiTone.BODY, bt, 5, 54, 72);
        if (mouseY >= row1Y && mouseY < row1Y + filterRowHeight() && mouseX >= x && mouseX < x + bw) { openBlockedListOverlay(); return true; }

        x = panelX + 4;
        int sw = 148;
        if (mouseY >= row2Y && mouseY < row2Y + filterRowHeight() && mouseX >= x && mouseX < x + sw) {
            searchField.mouseClicked(mouseX, mouseY, 0);
            return true;
        }
        x += sw + 3;

        String[] dirLabels = {"Both", "C2S", "S2C"};
        for (int d = 0; d < 3; d++) {
            int dirW = DirectLayout.fitOverlayButtonWidth(textRenderer, theme, UiTone.BODY, dirLabels[d], 4, 30, 48);
            if (mouseY >= row2Y && mouseY < row2Y + filterRowHeight() && mouseX >= x && mouseX < x + dirW) { dirFilter = d; dirty = true; return true; }
            x += dirW + gap;
        }
        searchField.setFocused(false);
        return true;
    }

    private boolean handleContentClick(double mouseX, double mouseY, int contentY, int button) {
        int idx = (int) ((mouseY - contentY + contentScrollState.tick(0.0f, Math.max(0, displayRows.size() * lineHeight() - contentAreaHeight()))) / lineHeight());
        if (idx < 0 || idx >= displayRows.size()) return false;
        DisplayRow row = displayRows.get(idx);

        if (row.type == RowType.GROUP) {
            int bx = panelX + PANEL_WIDTH - 28;
            if (button == 0 && mouseX >= bx && mouseX <= bx + 24 && row.packetClass != null) {
                @SuppressWarnings("unchecked")
                String n = AutismPacketNamer.getFriendlyName((Class<? extends Packet<?>>) row.packetClass);
                blockPacketName(n); return true;
            }
            if (button == 0) {
                if (expandedGroups.contains(row.groupKey)) expandedGroups.remove(row.groupKey);
                else expandedGroups.add(row.groupKey);
                dirty = true; return true;
            }
        }

        if (row.type == RowType.ENTRY && row.entry != null) {

            int menuIconX = panelX + PANEL_WIDTH - 16;
            if (button == 1 || (button == 0 && mouseX >= menuIconX)) {
                openCtxMenu(row.entry, (int) mouseX, (int) mouseY);
                return true;
            }
        }

        return false;
    }

    @SuppressWarnings("unchecked")
    private void executeCtxAction(String action, LogEntry e) {
        switch (action) {
            case "Block":
                blockPacketName(e.shortName);
                AutismClientMessaging.sendPrefixed("Blocked + purged from logger: " + e.shortName);
                break;
            case "Queue":
                AutismPacketEntryActions.queue(e);
                break;
            case "Send":
                AutismPacketEntryActions.directSend(e);
                break;
            case "+SEND":
                AutismPacketEntryActions.addSendActionToVisibleMacro(e);
                break;
            case "+WAIT":
                AutismPacketEntryActions.addWaitActionToVisibleMacro(e);
                break;
            case "Edit Payload":
                AutismPacketEntryActions.openPayloadEditor(e);
                break;
            case "+PAYLOAD":
                AutismPacketEntryActions.addPayloadActionToVisibleMacro(e);
                break;
            case "+ Filter": {
                AutismSharedState shared = AutismSharedState.get();
                Class<? extends Packet<?>> pktCls = (Class<? extends Packet<?>>) e.packetClass;
                boolean added;
                if (e.direction.equals("C2S")) {
                    added = shared.getC2SPackets().add(pktCls);
                } else {
                    added = shared.getS2CPackets().add(pktCls);
                }
                shared.setUseCustomPackets(true);
                AutismClientMessaging.sendPrefixed(added
                    ? "Added to custom " + e.direction + " filter: " + e.shortName
                    : "Already present in custom " + e.direction + " filter: " + e.shortName);
                break;
            }
            case "- Filter": {
                AutismSharedState shared = AutismSharedState.get();
                Class<? extends Packet<?>> pktCls = (Class<? extends Packet<?>>) e.packetClass;
                boolean removed;
                if (e.direction.equals("C2S")) {
                    removed = shared.getC2SPackets().remove(pktCls);
                } else {
                    removed = shared.getS2CPackets().remove(pktCls);
                }
                AutismClientMessaging.sendPrefixed(removed
                    ? "Removed from custom " + e.direction + " filter: " + e.shortName
                    : "Not present in custom " + e.direction + " filter: " + e.shortName);
                break;
            }
            case "Replay":
                if (e.packetRef != null && e.direction.equals("C2S")) {
                    AutismPacketEntryActions.directSend(e);
                }
                break;
            case "Copy": {
                String line = e.direction + " " + TIME_FMT.format(Instant.ofEpochMilli(e.timestampMs))
                        + " T" + e.gameTick + " " + e.shortName;
                MC.keyboardHandler.setClipboard(line);
                AutismNotifications.copied("Copied packet info.");
                break;
            }
            case "Inspect": {
                AutismPacketInspector.PacketInspection inspection = AutismPacketInspector.inspectSafe(e);
                inspectOverlay.open(inspection, e, panelX + PANEL_WIDTH + 10, panelY + 8);
                break;
            }
        }
    }

    private boolean handleBlockedClick(double mouseX, double mouseY, int contentEndY) {
        int y = contentEndY + 3;
        if (mouseY >= y && mouseY < y + lineHeight()) {
            int ubX = panelX + PANEL_WIDTH - 58;
            if (mouseX >= ubX && mouseX <= ubX + 54) { clearBlocked(); return true; }
            int rstX = ubX - 44;
            if (mouseX >= rstX && mouseX <= rstX + 40) { resetBlockedToDefault(); return true; }
            blockedExpanded = !blockedExpanded; return true;
        }
        if (blockedExpanded) {
            int ey = y + lineHeight();
            int ux = panelX + PANEL_WIDTH - 28;
            for (String name : new ArrayList<>(blockedNames)) {
                if (mouseX >= ux && mouseX <= ux + 24 && mouseY >= ey && mouseY < ey + lineHeight()) {
                    unblockName(name); return true;
                }
                ey += lineHeight();
            }
        }
        return false;
    }

    @Override public boolean mouseDragged(double mx, double my, int b, double dx, double dy) {
        if (scrollbarDragging && b == 0) {
            CompactScrollbar.Metrics scrollbarMetrics = getContentScrollbarMetrics();
            scrollOffset = quantizeScrollOffset(CompactScrollbar.scrollFromThumb(scrollbarMetrics, (int) my, scrollbarGrabOffset), lineHeight(), scrollbarMetrics.maxScroll());
            contentScrollState.jumpTo(scrollOffset, scrollbarMetrics.maxScroll());
            return true;
        }
        if (isDragging && b == 0) {
            AutismWindowLayout nextBounds = clampToScreen(this,
                new AutismWindowLayout((int) (mx - dragOffX), (int) (my - dragOffY),
                    PANEL_WIDTH, PANEL_HEIGHT, visible, collapsed));
            if (nextBounds.x != panelX || nextBounds.y != panelY) {
                headerDragMoved = true;
            }
            panelX = nextBounds.x;
            panelY = nextBounds.y;
            return true;
        }
        return false;
    }
    @Override public boolean mouseReleased(double mx, double my, int b) {
        if (b == 0 && scrollbarDragging) { scrollbarDragging = false; return true; }
        if (b == 0 && isDragging) {
            boolean moved = headerDragMoved
                || Math.abs(mx - headerPressMouseX) >= 3.0
                || Math.abs(my - headerPressMouseY) >= 3.0
                || panelX != headerPressPanelX
                || panelY != headerPressPanelY;
            AutismWindowLayout bounds = new AutismWindowLayout(panelX, panelY, PANEL_WIDTH, calcPanelH(), visible, collapsed);
            isDragging = false;
            headerDragMoved = false;
            if (!moved
                && mx >= panelX && mx <= panelX + PANEL_WIDTH
                && my >= panelY && my <= panelY + HEADER_H
                && !isOverCloseButton(mx, my, bounds)) {
                setCollapsed(!collapsed);
            }
            saveLayout();
            return true;
        }
        return false;
    }
    @Override public boolean mouseScrolled(double mx, double my, double amt) {
        if (!visible || collapsed) return false;
        int totalH = displayRows.size() * lineHeight();
        int visH = contentAreaHeight();
        int maxScroll = Math.max(0, totalH - visH);
        scrollOffset = quantizeScrollOffset(scrollOffset - (int)Math.round(amt * lineHeight() * 3.0), lineHeight(), maxScroll);
        return true;
    }
    @Override public boolean keyPressed(int key, int scan, int mods) {
        if (!visible || collapsed) return false;
        return searchField.isFocused() && searchField.keyPressed(new KeyEvent(key, scan, mods));
    }
    @Override public boolean charTyped(char c, int mods) {
        if (!visible || collapsed) return false;
        return searchField.isFocused() && searchField.charTyped(new CharacterEvent(c));
    }

    private List<LogEntry> getActiveBuffer() {
        switch (activeTab) {
            case INVENTORY: return bufInventory;
            case MOVEMENT: return bufMovement;
            case PAYLOAD: return bufPayload;
            case ALL: default: return bufAll;
        }
    }

    private void rebuildDisplay() {
        List<LogEntry> source = getActiveBuffer();
        if (source == null) source = new ArrayList<>();

        String ls = searchFilter.toLowerCase(Locale.ROOT);
        List<LogEntry> filtered = new ArrayList<>();
        for (LogEntry e : source) {
            if (isBlockedClass(e.packetClass)) continue;
            if (!ls.isEmpty() && !e.searchKey.contains(ls)) continue;

            if (dirFilter == 1 && !e.direction.equals("C2S")) continue;
            if (dirFilter == 2 && !e.direction.equals("S2C")) continue;
            filtered.add(e);
        }

        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, String> dirs = new LinkedHashMap<>();
        Map<String, Class<?>> classes = new LinkedHashMap<>();
        for (LogEntry e : filtered) {
            String key = e.groupKey;
            counts.merge(key, 1, Integer::sum);
            dirs.put(key, e.direction);
            classes.put(key, e.packetClass);
        }

        Set<String> groupedKeys = new HashSet<>();
        if (groupingEnabled) {
            for (Map.Entry<String, Integer> entry : counts.entrySet()) {
                if (entry.getValue() >= GROUP_THRESHOLD) groupedKeys.add(entry.getKey());
            }
        }

        List<LogEntry> reversed = new ArrayList<>(filtered);
        java.util.Collections.reverse(reversed);

        List<DisplayRow> rows = new ArrayList<>();
        Set<String> added = new LinkedHashSet<>();

        for (LogEntry e : reversed) {
            String key = e.groupKey;
            if (groupedKeys.contains(key) && added.add(key)) {
                DisplayRow h = new DisplayRow();
                h.type = RowType.GROUP; h.groupKey = key; h.groupCount = counts.get(key);
                h.direction = dirs.get(key); h.packetClass = classes.get(key);
                h.payloadSnapshot = e.payloadSnapshot;
                rows.add(h);
                if (expandedGroups.contains(key)) {
                    for (LogEntry child : reversed) {
                        if (child.groupKey.equals(key)) {
                            DisplayRow r = new DisplayRow(); r.type = RowType.ENTRY; r.entry = child; rows.add(r);
                        }
                    }
                }
            }
        }

        for (LogEntry e : reversed) {
            if (!groupedKeys.contains(e.groupKey)) {
                DisplayRow r = new DisplayRow(); r.type = RowType.ENTRY; r.entry = e; rows.add(r);
            }
        }

        displayRows = rows;
        clampScrollOffset();
    }

    private void clampScrollOffset() {
        int totalH = displayRows.size() * lineHeight();
        int visH = currentPanelHeight - HEADER_H - tabHeight() - filterHeight() - 8 - blockedH();
        int maxScroll = Math.max(0, totalH - visH);
        scrollOffset = quantizeScrollOffset(scrollOffset, lineHeight(), maxScroll);
    }

    private int calcPanelH() {
        int rc = Math.min(displayRows.size(), maxVisibleRows());
        return Math.max(PANEL_HEIGHT, HEADER_H + tabHeight() + filterHeight() + 8 + Math.max(rc * lineHeight(), 24) + blockedH() + 4);
    }

    private int blockedH() {
        return 0;
    }

    private void openBlockedListOverlay() {

        int sw = AutismUiScale.getVirtualScreenWidth();
        int sh = AutismUiScale.getVirtualScreenHeight();
        blockedListOverlay.panelX = Math.max(4, (sw - blockedListOverlay.panelWidth) / 2);
        blockedListOverlay.panelY = Math.max(4, (sh - blockedListOverlay.panelHeight) / 2);
        blockedListOverlay.setVisible(true);
        blockedListOverlay.rebuildRows();
        AutismOverlayManager.get().bringToFront(blockedListOverlay);
    }

    private final class BlockedPacketListOverlay extends AutismOverlayBase {
        private final AutismChatField search = new AutismChatField(MC, textRenderer, 0, 0, 120, 16, false);
        private final ScrollState rowsScroll = new ScrollState();
        private final List<Class<? extends Packet<?>>> allPackets = new ArrayList<>();
        private final Set<Class<? extends Packet<?>>> c2sSet = new HashSet<>();
        private final List<Class<? extends Packet<?>>> rows = new ArrayList<>();
        private int rowScroll;
        private boolean draggingScroll;
        private int scrollGrabOffset;
        private boolean showBlockedOnly;
        private boolean draggingWindow;
        private double dragOffsetX;
        private double dragOffsetY;

        private static final int BLOCKED_W = 54;
        private static final int CLEAR_W = 44;
        private static final int RESET_W = 44;

        BlockedPacketListOverlay() {
            super("PacketLoggerBlockedList", 250, 248);
            this.panelX = 260;
            this.panelY = 56;
            this.visible = false;
            search.setPlaceholder(Component.literal("Search packets..."));
            search.setMaxLength(120);
            search.setChangedListener(value -> rebuildRows());

            List<Class<? extends Packet<?>>> c2s = new ArrayList<>(AutismPacketRegistry.getC2SPackets());
            List<Class<? extends Packet<?>>> s2c = new ArrayList<>(AutismPacketRegistry.getS2CPackets());
            Comparator<Class<? extends Packet<?>>> byName =
                Comparator.comparing(AutismPacketNamer::getFriendlyName, String.CASE_INSENSITIVE_ORDER);
            c2s.sort(byName);
            s2c.sort(byName);
            c2sSet.addAll(c2s);
            allPackets.addAll(c2s);
            allPackets.addAll(s2c);
        }

        private int controlsRowY() { return panelY + HEADER_H + 5 + 20; }
        private int listTopY() { return controlsRowY() + 21; }

        private String blockKey(Class<? extends Packet<?>> cls) {
            String name = AutismPacketRegistry.getName(cls);
            return name != null ? name : AutismPacketNamer.getFriendlyName(cls);
        }

        @Override
        public void render(GuiGraphicsExtractor ctx, int mx, int my, float delta) {
            if (!visible) return;
            AutismWindowLayout bounds = clampToScreen(this, new AutismWindowLayout(panelX, panelY, panelWidth, panelHeight, visible, collapsed));
            panelX = bounds.x;
            panelY = bounds.y;
            renderWindowFrame(ctx, mx, my, bounds, "Block List", collapsed, false);
            boolean clip = beginWindowBodyClip(ctx, bounds, collapsed);
            if (!clip) {
                renderWindowInactiveOverlay(ctx, bounds, collapsed, false);
                return;
            }
            try {
                int x = panelX + 6;
                int y = panelY + HEADER_H + 5;
                int rowH = 16;
                search.setX(x);
                search.setY(y);
                search.setWidth(Math.max(80, panelWidth - 12));
                search.setHeight(rowH);
                search.render(ctx, mx, my, delta);

                y = controlsRowY();
                int bx = x;
                CompactOverlayControls.tab(ctx, textRenderer, bx, y, BLOCKED_W, rowH, "Blocked", showBlockedOnly, mx, my);
                int clearX = bx + BLOCKED_W + 4;
                CompactOverlayControls.action(ctx, textRenderer, clearX, y, CLEAR_W, rowH, "Clear", CompactOverlayButton.Variant.DANGER, true, mx, my);
                int resetX = clearX + CLEAR_W + 4;
                CompactOverlayControls.action(ctx, textRenderer, resetX, y, RESET_W, rowH, "Reset", CompactOverlayButton.Variant.GHOST, true, mx, my);
                int countX = resetX + RESET_W + 8;
                drawUiText(ctx, blockedNames.size() + " blocked", UiTone.MUTED, AutismColors.textSecondary(), countX, y + 4);

                int listTop = listTopY();
                int listH = Math.max(20, panelY + panelHeight - listTop - 6);
                int contentH = rows.size() * lineHeight();
                int maxScroll = Math.max(0, contentH - listH);
                rowScroll = quantizeScrollOffset(rowScroll, lineHeight(), maxScroll);
                rowsScroll.setTarget(rowScroll, maxScroll);
                int drawScroll = rowsScroll.tick(delta, maxScroll);
                autismclient.gui.vanillaui.UiScissorStack.global().push(ctx,
                    autismclient.gui.vanillaui.UiBounds.of(panelX + 4, listTop, panelWidth - 8, listH));
                int base = listTop - drawScroll;
                for (int i = 0; i < rows.size(); i++) {
                    int ry = base + i * lineHeight();
                    if (ry + lineHeight() <= listTop || ry >= listTop + listH) continue;
                    Class<? extends Packet<?>> cls = rows.get(i);
                    boolean c2s = c2sSet.contains(cls);
                    boolean blocked = isBlockedName(cls, AutismPacketNamer.getFriendlyName(cls));
                    boolean hover = mx >= panelX + 4 && mx < panelX + panelWidth - 8 && my >= ry && my < ry + lineHeight();
                    int fill = blocked ? AutismColors.packetRowBlockedBg(hover) : AutismColors.packetRowBg(c2s, i, hover);
                    ctx.fill(panelX + 4, ry, panelX + panelWidth - 8, ry + lineHeight(), fill);
                    if (blocked) {
                        ctx.fill(panelX + 4, ry, panelX + 6, ry + lineHeight(), AutismColors.packetRowBlockedAccent());
                    }
                    int color = AutismColors.packetRowText(c2s, i);
                    String name = AutismPacketNamer.getFriendlyName(cls);
                    String label = UiText.trimToWidth(textRenderer, name, Math.max(1, panelWidth - 24), theme.fontFor(UiTone.BODY), color);
                    drawUiText(ctx, label, UiTone.BODY, color, panelX + 9, ry + 1);
                }
                autismclient.gui.vanillaui.UiScissorStack.global().pop(ctx);
                CompactScrollbar.Metrics metrics = CompactScrollbar.compute(contentH, listH, panelX + panelWidth - 5, listTop, 3, listH, drawScroll);
                CompactScrollbar.draw(ctx, metrics, metrics.contains(mx, my), draggingScroll);
            } finally {
                endWindowBodyClip(ctx, clip);
                renderWindowInactiveOverlay(ctx, bounds, collapsed, false);
            }
        }

        private void rebuildRows() {
            String query = search.getText() == null ? "" : search.getText().trim().toLowerCase(Locale.ROOT);
            rows.clear();
            for (Class<? extends Packet<?>> cls : allPackets) {
                if (showBlockedOnly && !isBlockedName(cls, AutismPacketNamer.getFriendlyName(cls))) continue;
                if (!query.isEmpty()) {
                    String hay = (AutismPacketNamer.getFriendlyName(cls) + ' ' + cls.getSimpleName()).toLowerCase(Locale.ROOT);
                    if (!hay.contains(query)) continue;
                }
                rows.add(cls);
            }
            rowScroll = Math.min(rowScroll, Math.max(0, rows.size() * lineHeight()));
        }

        @Override
        public boolean mouseClicked(double mx, double my, int button) {
            if (!visible) return false;
            AutismWindowLayout bounds = getBounds();
            if (button == 0 && isOverCloseButton(mx, my, bounds)) {
                setVisible(false);
                return true;
            }
            if (button == 0 && isOverDragBar(mx, my)) {
                draggingWindow = true;
                dragOffsetX = mx - panelX;
                dragOffsetY = my - panelY;
                return true;
            }
            if (collapsed) return true;
            int x = panelX + 6;
            if (search.mouseClicked(mx, my, button)) return true;

            int y = controlsRowY();
            if (button == 0 && my >= y && my < y + 16) {
                int clearX = x + BLOCKED_W + 4;
                int resetX = clearX + CLEAR_W + 4;
                if (mx >= x && mx < x + BLOCKED_W) {
                    showBlockedOnly = !showBlockedOnly;
                    rowScroll = 0;
                    rebuildRows();
                    return true;
                }
                if (mx >= clearX && mx < clearX + CLEAR_W) {
                    clearBlocked();
                    rebuildRows();
                    return true;
                }
                if (mx >= resetX && mx < resetX + RESET_W) {
                    resetBlockedToDefault();
                    rebuildRows();
                    return true;
                }
            }

            int listTop = listTopY();
            int listH = Math.max(20, panelY + panelHeight - listTop - 6);
            CompactScrollbar.Metrics metrics = CompactScrollbar.compute(rows.size() * lineHeight(), listH, panelX + panelWidth - 5, listTop, 3, listH, rowScroll);
            if (button == 0 && metrics.hasScroll() && metrics.contains((int) mx, (int) my)) {
                draggingScroll = true;
                scrollGrabOffset = (int) my - metrics.thumbY();
                return true;
            }
            if (button == 0 && my >= listTop) {
                int index = (int) ((my - listTop + rowScroll) / lineHeight());
                if (index >= 0 && index < rows.size()) {
                    Class<? extends Packet<?>> cls = rows.get(index);
                    String key = blockKey(cls);
                    if (isBlockedName(cls, AutismPacketNamer.getFriendlyName(cls))) unblockName(key);
                    else blockPacketName(key);
                    if (showBlockedOnly) rebuildRows();
                    return true;
                }
            }
            return isMouseOver(mx, my);
        }

        @Override
        public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
            if (draggingWindow && button == 0) {
                setBounds(new AutismWindowLayout((int) Math.round(mx - dragOffsetX), (int) Math.round(my - dragOffsetY),
                    panelWidth, panelHeight, visible, collapsed));
                return true;
            }
            if (draggingScroll && button == 0) {
                int listTop = listTopY();
                int listH = Math.max(20, panelY + panelHeight - listTop - 6);
                CompactScrollbar.Metrics metrics = CompactScrollbar.compute(rows.size() * lineHeight(), listH, panelX + panelWidth - 5, listTop, 3, listH, rowScroll);
                rowScroll = quantizeScrollOffset(CompactScrollbar.scrollFromThumb(metrics, (int) my, scrollGrabOffset), lineHeight(), metrics.maxScroll());
                return true;
            }
            return false;
        }

        @Override
        public boolean mouseReleased(double mx, double my, int button) {
            if (button == 0 && draggingWindow) {
                draggingWindow = false;
                saveLayout();
                return true;
            }
            if (button == 0 && draggingScroll) {
                draggingScroll = false;
                return true;
            }
            return false;
        }

        @Override
        public boolean mouseScrolled(double mx, double my, double amount) {
            if (!visible || collapsed || !isMouseOver(mx, my)) return false;
            int listTop = listTopY();
            int listH = Math.max(20, panelY + panelHeight - listTop - 6);
            int maxScroll = Math.max(0, rows.size() * lineHeight() - listH);
            rowScroll = quantizeScrollOffset(rowScroll - (int) Math.signum(amount) * lineHeight(), lineHeight(), maxScroll);
            rowsScroll.jumpTo(rowScroll, maxScroll);
            return true;
        }

        @Override
        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            return visible && search.isFocused() && search.keyPressed(new KeyEvent(keyCode, scanCode, modifiers));
        }

        @Override
        public boolean charTyped(char chr, int modifiers) {
            return visible && search.isFocused() && search.charTyped(new CharacterEvent(chr));
        }

        @Override
        public boolean hasTextFieldFocused() {
            return visible && search.isFocused();
        }

        @Override
        public void clearTextFieldFocus() {
            search.setFocused(false);
        }
    }

    private void clearAll() {
        pendingEntries.clear();
        bufAll.clear(); bufInventory.clear(); bufMovement.clear(); bufPayload.clear();
        displayRows.clear(); scrollOffset = 0; contentScrollState.jumpTo(0, 0); expandedGroups.clear(); dirty = true;
    }

    private void copyToClipboard() {
        flushPending();
        StringBuilder sb = new StringBuilder();
        sb.append("=== Packet Logger [").append(activeTab.label).append("] ===\n");
        if (!searchFilter.isEmpty()) sb.append("Search: \"").append(searchFilter).append("\"\n");
        sb.append(String.format("%-5s %-14s %-8s %s%n", "DIR", "TIME", "TICK", "PACKET"));
        sb.append("----------------------------------------------\n");

        List<LogEntry> source = getActiveBuffer();
        if (source == null) source = new ArrayList<>();
        String ls = searchFilter.toLowerCase(Locale.ROOT);
        int count = 0;
        for (LogEntry e : source) {
            if (isBlockedClass(e.packetClass)) continue;
            if (!ls.isEmpty() && !e.searchKey.contains(ls)) continue;
            if (dirFilter == 1 && !e.direction.equals("C2S")) continue;
            if (dirFilter == 2 && !e.direction.equals("S2C")) continue;
            sb.append(String.format("%-5s %-14s %-8s %s%n",
                    e.direction.equals("C2S") ? "->" : "<-",
                    TIME_FMT.format(Instant.ofEpochMilli(e.timestampMs)),
                    "T" + e.gameTick, e.shortName));
            count++;
        }
        sb.append("----------------------------------------------\n");
        sb.append("Total: ").append(count).append(" packets\n");

        MC.keyboardHandler.setClipboard(sb.toString());
        AutismNotifications.copied("Copied " + count + " packets.");
    }

        private int defaultPanelWidth() {
        return 323;
    }

    private int defaultPanelHeight() {
        return 250;
    }

    private int lineHeight() {
        return 12;
    }

    private int tabHeight() {
        return 16;
    }

    private int filterRowHeight() {
        return 16;
    }

    private int filterRowGap() {
        return 2;
    }

    private int filterHeight() {
        return filterRowHeight() * 2 + filterRowGap();
    }

    private int searchFieldWidth() {
        return 148;
    }

    private int maxVisibleRows() {
        return 22;
    }

    enum RowType { ENTRY, GROUP }

    static class DisplayRow {
        RowType type;
        LogEntry entry;
        String groupKey;
        int groupCount;
        String direction;
        Class<?> packetClass;
        AutismPayloadSupport.PayloadSnapshot payloadSnapshot;
    }

    private static long idCounter = 0;

    public static class LogEntry {
        public final long id;
        public final long timestampMs;
        public final int gameTick;
        public final String direction;
        public final String shortName;
        public final String searchKey;
        public final String groupKey;
        public final Class<?> packetClass;
        public final Packet<?> packetRef;
        public final boolean isInventory;
        public final boolean isMovement;
        public final boolean isPayload;
        public final String capturedBlockState;
        public final String capturedScreen;
        public final AutismPayloadSupport.PayloadSnapshot payloadSnapshot;

        public LogEntry(long ts, int tick, String dir, String name, Class<?> cls, Packet<?> ref,
                        boolean inv, boolean mov, boolean payload, String capturedBlockState, String capturedScreen,
                        AutismPayloadSupport.PayloadSnapshot payloadSnapshot) {
            this.id = idCounter++;
            this.timestampMs = ts;
            this.gameTick = tick;
            this.direction = dir;
            this.shortName = name;
            this.payloadSnapshot = payloadSnapshot;
            String payloadSearch = payloadSnapshot == null ? "" : (" " + payloadSnapshot.channel() + " " + payloadSnapshot.rawDump());
            this.searchKey = (name == null ? "" : name) .concat(payloadSearch).toLowerCase(Locale.ROOT);
            this.groupKey = dir + ":" + name;
            this.packetClass = cls;
            this.packetRef = ref;
            this.isInventory = inv;
            this.isMovement = mov;
            this.isPayload = payload;
            this.capturedBlockState = capturedBlockState;
            this.capturedScreen = capturedScreen;
        }
    }

    private record LogCaptureContext(String blockStateSummary, String screenSummary) {
        private static final LogCaptureContext EMPTY = new LogCaptureContext(null, null);
    }
}
