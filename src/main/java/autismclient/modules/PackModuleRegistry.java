package autismclient.modules;

import autismclient.util.AutismBindUtil;
import autismclient.util.AutismConfig;
import autismclient.util.AutismInputGate;
import autismclient.util.AutismPerf;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class PackModuleRegistry {
    private static final Minecraft MC = Minecraft.getInstance();
    private static final Map<String, PackModule> MODULES = new LinkedHashMap<>();
    private static final Map<String, Boolean> KEY_STATES = new LinkedHashMap<>();
    private static final Map<PackModuleCategory, List<PackModule>> CATEGORY_CACHE = new java.util.HashMap<>();
    private static List<PackModule> activeModulesCache = List.of();
    private static int activeModulesCacheRevision = -1;
    private static List<PackModule> disabledTickModulesCache = List.of();
    private static int disabledTickModulesCacheRevision = -1;
    private static List<PackModule> keyboundModulesCache = List.of();
    private static int keyboundModulesCacheRevision = -1;
    private static List<PackModule> packetSendModulesCache = List.of();
    private static int packetSendModulesCacheRevision = -1;
    private static List<PackModule> packetReceiveModulesCache = List.of();
    private static int packetReceiveModulesCacheRevision = -1;
    private static List<PackModule> disabledPacketReceiveModulesCache = List.of();
    private static int disabledPacketReceiveModulesCacheRevision = -1;
    private static List<PackModule> soundModulesCache = List.of();
    private static int soundModulesCacheRevision = -1;
    private static List<PackModule> renderModulesCache = List.of();
    private static int renderModulesCacheRevision = -1;
    private static List<PackModule> blockBreakingProgressModulesCache = List.of();
    private static int blockBreakingProgressModulesCacheRevision = -1;
    private static List<PackModule> startBreakingModulesCache = List.of();
    private static int startBreakingModulesCacheRevision = -1;
    private static boolean activePacketEventModulesCache;
    private static int activePacketEventModulesCacheRevision = -1;
    private static int revision;
    private static int activeRevision;
    private static boolean initialized;
    private static boolean menuKeyDown;

    private PackModuleRegistry() {
    }

    public static void initialize(AutismConfig config) {
        if (initialized) return;
        PackBuiltinModules.register();
        PackModuleWorldRenderer.initialize();
        initialized = true;
        PackHideState.enforceStartupHidden();

        autismclient.util.AutismEssentialBridge.restoreIfOrphaned(config);
    }

    public static void register(PackModule module) {
        if (module == null) return;
        MODULES.put(module.id(), module);
        invalidateCaches(true);
    }

    public static Collection<PackModule> all() {
        return MODULES.values();
    }

    public static List<PackModule> byCategory(PackModuleCategory category) {
        if (category == null) return List.of();
        List<PackModule> cached = CATEGORY_CACHE.get(category);
        if (cached != null) return cached;
        List<PackModule> modules = new ArrayList<>();
        for (PackModule module : MODULES.values()) {
            if (module.category() == category && module.showInModuleMenu()) modules.add(module);
        }
        List<PackModule> immutable = Collections.unmodifiableList(modules);
        CATEGORY_CACHE.put(category, immutable);
        return immutable;
    }

    public static PackModule get(String idOrName) {
        if (idOrName == null || idOrName.isBlank()) return null;
        PackModule exact = MODULES.get(idOrName);
        if (exact != null) return exact;
        String needle = normalize(idOrName);
        PackModule direct = MODULES.get(needle);
        if (direct != null) return direct;
        for (PackModule module : MODULES.values()) {
            if (normalize(module.name()).equals(needle)) return module;
        }
        return null;
    }

    public static boolean toggle(String idOrName, autismclient.util.macro.ToggleModuleAction.ToggleMode mode) {
        PackModule module = get(idOrName);
        if (module == null) return false;
        autismclient.util.macro.ToggleModuleAction.ToggleMode resolvedMode =
            mode == null ? autismclient.util.macro.ToggleModuleAction.ToggleMode.TOGGLE : mode;
        if (PackHideState.isActive()
            && !PackHideState.isHideModule(module)
            && (resolvedMode == autismclient.util.macro.ToggleModuleAction.ToggleMode.ENABLE
                || resolvedMode == autismclient.util.macro.ToggleModuleAction.ToggleMode.TOGGLE)) {
            return false;
        }
        switch (resolvedMode) {
            case ENABLE -> module.setEnabled(true);
            case DISABLE -> module.setEnabled(false);
            default -> module.toggle();
        }
        return true;
    }

    public static List<String> names() {
        List<String> names = new ArrayList<>();
        for (PackModule module : MODULES.values()) {
            if (module.showInModuleMenu()) names.add(module.name());
        }
        return names;
    }

    public static List<PackModule> activeModules() {
        if (activeModulesCacheRevision == activeRevision) return activeModulesCache;
        List<PackModule> modules = new ArrayList<>();
        for (PackModule module : MODULES.values()) {
            if (module.isEnabled()) modules.add(module);
        }
        activeModulesCache = Collections.unmodifiableList(modules);
        activeModulesCacheRevision = activeRevision;
        return activeModulesCache;
    }

    public static boolean hasActiveModules() {
        return !activeModules().isEmpty();
    }

    public static boolean hasActivePacketEventModules() {
        if (activePacketEventModulesCacheRevision == activeRevision) return activePacketEventModulesCache;
        boolean result = !packetSendModules().isEmpty() || !packetReceiveModules().isEmpty();
        activePacketEventModulesCache = result;
        activePacketEventModulesCacheRevision = activeRevision;
        return result;
    }

    private static List<PackModule> disabledTickModules() {
        if (disabledTickModulesCacheRevision == revision) return disabledTickModulesCache;
        List<PackModule> modules = new ArrayList<>();
        for (PackModule module : MODULES.values()) {
            if (module.ticksWhenDisabled()) modules.add(module);
        }
        disabledTickModulesCache = Collections.unmodifiableList(modules);
        disabledTickModulesCacheRevision = revision;
        return disabledTickModulesCache;
    }

    private static List<PackModule> keyboundModules() {
        if (keyboundModulesCacheRevision == revision) return keyboundModulesCache;
        List<PackModule> modules = new ArrayList<>();
        for (PackModule module : MODULES.values()) {
            if (module.keybind() != -1) modules.add(module);
        }
        keyboundModulesCache = Collections.unmodifiableList(modules);
        keyboundModulesCacheRevision = revision;
        return keyboundModulesCache;
    }

    private static List<PackModule> activeOverrideModules(String methodName, Class<?>... parameterTypes) {
        List<PackModule> modules = new ArrayList<>();
        for (PackModule module : activeModules()) {
            if (overridesModuleMethod(module, methodName, parameterTypes)) modules.add(module);
        }
        return Collections.unmodifiableList(modules);
    }

    private static List<PackModule> disabledOverrideModules(String methodName, Class<?>... parameterTypes) {
        List<PackModule> modules = new ArrayList<>();
        for (PackModule module : disabledTickModules()) {
            if (!module.isEnabled() && overridesModuleMethod(module, methodName, parameterTypes)) modules.add(module);
        }
        return Collections.unmodifiableList(modules);
    }

    private static List<PackModule> packetSendModules() {
        if (packetSendModulesCacheRevision == activeRevision) return packetSendModulesCache;
        packetSendModulesCache = activeOverrideModules("onPacketSend", Packet.class);
        packetSendModulesCacheRevision = activeRevision;
        return packetSendModulesCache;
    }

    private static List<PackModule> packetReceiveModules() {
        if (packetReceiveModulesCacheRevision == activeRevision) return packetReceiveModulesCache;
        packetReceiveModulesCache = activeOverrideModules("onPacketReceive", Packet.class);
        packetReceiveModulesCacheRevision = activeRevision;
        return packetReceiveModulesCache;
    }

    private static List<PackModule> disabledPacketReceiveModules() {
        if (disabledPacketReceiveModulesCacheRevision == revision) return disabledPacketReceiveModulesCache;
        disabledPacketReceiveModulesCache = disabledOverrideModules("onPacketReceive", Packet.class);
        disabledPacketReceiveModulesCacheRevision = revision;
        return disabledPacketReceiveModulesCache;
    }

    private static List<PackModule> soundModules() {
        if (soundModulesCacheRevision == activeRevision) return soundModulesCache;
        soundModulesCache = activeOverrideModules("onSoundPacket", ClientboundSoundPacket.class);
        soundModulesCacheRevision = activeRevision;
        return soundModulesCache;
    }

    private static List<PackModule> renderModules() {
        if (renderModulesCacheRevision == activeRevision) return renderModulesCache;
        renderModulesCache = activeOverrideModules("onRenderLevel", float.class);
        renderModulesCacheRevision = activeRevision;
        return renderModulesCache;
    }

    private static List<PackModule> blockBreakingProgressModules() {
        if (blockBreakingProgressModulesCacheRevision == activeRevision) return blockBreakingProgressModulesCache;
        blockBreakingProgressModulesCache = activeOverrideModules("onBlockBreakingProgress", BlockPos.class, Direction.class);
        blockBreakingProgressModulesCacheRevision = activeRevision;
        return blockBreakingProgressModulesCache;
    }

    private static List<PackModule> startBreakingModules() {
        if (startBreakingModulesCacheRevision == activeRevision) return startBreakingModulesCache;
        List<PackModule> modules = new ArrayList<>();
        for (PackModule module : activeModules()) {
            if (overridesModuleMethod(module, "shouldCancelStartBreakingBlock", BlockPos.class, Direction.class)
                || overridesModuleMethod(module, "onStartBreakingBlock", BlockPos.class, Direction.class)) {
                modules.add(module);
            }
        }
        startBreakingModulesCache = Collections.unmodifiableList(modules);
        startBreakingModulesCacheRevision = activeRevision;
        return startBreakingModulesCache;
    }

    static void markModuleSettingsChanged() {
        invalidateCaches(false);
    }

    static void markModuleEnabledChanged() {
        invalidateCaches(true);
    }

    public static int revision() {
        return revision;
    }

    public static int activeRevision() {
        return activeRevision;
    }

    private static void invalidateCaches(boolean activeChanged) {
        revision++;
        CATEGORY_CACHE.clear();
        disabledTickModulesCacheRevision = -1;
        keyboundModulesCacheRevision = -1;
        disabledPacketReceiveModulesCacheRevision = -1;
        if (activeChanged) {
            activeRevision++;
            activeModulesCacheRevision = -1;
            activePacketEventModulesCacheRevision = -1;
            packetSendModulesCacheRevision = -1;
            packetReceiveModulesCacheRevision = -1;
            soundModulesCacheRevision = -1;
            renderModulesCacheRevision = -1;
            blockBreakingProgressModulesCacheRevision = -1;
            startBreakingModulesCacheRevision = -1;
            activeModulesCache = List.of();
        }
    }

    public static void tick() {
        if (!initialized || MC == null) return;
        tickModuleKeybinds();
        boolean profileJoin = AutismPerf.isJoinWindowActive();
        for (PackModule module : activeModules()) {
            if (profileJoin) {
                long perf = AutismPerf.beginJoin();
                module.tick();
                AutismPerf.endJoinSpike("join.module.tick." + module.id(), perf);
            } else {
                module.tick();
            }
        }
        for (PackModule module : disabledTickModules()) {
            if (module.isEnabled()) continue;
            if (profileJoin) {
                long perf = AutismPerf.beginJoin();
                module.tick();
                AutismPerf.endJoinSpike("join.module.tick." + module.id(), perf);
            } else {
                module.tick();
            }
        }
    }

    public static void preMovementTick() {
        if (!initialized || MC == null) return;
        for (PackModule module : activeModules()) module.preMovementTick();
    }

    public static void onRenderLevel(float partialTick) {
        if (!initialized || MC == null || MC.level == null || MC.player == null || MC.getConnection() == null || PackHideState.isActive()) return;
        for (PackModule module : renderModules()) module.onRenderLevel(partialTick);
    }

    public static void onMouseRotation(double deltaYaw, double deltaPitch) {
        if (!initialized || MC == null || PackHideState.isActive()) return;
        for (PackModule module : activeModules()) module.onMouseRotation(deltaYaw, deltaPitch);
    }

    public static Vec3 onPlayerMove(MoverType type, Vec3 movement) {
        if (!initialized || MC == null || movement == null) return movement;
        Vec3 adjusted = movement;
        for (PackModule module : activeModules()) adjusted = module.onPlayerMove(type, adjusted);
        return adjusted;
    }

    public static boolean tickMenuKey(int keyCode) {
        if (MC == null || MC.getWindow() == null || keyCode == -1) return false;
        boolean pressed = AutismBindUtil.isBindPressed(MC, keyCode);
        boolean justPressed = pressed && !menuKeyDown;
        menuKeyDown = pressed;
        return justPressed;
    }

    public static void onGameJoin() {
        boolean profileJoin = AutismPerf.isJoinWindowActive();
        for (PackModule module : MODULES.values()) {
            if (module.isEnabled() || module.ticksWhenDisabled()) {
                if (profileJoin) {
                    long perf = AutismPerf.beginJoin();
                    module.onGameJoin();
                    AutismPerf.endJoinSpike("join.module.onGameJoin." + module.id(), perf);
                } else {
                    module.onGameJoin();
                }
            }
        }
    }

    public static void onGameLeft() {
        for (PackModule module : MODULES.values()) {
            if (module.isEnabled() || module.ticksWhenDisabled()) module.onGameLeft();
        }
        KEY_STATES.clear();
        menuKeyDown = false;
    }

    public static boolean onPacketSend(Packet<?> packet) {
        for (PackModule module : packetSendModules()) if (module.onPacketSend(packet)) return true;
        return false;
    }

    public static boolean onPacketReceive(Packet<?> packet) {
        for (PackModule module : packetReceiveModules()) if (module.onPacketReceive(packet)) return true;
        for (PackModule module : disabledPacketReceiveModules()) if (module.onPacketReceive(packet)) return true;
        return false;
    }

    public static void onSoundPacket(ClientboundSoundPacket packet) {
        if (!initialized || PackHideState.isActive()) return;
        for (PackModule module : soundModules()) module.onSoundPacket(packet);
    }

    public static void onBlockBreakingProgress(BlockPos pos, Direction direction) {
        if (!initialized || PackHideState.isActive()) return;
        for (PackModule module : blockBreakingProgressModules()) module.onBlockBreakingProgress(pos, direction);
    }

    private static void tickModuleKeybinds() {
        if (PackHideState.isActive()) {
            KEY_STATES.clear();
            return;
        }
        for (PackModule module : keyboundModules()) {
            int bind = module.keybind();
            boolean pressed = AutismBindUtil.isBindPressed(MC, bind);
            boolean wasPressed = KEY_STATES.getOrDefault(module.id(), false);
            if (pressed && !wasPressed && AutismInputGate.canRunAutismKeybinds() && module.hasActivationToggle()) module.toggle();
            KEY_STATES.put(module.id(), pressed);
        }
    }

    private static boolean overridesPacketEvents(PackModule module) {
        if (module == null) return false;
        return overridesModuleMethod(module, "onPacketSend", Packet.class)
            || overridesModuleMethod(module, "onPacketReceive", Packet.class);
    }

    public static List<PackModule> startBreakingModulesForDispatch() {
        return startBreakingModules();
    }

    private static boolean overridesModuleMethod(PackModule module, String methodName, Class<?>... parameterTypes) {
        if (module == null || methodName == null || methodName.isBlank()) return false;
        try {
            return module.getClass().getMethod(methodName, parameterTypes).getDeclaringClass() != PackModule.class;
        } catch (NoSuchMethodException ignored) {
            return false;
        }
    }

    public static void clearKeyStates() {
        KEY_STATES.clear();
        menuKeyDown = false;
    }

    private static String normalize(String value) {
        return value.toLowerCase(Locale.ROOT).replace(' ', '-').replace("_", "-");
    }
}
