package autismclient.util;

import autismclient.AutismClientAddon;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class AutismConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final File FILE = new File(AutismClientAddon.FOLDER, "config.json");
    private static AutismConfig globalInstance;

    public static AutismConfig getGlobal() {
        if (globalInstance == null) globalInstance = load();
        return globalInstance;
    }

    public static void setGlobal(AutismConfig config) {
        globalInstance = config;
    }

    public transient boolean sendGuiPackets = true;
    public transient boolean delayGuiPackets = false;
    public boolean useCustomPackets = false;
    public List<String> c2sPackets = new ArrayList<>();
    public List<String> s2cPackets = new ArrayList<>();

    public List<String> packetLoggerBlocked = new ArrayList<>();
    public boolean packetLoggerBlockedInit = false;
    public int packetLoggerBlockedDefaultsVersion = 0;
    public boolean packetLoggerCapturing = false;
    public boolean allowSignEditing = true;
    public boolean autoDenyResourcePack = false;
    public boolean pretendPackAccepted = false;
    public boolean resourcePackChoiceInitialized = false;
    public boolean spoofClientVanilla = true;

    public boolean protectorEnabled = true;
    public boolean protectorSpoofBrand = true;
    public boolean protectorFilterChannels = true;
    public boolean protectorTranslationProtection = true;
    public boolean protectorDisableTelemetry = true;

    public boolean protectorBlockLocalUrls = true;

    public boolean protectorIsolatePackCache = true;

    public boolean protectorStripServerPacks = false;

    public boolean protectorChatSigningOff = false;
    public boolean performanceDebug = false;
    public boolean inventoryMove = false;
    public boolean xCarry = true;
    public boolean noPauseOnLostFocus = true;
    public boolean showItemIds = true;
    public boolean lanSyncEnabled = true;
    public boolean staggeredPacketSend = false;
    public int staggeredSendDelay = 1;
    public String executionPreset = "DEFAULT";
    public boolean packetBurstMode = true;
    public boolean useMsSleepMode = false;
    public int msSleepInterval = 5;
    public boolean instantExecutionMode = true;
    public int actionDelayUs = 0;
    public boolean useDirectFlush = true;
    public boolean forceChannelFlush = true;
    public boolean flushQueueOnDelayDisable = true;
    public boolean captureAsExact = false;
    public String commandPrefix = "";
    public boolean joinMacroEnabled = false;
    public String joinMacroName = "";
    public String joinMacroTiming = "WORLD";
    public String joinMacroTriggerJoin = "FIRST";
    public boolean joinMacroKeepEnabled = false;
    public Map<Integer, String> commandBinds = new LinkedHashMap<>();

    public Map<String, ModuleState> modules = new LinkedHashMap<>();
    public List<String> hideRestoreModules = new ArrayList<>();
    public Map<String, ModuleCategoryLayout> moduleCategoryLayouts = new LinkedHashMap<>();
    public Map<String, ModuleWindowLayout> moduleWindowLayouts = new LinkedHashMap<>();
    public Map<String, List<String>> moduleCategoryOrder = new LinkedHashMap<>();
    public Map<String, HudElementState> hudElements = new LinkedHashMap<>();
    public boolean hudLayoutMigrated = false;
    public int hudSnapRange = 10;
    public int hudEdgePadding = 4;
    public boolean hudEditorGrid = true;

    public int keybindLoadGui = org.lwjgl.glfw.GLFW.GLFW_KEY_V;
    public int keybindModuleMenu = org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_SHIFT;
    public int keybindFlushQueue = -1;
    public int keybindClearQueue = -1;
    public int keybindToggleLogger = -1;
    public int keybindToggleSend = -1;
    public int keybindToggleDelay = -1;

    public boolean keybindInsideGui = false;

    public boolean customMainMenu = true;

    public boolean welcomeShown = false;
    public String welcomeInstallIdentity = "";

    public List<String> hideRestoreMeteorModules = new ArrayList<>();

    public boolean hideMeteorHudActive = true;

    public boolean essentialHiddenByPanic = false;

    public boolean essentialSavedEnabled = true;

    public static final class ModuleState {
        public boolean enabled = false;
        public int keybind = -1;
        public Map<String, String> settings = new LinkedHashMap<>();
    }

    public static final class ModuleCategoryLayout {
        public int x = -1;
        public int y = -1;
        public boolean collapsed = false;
    }

    public static final class ModuleWindowLayout {
        public int x = -1;
        public int y = -1;
        public boolean pinned = false;
        public boolean open = false;
    }

    public static final class HudElementState {
        public boolean enabled = true;
        public int x = 8;
        public int y = 8;
        public double scale = 1.0;
        public String anchor = "TOP_LEFT";
        public Map<String, String> settings = new LinkedHashMap<>();
    }

    public static AutismConfig load() {
        if (!FILE.exists()) {
            AutismConfig config = new AutismConfig();
            config.applyRuntimeDefaults();
            return config;
        }

        try (FileReader reader = new FileReader(FILE)) {
            AutismConfig loaded = GSON.fromJson(reader, AutismConfig.class);
            AutismConfig config = loaded != null ? loaded : new AutismConfig();
            config.applyRuntimeDefaults();
            return config;
        } catch (IOException e) {
            AutismClientAddon.LOG.error("Failed to load Autism config", e);
            AutismConfig config = new AutismConfig();
            config.applyRuntimeDefaults();
            return config;
        }
    }

    public void save() {
        FILE.getParentFile().mkdirs();

        try (FileWriter writer = new FileWriter(FILE)) {
            GSON.toJson(this, writer);
        } catch (IOException e) {
            AutismClientAddon.LOG.error("Failed to save Autism config", e);
        }
    }

    public void applyRuntimeDefaults() {
        sendGuiPackets = true;
        delayGuiPackets = false;
        staggeredPacketSend = false;
        if (c2sPackets == null) c2sPackets = new ArrayList<>();
        if (s2cPackets == null) s2cPackets = new ArrayList<>();
        if (modules == null) modules = new LinkedHashMap<>();
        if (hideRestoreModules == null) hideRestoreModules = new ArrayList<>();
        if (hideRestoreMeteorModules == null) hideRestoreMeteorModules = new ArrayList<>();
        if (moduleCategoryLayouts == null) moduleCategoryLayouts = new LinkedHashMap<>();
        if (moduleWindowLayouts == null) moduleWindowLayouts = new LinkedHashMap<>();
        if (moduleCategoryOrder == null) moduleCategoryOrder = new LinkedHashMap<>();
        if (hudElements == null) hudElements = new LinkedHashMap<>();
        if (hudSnapRange <= 0) hudSnapRange = 10;
        if (hudEdgePadding < 0) hudEdgePadding = 4;
        commandPrefix = AutismCompatManager.normalizeStoredCommandPrefix(commandPrefix);
        if (!resourcePackChoiceInitialized) {
            pretendPackAccepted = false;
            resourcePackChoiceInitialized = true;
        }
        for (HudElementState state : hudElements.values()) {
            if (state != null && state.settings == null) state.settings = new LinkedHashMap<>();
        }
    }
}
