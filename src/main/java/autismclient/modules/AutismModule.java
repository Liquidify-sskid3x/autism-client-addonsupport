package autismclient.modules;

import autismclient.AutismClientAddon;
import autismclient.gui.screen.AutismModuleScreen;
import autismclient.util.AutismClientMessaging;
import autismclient.util.AutismConfig;
import autismclient.util.AutismLANSync;
import autismclient.util.AutismInputGate;
import autismclient.util.AutismJoinMacroController;
import autismclient.util.AutismOverlayManager;
import autismclient.util.AutismMacro;
import autismclient.util.AutismMacroManager;
import autismclient.util.AutismNotifications;
import autismclient.util.AutismPacketLoggerOverlay;
import autismclient.util.AutismPacketRegistry;
import autismclient.util.AutismPerf;
import autismclient.util.AutismServerInfoOverlay;
import autismclient.util.AutismSharedState;
import autismclient.util.macro.MacroConditionRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import net.minecraft.network.protocol.Packet;
import net.minecraft.world.inventory.AbstractContainerMenu;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class AutismModule {
    private static final Minecraft MC = Minecraft.getInstance();
    private static final AutismModule INSTANCE = new AutismModule();
    private static final long PASSIVE_PAYLOAD_CAPTURE_MS = 20_000L;
    private static final int PASSIVE_PAYLOAD_RING_CAP = 96;

    private static final Set<Class<?>> C2S_EXCLUDED_DEFAULTS = Set.of(
        net.minecraft.network.protocol.common.ServerboundKeepAlivePacket.class,
        net.minecraft.network.protocol.common.ServerboundPongPacket.class,
        net.minecraft.network.protocol.game.ServerboundClientTickEndPacket.class,
        net.minecraft.network.protocol.game.ServerboundChunkBatchReceivedPacket.class,
        net.minecraft.network.protocol.game.ServerboundClientCommandPacket.class,
        net.minecraft.network.protocol.game.ServerboundContainerClosePacket.class,
        net.minecraft.network.protocol.game.ServerboundSwingPacket.class,
        net.minecraft.network.protocol.game.ServerboundPlayerInputPacket.class,
        net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.class,
        net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.PosRot.class,
        net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.Rot.class,
        net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.StatusOnly.class,
        net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.Pos.class
    );

    private AutismConfig config;
    private boolean initialized;
    private boolean loadGuiKeyPressed;
    private boolean flushQueueKeyPressed;
    private boolean clearQueueKeyPressed;
    private boolean toggleLoggerKeyPressed;
    private boolean toggleSendKeyPressed;
    private boolean toggleDelayKeyPressed;
    private boolean moduleMenuKeyPressed;
    private final java.util.Map<String, Boolean> macroKeyStates = new java.util.HashMap<>();
    private List<AutismMacro> cachedKeyboundMacros = List.of();
    private long cachedMacroKeybindRevision = -1L;
    private int autoSendTickCounter;
    private int packetLoggerTickCounter;
    private AutismPacketLoggerOverlay packetLoggerOverlay;
    private AutismServerInfoOverlay serverInfoOverlay;
    private final Deque<PassivePayloadCapture> passivePayloadRing = new ArrayDeque<>(PASSIVE_PAYLOAD_RING_CAP);
    private volatile boolean joinedPlayConnection;
    private volatile boolean spawnedInWorld;
    private volatile long passivePayloadCaptureUntilMs;

    private AutismModule() {
    }

    public static AutismModule get() {
        return INSTANCE;
    }

    public void initialize() {
        if (initialized) return;

        config = AutismConfig.load();
        config.applyRuntimeDefaults();
        AutismConfig.setGlobal(config);
        PackModuleRegistry.initialize(config);
        if (config.c2sPackets.isEmpty()) {
            config.c2sPackets = encodePackets(defaultC2SPackets());
        }

        applyConfigToSharedState();
        if (PackHideState.isActive()) PackHideState.stopRuntimeWork();

        if (config.lanSyncEnabled && !PackHideState.isActive()) {
            AutismLANSync.getInstance().start();
        }

        initialized = true;
    }

    public void tick() {
        if (!initialized || MC == null) return;

        AutismPerf.tickJoinWindow();
        long perf = AutismPerf.beginJoin();
        updateWorldSpawnState();
        AutismPerf.endJoinSpike("join.worldSpawnState", perf);
        AutismLANSync lanSync = AutismLANSync.getInstance();
        if (!PackHideState.isActive() && lanSync.hasTickWork()) {
            perf = AutismPerf.beginJoin();
            lanSync.tick();
            AutismPerf.endJoinSpike("join.lanSync.tick", perf);
        }
        if (!PackHideState.isActive() && MacroConditionRegistry.hasPendingConditions()) {
            perf = AutismPerf.beginJoin();
            MacroConditionRegistry.onTick(MC);
            AutismPerf.endJoinSpike("join.macroConditions.tick", perf);
        }
        AutismSharedState shared = AutismSharedState.get();
        if (!PackHideState.isActive() && shared.hasStaggeredSendWork()) shared.tickStaggeredSend();
        perf = AutismPerf.beginJoin();
        PackModuleRegistry.tick();
        AutismPerf.endJoinSpike("join.modules.tick", perf, 8_000_000L);
        if (!PackHideState.isActive()) PackAutoReconnectState.tickCurrentScreen();
        updatePassiveXCarryState();
        AutismServerInfoOverlay serverInfo = getServerDataOverlayIfExists();
        if (!PackHideState.isActive() && serverInfo != null && (serverInfo.isVisible() || serverInfo.shouldRenderBackgroundProbeBanner())) {
            serverInfo.tickBackground();
        }

        if (shared.shouldDelayGuiPackets() && MC.getConnection() != null) {
            if (autoSendTickCounter++ >= 1) {
                autoSendTickCounter = 0;
            }
        } else {
            autoSendTickCounter = 0;
        }

        if (AutismInputGate.canRunAutismKeybinds()) {
            tickKeybinds();
        } else {
            loadGuiKeyPressed = false;
            flushQueueKeyPressed = false;
            clearQueueKeyPressed = false;
            toggleLoggerKeyPressed = false;
            toggleSendKeyPressed = false;
            toggleDelayKeyPressed = false;
            macroKeyStates.clear();
        }

        packetLoggerTickCounter++;
        AutismPacketLoggerOverlay logger = getPacketLoggerOverlayIfExists();
        if (logger != null) {
            logger.setGameTick(packetLoggerTickCounter);
        }
    }

    public void onGameJoin() {
        AutismPerf.beginJoinWindow();
        long perf = AutismPerf.beginJoin();
        joinedPlayConnection = true;
        spawnedInWorld = false;
        beginPassivePayloadCapture();
        applyRuntimePacketFlowDefaults();
        if (PackHideState.isActive()) PackHideState.stopRuntimeWork();

        if (!PackHideState.isActive() && config != null && config.packetLoggerCapturing) {
            getPacketLoggerOverlay();
        }

        if (config.lanSyncEnabled && !PackHideState.isActive() && !AutismLANSync.getInstance().isRunning()) {
            AutismLANSync.getInstance().start();
        }

        if (!PackHideState.isActive()) AutismLANSync.getInstance().onGameJoined();
        PackModuleRegistry.onGameJoin();
        AutismPerf.endJoinSpike("join.onGameJoin", perf, 8_000_000L);
    }

    public void onGameLeft() {
        joinedPlayConnection = false;
        spawnedInWorld = false;
        passivePayloadCaptureUntilMs = 0L;
        AutismSharedState.get().clearRealServerVersion();
        loadGuiKeyPressed = false;
        flushQueueKeyPressed = false;
        clearQueueKeyPressed = false;
        toggleLoggerKeyPressed = false;
        toggleSendKeyPressed = false;
        toggleDelayKeyPressed = false;
        moduleMenuKeyPressed = false;
        PackModuleRegistry.onGameLeft();
    }

    public boolean isActive() {
        return initialized && spawnedInWorld && isPlayerSpawnedInWorld();
    }

    public boolean arePacketHooksActive() {
        if (!isActive() || PackHideState.isActive()) return false;
        if (isPassivePayloadCaptureActive()) return true;
        AutismSharedState shared = AutismSharedState.get();
        return shared.hasPacketFlowWork()
            || isPacketLoggerCapturing()
            || getServerDataOverlayIfExists() != null
            || PackModuleRegistry.hasActivePacketEventModules()
            || autismclient.util.macro.PacketGateManager.hasActiveGates()
            || autismclient.util.macro.MacroExecutor.hasPacketObservationWork();
    }

    public void onConfigurationConnectionStarted() {
        beginPassivePayloadCapture();
    }

    public boolean isPassivePayloadCaptureActive() {
        return System.currentTimeMillis() <= passivePayloadCaptureUntilMs;
    }

    public boolean isPacketLoggerCapturing() {
        if (PackHideState.isActive()) return false;
        AutismPacketLoggerOverlay logger = getPacketLoggerOverlayIfExists();
        return logger != null && !logger.isPaused();
    }

    public void capturePassivePayloadPacket(Packet<?> packet, String direction) {
        capturePassivePayloadPacket(packet, direction, "");
    }

    public void capturePassivePayloadPacket(Packet<?> packet, String direction, String protocolPhase) {
        if (PackHideState.isActive()) return;
        if (!isPassivePayloadCaptureActive()) return;
        if (!AutismPacketLoggerOverlay.isPayloadPacket(packet)) return;
        autismclient.util.AutismPayloadSupport.rememberPayloadProtocol(packet, protocolPhase);

        AutismPacketLoggerOverlay logger = getPacketLoggerOverlayIfExists();
        if (logger != null) {
            logger.logPayloadPacketSilently(packet, direction);
        } else {
            rememberPassivePayload(packet, direction);
        }
    }

    private void rememberPassivePayload(Packet<?> packet, String direction) {
        long perf = AutismPerf.beginJoin();
        autismclient.util.AutismPayloadSupport.PayloadSnapshot snapshot =
            autismclient.util.AutismPayloadSupport.snapshot(packet, direction);
        if (snapshot == null) return;
        synchronized (passivePayloadRing) {
            while (passivePayloadRing.size() >= PASSIVE_PAYLOAD_RING_CAP) passivePayloadRing.removeFirst();
            passivePayloadRing.addLast(new PassivePayloadCapture(
                System.currentTimeMillis(),
                packetLoggerTickCounter,
                direction == null ? "" : direction,
                packet.getClass(),
                snapshot
            ));
        }
        AutismPerf.endJoinSpike("join.passivePayload.snapshot", perf);
    }

    public void toggle() {
        AutismClientMessaging.sendPrefixed("Autism is always enabled in standalone mode.");
    }

    private void beginPassivePayloadCapture() {
        passivePayloadCaptureUntilMs = Math.max(passivePayloadCaptureUntilMs, System.currentTimeMillis() + PASSIVE_PAYLOAD_CAPTURE_MS);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public void appendTooltip(ItemStack stack, List<?> lines) {
        for (PackModule module : PackModuleRegistry.activeModules()) {
            module.appendTooltip(stack, lines);
        }
    }

    public boolean handlePacketSend(Packet<?> packet) {
        long perf = AutismPerf.beginJoin();
        try {
        if (PackHideState.isActive()) return false;
        AutismPacketLoggerOverlay logger = getPacketLoggerOverlayIfExists();
        if (PackModuleRegistry.onPacketSend(packet)) return true;
        if (logger == null) return false;
        if (!logger.isPacketBlocked(packet.getClass())) {
            logger.logPacket(packet, "C2S");
        }
        return false;
        } finally {
            AutismPerf.endJoinSpike("join.packetSendHook", perf);
        }
    }

    public boolean handlePacketReceive(Packet<?> packet) {
        long perf = AutismPerf.beginJoin();
        try {
        if (PackHideState.isActive()) return false;
        AutismServerInfoOverlay overlay = getServerDataOverlayIfExists();
        if (packet instanceof net.minecraft.network.protocol.game.ClientboundCommandSuggestionsPacket suggestions) {
            if (overlay != null) {
                overlay.onCommandSuggestions(suggestions.id(), suggestions);
            }
        }

        if (packet instanceof net.minecraft.network.protocol.game.ClientboundCommandsPacket) {
            if (overlay != null) {
                overlay.onCommandTreeChanged();
            }
        }

        AutismPacketLoggerOverlay logger = getPacketLoggerOverlayIfExists();
        if (PackModuleRegistry.onPacketReceive(packet)) return true;
        if (logger == null) return false;
        if (!logger.isPacketBlocked(packet.getClass())) {
            logger.logPacket(packet, "S2C");
        }
        return false;
        } finally {
            AutismPerf.endJoinSpike("join.packetReceiveHook", perf);
        }
    }

    public AutismPacketLoggerOverlay getPacketLoggerOverlay() {
        if (packetLoggerOverlay == null && MC != null && MC.font != null) {
            packetLoggerOverlay = new AutismPacketLoggerOverlay(MC.font);
            packetLoggerOverlay.restoreLayout();
            hydratePassivePayloads(packetLoggerOverlay);
        }
        return packetLoggerOverlay;
    }

    public AutismPacketLoggerOverlay getPacketLoggerOverlayIfExists() {
        return packetLoggerOverlay;
    }

    private void hydratePassivePayloads(AutismPacketLoggerOverlay logger) {
        if (logger == null) return;
        List<PassivePayloadCapture> captures;
        synchronized (passivePayloadRing) {
            if (passivePayloadRing.isEmpty()) return;
            captures = new ArrayList<>(passivePayloadRing);
            passivePayloadRing.clear();
        }
        for (PassivePayloadCapture capture : captures) {
            logger.logPayloadSnapshotSilently(
                capture.timestampMs(),
                capture.gameTick(),
                capture.direction(),
                capture.packetClass(),
                capture.snapshot()
            );
        }
    }

    private record PassivePayloadCapture(long timestampMs, int gameTick, String direction, Class<?> packetClass,
                                         autismclient.util.AutismPayloadSupport.PayloadSnapshot snapshot) {
    }

    public AutismServerInfoOverlay getServerDataOverlay() {
        if (serverInfoOverlay == null && MC != null && MC.font != null) {
            serverInfoOverlay = new AutismServerInfoOverlay(MC.font);
            serverInfoOverlay.restoreState();
        }
        return serverInfoOverlay;
    }

    public AutismServerInfoOverlay getServerDataOverlayIfExists() {
        return serverInfoOverlay;
    }

    public boolean isNoPauseOnLostFocus() {
        return config.noPauseOnLostFocus;
    }

    public boolean isLANSyncEnabled() {
        return config.lanSyncEnabled;
    }

    public boolean isBypassResourcePack() {
        return config != null ? config.pretendPackAccepted : AutismConfig.getGlobal().pretendPackAccepted;
    }

    public boolean isSpoofClientVanilla() {
        return config != null ? config.spoofClientVanilla : AutismConfig.getGlobal().spoofClientVanilla;
    }

    public boolean isInventoryMoveEnabled() {
        PackModule module = PackModuleRegistry.get("inv-move");
        return module != null ? module.isEnabled() : config != null && config.inventoryMove;
    }

    public void setInventoryMoveEnabled(boolean value) {
        if (config == null) return;
        config.inventoryMove = value;
        PackModule module = PackModuleRegistry.get("inv-move");
        if (module != null && module.isEnabled() != value) module.setEnabledSilently(value);
        saveConfig();
    }

    public boolean isXCarryEnabled() {
        PackModule module = PackModuleRegistry.get("xcarry");
        return module != null ? module.isEnabled() : config != null && config.xCarry;
    }

    public void setXCarryEnabled(boolean value) {
        if (config == null) return;
        config.xCarry = value;
        PackModule module = PackModuleRegistry.get("xcarry");
        if (module != null && module.isEnabled() != value) module.setEnabledSilently(value);
        saveConfig();
    }

    public void setBypassResourcePack(boolean value) {
        config.pretendPackAccepted = value;
        AutismSharedState.get().setBypassResourcePack(value);
        saveConfig();
    }

    public void setSpoofClientVanilla(boolean value) {
        if (config == null) return;
        config.spoofClientVanilla = value;
        saveConfig();
    }

    public boolean isForceDenyResourcePack() {
        return config != null ? config.autoDenyResourcePack : AutismConfig.getGlobal().autoDenyResourcePack;
    }

    public void setForceDenyResourcePack(boolean value) {
        config.autoDenyResourcePack = value;
        AutismSharedState.get().setResourcePackForceDeny(value);
        saveConfig();
    }

    public boolean useMsSleepMode() {
        return config.useMsSleepMode;
    }

    public int getMsSleepInterval() {
        return config.msSleepInterval;
    }

    public boolean useInstantExecutionMode() {
        return config.instantExecutionMode;
    }

    public int getActionDelayUs() {
        return config.actionDelayUs;
    }

    public boolean usePacketBurstMode() {
        return config.packetBurstMode;
    }

    public boolean shouldUseDirectFlush() {
        return config.useDirectFlush;
    }

    public boolean shouldForceChannelFlush() {
        return config.forceChannelFlush;
    }

    public boolean shouldFlushQueueOnDelayDisable() {
        return config != null && config.flushQueueOnDelayDisable;
    }

    public void setFlushQueueOnDelayDisable(boolean value) {
        if (config == null) return;
        config.flushQueueOnDelayDisable = value;
        saveConfig();
    }

    public boolean isCaptureAsExact() {
        return config != null && config.captureAsExact;
    }

    public void setCaptureAsExact(boolean value) {
        if (config == null) return;
        config.captureAsExact = value;
        saveConfig();
    }

    public boolean shouldUseCustomPackets() {
        return config.useCustomPackets;
    }

    public void setUseCustomPackets(boolean value) {
        config.useCustomPackets = value;
        AutismSharedState.get().setUseCustomPackets(value);
        saveConfig();
    }

    public void setSendGuiPackets(boolean value) {
        config.sendGuiPackets = value;
        AutismSharedState.get().setSendGuiPackets(value);
    }

    public boolean applySendGuiPacketsUiBehavior(boolean value) {
        setSendGuiPackets(value);
        saveConfig();
        return value;
    }

    public void setDelayGuiPackets(boolean value) {
        config.delayGuiPackets = value;
        AutismSharedState.get().setDelayGuiPackets(value);
    }

    public String getCommandPrefix() {
        return config == null ? "" : (config.commandPrefix == null ? "" : config.commandPrefix);
    }

    public void setCommandPrefix(String prefix) {
        if (config == null) return;
        config.commandPrefix = autismclient.util.AutismCompatManager.normalizeStoredCommandPrefix(prefix);
        saveConfig();
    }

    public java.util.Map<Integer, String> getCommandBinds() {
        if (config == null) return java.util.Collections.emptyMap();
        if (config.commandBinds == null) config.commandBinds = new java.util.LinkedHashMap<>();
        return config.commandBinds;
    }

    public void setCommandBind(int key, String command) {
        if (config == null) return;
        if (config.commandBinds == null) config.commandBinds = new java.util.LinkedHashMap<>();
        if (command == null || command.isBlank()) config.commandBinds.remove(key);
        else config.commandBinds.put(key, command);
        saveConfig();
    }

    public void clearCommandBind(int key) {
        if (config == null || config.commandBinds == null) return;
        config.commandBinds.remove(key);
        saveConfig();
    }

    private autismclient.modules.PackModule xcarryModule() {
        return autismclient.modules.PackModuleRegistry.get("xcarry");
    }
    public boolean isXCarryUseCrafting() {
        autismclient.modules.PackModule m = xcarryModule();
        if (m == null) return true;
        String v = m.value("use-crafting");
        return v == null || v.isBlank() || Boolean.parseBoolean(v);
    }
    public boolean isXCarryUseArmor() {
        autismclient.modules.PackModule m = xcarryModule();
        if (m == null) return true;
        String v = m.value("use-armor");
        return v == null || v.isBlank() || Boolean.parseBoolean(v);
    }
    public boolean isXCarryUseOffhand() {
        autismclient.modules.PackModule m = xcarryModule();
        if (m == null) return true;
        String v = m.value("use-offhand");
        return v == null || v.isBlank() || Boolean.parseBoolean(v);
    }
    public void setXCarryUseCrafting(boolean v) {
        autismclient.modules.PackModule m = xcarryModule();
        if (m != null) m.setValue("use-crafting", Boolean.toString(v));
    }
    public void setXCarryUseArmor(boolean v) {
        autismclient.modules.PackModule m = xcarryModule();
        if (m != null) m.setValue("use-armor", Boolean.toString(v));
    }
    public void setXCarryUseOffhand(boolean v) {
        autismclient.modules.PackModule m = xcarryModule();
        if (m != null) m.setValue("use-offhand", Boolean.toString(v));
    }
    public boolean isXCarryCarryCursor() {
        autismclient.modules.PackModule m = xcarryModule();
        if (m == null) return true;
        String v = m.value("carry-cursor");
        return v == null || v.isBlank() || Boolean.parseBoolean(v);
    }
    public void setXCarryCarryCursor(boolean v) {
        autismclient.modules.PackModule m = xcarryModule();
        if (m != null) m.setValue("carry-cursor", Boolean.toString(v));
    }

    public java.util.Set<Integer> getXCarryModuleSlotMask() {
        java.util.LinkedHashSet<Integer> s = new java.util.LinkedHashSet<>();
        if (isXCarryUseCrafting()) { s.add(1); s.add(2); s.add(3); s.add(4); }
        if (isXCarryUseArmor())    { s.add(5); s.add(6); s.add(7); s.add(8); }
        if (isXCarryUseOffhand())  { s.add(45); }
        if (s.isEmpty()) { s.add(1); s.add(2); s.add(3); s.add(4); }
        return s;
    }

    public int applyDelayGuiPacketsUiBehavior(boolean value) {
        setDelayGuiPackets(value);
        saveConfig();

        if (!value && shouldFlushQueueOnDelayDisable() && MC != null && MC.getConnection() != null) {
            return AutismSharedState.get().flushDelayedPackets(MC.getConnection());
        }

        return 0;
    }

    public int flushQueuedPacketsUiBehavior() {
        if (MC == null || MC.getConnection() == null) return 0;
        return AutismSharedState.get().flushDelayedPackets(MC.getConnection());
    }

    public int clearQueuedPacketsUiBehavior() {
        AutismSharedState shared = AutismSharedState.get();
        int count = shared.clearQueuedPackets();
        return count;
    }

    public void notifyDelayPacketsUiResult(boolean enabled, int flushed) {
        AutismSharedState shared = AutismSharedState.get();
        if (enabled) {
            AutismNotifications.show("Delay Packets on", 0xFF35D873);
            return;
        }

        if (flushed > 0) {
            if (shared.isStaggering()) {
                shared.setPendingQueueCompletionMessage("Sent " + flushed + " packet" + (flushed == 1 ? "" : "s") + ".");
                AutismNotifications.show("Delay Packets off - sending " + flushed, 0xFFFF5B5B);
            } else {
                AutismNotifications.show("Delay Packets off - sent " + flushed, 0xFFFF5B5B);
            }
            return;
        }

        if (!shouldFlushQueueOnDelayDisable()
            && (!shared.getDelayedPackets().isEmpty() || !shared.getStaggeredQueue().isEmpty())) {
            AutismNotifications.show("Delay Packets off - queue kept", 0xFFFF5B5B);
        } else {
            AutismNotifications.show("Delay Packets off - queue empty", 0xFFFF5B5B);
        }
    }

    public void notifyFlushQueuedPacketsUiResult(int count) {
        AutismSharedState shared = AutismSharedState.get();
        if (count > 0) {
            if (shared.isStaggering()) {
                shared.setPendingQueueCompletionMessage("Sent " + count + " packet" + (count == 1 ? "" : "s") + ".");
                AutismNotifications.show("Sending " + count + " packet" + (count == 1 ? "" : "s"), 0xFFFFC857);
            } else {
                AutismNotifications.show("Sent " + count + " packet" + (count == 1 ? "" : "s"), 0xFF35D873);
            }
        } else {
            AutismNotifications.show("Queue empty", 0xFFFF5B5B);
        }
    }

    public void notifyClearQueuedPacketsUiResult(int count) {
        AutismNotifications.show(
            count > 0 ? "Cleared " + count + " packet" + (count == 1 ? "" : "s") : "Queue empty",
            count > 0 ? 0xFFFFC857 : 0xFFFF5B5B
        );
    }

    public boolean togglePacketLoggerUiBehavior() {
        AutismPacketLoggerOverlay overlay = getPacketLoggerOverlay();
        if (overlay == null) return false;
        overlay.toggle();
        return true;
    }

    public boolean restoreSavedScreenUiBehavior() {
        AutismSharedState shared = AutismSharedState.get();
        if (MC == null) return false;
        if (shared.getStoredScreen() == null || shared.getStoredAbstractContainerMenu() == null) {
            return false;
        }

        MC.execute(() -> {
            MC.setScreen(shared.getStoredScreen());
            AbstractContainerMenu handler = shared.getStoredAbstractContainerMenu();
            if (MC.player != null) MC.player.containerMenu = handler;
        });
        return true;
    }

    public Set<Class<? extends Packet<?>>> getC2SPackets() {
        return new LinkedHashSet<>(AutismSharedState.get().getC2SPackets());
    }

    public Set<Class<? extends Packet<?>>> getS2CPackets() {
        return new LinkedHashSet<>(AutismSharedState.get().getS2CPackets());
    }

    public void setC2SPackets(Set<Class<? extends Packet<?>>> packets) {
        Set<Class<? extends Packet<?>>> safe = packets == null ? defaultC2SPackets() : new LinkedHashSet<>(packets);
        AutismSharedState.get().setC2SPackets(safe);
        config.c2sPackets = encodePackets(safe);
        saveConfig();
    }

    public void setS2CPackets(Set<Class<? extends Packet<?>>> packets) {
        Set<Class<? extends Packet<?>>> safe = packets == null ? defaultS2CPackets() : new LinkedHashSet<>(packets);
        AutismSharedState.get().setS2CPackets(safe);
        config.s2cPackets = encodePackets(safe);
        saveConfig();
    }

    public void resetC2SPacketsToDefault() {
        setC2SPackets(defaultC2SPackets());
    }

    public void resetS2CPacketsToDefault() {
        setS2CPackets(defaultS2CPackets());
    }

    public Set<Class<? extends Packet<?>>> defaultC2SPackets() {
        Set<Class<? extends Packet<?>>> defaults = new LinkedHashSet<>();
        for (Class<? extends Packet<?>> packetClass : AutismPacketRegistry.getC2SPackets()) {
            if (!C2S_EXCLUDED_DEFAULTS.contains(packetClass)) defaults.add(packetClass);
        }

        defaults.add(net.minecraft.network.protocol.game.ServerboundChatPacket.class);
        defaults.add(net.minecraft.network.protocol.game.ServerboundChatCommandPacket.class);
        defaults.add(net.minecraft.network.protocol.game.ServerboundChatCommandSignedPacket.class);
        defaults.add(net.minecraft.network.protocol.game.ServerboundSignUpdatePacket.class);
        return defaults;
    }

    public Set<Class<? extends Packet<?>>> defaultS2CPackets() {
        return new LinkedHashSet<>();
    }

    private void applyConfigToSharedState() {
        AutismSharedState shared = AutismSharedState.get();
        applyRuntimePacketFlowDefaults();
        shared.setUseCustomPackets(config.useCustomPackets);
        shared.setC2SPackets(resolvePackets(config.c2sPackets, true));
        shared.setS2CPackets(resolvePackets(config.s2cPackets, false));
        shared.setAllowSignEditing(config.allowSignEditing);
        shared.setResourcePackForceDeny(config.autoDenyResourcePack);
        shared.setBypassResourcePack(config.pretendPackAccepted);
        shared.setStaggeredPacketSend(config.staggeredPacketSend);
        shared.setStaggeredSendDelay(config.staggeredSendDelay);
    }

    private void applyRuntimePacketFlowDefaults() {
        if (config != null) {
            config.applyRuntimeDefaults();
        }
        AutismSharedState shared = AutismSharedState.get();
        shared.setSendGuiPackets(true);
        shared.setDelayGuiPackets(false);
        shared.setStaggeredPacketSend(false);
        shared.setCaptureMode(false);
    }

    private void updatePassiveXCarryState() {
        AutismSharedState shared = AutismSharedState.get();
        if (shared.isXCarryForced()) return;
        if (!shared.isXCarryActive() && !isXCarryEnabled()) return;
        if (PackHideState.isActive()) {
            shared.setXCarryActive(false);
            return;
        }

        boolean active = false;
        if (isXCarryEnabled() && MC.player != null && MC.player.inventoryMenu != null) {
            active = autismclient.util.macro.XCarryAction.hasStoredItems(MC.player.inventoryMenu, true);
        }

        shared.setXCarryActive(active);
    }

    private void updateWorldSpawnState() {
        if (!joinedPlayConnection) {
            spawnedInWorld = false;
            return;
        }

        boolean wasSpawned = spawnedInWorld;
        spawnedInWorld = isPlayerSpawnedInWorld();
        if (!wasSpawned && spawnedInWorld && !PackHideState.isActive()) {
            AutismJoinMacroController.onWorldReady();
        }
    }

    private boolean isPlayerSpawnedInWorld() {
        return MC != null && MC.getConnection() != null && MC.player != null && MC.level != null;
    }

    private void saveConfig() {
        if (config == null) return;
        config.save();
    }

    private Set<Class<? extends Packet<?>>> resolvePackets(List<String> names, boolean c2s) {
        Set<Class<? extends Packet<?>>> resolved = new LinkedHashSet<>();
        if (names != null) {
            for (String name : names) {
                if (name == null || name.isBlank()) continue;
                Class<? extends Packet<?>> packetClass = AutismPacketRegistry.getPacket(name);
                if (packetClass == null) {
                    try {
                        Class<?> direct = Class.forName(name);
                        if (Packet.class.isAssignableFrom(direct)) {
                            @SuppressWarnings("unchecked")
                            Class<? extends Packet<?>> typed = (Class<? extends Packet<?>>) direct;
                            packetClass = typed;
                        }
                    } catch (ClassNotFoundException ignored) {
                    }
                }
                if (packetClass != null) resolved.add(packetClass);
            }
        }

        if (resolved.isEmpty()) {
            return c2s ? defaultC2SPackets() : defaultS2CPackets();
        }

        return resolved;
    }

    private List<String> encodePackets(Set<Class<? extends Packet<?>>> packets) {
        List<String> names = new ArrayList<>();
        for (Class<? extends Packet<?>> packetClass : packets) {
            String name = AutismPacketRegistry.getName(packetClass);
            names.add(name != null ? name : packetClass.getName());
        }
        return names;
    }

    private void tickKeybinds() {
        AutismConfig cfg = config;
        if (cfg == null) return;
        AutismSharedState shared = AutismSharedState.get();
        refreshKeyboundMacroCache();

        if (cfg.keybindModuleMenu != -1) {
            boolean pressed = isBindPressed(cfg.keybindModuleMenu);
            if (pressed && !moduleMenuKeyPressed && MC.screen == null) {
                MC.setScreen(new AutismModuleScreen(null));
            }
            moduleMenuKeyPressed = pressed;
        }

        if (PackHideState.isActive()) {
            loadGuiKeyPressed = false;
            flushQueueKeyPressed = false;
            clearQueueKeyPressed = false;
            toggleLoggerKeyPressed = false;
            toggleSendKeyPressed = false;
            toggleDelayKeyPressed = false;
            macroKeyStates.clear();
            return;
        }

        if (cfg.keybindLoadGui != -1) {
            boolean pressed = isBindPressed(cfg.keybindLoadGui);
            if (pressed && !loadGuiKeyPressed) {
                if (restoreSavedScreenUiBehavior()) {
                    AutismNotifications.show("GUI restored.", 0xFF35D873);
                } else {
                    AutismNotifications.error("No stored GUI.");
                }
            }
            loadGuiKeyPressed = pressed;
        }

        if (cfg.keybindFlushQueue != -1) {
            boolean pressed = isBindPressed(cfg.keybindFlushQueue);
            if (pressed && !flushQueueKeyPressed) {
                int count = flushQueuedPacketsUiBehavior();
                notifyFlushQueuedPacketsUiResult(count);
            }
            flushQueueKeyPressed = pressed;
        }

        if (cfg.keybindClearQueue != -1) {
            boolean pressed = isBindPressed(cfg.keybindClearQueue);
            if (pressed && !clearQueueKeyPressed) {
                int count = clearQueuedPacketsUiBehavior();
                notifyClearQueuedPacketsUiResult(count);
            }
            clearQueueKeyPressed = pressed;
        }

        if (cfg.keybindToggleLogger != -1) {
            boolean pressed = isBindPressed(cfg.keybindToggleLogger);
            if (pressed && !toggleLoggerKeyPressed) {
                togglePacketLoggerUiBehavior();
            }
            toggleLoggerKeyPressed = pressed;
        }

        if (cfg.keybindToggleSend != -1) {
            boolean pressed = isBindPressed(cfg.keybindToggleSend);
            if (pressed && !toggleSendKeyPressed) {
                boolean newValue = !shared.shouldSendGuiPackets();
                applySendGuiPacketsUiBehavior(newValue);
                AutismNotifications.show("Send Packets " + (newValue ? "on" : "off"), newValue ? 0xFF35D873 : 0xFFFF3B3B);
            }
            toggleSendKeyPressed = pressed;
        }

        if (cfg.keybindToggleDelay != -1) {
            boolean pressed = isBindPressed(cfg.keybindToggleDelay);
            if (pressed && !toggleDelayKeyPressed) {
                boolean newValue = !shared.shouldDelayGuiPackets();
                int sent = applyDelayGuiPacketsUiBehavior(newValue);
                notifyDelayPacketsUiResult(newValue, sent);
            }
            toggleDelayKeyPressed = pressed;
        }

        for (AutismMacro macro : cachedKeyboundMacros) {
            boolean pressed = isBindPressed(macro.keyCode);
            boolean wasPressed = macroKeyStates.getOrDefault(macro.name, false);
            if (pressed && !wasPressed) {
                if (autismclient.util.macro.MacroExecutor.isVisibleRunning()) {
                    autismclient.util.macro.MacroExecutor.stop();
                } else {
                    macro.execute();
                }
            }
            macroKeyStates.put(macro.name, pressed);
        }
    }

    private void refreshKeyboundMacroCache() {
        AutismMacroManager macroManager = AutismMacroManager.get();
        long revision = macroManager.getRevision();
        if (revision == cachedMacroKeybindRevision) return;

        List<AutismMacro> keyboundMacros = new ArrayList<>();
        Set<String> activeMacroNames = new HashSet<>();
        for (AutismMacro macro : macroManager.getAll()) {
            if (macro == null || macro.keyCode == -1) continue;
            keyboundMacros.add(macro);
            activeMacroNames.add(macro.name);
        }

        macroKeyStates.keySet().removeIf(name -> !activeMacroNames.contains(name));
        cachedKeyboundMacros = keyboundMacros;
        cachedMacroKeybindRevision = revision;
    }

    private boolean isAnyTextFieldFocused() {
        return AutismOverlayManager.get().isAnyTextFieldFocused();
    }

    private boolean isBindPressed(int bindCode) {
        return autismclient.util.AutismBindUtil.isBindPressed(MC, bindCode);
    }

    private void restoreSavedScreen() {
        AutismSharedState shared = AutismSharedState.get();
        if (MC == null) return;

        if (shared.getStoredScreen() == null || shared.getStoredAbstractContainerMenu() == null) {
            AutismNotifications.error("No stored GUI.");
            return;
        }

        MC.setScreen(shared.getStoredScreen());
        AbstractContainerMenu handler = shared.getStoredAbstractContainerMenu();
        if (MC.player != null) MC.player.containerMenu = handler;
        AutismNotifications.show("GUI restored.", 0xFF35D873);
    }
}
