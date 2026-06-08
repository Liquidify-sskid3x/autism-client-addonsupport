package autismclient.util;

import autismclient.gui.vanillaui.assets.UiAssets;
import autismclient.gui.vanillaui.direct.DirectUiButton;
import autismclient.gui.vanillaui.direct.DirectUiInsets;
import autismclient.gui.vanillaui.direct.DirectUiLabel;
import autismclient.gui.vanillaui.components.CompactListRenderer;
import autismclient.gui.vanillaui.direct.DirectRenderContext;
import autismclient.gui.vanillaui.components.CompactScrollbar;
import autismclient.gui.vanillaui.components.UiSizing;
import autismclient.gui.vanillaui.direct.DirectSurface;
import autismclient.gui.vanillaui.components.ScrollState;
import autismclient.gui.vanillaui.direct.DirectProgressBar;
import autismclient.gui.vanillaui.direct.DirectInfoRow;
import autismclient.gui.vanillaui.direct.DirectRow;
import autismclient.gui.vanillaui.components.CompactSurfaces;
import autismclient.gui.vanillaui.direct.DirectSlider;
import autismclient.gui.vanillaui.direct.DirectSpacer;
import autismclient.gui.vanillaui.direct.DirectTabStrip;
import autismclient.gui.vanillaui.components.UiText;
import autismclient.gui.vanillaui.components.CompactTextInput;
import autismclient.gui.vanillaui.components.CompactTheme;
import autismclient.gui.vanillaui.components.UiTone;
import autismclient.gui.vanillaui.direct.DirectViewport;
import autismclient.gui.vanillaui.direct.DirectViewportSlot;
import autismclient.gui.vanillaui.direct.DirectWindow;
import autismclient.gui.vanillaui.UiBounds;
import autismclient.gui.vanillaui.UiRenderer;
import autismclient.gui.vanillaui.components.OverlayTopBar;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.*;
import java.util.stream.Collectors;

public class AutismServerInfoOverlay extends AutismOverlayBase {
    private static final Minecraft MC = Minecraft.getInstance();
    private static final int COMPLETION_ID = 1337;
    private static final int HEADER_CONTROL = 12;
    private static final int HEADER_ARROW_WIDTH = 10;
    private static final int HEADER_ARROW_GAP = 3;
    private static final float HEADER_CLICK_DRAG_THRESHOLD = 3.0f;

    private int panelX = 350;
    private int panelY = 30;
    private int panelW = 236;
    private int panelH = 246;
    private static final int ROW_H = 16;
    private static final int PAD = 8;

    private boolean visible = false;
    private boolean collapsed = false;
    private boolean isDragging = false;
    private double dragOffsetX, dragOffsetY;
    private float pressStartUiX, pressStartUiY;
    private int pressStartPanelX, pressStartPanelY;
    private boolean dragMoved = false;
    private final Font textRenderer;
    private final CompactTheme theme = new CompactTheme();
    private final DirectWindow windowNode = new DirectWindow("Server Info");
    private final DirectSurface surface = new DirectSurface(theme, windowNode);
    private final DirectTabStrip tabStrip = new DirectTabStrip();
    private final CompactTextInput searchField = new CompactTextInput();
    private final DirectSlider probeDelaySlider = new DirectSlider();
    private final DirectProgressBar scanProgressBar = new DirectProgressBar();
    private final DirectViewportSlot pluginListSlot = new DirectViewportSlot();
    private boolean pluginScrollbarDragging = false;
    private int pluginScrollbarGrabOffset = 0;
    private long lastUiRebuildMs = 0L;

    private static final String[] TAB_NAMES = {"Info", "Plugins"};
    private int activeTab = 0;

    private final List<ClickRegion> clickRegions = new ArrayList<>();

    private volatile String resolvedIp = null;
    private String lastResolvedAddress = null;
    private volatile boolean resolvingIp = false;

    private final List<String> detectedPlugins = new ArrayList<>();
    private final Map<String, List<String>> pluginCommands = new LinkedHashMap<>();
    private List<PluginListRow> cachedPluginRows = List.of();
    private String cachedPluginRowsQuery = null;
    private int pluginRowsRevision = 0;
    private int cachedPluginRowsRevision = -1;
    private final ScrollState pluginScrollState = new ScrollState();
    private int pluginContentHeight = 0;
    private String selectedPlugin = null;
    private int pluginProbeDelayMs = 50;
    private boolean pluginScanDone = false;
    private boolean pluginScanInProgress = false;
    private long pluginScanStartedAt = 0L;
    private long pluginScanLastResponseAt = 0L;
    private final Set<Integer> pendingPluginProbeIds = new HashSet<>();
    private final Map<Integer, PluginProbeSpec> pluginProbes = new HashMap<>();
    private final Set<String> observedPluginCommands = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
    private final Deque<PluginProbeRequest> queuedPluginProbes = new ArrayDeque<>();
    private final Map<String, PluginEvidence> pluginEvidence = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private final Map<String, PluginScanEntry> scanWorkingEntries = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private long nextPluginProbeSendAt = 0L;
    private int pluginScanTotalSteps = 0;
    private boolean pluginScanCompletionAnnounced = false;

    private String scannedServerAddress = null;
    private String scannedPluginContextSignature = "";
    private String cachedPluginContextServerAddress = "";
    private String cachedPluginContextBrand = "";
    private String cachedPluginContextSignature = "";
    private boolean pluginContextSignatureDirty = true;
    private static final long PLUGIN_SCAN_IDLE_MS = 700L;
    private static final long PLUGIN_SCAN_TIMEOUT_MS = 12000L;
    private static final int DEFAULT_PLUGIN_PROBE_DELAY_MS = 50;
    private static final int MIN_PLUGIN_PROBE_DELAY_MS = 10;
    private static final int MAX_PLUGIN_PROBE_DELAY_MS = 500;
    private static final long PLUGIN_SCAN_SETTLE_MS = 450L;
    private static final int PLUGIN_HEADER_H = 14;
    private static final int SHARED_PANEL_WIDTH = 200;
    private static final int PLUGIN_SETUP_WIDTH = SHARED_PANEL_WIDTH;
    private static final int PLUGIN_SETUP_HEIGHT = 126;
    private static final int PLUGIN_SCANNING_WIDTH = SHARED_PANEL_WIDTH;
    private static final int PLUGIN_SCANNING_HEIGHT = 92;
    private static final int INFO_MIN_WIDTH = SHARED_PANEL_WIDTH;
    private static final int INFO_MIN_HEIGHT = 258;
    private static final int PLUGIN_RESULTS_MIN_WIDTH = SHARED_PANEL_WIDTH;
    private static final int PLUGIN_RESULTS_MIN_HEIGHT = 280;
    private int infoPreferredWidth = INFO_MIN_WIDTH;
    private int infoPreferredHeight = INFO_MIN_HEIGHT;
    private int pluginPreferredWidth = PLUGIN_RESULTS_MIN_WIDTH;
    private int pluginPreferredHeight = PLUGIN_RESULTS_MIN_HEIGHT;
    private static final String[] COMMON_PLUGIN_NAMESPACES = {
        "essentials", "essentialsx", "worldedit", "worldguard", "luckperms", "vault",
        "citizens", "cmi", "cmilib", "multiverse-core", "multiverse", "viaversion",
        "viabackwards", "viarewind", "geysermc", "geyser", "floodgate", "protocollib",
        "coreprotect", "griefprevention", "shopkeepers", "dynmap", "placeholderapi",
        "skinsrestorer", "skript", "advancedanticheat", "vulcan", "grimac", "matrix",
        "spartan", "aac", "karhu", "verus", "nocheatplus", "authme", "deluxemenus",
        "plotsquared", "supervanish", "packetevents", "oraxen", "itemsadder",
        "fawe", "fastasyncworldedit", "luckpermsbukkit", "essentialsgeoip", "essentialsprotect",
        "essentialsspawn", "essentialsxspawn", "multiverse-inventories", "multiverse-netherportals",
        "worldborder", "votifier", "nuVotifier", "votingplugin", "excellentcrates", "crazycrates",
        "cratekeys", "jobs", "jobsreborn", "mcmmo", "towny", "factions", "factionsuuid",
        "lands", "residence", "claimchunk", "quickshop", "quickshop-hikari", "chestshop",
        "shopgui", "auctionhouse", "combatlogx", "litebans", "advancedban", "libertybans",
        "luckpermsgui", "tab", "tablist", "scoreboard", "animatedscoreboard", "ajleaderboards",
        "ajqueue", "spark", "sparkbukkit", "plan", "minimotd", "protocolsupport", "viaversion",
        "excellentenchants", "eco", "ecoenchants", "mythicmobs", "mythiclib", "modelengine",
        "mmoitems", "mmocore", "denizen", "citizenscmd", "sentinel", "npcs", "vulcanbungee",
        "grimacbukkit", "negativity", "intave", "polar", "horizon", "themis", "libreforge"
    };
    private static final String[] PLUGIN_LIST_PROBE_COMMANDS = {
        "/plugins ", "/pl ", "/bukkit:plugins ", "/bukkit:pl "
    };
    private static final String[] VERSION_PROBE_COMMANDS = {
        "/ver ", "/version ", "/about ", "/icanhasbukkit ", "/bukkit:ver ", "/bukkit:version "
    };
    private static final String[] HELP_PROBE_COMMANDS = {
        "/help ", "/? ", "/bukkit:help ", "/minecraft:help "
    };
    private static final String[] HIGH_VALUE_PLUGIN_HINTS = {
        "essentials", "essentialsx", "worldedit", "worldguard", "luckperms", "vault",
        "cmi", "citizens", "multiverse", "viaversion", "geyser", "floodgate",
        "protocollib", "coreprotect", "placeholderapi", "skinsrestorer", "skript",
        "vulcan", "grimac", "matrix", "spartan", "authme", "deluxemenus",
        "plotsquared", "tab", "spark"
    };
    private static final String ROOT_PROBE_PREFIXES = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final Map<String, String> ROOT_COMMAND_PLUGIN_ALIASES = Map.ofEntries(
        Map.entry("lp", "luckperms"),
        Map.entry("we", "worldedit"),
        Map.entry("rg", "worldguard"),
        Map.entry("mv", "multiverse-core"),
        Map.entry("npc", "citizens"),
        Map.entry("papi", "placeholderapi"),
        Map.entry("cmi", "cmi"),
        Map.entry("co", "coreprotect"),
        Map.entry("grim", "grimac"),
        Map.entry("geyser", "geysermc"),
        Map.entry("floodgate", "floodgate"),
        Map.entry("viaver", "viaversion"),
        Map.entry("sr", "skinsrestorer"),
        Map.entry("authme", "authme"),
        Map.entry("dm", "deluxemenus"),
        Map.entry("plots", "plotsquared"),
        Map.entry("sv", "supervanish"),
        Map.entry("spawn", "essentialsx"),
        Map.entry("home", "essentialsx"),
        Map.entry("homes", "essentialsx"),
        Map.entry("warp", "essentialsx"),
        Map.entry("warps", "essentialsx"),
        Map.entry("tpa", "essentialsx"),
        Map.entry("tpahere", "essentialsx"),
        Map.entry("bal", "essentialsx"),
        Map.entry("balance", "essentialsx"),
        Map.entry("money", "essentialsx"),
        Map.entry("eco", "essentialsx"),
        Map.entry("ban", "essentialsx"),
        Map.entry("kick", "essentialsx"),
        Map.entry("mute", "essentialsx"),
        Map.entry("jail", "essentialsx"),
        Map.entry("seen", "essentialsx"),
        Map.entry("ptime", "essentialsx"),
        Map.entry("pweather", "essentialsx"),
        Map.entry("tppos", "essentialsx"),
        Map.entry("near", "essentialsx"),
        Map.entry("back", "essentialsx"),
        Map.entry("afk", "essentialsx"),
        Map.entry("msg", "essentialsx"),
        Map.entry("r", "essentialsx"),
        Map.entry("reply", "essentialsx"),
        Map.entry("mail", "essentialsx"),
        Map.entry("pay", "essentialsx"),
        Map.entry("sell", "essentialsx"),
        Map.entry("worth", "essentialsx"),
        Map.entry("kit", "essentialsx"),
        Map.entry("kits", "essentialsx"),
        Map.entry("lb", "litebans"),
        Map.entry("litebans", "litebans"),
        Map.entry("ab", "advancedban"),
        Map.entry("advancedban", "advancedban"),
        Map.entry("tab", "tab"),
        Map.entry("spark", "spark"),
        Map.entry("plan", "plan"),
        Map.entry("votingplugin", "votingplugin"),
        Map.entry("vote", "votingplugin"),
        Map.entry("votes", "votingplugin"),
        Map.entry("jobs", "jobsreborn"),
        Map.entry("mcmmo", "mcmmo"),
        Map.entry("towny", "towny"),
        Map.entry("f", "factions"),
        Map.entry("factions", "factions"),
        Map.entry("lands", "lands"),
        Map.entry("res", "residence"),
        Map.entry("residence", "residence"),
        Map.entry("qs", "quickshop"),
        Map.entry("quickshop", "quickshop"),
        Map.entry("ah", "auctionhouse"),
        Map.entry("auctionhouse", "auctionhouse"),
        Map.entry("mythicmobs", "mythicmobs"),
        Map.entry("mm", "mythicmobs"),
        Map.entry("mmoitems", "mmoitems"),
        Map.entry("denizen", "denizen"),
        Map.entry("sentinel", "sentinel"),
        Map.entry("vulcan", "vulcan"),
        Map.entry("matrix", "matrix"),
        Map.entry("karhu", "karhu"),
        Map.entry("verus", "verus"),
        Map.entry("ncp", "nocheatplus")
    );

    private static final Set<String> ANTICHEATS = Set.of(
        "nocheatplus", "aac", "spartan", "matrix", "vulcan", "grim",
        "grimac", "intave", "karhu", "verus", "polar", "negativity",
        "themis", "fairfight", "wraith", "horizon", "reflex", "antiaura",
        "guardian", "hac", "thotpatrol", "alice"
    );

    private static final Set<String> VANILLA_NAMESPACES = Set.of(
        "minecraft", "brigadier", "bukkit", "spigot", "paper", "purpur",
        "velocity", "bungeecord", "waterfall"
    );

    private static final class PluginScanEntry {
        String displayName;
        boolean commandBacked;
        PluginEvidence evidence = PluginEvidence.UNKNOWN;
        final Set<String> commands = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
    }

    private enum PluginEvidence {
        COMMAND_TREE,
        NAMESPACE,
        ROOT_HINT,
        HELP_HINT,
        PLUGIN_LIST,
        VERSION_HINT,
        UNKNOWN
    }

    private enum PluginProbeKind {
        ROOT,
        HELP,
        PLUGIN_LIST,
        VERSION,
        NAMESPACE
    }

    private static final class PluginProbeSpec {
        final String query;
        final PluginProbeKind kind;
        final String hint;

        PluginProbeSpec(String query, PluginProbeKind kind, String hint) {
            this.query = query;
            this.kind = kind;
            this.hint = hint;
        }
    }

    private static final class PluginProbeRequest {
        final int id;
        final PluginProbeSpec spec;

        PluginProbeRequest(int id, PluginProbeSpec spec) {
            this.id = id;
            this.spec = spec;
        }
    }

    private static final class PluginListRow {
        final boolean header;
        final String title;
        final String plugin;
        final PluginEvidence evidence;

        private PluginListRow(boolean header, String title, String plugin, PluginEvidence evidence) {
            this.header = header;
            this.title = title;
            this.plugin = plugin;
            this.evidence = evidence;
        }

        static PluginListRow header(String title) {
            return new PluginListRow(true, title, null, PluginEvidence.UNKNOWN);
        }

        static PluginListRow plugin(String plugin, PluginEvidence evidence) {
            return new PluginListRow(false, null, plugin, evidence);
        }
    }

    private static class ClickRegion {
        final int x, y, width, height;
        final Runnable action;
        ClickRegion(int x, int y, int width, int height, Runnable action) {
            this.x = x; this.y = y; this.width = width; this.height = height;
            this.action = action;
        }
        boolean contains(double mx, double my) {
            return mx >= x && mx < x + width && my >= y && my < y + height;
        }
    }

    public AutismServerInfoOverlay(Font textRenderer) {
        this.textRenderer = textRenderer;
        buildUi();
    }

    private void buildUi() {
        windowNode.setCenterTitle(false);
        windowNode.setTitleTone(UiTone.LABEL);
        windowNode.setHeaderControls(true, true);
        windowNode.setTitleAreaInsets(panelPadding() + 1, panelPadding() + headerControlSize() + headerArrowWidth() + headerArrowGap() + 12);
        windowNode.content().setGap(contentGap()).setPadding(DirectUiInsets.all(panelPadding()));

        tabStrip.setTabs(TAB_NAMES).setActiveIndex(activeTab).setOnSelect(this::selectTab);
        searchField
            .setPlaceholder("Search plugins...")
            .setPreferredWidth(pluginSearchWidth())
            .setFieldHeight(searchFieldHeight())
            .setOnChange(text -> pluginScrollState.jumpTo(0, 0));
        probeDelaySlider
            .setRange(MIN_PLUGIN_PROBE_DELAY_MS, MAX_PLUGIN_PROBE_DELAY_MS)
            .setStep(10.0f)
            .setValue(pluginProbeDelayMs)
            .setOnChange(value -> setPluginProbeDelayMs(Math.round(value)));
        scanProgressBar.setProgress(0.0f);
    }

    public void saveState() {
        AutismSharedState shared = AutismSharedState.get();
        rememberCurrentTabSize();
        String stateAddress = currentServerAddress();
        if (stateAddress.isEmpty() && scannedServerAddress != null) stateAddress = scannedServerAddress;
        shared.setServerDataOverlayActiveTab(activeTab);
        shared.setServerDataOverlayPluginScrollOffset(pluginScrollState.targetOffset());
        shared.setServerDataOverlaySelectedPlugin(selectedPlugin);
        shared.setServerDataOverlayStateAddress(stateAddress);
        shared.setServerDataOverlayProbeDelayMs(pluginProbeDelayMs);
        shared.setServerDataOverlayInfoWidth(infoPreferredWidth);
        shared.setServerDataOverlayInfoHeight(infoPreferredHeight);
        shared.setServerDataOverlayPluginWidth(pluginPreferredWidth);
        shared.setServerDataOverlayPluginHeight(pluginPreferredHeight);
        cacheCurrentScan();
        saveLayout();
    }

    public void restoreState() {
        AutismSharedState shared = AutismSharedState.get();
        restoreLayout();
        activeTab = Math.max(0, Math.min(TAB_NAMES.length - 1, shared.getServerDataOverlayActiveTab()));
        pluginProbeDelayMs = Math.max(MIN_PLUGIN_PROBE_DELAY_MS, Math.min(MAX_PLUGIN_PROBE_DELAY_MS, shared.getServerDataOverlayProbeDelayMs()));
        infoPreferredWidth = Math.max(infoMinWidth(), shared.getServerDataOverlayInfoWidth());
        infoPreferredHeight = Math.max(infoMinHeight(), shared.getServerDataOverlayInfoHeight());
        pluginPreferredWidth = Math.max(pluginResultsMinWidth(), shared.getServerDataOverlayPluginWidth());
        pluginPreferredHeight = Math.max(pluginResultsMinHeightPreset(), shared.getServerDataOverlayPluginHeight());
        syncScanStateForCurrentServer();
        String stateAddress = currentServerAddress();
        if (stateAddress.isEmpty() && scannedServerAddress != null) stateAddress = scannedServerAddress;
        if (!stateAddress.isEmpty() && stateAddress.equals(shared.getServerDataOverlayStateAddress())) {
            pluginScrollState.restore(shared.getServerDataOverlayPluginScrollOffset());
            selectedPlugin = shared.getServerDataOverlaySelectedPlugin();
            if (selectedPlugin != null && selectedPlugin.isBlank()) selectedPlugin = null;
            if (selectedPlugin != null && !detectedPlugins.contains(selectedPlugin)) selectedPlugin = null;
        } else {
            pluginScrollState.jumpTo(0, 0);
            selectedPlugin = null;
        }
        if (activeTab == 0) {
            applyInfoLayout();
        } else {
            if (pluginScanInProgress) applyPluginScanningLayout();
            else if (!pluginScanDone) applyPluginSetupLayout();
        }
        tabStrip.setActiveIndex(activeTab);
        probeDelaySlider.setValue(pluginProbeDelayMs);
    }

    private void selectTab(int tabIdx) {
        rememberCurrentTabSize();
        activeTab = Math.max(0, Math.min(TAB_NAMES.length - 1, tabIdx));
        tabStrip.setActiveIndex(activeTab);
        pluginScrollState.jumpTo(0, 0);
        selectedPlugin = null;
        if (activeTab == 0) {
            applyInfoLayout();
        } else if (pluginScanInProgress) {
            applyPluginScanningLayout();
        } else if (!pluginScanDone) {
            applyPluginSetupLayout();
        } else {
            applyPluginResultsLayout();
        }
        saveState();
    }

    private void rebuildUi() {
        windowNode.content().clearChildren();
        tabStrip.setActiveIndex(activeTab);
        probeDelaySlider.setValue(pluginProbeDelayMs);
        scanProgressBar.setProgress(getPluginScanProgress());

        if (!pluginScanInProgress) {
            windowNode.content().add(tabStrip);
        }

        if (activeTab == 0) {
            buildInfoUi();
            return;
        }

        if (!pluginScanDone && !pluginScanInProgress) {
            buildPluginSetupUi();
            return;
        }

        if (pluginScanInProgress) {
            buildPluginScanningUi();
            return;
        }

        buildPluginResultsUi();
    }

    private void buildInfoUi() {
        ServerData entry = MC.getCurrentServer();
        String displayedAddress = getDisplayedServerAddress();
        String realIp = getDisplayedRealIp(displayedAddress);
        String software = getSoftwareGuess(entry);
        String versionNote = getVersionNote(entry);
        String ping = entry != null ? entry.ping + " ms" : "--";
        String players = "--";
        if (MC.getConnection() != null) {
            int online = MC.getConnection().getListedOnlinePlayers().size();
            players = entry != null && entry.players != null ? online + " / " + entry.players.max() : String.valueOf(online);
        }
        String proto = entry != null ? String.valueOf(entry.protocol) : "--";
        String diff = MC.level != null ? MC.level.getDifficulty().getDisplayName().getString() : "--";
        String time = "--";
        if (MC.level != null) {
            long dayCount = MC.level.getOverworldClockTime() / 24000L;
            long timeOfDay = MC.level.getOverworldClockTime() % 24000L;
            int hours = (int) ((timeOfDay / 1000 + 6) % 24);
            int minutes = (int) ((timeOfDay % 1000) * 60 / 1000);
            time = "Day " + dayCount + " (" + String.format("%02d:%02d", hours, minutes) + ")";
        }
        double estimatedTps = AutismSharedState.get().getEstimatedTps();
        String tps = estimatedTps > 0 ? String.format("%.1f", Math.min(20.0, estimatedTps)) : "--";

        addInfoRow("IP:", displayedAddress, null, () -> copyClipboardValue(displayedAddress, "Server address copied.", "Server address unavailable."));
        String host = extractLookupHost(displayedAddress);
        if (!host.isBlank() && (!host.equals(realIp) || resolvingIp)) {
            addInfoRow("Real IP:", realIp, null, this::copyResolvedServerIp);
        }
        addInfoRow("Version:", getReportedVersion(entry), null);
        addInfoRow("Real Version:", getRealServerVersion(), null);
        addInfoRow("Brand:", getLiveBrand(), null);
        if (!"--".equals(software)) addInfoRow("Software:", software, null);
        addInfoRow("Ping:", ping, null);
        addInfoRow("Players:", players, null);
        addInfoRow("Protocol:", proto, null);
        if (!"--".equals(versionNote)) addInfoRow("Version Note:", versionNote, AutismColors.packetYellow());
        addInfoRow("Difficulty:", diff, null);
        addInfoRow("World:", getCurrentWorldName(), null);
        addInfoRow("Time:", time, null);
        addInfoRow("TPS:", tps, null);

        List<String> detectedAcs = getDetectedAnticheats();
        if (pluginScanInProgress) addInfoRow("AntiCheats:", "Scanning...", 0xFFFF4444);
        else if (!pluginScanDone) addInfoRow("AntiCheats:", "Probe Plugins First", 0xFFFF4444);
        else if (detectedAcs.isEmpty()) addInfoRow("AntiCheats:", "None detected", null);
        else addInfoRow("AntiCheats:", String.join(", ", detectedAcs), 0xFFFF4444);

        windowNode.content().add(new DirectSpacer(0, 2));
        windowNode.content().add(new DirectUiButton("Copy Report", DirectUiButton.Variant.SECONDARY, this::copyServerData).setGrowX(true).setButtonHeight(actionButtonHeight()));
    }

    private void addInfoRow(String label, String value, Integer valueColor) {
        addInfoRow(label, value, valueColor, null);
    }

    private void addInfoRow(String label, String value, Integer valueColor, Runnable onPress) {
        windowNode.content().add(
            new DirectInfoRow(label, value)
                .setLabelWidth(infoLabelWidth())
                .setValueColorOverride(valueColor)
                .setOnPress(onPress)
        );
    }

    private void buildPluginSetupUi() {
        windowNode.content().add(new DirectUiLabel("Start probing manually", UiTone.MUTED));
        windowNode.content().add(new DirectInfoRow("Delay:", pluginProbeDelayMs + " ms").setLabelWidth(pluginSetupLabelWidth()));
        windowNode.content().add(probeDelaySlider);
        windowNode.content().add(new DirectUiLabel("Default: " + DEFAULT_PLUGIN_PROBE_DELAY_MS + " ms", UiTone.MUTED));
        windowNode.content().add(new DirectUiLabel("If you get kicked increase the delay.", UiTone.MUTED));
        windowNode.content().add(new DirectUiButton("Start Probing", DirectUiButton.Variant.SECONDARY, () -> {
            AutismSharedState.get().clearServerPluginScan(currentServerAddress());
            resetScan();
            scanPlugins();
        }).setGrowX(true).setButtonHeight(actionButtonHeight()));
    }

    private void buildPluginScanningUi() {
        int progressPercent = Math.max(0, Math.min(100, Math.round(getPluginScanProgress() * 100.0f)));
        windowNode.content().add(new DirectInfoRow("State:", getPluginScanPhaseLabel()).setLabelWidth(pluginScanLabelWidth()).setValueColorOverride(getPluginScanPhaseColor()));
        windowNode.content().add(new DirectInfoRow("Detail:", getPluginScanPhaseDetail()).setLabelWidth(pluginScanLabelWidth()).setValueColorOverride(getPluginScanPhaseColor()));
        int foundCount = getDisplayedPluginCount();
        windowNode.content().add(new DirectInfoRow("Found:", foundCount + " plugin" + (foundCount == 1 ? "" : "s")).setLabelWidth(pluginScanLabelWidth()));
        windowNode.content().add(new DirectInfoRow("Progress:", progressPercent + "%").setLabelWidth(pluginScanLabelWidth()).setValueColorOverride(getPluginScanPhaseColor()));
        windowNode.content().add(scanProgressBar);
    }

    private void buildPluginResultsUi() {
        searchField.setPreferredWidth(Math.max(pluginSearchWidth(), panelW - pluginSearchReserveWidth()));
        windowNode.content().add(searchField);

        DirectRow buttons = new DirectRow().setGap(buttonRowGap());
        buttons.add(new DirectUiButton("Rescan", DirectUiButton.Variant.SECONDARY, () -> {
            AutismSharedState.get().clearServerPluginScan(currentServerAddress());
            resetScan();
            applyPluginSetupLayout();
            saveState();
        }).setGrowX(true).setButtonHeight(actionButtonHeight()));
        buttons.add(new DirectUiButton("Copy Plugins", DirectUiButton.Variant.SECONDARY, this::copyPluginList).setGrowX(true).setButtonHeight(actionButtonHeight()));
        windowNode.content().add(buttons);

        String query = searchField.text().toLowerCase(Locale.ROOT);
        int filteredCount = 0;
        for (String plugin : detectedPlugins) {
            if (query.isEmpty() || plugin.toLowerCase(Locale.ROOT).contains(query)) filteredCount++;
        }
        String header = detectedPlugins.isEmpty() ? "No plugins detected" : "Detected: " + filteredCount + " plugin" + (filteredCount == 1 ? "" : "s");
        windowNode.content().add(new DirectUiLabel(header, UiTone.MUTED));

        float viewportHeight = Math.max(pluginViewportMinHeight(), panelH - getPluginResultsReservedHeight());
        pluginListSlot.setPreferredHeight(viewportHeight);
        windowNode.content().add(pluginListSlot);
    }

    private String currentServerAddress() {
        ServerData entry = MC.getCurrentServer();
        if (entry != null && entry.ip != null && !entry.ip.isBlank()) {
            return entry.ip.trim().toLowerCase(Locale.ROOT);
        }
        if (MC.getConnection() != null && MC.getConnection().getConnection() != null) {
            SocketAddress address = MC.getConnection().getConnection().getRemoteAddress();
            if (address instanceof InetSocketAddress inet) {
                String host = inet.getHostString();
                if ((host == null || host.isBlank()) && inet.getAddress() != null) {
                    host = inet.getAddress().getHostAddress();
                }
                if (host != null && !host.isBlank()) {
                    return (host + ":" + inet.getPort()).trim().toLowerCase(Locale.ROOT);
                }
            } else if (address != null) {
                String raw = address.toString();
                if (raw != null && !raw.isBlank()) {
                    return raw.replaceFirst("^/", "").trim().toLowerCase(Locale.ROOT);
                }
            }
        }
        return "";
    }

    private String normalizePluginContextSignature(String signature) {
        return signature == null ? "" : signature.trim().toLowerCase(Locale.ROOT);
    }

    private String currentPluginContextSignature() {
        if (MC.getConnection() == null) return "";

        String currentAddress = currentServerAddress();
        String brand = MC.getConnection().serverBrand();
        String normalizedBrand = brand == null ? "" : brand.trim().toLowerCase(Locale.ROOT);
        if (!pluginContextSignatureDirty
            && currentAddress.equals(cachedPluginContextServerAddress)
            && normalizedBrand.equals(cachedPluginContextBrand)) {
            return cachedPluginContextSignature;
        }

        List<String> parts = new ArrayList<>();
        if (brand != null && !brand.isBlank()) {
            parts.add("brand=" + normalizedBrand);
        }

        try {
            com.mojang.brigadier.CommandDispatcher<?> dispatcher = MC.getConnection().getCommands();
            if (dispatcher != null) {
                com.mojang.brigadier.tree.RootCommandNode<?> root = dispatcher.getRoot();
                if (root != null && !root.getChildren().isEmpty()) {
                    List<String> rootCommands = new ArrayList<>();
                    for (com.mojang.brigadier.tree.CommandNode<?> child : root.getChildren()) {
                        String name = child.getName();
                        if (name != null && !name.isBlank()) {
                            rootCommands.add(name.trim().toLowerCase(Locale.ROOT));
                        }
                    }
                    rootCommands.sort(String.CASE_INSENSITIVE_ORDER);
                    if (!rootCommands.isEmpty()) {
                        parts.add("cmd=" + String.join(",", rootCommands));
                    }
                }
            }
        } catch (Exception ignored) {
        }

        cachedPluginContextServerAddress = currentAddress;
        cachedPluginContextBrand = normalizedBrand;
        cachedPluginContextSignature = normalizePluginContextSignature(String.join("|", parts));
        pluginContextSignatureDirty = false;
        return cachedPluginContextSignature;
    }

    private void invalidatePluginContextSignature() {
        pluginContextSignatureDirty = true;
    }

    private void setPluginProbeDelayMs(int delayMs) {
        pluginProbeDelayMs = Math.max(MIN_PLUGIN_PROBE_DELAY_MS, Math.min(MAX_PLUGIN_PROBE_DELAY_MS, delayMs));
        AutismSharedState.get().setServerDataOverlayProbeDelayMs(pluginProbeDelayMs);
    }

    private void applyClampedBounds() {
        AutismWindowLayout clamped = clampToViewport(new AutismWindowLayout(panelX, panelY, panelW, panelH, visible, collapsed));
        panelX = clamped.x;
        panelY = clamped.y;
        panelW = clamped.width;
        panelH = clamped.height;
    }

    private void applyPluginSetupLayout() {
        panelW = getSharedPanelWidth();
        panelH = getPluginSetupPanelHeight();
        applyClampedBounds();
    }

    private void applyPluginScanningLayout() {
        panelW = getSharedPanelWidth();
        panelH = getPluginScanningPanelHeight();
        applyClampedBounds();
    }

    private void applyPluginResultsLayout() {
        panelW = getSharedPanelWidth();
        panelH = Math.max(getPluginResultsMinHeight(), Math.min(getSharedPanelHeight(), getPluginResultsMaxHeight()));
        applyClampedBounds();
    }

    private void applyInfoLayout() {
        panelW = getSharedPanelWidth();
        panelH = getSharedPanelHeight();
        applyClampedBounds();
    }

    private int getSharedPanelWidth() {
        DirectViewport viewport = surface.viewport();
        return Math.max(infoMinWidth(), Math.min(sharedPanelWidth(), Math.round(viewport.uiWidth())));
    }

    private int getPluginResultsMinHeight() {
        return getSharedPanelHeight();
    }

    private int getPluginResultsMaxHeight() {
        DirectViewport viewport = surface.viewport();
        int viewportHeight = Math.round(viewport.uiHeight());
        return Math.max(getPluginResultsMinHeight(), viewportHeight - 28);
    }

    private int getSharedPanelHeight() {
        DirectViewport viewport = surface.viewport();
        int measured = Math.max(infoMinHeight(), Math.max(infoPreferredHeight, measurePreferredInfoPanelHeight()));
        return Math.max(infoMinHeight(), Math.min(measured, Math.max(infoMinHeight(), Math.round(viewport.uiHeight()) - viewportHeightMargin())));
    }

    private int getPluginSetupPanelHeight() {
        DirectViewport viewport = surface.viewport();
        int measured = Math.max(pluginSetupHeight(), measurePreferredHeightForState(1, false, false, Math.max(panelW, pluginSetupWidth())));
        return Math.min(measured, Math.max(pluginSetupHeight(), Math.round(viewport.uiHeight()) - viewportHeightMargin()));
    }

    private int getPluginScanningPanelHeight() {
        DirectViewport viewport = surface.viewport();
        int measured = Math.max(pluginScanningHeight(), measurePreferredHeightForState(1, false, true, Math.max(panelW, pluginScanningWidth())));
        return Math.min(measured, Math.max(pluginScanningHeight(), Math.round(viewport.uiHeight()) - viewportHeightMargin()));
    }

    private int getPluginResultsReservedHeight() {
        int contentPadding = panelPadding() * 2;
        int contentGap = contentGap() * 3;
        int searchHeight = searchFieldHeight();
        int buttonRowHeight = actionButtonHeight();
        int headerLabelHeight = 13;
        return theme.headerHeight() + contentPadding + contentGap + searchHeight + buttonRowHeight + headerLabelHeight;
    }

    private int measurePreferredInfoPanelHeight() {
        return Math.max(infoMinHeight(), measurePreferredHeightForState(0, pluginScanDone, pluginScanInProgress, Math.max(infoMinWidth(), panelW)));
    }

    private int measurePreferredHeightForState(int tab, boolean scanDone, boolean scanInProgress, int measureWidth) {
        int previousTab = activeTab;
        boolean previousScanDone = pluginScanDone;
        boolean previousScanInProgress = pluginScanInProgress;
        activeTab = tab;
        pluginScanDone = scanDone;
        pluginScanInProgress = scanInProgress;
        rebuildUi();
        int measured = Math.round(surface.measurePreferredHeight(measureWidth));
        activeTab = previousTab;
        pluginScanDone = previousScanDone;
        pluginScanInProgress = previousScanInProgress;
        rebuildUi();
        return measured;
    }

    private int getInfoRequiredHeight() {
        int rowCount = 11;
        String displayedAddress = getDisplayedServerAddress();
        String host = extractLookupHost(displayedAddress);
        String realIp = getDisplayedRealIp(displayedAddress);
        if (!host.isBlank() && (!host.equals(realIp) || resolvingIp)) rowCount++;

        ServerData entry = MC.getCurrentServer();
        String software = getSoftwareGuess(entry);
        if (!"--".equals(software)) rowCount++;

        String versionNote = getVersionNote(entry);
        if (!"--".equals(versionNote)) rowCount++;

        int contentHeight = 5 + (rowCount * 13) + 11 + actionButtonHeight();
        return theme.headerHeight() + (panelPadding() * 2) + (contentGap() * 2) + contentHeight;
    }

    private void rememberCurrentTabSize() {
        if (activeTab == 0) {
            infoPreferredWidth = Math.max(infoMinWidth(), panelW);
            infoPreferredHeight = Math.max(getInfoRequiredHeight(), panelH);
            pluginPreferredWidth = Math.max(pluginPreferredWidth, infoPreferredWidth);
            return;
        }

        if (pluginScanDone && !pluginScanInProgress) {
            pluginPreferredWidth = Math.max(pluginResultsMinWidth(), panelW);
            pluginPreferredHeight = Math.max(getPluginResultsMinHeight(), Math.min(getPluginResultsMaxHeight(), panelH));
            infoPreferredWidth = Math.max(infoPreferredWidth, pluginPreferredWidth);
        }
    }

    private void clearLocalScanState(String address, String contextSignature) {
        pluginScanDone = false;
        pluginScanInProgress = false;
        pluginScanCompletionAnnounced = false;
        pluginScanStartedAt = 0L;
        pluginScanLastResponseAt = 0L;
        pendingPluginProbeIds.clear();
        pluginProbes.clear();
        observedPluginCommands.clear();
        queuedPluginProbes.clear();
        pluginEvidence.clear();
        scanWorkingEntries.clear();
        nextPluginProbeSendAt = 0L;
        pluginScanTotalSteps = 0;
        scannedServerAddress = address;
        scannedPluginContextSignature = normalizePluginContextSignature(contextSignature);
        detectedPlugins.clear();
        pluginCommands.clear();
        invalidatePluginRows();
        pluginScrollState.jumpTo(0, 0);
        selectedPlugin = null;
        invalidatePluginContextSignature();
    }

    private boolean loadCachedScan(String address, String contextSignature) {
        AutismSharedState.ServerPluginScan cached = AutismSharedState.get().getServerPluginScan(address, contextSignature);
        if (cached == null) return false;

        detectedPlugins.clear();
        detectedPlugins.addAll(cached.getPlugins());
        pluginCommands.clear();
        pluginCommands.putAll(cached.getPluginCommands());
        pluginScrollState.jumpTo(0, 0);
        selectedPlugin = null;
        pluginScanDone = true;
        pluginScanInProgress = false;
        pluginScanStartedAt = 0L;
        pluginScanLastResponseAt = 0L;
        pendingPluginProbeIds.clear();
        pluginProbes.clear();
        observedPluginCommands.clear();
        queuedPluginProbes.clear();
        pluginEvidence.clear();
        scanWorkingEntries.clear();
        nextPluginProbeSendAt = 0L;
        pluginScanTotalSteps = 0;
        scannedServerAddress = cached.getAddress();
        scannedPluginContextSignature = normalizePluginContextSignature(cached.getContextSignature());
        Map<String, String> cachedEvidence = cached.getPluginEvidence();
        for (String plugin : detectedPlugins) {
            String key = normalizePluginKey(plugin);
            pluginEvidence.put(key, parseEvidenceName(cachedEvidence.get(key)));
        }
        invalidatePluginRows();
        return true;
    }

    private void cacheCurrentScan() {
        if (scannedServerAddress == null || scannedServerAddress.isBlank()) return;
        if (!pluginScanDone || pluginScanInProgress) return;
        Map<String, String> evidenceSnapshot = new LinkedHashMap<>();
        for (Map.Entry<String, PluginEvidence> entry : pluginEvidence.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() == null) continue;
            evidenceSnapshot.put(entry.getKey(), entry.getValue().name());
        }
        AutismSharedState.get().setServerPluginScan(scannedServerAddress, scannedPluginContextSignature, detectedPlugins, pluginCommands, evidenceSnapshot);
    }

    private void syncScanStateForCurrentServer() {
        String currentAddress = currentServerAddress();
        if (currentAddress.isEmpty()) return;

        String currentContextSignature = currentPluginContextSignature();
        boolean sameAddress = currentAddress.equals(scannedServerAddress);
        boolean hasCurrentSignature = !currentContextSignature.isEmpty();
        boolean sameContext = sameAddress && currentContextSignature.equals(scannedPluginContextSignature);

        if (sameContext && (pluginScanDone || pluginScanInProgress)) return;
        if (sameAddress && !hasCurrentSignature && (pluginScanDone || pluginScanInProgress)) return;

        if (!loadCachedScan(currentAddress, currentContextSignature)) {
            clearLocalScanState(currentAddress, currentContextSignature);
        }
    }

    private String normalizePluginKey(String plugin) {
        return plugin == null ? "" : plugin.trim().toLowerCase(Locale.ROOT);
    }

    private int evidenceRank(PluginEvidence evidence) {
        return switch (evidence) {
            case COMMAND_TREE -> 0;
            case NAMESPACE -> 1;
            case ROOT_HINT -> 2;
            case PLUGIN_LIST -> 3;
            case VERSION_HINT -> 4;
            case UNKNOWN -> 5;
            case HELP_HINT -> 6;
        };
    }

    private PluginEvidence mergeEvidence(PluginEvidence current, PluginEvidence candidate) {
        if (current == null) return candidate == null ? PluginEvidence.UNKNOWN : candidate;
        if (candidate == null) return current;
        return evidenceRank(candidate) < evidenceRank(current) ? candidate : current;
    }

    private PluginEvidence parseEvidenceName(String value) {
        if (value == null || value.isBlank()) return PluginEvidence.UNKNOWN;
        try {
            return PluginEvidence.valueOf(value);
        } catch (IllegalArgumentException ignored) {
            return PluginEvidence.UNKNOWN;
        }
    }

    private String getEvidenceTitle(PluginEvidence evidence) {
        return switch (evidence) {
            case COMMAND_TREE -> "Command Tree";
            case NAMESPACE -> "Namespace Probe";
            case ROOT_HINT -> "Command Root Hint";
            case HELP_HINT -> "Help Hint";
            case PLUGIN_LIST -> "Plugin Command Hint";
            case VERSION_HINT -> "Version Command Hint";
            case UNKNOWN -> "Other";
        };
    }

    private int getEvidenceColor(PluginEvidence evidence, boolean hovered) {
        int base = switch (evidence) {
            case COMMAND_TREE -> AutismColors.packetGreen();
            case NAMESPACE -> AutismColors.packetCyan();
            case ROOT_HINT -> AutismColors.packetBlue();
            case HELP_HINT -> AutismColors.packetLightYellow();
            case PLUGIN_LIST -> AutismColors.packetYellow();
            case VERSION_HINT -> AutismColors.packetOrange();
            case UNKNOWN -> AutismColors.packetWhite();
        };
        return hovered ? AutismColors.packetWhite() : base;
    }

    private Map<String, PluginScanEntry> buildCurrentPluginEntries() {
        Map<String, PluginScanEntry> entries = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (String plugin : detectedPlugins) {
            if (plugin == null || plugin.isBlank()) continue;
            List<String> existing = pluginCommands.getOrDefault(plugin, List.of());
            PluginEvidence evidence = pluginEvidence.getOrDefault(normalizePluginKey(plugin), existing != null && !existing.isEmpty() ? PluginEvidence.ROOT_HINT : PluginEvidence.UNKNOWN);
            mergePluginEntry(entries, plugin, existing, existing != null && !existing.isEmpty(), evidence);
        }
        return entries;
    }

    private void mergePluginEntry(Map<String, PluginScanEntry> entries, String pluginName, Collection<String> commands, boolean commandBacked, PluginEvidence evidence) {
        if (pluginName == null || pluginName.isBlank()) return;

        String cleanName = pluginName.trim();
        String key = normalizePluginKey(cleanName);
        if (key.isEmpty() || VANILLA_NAMESPACES.contains(key)) return;

        PluginScanEntry entry = entries.computeIfAbsent(key, unused -> new PluginScanEntry());
        if (entry.displayName == null || entry.displayName.isBlank() || (commandBacked && !entry.commandBacked)) {
            entry.displayName = cleanName;
        }
        if (commandBacked) entry.commandBacked = true;
        entry.evidence = mergeEvidence(entry.evidence, evidence);
        pluginEvidence.put(key, mergeEvidence(pluginEvidence.get(key), evidence));

        if (commands != null) {
            for (String command : commands) {
                if (command == null) continue;
                String cleanCommand = command.trim();
                if (!cleanCommand.isEmpty()) entry.commands.add(cleanCommand);
            }
        }
    }

    private void applyPluginEntries(Map<String, PluginScanEntry> entries, boolean resetSelection) {
        detectedPlugins.clear();
        pluginCommands.clear();

        List<PluginScanEntry> sortedEntries = new ArrayList<>(entries.values());
        sortedEntries.removeIf(entry -> entry == null || entry.displayName == null || entry.displayName.isBlank());
        sortedEntries.sort((a, b) -> String.CASE_INSENSITIVE_ORDER.compare(a.displayName, b.displayName));

        for (PluginScanEntry entry : sortedEntries) {
            String plugin = entry.displayName;
            List<String> commands = new ArrayList<>(entry.commands);
            commands.removeIf(cmd -> cmd == null || cmd.isBlank());
            commands.sort(String.CASE_INSENSITIVE_ORDER);

            detectedPlugins.add(plugin);
            pluginCommands.put(plugin, commands);
        }

        if (resetSelection) {
            pluginScrollState.jumpTo(0, 0);
            selectedPlugin = null;
        } else if (selectedPlugin != null && !detectedPlugins.contains(selectedPlugin)) {
            selectedPlugin = null;
        }
        invalidatePluginRows();
    }

    private boolean isKnownPluginNamespace(String key) {
        for (String namespace : COMMON_PLUGIN_NAMESPACES) {
            if (namespace.equalsIgnoreCase(key)) return true;
        }
        return false;
    }

    private String normalizeCommandToken(String raw) {
        if (raw == null) return "";
        String token = raw.trim();
        if (token.isEmpty()) return "";
        while (token.startsWith("/")) token = token.substring(1).trim();
        int spaceIndex = token.indexOf(' ');
        if (spaceIndex >= 0) token = token.substring(0, spaceIndex).trim();
        while (token.endsWith(":")) token = token.substring(0, token.length() - 1).trim();
        return token.toLowerCase(Locale.ROOT);
    }

    private Set<String> getOnlinePlayerNames() {
        if (MC.getConnection() == null) return Set.of();
        Set<String> names = new HashSet<>();
        for (var player : MC.getConnection().getListedOnlinePlayers()) {
            if (player == null || player.getProfile() == null) continue;
            String name = player.getProfile().name();
            if (name != null && !name.isBlank()) {
                names.add(name.trim().toLowerCase(Locale.ROOT));
            }
        }
        return names;
    }

    private boolean isLikelyPluginNameCandidate(String candidate, PluginProbeKind kind) {
        if (candidate == null) return false;
        String clean = candidate.trim();
        String key = normalizePluginKey(clean);
        if (key.isEmpty()) return false;
        if (key.length() < 2) return false;
        if (clean.contains(" ") || clean.contains("/") || clean.contains(":")) return false;
        if (VANILLA_NAMESPACES.contains(key)) return false;
        if (getOnlinePlayerNames().contains(key)) return false;

        if (kind == PluginProbeKind.PLUGIN_LIST || kind == PluginProbeKind.VERSION) {
            if (key.equals("plugins") || key.equals("plugin") || key.equals("version") || key.equals("about")) return false;
        }

        return key.chars().allMatch(ch -> Character.isLetterOrDigit(ch) || ch == '_' || ch == '-' || ch == '.');
    }

    private void addObservedPluginCommand(String raw) {
        String token = normalizeCommandToken(raw);
        if (token.isEmpty()) return;
        if (!token.chars().allMatch(ch -> Character.isLetterOrDigit(ch) || ch == '_' || ch == '-' || ch == '.')) return;
        if (VANILLA_NAMESPACES.contains(token)) return;
        observedPluginCommands.add(token);
    }

    private void inferPluginsFromObservedCommands(Map<String, PluginScanEntry> entries) {
        if (observedPluginCommands.isEmpty()) return;

        Map<String, Set<String>> inferredMatches = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (String command : observedPluginCommands) {
            String plugin = null;
            if (isKnownPluginNamespace(command)) {
                plugin = command;
            } else if (ROOT_COMMAND_PLUGIN_ALIASES.containsKey(command)) {
                plugin = ROOT_COMMAND_PLUGIN_ALIASES.get(command);
            }

            if (plugin != null && !plugin.isBlank()) {
                inferredMatches.computeIfAbsent(plugin, unused -> new TreeSet<>(String.CASE_INSENSITIVE_ORDER)).add(command);
            }
        }

        for (Map.Entry<String, Set<String>> inferred : inferredMatches.entrySet()) {
            mergePluginEntry(entries, inferred.getKey(), inferred.getValue(), true, PluginEvidence.ROOT_HINT);
        }
    }

    private int addProbeVariants(Map<Integer, PluginProbeSpec> probes, int nextId, PluginProbeKind kind, String... baseCommands) {
        for (String base : baseCommands) {
            if (base == null || base.isBlank()) continue;
            String trimmed = base.trim();
            probes.put(nextId++, new PluginProbeSpec(trimmed, kind, null));
            probes.put(nextId++, new PluginProbeSpec(trimmed + " ", kind, null));
        }
        return nextId;
    }

    private Map<Integer, PluginProbeSpec> buildPluginProbes() {
        Map<Integer, PluginProbeSpec> probes = new LinkedHashMap<>();
        int nextId = COMPLETION_ID;

        probes.put(nextId++, new PluginProbeSpec("/", PluginProbeKind.ROOT, null));
        probes.put(nextId++, new PluginProbeSpec("/ ", PluginProbeKind.ROOT, null));

        nextId = addProbeVariants(probes, nextId, PluginProbeKind.PLUGIN_LIST, "/plugins", "/pl", "/bukkit:plugins", "/bukkit:pl");
        nextId = addProbeVariants(probes, nextId, PluginProbeKind.VERSION, "/ver", "/version", "/about", "/icanhasbukkit", "/bukkit:ver", "/bukkit:version");
        nextId = addProbeVariants(probes, nextId, PluginProbeKind.HELP, "/help", "/?", "/bukkit:help", "/minecraft:help");

        for (int i = 0; i < ROOT_PROBE_PREFIXES.length(); i++) {
            char prefix = ROOT_PROBE_PREFIXES.charAt(i);
            String rootProbe = "/" + prefix;
            probes.put(nextId++, new PluginProbeSpec(rootProbe, PluginProbeKind.ROOT, String.valueOf(prefix)));
            probes.put(nextId++, new PluginProbeSpec("/help " + prefix, PluginProbeKind.HELP, String.valueOf(prefix)));
            probes.put(nextId++, new PluginProbeSpec("/? " + prefix, PluginProbeKind.HELP, String.valueOf(prefix)));
        }

        for (String namespace : COMMON_PLUGIN_NAMESPACES) {
            probes.put(nextId++, new PluginProbeSpec("/" + namespace + ":", PluginProbeKind.NAMESPACE, namespace));
        }

        for (String plugin : HIGH_VALUE_PLUGIN_HINTS) {
            probes.put(nextId++, new PluginProbeSpec("/version " + plugin, PluginProbeKind.VERSION, plugin));
            probes.put(nextId++, new PluginProbeSpec("/ver " + plugin, PluginProbeKind.VERSION, plugin));
            probes.put(nextId++, new PluginProbeSpec("/help " + plugin, PluginProbeKind.HELP, plugin));
        }

        return probes;
    }

    private void finalizePluginScan() {
        if (!pluginScanInProgress && pluginScanDone) return;

        applyPluginEntries(scanWorkingEntries, true);
        pluginScanInProgress = false;
        pluginScanDone = true;
        pendingPluginProbeIds.clear();
        pluginProbes.clear();
        observedPluginCommands.clear();
        queuedPluginProbes.clear();
        nextPluginProbeSendAt = 0L;
        scanWorkingEntries.clear();
        pluginScanStartedAt = 0L;
        pluginScanLastResponseAt = 0L;
        pluginScanTotalSteps = 0;
        scanWorkingEntries.clear();
        applyPluginResultsLayout();
        cacheCurrentScan();
        announcePluginScanComplete();
    }

    private void dispatchQueuedPluginProbes() {
        if (!pluginScanInProgress || MC.getConnection() == null) return;

        long now = System.currentTimeMillis();
        if (now < nextPluginProbeSendAt) return;

        PluginProbeRequest request = queuedPluginProbes.pollFirst();
        if (request == null) return;

        try {
            net.minecraft.network.protocol.game.ServerboundCommandSuggestionPacket packet =
                new net.minecraft.network.protocol.game.ServerboundCommandSuggestionPacket(request.id, request.spec.query);
            MC.getConnection().send(packet);
            nextPluginProbeSendAt = now + pluginProbeDelayMs;
        } catch (Exception ignored) {
            pendingPluginProbeIds.remove(request.id);
            pluginProbes.remove(request.id);
        }
    }

    private int getTotalPluginProbeCount() {
        return Math.max(0, pluginScanTotalSteps - 1);
    }

    private long getPluginScanSendWindowMs() {
        return Math.max(pluginProbeDelayMs, (long) getTotalPluginProbeCount() * pluginProbeDelayMs);
    }

    private long getPluginScanFinishedSendingAt() {
        if (pluginScanStartedAt <= 0L) return 0L;
        if (!queuedPluginProbes.isEmpty()) return 0L;
        long candidate = nextPluginProbeSendAt - pluginProbeDelayMs;
        return Math.max(pluginScanStartedAt, candidate);
    }

    private long getPluginScanHardTimeoutMs() {
        return Math.max(PLUGIN_SCAN_TIMEOUT_MS, getPluginScanSendWindowMs() + PLUGIN_SCAN_SETTLE_MS + 600L);
    }

    private int lerpColor(int from, int to, float t) {
        float clamped = Math.max(0.0f, Math.min(1.0f, t));
        int a1 = (from >>> 24) & 0xFF;
        int r1 = (from >>> 16) & 0xFF;
        int g1 = (from >>> 8) & 0xFF;
        int b1 = from & 0xFF;
        int a2 = (to >>> 24) & 0xFF;
        int r2 = (to >>> 16) & 0xFF;
        int g2 = (to >>> 8) & 0xFF;
        int b2 = to & 0xFF;
        int a = Math.round(a1 + (a2 - a1) * clamped);
        int r = Math.round(r1 + (r2 - r1) * clamped);
        int g = Math.round(g1 + (g2 - g1) * clamped);
        int b = Math.round(b1 + (b2 - b1) * clamped);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private int getSentPluginProbeCount() {
        int total = getTotalPluginProbeCount();
        return Math.max(0, Math.min(total, total - queuedPluginProbes.size()));
    }

    private int getAnsweredPluginProbeCount() {
        int total = getTotalPluginProbeCount();
        return Math.max(0, Math.min(total, total - pendingPluginProbeIds.size()));
    }

    private float getPluginScanProgress() {
        if (pluginScanDone) return 1.0f;
        int total = getTotalPluginProbeCount();
        if (total <= 0) return 0.0f;

        float sentRatio = getSentPluginProbeCount() / (float) total;
        float answeredRatio = getAnsweredPluginProbeCount() / (float) total;
        if (!queuedPluginProbes.isEmpty()) {
            float progress = 0.80f * sentRatio;
            progress += 0.05f * answeredRatio;
            return Math.max(0.0f, Math.min(0.85f, progress));
        }

        float progress = 0.85f;
        long finishedSendingAt = getPluginScanFinishedSendingAt();
        long waitWindow = pendingPluginProbeIds.isEmpty() ? PLUGIN_SCAN_IDLE_MS : PLUGIN_SCAN_SETTLE_MS;
        if (finishedSendingAt > 0L && waitWindow > 0L) {
            long now = System.currentTimeMillis();
            float settle = Math.max(0.0f, Math.min(1.0f, (now - finishedSendingAt) / (float) waitWindow));
            progress += 0.14f * settle;
        }

        return Math.max(0.0f, Math.min(0.99f, progress));
    }

    private int getWorkingPluginCount() {
        return (int) scanWorkingEntries.values().stream()
            .filter(entry -> entry != null && entry.displayName != null && !entry.displayName.isBlank())
            .count();
    }

    private int getDisplayedPluginCount() {
        if (pluginScanInProgress) {
            return getWorkingPluginCount();
        }
        return detectedPlugins.size();
    }

    private String getPluginScanStatusLabel() {
        int foundCount = getDisplayedPluginCount();
        if (foundCount > 0) {
            return "Scanning plugins | found " + foundCount;
        }
        return "Scanning plugins";
    }

    private String getPluginScanPhaseLabel() {
        return queuedPluginProbes.isEmpty() ? "Analyzing replies" : "Sending probes";
    }

    private int getPluginScanPhaseColor() {
        return queuedPluginProbes.isEmpty() ? AutismColors.packetCyan() : AutismColors.packetYellow();
    }

    private String getPluginScanPhaseDetail() {
        int total = Math.max(1, getTotalPluginProbeCount());
        return queuedPluginProbes.isEmpty()
            ? getAnsweredPluginProbeCount() + "/" + total
            : getSentPluginProbeCount() + "/" + total;
    }

    private void announcePluginScanComplete() {
        if (pluginScanCompletionAnnounced) return;
        pluginScanCompletionAnnounced = true;

        int count = detectedPlugins.size();
        if (count <= 0) {
            AutismClientMessaging.sendPrefixed("Plugin probing finished: no plugins found.");
        } else if (count == 1) {
            AutismClientMessaging.sendPrefixed("Plugin probing finished: found 1 plugin.");
        } else {
            AutismClientMessaging.sendPrefixed("Plugin probing finished: found " + count + " plugins.");
        }
    }

    public boolean shouldRenderBackgroundProbeBanner() {
        return pluginScanInProgress;
    }

    public void renderBackgroundProbeBanner(GuiGraphicsExtractor ctx) {
        if (!shouldRenderBackgroundProbeBanner() || MC == null || MC.getWindow() == null || textRenderer == null) return;

        DirectViewport viewport = surface.viewport();
        String status = getPluginScanStatusLabel();
        String phase = getPluginScanPhaseLabel();
        String detail = getPluginScanPhaseDetail();
        int progressPercent = Math.max(0, Math.min(100, Math.round(getPluginScanProgress() * 100.0f)));
        String percentComponent = progressPercent + "%";
        int screenW = Math.round(viewport.uiWidth());
        int statusWidth = UiText.width(textRenderer, status, theme.fontFor(UiTone.MUTED), theme.color(UiTone.MUTED));
        int phaseWidth = UiText.width(textRenderer, phase, theme.fontFor(UiTone.BODY), theme.color(UiTone.BODY));
        int detailWidth = UiText.width(textRenderer, detail, theme.fontFor(UiTone.MUTED), getPluginScanPhaseColor());
        int percentWidth = UiText.width(textRenderer, percentComponent, theme.fontFor(UiTone.BODY), theme.color(UiTone.BODY));
        int contentW = Math.max(180, Math.max(statusWidth, Math.max(phaseWidth + 12 + detailWidth, percentWidth + 150)));

        int boxW = Math.max(1, Math.min(Math.max(1, screenW - 16), Math.max(236, contentW + 24)));
        int boxH = 42;
        int boxX = Math.max(8, (screenW - boxW) / 2);
        int boxY = 8;
        int innerX = boxX + 8;
        int barY = boxY + 17;
        int barW = boxW - 16;
        int barH = 10;

        viewport.push(ctx);
        try {
            DirectRenderContext bannerContext = new DirectRenderContext(ctx, textRenderer, viewport, theme, 0, 0, 0.0f);
            int border = theme.borderColor();
            int headerAccent = theme.headerAccent();
            UiRenderer.window(
                ctx,
                UiBounds.of(boxX, boxY, boxW, boxH),
                14,
                bannerContext.applyAlpha(theme.windowFill()),
                bannerContext.applyAlpha(theme.headerFill()),
                bannerContext.applyAlpha(theme.windowFill()),
                bannerContext.applyAlpha(border),
                bannerContext.applyAlpha(headerAccent),
                1.0f
            );

            UiText.draw(ctx, textRenderer, status, theme.fontFor(UiTone.MUTED), theme.color(UiTone.MUTED), innerX, boxY + 3, false);
            UiText.draw(ctx, textRenderer, percentComponent, theme.fontFor(UiTone.BODY), getPluginScanPhaseColor(), boxX + boxW - 8 - percentWidth, boxY + 3, false);

            UiRenderer.frame(
                ctx,
                UiBounds.of(innerX, barY, barW, barH),
                bannerContext.applyAlpha(theme.overlaySurfaceSoft(0x000A090C)),
                bannerContext.applyAlpha(0xFF8F3131)
            );

            int fillW = Math.max(0, Math.min(barW - 2, Math.round((barW - 2) * getPluginScanProgress())));
            if (fillW > 0) {
                int fillColor = UiSizing.lerpColor(0xFFFF5A5A, 0xFF66E08A, getPluginScanProgress());
                UiRenderer.rect(ctx, UiBounds.of(innerX + 1, barY + 1, fillW, barH - 2), bannerContext.applyAlpha(fillColor));
            }

            UiText.draw(ctx, textRenderer, phase, theme.fontFor(UiTone.BODY), theme.color(UiTone.BODY), innerX, barY + 13, false);
            UiText.draw(ctx, textRenderer, detail, theme.fontFor(UiTone.MUTED), getPluginScanPhaseColor(), boxX + boxW - 8 - detailWidth, barY + 13, false);
        } finally {
            viewport.pop(ctx);
        }
    }

    private void updatePluginScanLifecycle() {
        if (!pluginScanInProgress) return;

        dispatchQueuedPluginProbes();

        long now = System.currentTimeMillis();
        boolean allProbesSent = queuedPluginProbes.isEmpty();
        boolean allResponsesReceived = pendingPluginProbeIds.isEmpty();
        boolean idle = pluginScanLastResponseAt > 0L && now - pluginScanLastResponseAt >= PLUGIN_SCAN_IDLE_MS;
        long finishedSendingAt = getPluginScanFinishedSendingAt();
        boolean settledAfterSend = allProbesSent
            && finishedSendingAt > 0L
            && now - finishedSendingAt >= (allResponsesReceived ? PLUGIN_SCAN_IDLE_MS : PLUGIN_SCAN_SETTLE_MS);
        boolean timedOut = pluginScanStartedAt > 0L && now - pluginScanStartedAt >= getPluginScanHardTimeoutMs();

        if ((allProbesSent && allResponsesReceived) || (allProbesSent && settledAfterSend) || timedOut) {
            finalizePluginScan();
        }
    }

    public void tickBackground() {
        syncScanStateForCurrentServer();
        if (pluginScanInProgress) {
            updatePluginScanLifecycle();
        }
    }

    private void parseCommandTree(com.mojang.brigadier.tree.RootCommandNode<?> root) {
        Map<String, PluginScanEntry> entries = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        entries.putAll(scanWorkingEntries);
        for (var child : root.getChildren()) {
            String name = child.getName();
            if (name.contains(":")) {
                String[] parts = name.split(":", 2);
                String ns = parts[0].toLowerCase();
                String cmd = parts[1];
                if (!VANILLA_NAMESPACES.contains(ns)) {
                    mergePluginEntry(entries, ns, List.of(cmd), true, PluginEvidence.COMMAND_TREE);
                    addObservedPluginCommand(cmd);
                }
            } else {
                addObservedPluginCommand(name);
            }
        }
        inferPluginsFromObservedCommands(entries);
        scanWorkingEntries.clear();
        scanWorkingEntries.putAll(entries);
    }

    public void resetScan() {
        clearLocalScanState(null, "");
        searchField.setText("");
        searchField.setFocused(false);
        resolvedIp = null;
        lastResolvedAddress = null;
        invalidatePluginContextSignature();
    }

    private void scanPlugins() {
        syncScanStateForCurrentServer();

        if (pluginScanDone || pluginScanInProgress) return;
        if (MC.getConnection() == null) {
            pluginScanDone = true;
            cacheCurrentScan();
            return;
        }

        scannedServerAddress = currentServerAddress();
        scannedPluginContextSignature = currentPluginContextSignature();
        activeTab = 1;
        pluginScanInProgress = true;
        pluginScanDone = false;
        pluginScanStartedAt = System.currentTimeMillis();
        pluginScanLastResponseAt = pluginScanStartedAt;
        pendingPluginProbeIds.clear();
        pluginProbes.clear();
        observedPluginCommands.clear();
        queuedPluginProbes.clear();
        pluginEvidence.clear();
        scanWorkingEntries.clear();
        nextPluginProbeSendAt = pluginScanStartedAt + pluginProbeDelayMs;
        applyPluginScanningLayout();
        try {
            com.mojang.brigadier.CommandDispatcher<?> dispatcher =
                MC.getConnection().getCommands();
            if (dispatcher != null) {
                com.mojang.brigadier.tree.RootCommandNode<?> root = dispatcher.getRoot();
                if (root != null && !root.getChildren().isEmpty()) {
                    parseCommandTree(root);
                }
            }

            Map<Integer, PluginProbeSpec> probes = buildPluginProbes();
            pluginScanTotalSteps = probes.size() + 1;
            for (Map.Entry<Integer, PluginProbeSpec> probe : probes.entrySet()) {
                pendingPluginProbeIds.add(probe.getKey());
                pluginProbes.put(probe.getKey(), probe.getValue());
                queuedPluginProbes.addLast(new PluginProbeRequest(probe.getKey(), probe.getValue()));
            }
            updatePluginScanLifecycle();
        } catch (Exception ignored) {
            finalizePluginScan();
        }
    }

    public void onCommandSuggestions(int id, net.minecraft.network.protocol.game.ClientboundCommandSuggestionsPacket packet) {
        PluginProbeSpec probe = pluginProbes.get(id);
        if (probe == null && !pendingPluginProbeIds.contains(id)) return;

        MC.execute(() -> {
            if (scannedServerAddress == null || scannedServerAddress.isBlank()) return;
            String currentAddress = currentServerAddress();
            String currentContextSignature = currentPluginContextSignature();
            if (!currentAddress.equals(scannedServerAddress)) return;
            if (!currentContextSignature.isEmpty() && !currentContextSignature.equals(scannedPluginContextSignature)) {
                syncScanStateForCurrentServer();
                return;
            }

            var list = packet.suggestions();
            Map<String, PluginScanEntry> entries = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            entries.putAll(scanWorkingEntries);
            pendingPluginProbeIds.remove(id);
            pluginScanLastResponseAt = System.currentTimeMillis();

            for (var s : list) {
                String text = s.text();
                String normalizedToken = normalizeCommandToken(text);
                if (!normalizedToken.isEmpty()) addObservedPluginCommand(normalizedToken);

                if (text.contains(":")) {
                    String[] parts = text.split(":", 2);
                    String ns = parts[0].toLowerCase();
                    String cmd = parts[1];

                    if (!VANILLA_NAMESPACES.contains(ns)) {
                        mergePluginEntry(entries, ns, List.of(cmd), true, PluginEvidence.COMMAND_TREE);
                        addObservedPluginCommand(cmd);
                    }
                    continue;
                }

                if (probe != null) {
                    if (probe.kind == PluginProbeKind.NAMESPACE && probe.hint != null && !normalizedToken.isEmpty()) {
                        mergePluginEntry(entries, probe.hint, List.of(normalizedToken), true, PluginEvidence.NAMESPACE);
                    } else if ((probe.kind == PluginProbeKind.PLUGIN_LIST || probe.kind == PluginProbeKind.VERSION)
                        && text != null) {
                        String pluginCandidate = text.trim();
                        String pluginKey = normalizePluginKey(pluginCandidate);
                        if (isLikelyPluginNameCandidate(pluginCandidate, probe.kind)
                            && !ROOT_COMMAND_PLUGIN_ALIASES.containsKey(pluginKey)) {
                            mergePluginEntry(entries, pluginCandidate, List.of(), false,
                                probe.kind == PluginProbeKind.PLUGIN_LIST ? PluginEvidence.PLUGIN_LIST : PluginEvidence.VERSION_HINT);
                        }
                    } else if (probe.kind == PluginProbeKind.HELP && probe.hint != null && !normalizedToken.isEmpty()) {
                        mergePluginEntry(entries, probe.hint, List.of(normalizedToken), true, PluginEvidence.HELP_HINT);
                    }
                }
            }

            inferPluginsFromObservedCommands(entries);
            scanWorkingEntries.clear();
            scanWorkingEntries.putAll(entries);
            updatePluginScanLifecycle();
        });
    }

    public void onCommandTreeChanged() {
        invalidatePluginContextSignature();
        MC.execute(() -> {
            String currentAddress = currentServerAddress();
            if (currentAddress.isEmpty()) return;

            String currentContextSignature = currentPluginContextSignature();
            if (currentAddress.equals(scannedServerAddress) && currentContextSignature.equals(scannedPluginContextSignature)) {
                return;
            }

            syncScanStateForCurrentServer();
        });
    }

    public void openInfoTab() {
        setVisible(true);
        selectTab(0);
    }

    public void openPluginsTab() {
        setVisible(true);
        selectTab(1);
    }

    public void toggle() { setVisible(!visible); }

    @Override
    public void setVisible(boolean v) {
        this.visible = v;
        if (v) {
            windowNode.syncShowBody(!collapsed);
            AutismOverlayManager.get().bringToFront(this);
            syncScanStateForCurrentServer();
            if (activeTab == 0) {
                applyInfoLayout();
            } else {
                if (pluginScanInProgress) applyPluginScanningLayout();
                else if (!pluginScanDone) applyPluginSetupLayout();
                else applyPluginResultsLayout();
            }
        }
        saveState();
    }

    @Override public boolean isVisible() { return visible; }
    @Override public boolean isCollapsed() { return collapsed; }
    @Override public void setCollapsed(boolean c) {
        if (collapsed == c) return;
        this.collapsed = c;
        windowNode.syncShowBody(!collapsed);
        if (c) clearHiddenInteractionState();
        saveState();
    }
    @Override public String getOverlayId() { return "autism-serverinfo"; }

    @Override
    public boolean isMouseOver(double mx, double my) {
        if (!visible) return false;
        DirectViewport viewport = surface.viewport();
        float uiX = viewport.toUiX(mx);
        float uiY = viewport.toUiY(my);
        return uiX >= panelX && uiX <= panelX + panelW && uiY >= panelY && uiY <= panelY + panelH;
    }

    @Override
    public boolean isOverDragBar(double mx, double my) {
        if (!visible) return false;
        DirectViewport viewport = surface.viewport();
        return isOverHeaderUi(viewport.toUiX(mx), viewport.toUiY(my));
    }

    @Override
    public boolean hasTextFieldFocused() {
        return surface.hasFocusedTextInput();
    }

    @Override
    public AutismWindowLayout getBounds() {
        return new AutismWindowLayout(panelX, panelY, panelW, panelH, visible, collapsed);
    }

    @Override
    public void setBounds(AutismWindowLayout b) {
        if (b == null) return;
        AutismWindowLayout clamped = clampToViewport(b);
        panelX = clamped.x; panelY = clamped.y; panelW = clamped.width; panelH = clamped.height;
        visible = clamped.visible; collapsed = clamped.collapsed;
        windowNode.syncShowBody(!collapsed);
        rememberCurrentTabSize();
    }

    @Override
    public int getMinWidth() {
        return getSharedPanelWidth();
    }

    @Override
    public int getMinHeight() {
        if (activeTab == 1) {
            if (pluginScanInProgress) return getPluginScanningPanelHeight();
            if (!pluginScanDone) return getPluginSetupPanelHeight();
            return getPluginResultsMinHeight();
        }
        return measurePreferredInfoPanelHeight();
    }

    @Override
    public void render(GuiGraphicsExtractor ctx, int mx, int my, float delta) {
        if (!visible) return;

        syncScanStateForCurrentServer();
        updatePluginScanLifecycle();
        long nowMs = System.currentTimeMillis();
        if (clickRegions.isEmpty() || pluginScanInProgress && nowMs - lastUiRebuildMs >= 100L) {
            clickRegions.clear();
            rebuildUi();
            lastUiRebuildMs = nowMs;
        }

        DirectViewport viewport = surface.viewport();
        float uiMouseX = viewport.toUiX(mx);
        float uiMouseY = viewport.toUiY(my);
        boolean active = AutismOverlayManager.get().isFocusedOverlay(this) || AutismOverlayManager.get().isTopOverlay(this);
        boolean headerHovered = isOverHeaderUi(uiMouseX, uiMouseY);
        DirectRenderContext metrics = new DirectRenderContext(ctx, textRenderer, viewport, theme, uiMouseX, uiMouseY, delta);

        windowNode.setShowBody(!collapsed);
        windowNode.setActive(active);
        windowNode.setHeaderHovered(headerHovered);

        int preferredHeight = Math.round(windowNode.preferredHeight(metrics, panelW));
        if (collapsed) {
            panelH = preferredHeight;
        } else {
            if (activeTab == 0) {
                panelH = getSharedPanelHeight();
            } else if (pluginScanInProgress) {
                panelH = getPluginScanningPanelHeight();
            } else if (!pluginScanDone) {
                panelH = getPluginSetupPanelHeight();
            } else {
                panelH = Math.max(getPluginResultsMinHeight(), Math.min(getPluginResultsMaxHeight(), getSharedPanelHeight()));
            }
        }
        AutismWindowLayout clamped = clampToViewport(new AutismWindowLayout(panelX, panelY, panelW, panelH, visible, collapsed));
        panelX = clamped.x;
        panelY = clamped.y;
        panelW = clamped.width;
        panelH = clamped.height;

        windowNode.setBounds(panelX, panelY, panelW, panelH);
        surface.render(ctx, mx, my, delta);

        if (!collapsed && activeTab == 1 && pluginScanDone && !pluginScanInProgress) {
            clickRegions.clear();
            renderPluginResultsViewport(ctx, viewport, uiMouseX, uiMouseY, delta);
        }
    }

    private String getReportedVersion(ServerData entry) {
        return entry != null && entry.version != null ? entry.version.getString() : "--";
    }

    private String getRealServerVersion() {
        String version = AutismSharedState.get().getRealServerVersion(getDisplayedServerAddress());
        if (version != null && !version.isBlank()) return version;
        String brand = getLiveBrand();
        if (brand != null && !brand.isBlank() && !"--".equals(brand)) {
            String extracted = extractVersionFromBrand(brand);
            if (extracted != null) return extracted;
        }
        return "--";
    }

    private String extractVersionFromBrand(String brand) {
        if (brand == null || brand.isBlank()) return null;
        String lower = brand.toLowerCase(Locale.ROOT);
        int dashIdx = lower.indexOf('-');
        if (dashIdx >= 0 && dashIdx + 1 < brand.length()) {
            String afterDash = brand.substring(dashIdx + 1);
            int spaceIdx = afterDash.indexOf(' ');
            if (spaceIdx >= 0) {
                return afterDash.substring(0, spaceIdx);
            }
            return afterDash;
        }
        return null;
    }

    private void renderPluginResultsViewport(GuiGraphicsExtractor ctx, DirectViewport viewport, float uiMouseX, float uiMouseY, float delta) {
        int x = Math.round(pluginListSlot.x());
        int y = Math.round(pluginListSlot.y());
        int rowW = Math.round(pluginListSlot.width());
        int bodyBottom = panelY + panelH - 10;
        int rawViewH = Math.max(0, Math.min(Math.round(pluginListSlot.height()), bodyBottom - y));
        int viewH = getPluginlistViewportHeight(rawViewH);
        int listTop = y;
        int listBottom = listTop + viewH;
        if (rowW <= 0 || viewH <= 0) return;
        int clipLeft = x + 1;
        int clipTop = listTop + 1;
        int clipRight = x + rowW - 1;
        int clipBottom = listBottom - 1;
        int innerViewH = getPluginListInnerHeight(viewH);
        if (innerViewH <= 0) return;

        List<PluginListRow> rows = getCachedPluginRows(searchField.text());

        int estimatedContentHeight = estimatePluginContentHeight(rows);
        int maxScroll = Math.max(0, estimatedContentHeight - innerViewH);
        int quantizedTarget = quantizeScrollOffset(pluginScrollState.targetOffset(), pluginListRowStep(), maxScroll);
        pluginScrollState.setTarget(quantizedTarget, maxScroll);
        int visualScroll = quantizeScrollOffset(pluginScrollState.tick(delta, maxScroll), pluginListRowStep(), maxScroll);
        pluginContentHeight = estimatedContentHeight;
        CompactScrollbar.Metrics scrollbarMetrics = getPluginScrollbarMetrics(x, listTop, rowW, viewH);
        int rowContentW = Math.max(48, (clipRight - clipLeft) - (scrollbarMetrics.hasScroll() ? 10 : 0));
        boolean active = AutismOverlayManager.get().isFocusedOverlay(this) || AutismOverlayManager.get().isTopOverlay(this);

        viewport.push(ctx);
        try {
            CompactListRenderer.drawFrame(ctx, x, listTop, rowW, viewH, active);
            viewport.enableScissor(ctx, clipLeft, clipTop, clipRight, clipBottom);
            try {
                int cy = clipTop - visualScroll;
                for (PluginListRow row : rows) {
                    if (row.header) {
                        int headerTop = cy;
                        int headerBottom = cy + pluginHeaderHeight();
                        boolean headerVisible = headerTop >= clipTop && headerBottom <= clipBottom;
                        if (headerVisible) {
                            CompactSurfaces.header(ctx, clipLeft, headerTop, clipRight - clipLeft, headerBottom - headerTop);
                            int headerTextY = UiSizing.alignTextY(cy, pluginHeaderHeight(), theme.fontHeight(UiTone.MUTED), theme.bodyTextNudge());
                            UiText.draw(ctx, textRenderer, row.title, theme.fontFor(UiTone.MUTED), 0xFFB79E9E, clipLeft + rowTextInset(), headerTextY, false);
                        }
                        cy += pluginHeaderHeight();
                        continue;
                    }

                    String plugin = row.plugin;
                    boolean selected = plugin.equals(selectedPlugin);
                    List<String> cmds = pluginCommands.get(plugin);
                    boolean isAnticheat = ANTICHEATS.contains(plugin.toLowerCase(Locale.ROOT));
                    int rowY = cy;
                    int rowTop = rowY;
                    int rowBottom = rowY + pluginRowHeight();
                    boolean rowVisible = rowBottom > clipTop && rowTop < clipBottom;
                    boolean hovered = rowVisible && uiMouseX >= clipLeft && uiMouseX < clipLeft + rowContentW && uiMouseY >= rowTop && uiMouseY < rowBottom;
                    if (rowVisible) {
                        CompactSurfaces.row(ctx, clipLeft, rowTop, rowContentW, rowBottom - rowTop, hovered, selected);
                    }

                    boolean hasCommands = cmds != null && !cmds.isEmpty();
                    int rowTextY = UiSizing.alignTextY(rowY, pluginRowHeight(), theme.fontHeight(UiTone.BODY), theme.bodyTextNudge());
                    if (hasCommands && rowVisible) {
                        String arrow = selected ? "v" : ">";
                        int arrowColor = hovered || selected ? 0xFFF3ECE7 : 0xFFB79E9E;
                        UiText.draw(ctx, textRenderer, arrow, theme.fontFor(UiTone.BODY), arrowColor, clipLeft + 2, rowTextY, false);
                    }

                    int nameColor = isAnticheat ? 0xFFFF5555 : getEvidenceColor(row.evidence, hovered);
                    String label = isAnticheat ? "! " + plugin : plugin;
                    int labelX = clipLeft + 13;
                    int labelMaxWidth = Math.max(40, rowContentW - 18);

                    if (cmds != null && !cmds.isEmpty()) {
                        String countStr = cmds.size() + " cmd" + (cmds.size() != 1 ? "s" : "");
                        int cw = UiText.width(textRenderer, countStr, theme.fontFor(UiTone.MUTED), 0xFFB79E9E);
                        int countX = clipLeft + rowContentW - cw - 4;
                        labelMaxWidth = Math.max(36, countX - labelX - 4);
                        int countTextY = UiSizing.alignTextY(rowY, pluginRowHeight(), theme.fontHeight(UiTone.MUTED), theme.bodyTextNudge());
                        if (rowVisible) {
                            UiText.draw(ctx, textRenderer, countStr, theme.fontFor(UiTone.MUTED), 0xFFB79E9E, countX, countTextY, false);
                        }
                    }
                    String displayLabel = UiText.trimToWidth(textRenderer, label, labelMaxWidth, theme.fontFor(UiTone.BODY), nameColor);
                    if (rowVisible) {
                        UiText.draw(ctx, textRenderer, displayLabel, theme.fontFor(UiTone.BODY), nameColor, labelX, rowTextY, false);
                    }

                    if (rowVisible && hasCommands) {
                        final String clickedPlugin = plugin;
                        int hitTop = Math.max(rowTop, clipTop);
                        int hitBottom = Math.min(rowBottom, clipBottom);
                        clickRegions.add(new ClickRegion(clipLeft, hitTop, rowContentW, Math.max(1, hitBottom - hitTop), () -> {
                            selectedPlugin = clickedPlugin.equals(selectedPlugin) ? null : clickedPlugin;
                            saveState();
                        }));
                    }

                    cy += pluginRowHeight();
                    if (selected && cmds != null && !cmds.isEmpty()) {
                        for (String cmd : cmds) {
                            int cmdY = cy;
                            String cmdComponent = "/" + cmd;
                            int cmdTop = cmdY;
                            int cmdBottom = cmdY + pluginDetailRowHeight();
                            boolean cmdVisible = cmdBottom > clipTop && cmdTop < clipBottom;
                            boolean cmdHovered = cmdVisible && uiMouseX >= clipLeft + 9 && uiMouseX < clipLeft + rowContentW - 6 && uiMouseY >= cmdTop && uiMouseY < cmdBottom;
                            if (cmdVisible) {
                                CompactSurfaces.row(ctx, clipLeft + 5, cmdTop, rowContentW - 9, cmdBottom - cmdTop, cmdHovered, false);
                                CompactSurfaces.indicator(ctx, clipLeft + 5, cmdTop, 1, cmdBottom - cmdTop, 0xFFFF3B3B);
                            }
                            int cmdColor = cmdHovered ? 0xFFF3ECE7 : 0xFFE5D0D0;
                            int cmdComponentY = UiSizing.alignTextY(cmdY, pluginDetailRowHeight(), theme.fontHeight(UiTone.BODY), theme.bodyTextNudge());
                            if (cmdVisible) {
                                UiText.draw(ctx, textRenderer, cmdComponent, theme.fontFor(UiTone.BODY), cmdColor, clipLeft + 11, cmdComponentY, false);
                            }
                            if (cmdVisible) {
                                final String fullCmd = "/" + cmd;
                                int hitTop = Math.max(cmdTop, clipTop);
                                int hitBottom = Math.min(cmdBottom, clipBottom);
                                clickRegions.add(new ClickRegion(clipLeft + 9, hitTop, Math.max(1, rowContentW - 15), Math.max(1, hitBottom - hitTop), () -> {
                                    setVisible(false);
                                    clearTextFieldFocus();
                                    clickRegions.clear();
                                    MC.setScreen(new ChatScreen(fullCmd, false));
                                }));
                            }
                            cy += pluginDetailRowHeight();
                        }
                    }
                }
            } finally {
                viewport.disableScissor(ctx);
            }
            CompactScrollbar.draw(ctx, scrollbarMetrics, scrollbarMetrics.contains(uiMouseX, uiMouseY), pluginScrollbarDragging);
        } finally {
            viewport.pop(ctx);
        }
    }

    private CompactScrollbar.Metrics getPluginScrollbarMetrics(int x, int y, int width, int height) {
        int innerHeight = getPluginListInnerHeight(height);
        int maxScroll = Math.max(0, pluginContentHeight - innerHeight);
        return CompactScrollbar.compute(
            pluginContentHeight,
            Math.max(1, innerHeight),
            x + width - 5,
            y + 1,
            3,
            Math.max(1, innerHeight),
            quantizeScrollOffset(pluginScrollState.tick(0.0f, maxScroll), pluginListRowStep(), maxScroll)
        );
    }

    private String getLiveBrand() {
        if (MC.getConnection() == null) return "--";
        String brand = MC.getConnection().serverBrand();
        return brand != null && !brand.isBlank() ? brand : "--";
    }

    private boolean isOverHeaderUi(float uiMouseX, float uiMouseY) {
        return uiMouseX >= panelX && uiMouseX < panelX + panelW
            && uiMouseY >= panelY && uiMouseY < panelY + theme.headerHeight();
    }

    private boolean isOverCloseButton(float uiMouseX, float uiMouseY) {
        return OverlayTopBar.isOverClose(UiBounds.of(panelX, panelY, panelW, Math.max(theme.headerHeight(), panelH)),
            theme.headerHeight(), uiMouseX, uiMouseY);
    }

    private int panelPadding() {
        return 5;
    }

    private int contentGap() {
        return 3;
    }

    private int searchFieldHeight() {
        return 15;
    }

    private int actionButtonHeight() {
        return 15;
    }

    private int buttonRowGap() {
        return 2;
    }

    private int infoLabelWidth() {
        return 82;
    }

    private int pluginSetupLabelWidth() {
        return 46;
    }

    private int pluginScanLabelWidth() {
        return 40;
    }

    private int pluginSearchWidth() {
        return 128;
    }

    private int pluginSearchReserveWidth() {
        return 28;
    }

    private float pluginViewportMinHeight() {
        return 66.0f;
    }

    private int sharedPanelWidth() {
        return 204;
    }

    private int pluginSetupWidth() {
        return sharedPanelWidth();
    }

    private int pluginScanningWidth() {
        return sharedPanelWidth();
    }

    private int infoMinWidth() {
        return sharedPanelWidth();
    }

    private int infoMinHeight() {
        return 246;
    }

    private int pluginResultsMinWidth() {
        return sharedPanelWidth();
    }

    private int pluginResultsMinHeightPreset() {
        return 266;
    }

    private int pluginSetupHeight() {
        return 120;
    }

    private int pluginScanningHeight() {
        return 92;
    }

    private int viewportHeightMargin() {
        return 24;
    }

    private int pluginHeaderHeight() {
        return pluginListRowStep();
    }

    private int pluginRowHeight() {
        return 15;
    }

    private int pluginDetailRowHeight() {
        return pluginListRowStep();
    }

    private int pluginDetailPadding() {
        return 0;
    }

    private int headerControlSize() {
        return 12;
    }

    private int headerArrowWidth() {
        return 10;
    }

    private int headerArrowGap() {
        return 3;
    }

    private int rowTextInset() {
        return 3;
    }

    private int pluginListRowStep() {
        return pluginRowHeight();
    }

    private int getPluginlistViewportHeight(int rawHeight) {
        if (rawHeight <= 0) return 0;
        return Math.min(rawHeight, getPluginListInnerHeight(rawHeight) + 2);
    }

    private int getPluginListInnerHeight(int viewHeight) {
        return Math.max(0, alignViewportHeight(Math.max(0, viewHeight - 2), pluginListRowStep()));
    }

    private AutismWindowLayout clampToViewport(AutismWindowLayout bounds) {
        DirectViewport viewport = surface.viewport();
        int viewportWidth = Math.round(viewport.uiWidth());
        int viewportHeight = Math.round(viewport.uiHeight());
        int margin = 4;
        int availableWidth = Math.max(1, viewportWidth - margin * 2);
        int availableHeight = Math.max(theme.headerHeight(), viewportHeight - margin * 2);
        int minWidth = Math.min(getMinWidth(), availableWidth);
        int width = Math.max(minWidth, Math.min(bounds.width, availableWidth));
        int minHeight = bounds.collapsed ? theme.headerHeight() : Math.min(getMinHeight(), availableHeight);
        int height = Math.max(minHeight, Math.min(bounds.height, availableHeight));
        int renderedHeight = bounds.collapsed ? theme.headerHeight() : height;
        int x = Math.max(margin, Math.min(bounds.x, Math.max(margin, viewportWidth - margin - width)));
        int y = Math.max(margin, Math.min(bounds.y, Math.max(margin, viewportHeight - margin - renderedHeight)));
        return new AutismWindowLayout(x, y, width, height, bounds.visible, bounds.collapsed);
    }

    private String getSoftwareGuess(ServerData entry) {
        LinkedHashSet<String> guesses = new LinkedHashSet<>();
        String brand = getLiveBrand().toLowerCase(Locale.ROOT);
        if (brand.contains("purpur")) guesses.add("Purpur");
        else if (brand.contains("pufferfish")) guesses.add("Pufferfish");
        else if (brand.contains("folia")) guesses.add("Folia");
        else if (brand.contains("paper")) guesses.add("Paper");
        else if (brand.contains("spigot")) guesses.add("Spigot");
        else if (brand.contains("craftbukkit") || brand.contains("bukkit")) guesses.add("Bukkit");
        else if (brand.contains("waterfall")) guesses.add("Waterfall");
        else if (brand.contains("velocity")) guesses.add("Velocity");
        else if (brand.contains("bungeecord") || brand.contains("bungee")) guesses.add("Bungee");

        if (detectedPlugins.stream().anyMatch(plugin -> "viaversion".equalsIgnoreCase(plugin) || "viabackwards".equalsIgnoreCase(plugin) || "viarewind".equalsIgnoreCase(plugin))) {
            guesses.add("ViaVersion");
        }
        if (detectedPlugins.stream().anyMatch(plugin -> "geysermc".equalsIgnoreCase(plugin) || "floodgate".equalsIgnoreCase(plugin))) {
            guesses.add("Bedrock Bridge");
        }

        return guesses.isEmpty() ? "--" : String.join(" + ", guesses);
    }

    private String getVersionNote(ServerData entry) {
        String brand = getLiveBrand();
        String reportedVersion = getReportedVersion(entry);
        String brandLower = brand.toLowerCase(Locale.ROOT);
        String versionLower = reportedVersion.toLowerCase(Locale.ROOT);
        if (entry != null && entry.protocol == 0) {
            return "Ping Spoof";
        }
        if (!"--".equals(brand)
            && (versionLower.contains("paper")
            || versionLower.contains("spigot")
            || versionLower.contains("purpur")
            || versionLower.contains("velocity")
            || versionLower.contains("bungee"))
            && ((brandLower.contains("paper") && !versionLower.contains("paper"))
            || (brandLower.contains("spigot") && !versionLower.contains("spigot"))
            || (brandLower.contains("purpur") && !versionLower.contains("purpur"))
            || (brandLower.contains("velocity") && !versionLower.contains("velocity"))
            || (brandLower.contains("bungee") && !versionLower.contains("bungee")))) {
            return "Brand Mismatch";
        }
        if (detectedPlugins.stream().anyMatch(plugin -> "viaversion".equalsIgnoreCase(plugin) || "viabackwards".equalsIgnoreCase(plugin) || "viarewind".equalsIgnoreCase(plugin) || "protocolsupport".equalsIgnoreCase(plugin))) {
            return "Protocol Bridge";
        }
        return "--";
    }

    private String getDisplayedServerAddress() {
        ServerData entry = MC.getCurrentServer();
        if (entry != null && entry.ip != null && !entry.ip.isBlank()) {
            return entry.ip.trim();
        }
        if (MC.getConnection() != null && MC.getConnection().getConnection() != null) {
            SocketAddress address = MC.getConnection().getConnection().getRemoteAddress();
            if (address instanceof InetSocketAddress inet) {
                String host = inet.getHostString();
                if ((host == null || host.isBlank()) && inet.getAddress() != null) {
                    host = inet.getAddress().getHostAddress();
                }
                if (host != null && !host.isBlank()) {
                    return host + ":" + inet.getPort();
                }
            } else if (address != null) {
                String raw = address.toString();
                if (raw != null && !raw.isBlank()) {
                    return raw.replaceFirst("^/", "").trim();
                }
            }
        }
        return "--";
    }

    private String extractLookupHost(String rawAddress) {
        if (rawAddress == null || rawAddress.isBlank() || "--".equals(rawAddress)) return "";
        String trimmed = rawAddress.trim();
        if (trimmed.startsWith("[") && trimmed.contains("]")) {
            return trimmed.substring(1, trimmed.indexOf(']'));
        }
        int colonCount = 0;
        for (int i = 0; i < trimmed.length(); i++) {
            if (trimmed.charAt(i) == ':') colonCount++;
        }
        if (colonCount == 1 && trimmed.contains(":")) {
            return trimmed.substring(0, trimmed.lastIndexOf(':'));
        }
        return trimmed;
    }

    private String getLiveSocketIp() {
        if (MC.getConnection() == null || MC.getConnection().getConnection() == null) return "";
        SocketAddress address = MC.getConnection().getConnection().getRemoteAddress();
        if (address instanceof InetSocketAddress inet && inet.getAddress() != null) {
            String hostAddress = inet.getAddress().getHostAddress();
            return hostAddress == null ? "" : hostAddress.trim();
        }
        return "";
    }

    private Integer extractPort(String rawAddress) {
        if (rawAddress == null || rawAddress.isBlank() || "--".equals(rawAddress)) return null;
        String trimmed = rawAddress.trim();
        if (trimmed.startsWith("[") && trimmed.contains("]")) {
            int closing = trimmed.indexOf(']');
            if (closing >= 0 && closing + 1 < trimmed.length() && trimmed.charAt(closing + 1) == ':') {
                try {
                    return Integer.parseInt(trimmed.substring(closing + 2));
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
            return null;
        }
        int firstColon = trimmed.indexOf(':');
        int lastColon = trimmed.lastIndexOf(':');
        if (firstColon >= 0 && firstColon == lastColon) {
            try {
                return Integer.parseInt(trimmed.substring(lastColon + 1));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private String appendPortIfPresent(String host, Integer port) {
        if (host == null || host.isBlank() || port == null) return host;
        if (host.indexOf(':') >= 0 && !host.startsWith("[") && !host.endsWith("]")) {
            return "[" + host + "]:" + port;
        }
        return host + ":" + port;
    }

    private void copyClipboardValue(String value, String successMessage, String unavailableMessage) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty() || "--".equals(trimmed) || "Resolving...".equalsIgnoreCase(trimmed) || "Failed".equalsIgnoreCase(trimmed)) {
            AutismNotifications.error(unavailableMessage);
            return;
        }
        MC.keyboardHandler.setClipboard(trimmed);
        AutismNotifications.copied(successMessage);
    }

    private void copyResolvedServerIp() {
        String displayedAddress = getDisplayedServerAddress();
        String realIp = getDisplayedRealIp(displayedAddress);
        String resolvedWithPort = appendPortIfPresent(realIp, extractPort(displayedAddress));
        copyClipboardValue(resolvedWithPort, "Real IP copied.", "Real IP unavailable.");
    }

    private void ensureResolvedIpLookup(String rawAddress) {
        String host = extractLookupHost(rawAddress);
        if (host.isEmpty()) return;
        if (!host.equals(lastResolvedAddress)) {
            resolvedIp = null;
            lastResolvedAddress = host;
            resolvingIp = false;
        }
        if (resolvedIp == null && !resolvingIp) {
            resolvingIp = true;
            Thread t = new Thread(() -> {
                try { resolvedIp = java.net.InetAddress.getByName(host).getHostAddress(); }
                catch (Exception e) { resolvedIp = "Failed"; }
                finally { resolvingIp = false; }
            }, "Autism-DNS");
            t.setDaemon(true);
            t.start();
        }
    }

    private String getDisplayedRealIp(String rawAddress) {
        ensureResolvedIpLookup(rawAddress);
        String socketIp = getLiveSocketIp();
        if (!socketIp.isBlank()) return socketIp;
        if (resolvedIp != null) return resolvedIp;
        if (resolvingIp) return "Resolving...";
        return "--";
    }

    private List<String> getDetectedAnticheats() {
        return detectedPlugins.stream()
            .filter(Objects::nonNull)
            .filter(p -> ANTICHEATS.contains(p.toLowerCase(Locale.ROOT)))
            .sorted(String.CASE_INSENSITIVE_ORDER)
            .collect(Collectors.toList());
    }

    private String getCurrentWorldName() {
        if (MC.level == null) return "--";

        net.minecraft.resources.Identifier worldId = MC.level.dimension().identifier();
        if (worldId == null) return "--";

        String namespace = worldId.getNamespace();
        String path = worldId.getPath();
        if ("minecraft".equals(namespace)) {
            return switch (path) {
                case "overworld" -> "Overworld";
                case "the_nether" -> "The Nether";
                case "the_end" -> "The End";
                default -> path;
            };
        }

        return namespace + ":" + path;
    }

    private List<PluginListRow> buildPluginRows(List<String> filteredPlugins) {
        Map<PluginEvidence, List<String>> grouped = new LinkedHashMap<>();
        for (PluginEvidence evidence : List.of(
            PluginEvidence.COMMAND_TREE,
            PluginEvidence.NAMESPACE,
            PluginEvidence.ROOT_HINT,
            PluginEvidence.PLUGIN_LIST,
            PluginEvidence.VERSION_HINT,
            PluginEvidence.UNKNOWN,
            PluginEvidence.HELP_HINT
        )) {
            grouped.put(evidence, new ArrayList<>());
        }

        for (String plugin : filteredPlugins) {
            PluginEvidence evidence = pluginEvidence.getOrDefault(normalizePluginKey(plugin),
                pluginCommands.getOrDefault(plugin, List.of()).isEmpty() ? PluginEvidence.UNKNOWN : PluginEvidence.ROOT_HINT);
            grouped.computeIfAbsent(evidence, e -> new ArrayList<>()).add(plugin);
        }

        List<PluginListRow> rows = new ArrayList<>();
        for (Map.Entry<PluginEvidence, List<String>> group : grouped.entrySet()) {
            if (group.getValue().isEmpty()) continue;
            group.getValue().sort(String.CASE_INSENSITIVE_ORDER);
            rows.add(PluginListRow.header(getEvidenceTitle(group.getKey())));
            for (String plugin : group.getValue()) {
                rows.add(PluginListRow.plugin(plugin, group.getKey()));
            }
        }
        return rows;
    }

    private List<PluginListRow> getCachedPluginRows(String queryText) {
        String query = queryText == null ? "" : queryText.trim().toLowerCase(Locale.ROOT);
        if (cachedPluginRowsRevision == pluginRowsRevision && Objects.equals(cachedPluginRowsQuery, query)) {
            return cachedPluginRows;
        }
        List<String> filtered = new ArrayList<>();
        for (String plugin : detectedPlugins) {
            if (plugin != null && (query.isEmpty() || plugin.toLowerCase(Locale.ROOT).contains(query))) {
                filtered.add(plugin);
            }
        }
        cachedPluginRows = List.copyOf(buildPluginRows(filtered));
        cachedPluginRowsQuery = query;
        cachedPluginRowsRevision = pluginRowsRevision;
        return cachedPluginRows;
    }

    private void invalidatePluginRows() {
        pluginRowsRevision++;
        cachedPluginRowsRevision = -1;
        cachedPluginRowsQuery = null;
        cachedPluginRows = List.of();
    }

    private int estimatePluginContentHeight(List<PluginListRow> rows) {
        int total = 0;
        for (PluginListRow row : rows) {
            total += row.header ? pluginHeaderHeight() : pluginRowHeight();
            if (!row.header && row.plugin != null && row.plugin.equals(selectedPlugin)) {
                List<String> cmds = pluginCommands.get(row.plugin);
                if (cmds != null && !cmds.isEmpty()) {
                    total += cmds.size() * pluginDetailRowHeight() + pluginDetailPadding();
                }
            }
        }
        return total;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (!visible) return false;
        DirectViewport viewport = surface.viewport();
        float uiMouseX = viewport.toUiX(mx);
        float uiMouseY = viewport.toUiY(my);

        if (isOverCloseButton(uiMouseX, uiMouseY)) {
            setVisible(false);
            isDragging = false;
            dragMoved = false;
            return true;
        }
        if (button == 0 && isOverHeaderUi(uiMouseX, uiMouseY)) {
            isDragging = true;
            dragMoved = false;
            dragOffsetX = uiMouseX - panelX;
            dragOffsetY = uiMouseY - panelY;
            pressStartUiX = uiMouseX;
            pressStartUiY = uiMouseY;
            pressStartPanelX = panelX;
            pressStartPanelY = panelY;
            return true;
        }

        if (collapsed) return false;
        if (button != 0) return isMouseOver(mx, my);

        if (activeTab == 1 && pluginScanDone && !pluginScanInProgress) {
            CompactScrollbar.Metrics scrollbarMetrics = getPluginScrollbarMetrics(
                Math.round(pluginListSlot.x()),
                Math.round(pluginListSlot.y()),
                Math.round(pluginListSlot.width()),
                getPluginlistViewportHeight(Math.max(0, Math.min(Math.round(pluginListSlot.height()), panelY + panelH - 10 - Math.round(pluginListSlot.y()))))
            );
            if (scrollbarMetrics.hasScroll() && scrollbarMetrics.contains(uiMouseX, uiMouseY)) {
                pluginScrollbarDragging = true;
                pluginScrollbarGrabOffset = Math.max(0, Math.round(uiMouseY) - scrollbarMetrics.thumbY());
                pluginScrollState.setFromThumbStepped(scrollbarMetrics, Math.round(uiMouseY), pluginScrollbarGrabOffset, pluginListRowStep());
                saveState();
                return true;
            }
        }

        if (activeTab == 1 && pluginScanDone && !pluginScanInProgress) {
            for (ClickRegion region : clickRegions) {
                if (region.contains(uiMouseX, uiMouseY)) {
                    surface.clearFocusedTextInputs();
                    region.action.run();
                    return true;
                }
            }
        }

        if (surface.mouseClicked(mx, my, button)) {
            return true;
        }

        surface.clearFocusedTextInputs();

        return isMouseOver(mx, my);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (button == 0 && pluginScrollbarDragging) {
            pluginScrollbarDragging = false;
            saveState();
            return true;
        }
        if (isDragging) {
            boolean shouldCollapse = !dragMoved;
            isDragging = false;
            if (shouldCollapse) {
                setCollapsed(!collapsed);
                if (!collapsed) {
                    if (activeTab == 0) applyInfoLayout();
                    else if (pluginScanInProgress) applyPluginScanningLayout();
                    else if (!pluginScanDone) applyPluginSetupLayout();
                    else applyPluginResultsLayout();
                }
            }
            saveState();
            return true;
        }
        return surface.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (pluginScrollbarDragging && button == 0) {
            DirectViewport viewport = surface.viewport();
            float uiMouseY = viewport.toUiY(my);
            CompactScrollbar.Metrics scrollbarMetrics = getPluginScrollbarMetrics(
                Math.round(pluginListSlot.x()),
                Math.round(pluginListSlot.y()),
                Math.round(pluginListSlot.width()),
                getPluginlistViewportHeight(Math.max(0, Math.min(Math.round(pluginListSlot.height()), panelY + panelH - 10 - Math.round(pluginListSlot.y()))))
            );
            pluginScrollState.setFromThumbStepped(scrollbarMetrics, Math.round(uiMouseY), pluginScrollbarGrabOffset, pluginListRowStep());
            return true;
        }
        if (isDragging) {
            DirectViewport viewport = surface.viewport();
            float uiMouseX = viewport.toUiX(mx);
            float uiMouseY = viewport.toUiY(my);
            AutismWindowLayout clamped = clampToViewport(new AutismWindowLayout(
                Math.round(uiMouseX - (float) dragOffsetX),
                Math.round(uiMouseY - (float) dragOffsetY),
                panelW,
                panelH,
                visible,
                collapsed
            ));
            panelX = clamped.x;
            panelY = clamped.y;
            dragMoved = dragMoved
                || Math.abs(uiMouseX - pressStartUiX) >= HEADER_CLICK_DRAG_THRESHOLD
                || Math.abs(uiMouseY - pressStartUiY) >= HEADER_CLICK_DRAG_THRESHOLD
                || panelX != pressStartPanelX
                || panelY != pressStartPanelY;
            return true;
        }
        return surface.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double amount) {
        if (!visible || collapsed || !isMouseOver(mx, my)) return false;
        if (activeTab == 1 && pluginScanDone && !pluginScanInProgress) {
            int slotY = Math.round(pluginListSlot.y());
            int slotH = Math.round(pluginListSlot.height());
            int rawViewH = Math.max(0, Math.min(slotH, panelY + panelH - 10 - slotY));
            int viewH = getPluginlistViewportHeight(rawViewH);
            int maxScroll = Math.max(0, pluginContentHeight - getPluginListInnerHeight(viewH));
            pluginScrollState.nudge(amount, pluginListRowStep(), maxScroll);
            return true;
        }
        return surface.mouseScrolled(mx, my, amount);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        return visible && !collapsed && surface.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char chr, int modifiers) {
        return visible && !collapsed && surface.charTyped(chr, modifiers);
    }

    private void copyServerData() {
        StringBuilder sb = new StringBuilder();
        ServerData entry = MC.getCurrentServer();
        String ip = getDisplayedServerAddress();
        String realIp = getDisplayedRealIp(ip);
        List<String> detectedAcs = getDetectedAnticheats();
        String software = getSoftwareGuess(entry);
        String versionNote = getVersionNote(entry);
        sb.append("=== Server Info ===\n");
        sb.append("IP:         ").append(ip).append("\n");
        if (!realIp.equals("--")) {
            sb.append("Real IP:    ").append(realIp).append("\n");
        }
        sb.append("Version:    ").append(getReportedVersion(entry)).append("\n");
        sb.append("RealVersion: ").append(getRealServerVersion()).append("\n");
        sb.append("Brand:      ").append(getLiveBrand()).append("\n");
        if (!"--".equals(software)) {
            sb.append("Software:   ").append(software).append("\n");
        }
        if (MC.getConnection() != null) {
            sb.append("Players:    ").append(MC.getConnection().getListedOnlinePlayers().size());
            if (entry != null && entry.players != null) sb.append(" / ").append(entry.players.max());
            sb.append("\n");
        } else {
            sb.append("Players:    --\n");
        }
        sb.append("Protocol:   ").append(entry != null ? entry.protocol : "--").append("\n");
        if (!"--".equals(versionNote)) {
            sb.append("VersionNote: ").append(versionNote).append("\n");
        }
        sb.append("Difficulty: ").append(MC.level != null ? MC.level.getDifficulty().getDisplayName().getString() : "--").append("\n");
        sb.append("World:      ").append(getCurrentWorldName()).append("\n");
        if (MC.level != null) {
            long dayCount = MC.level.getOverworldClockTime() / 24000L;
            long timeOfDay = MC.level.getOverworldClockTime() % 24000L;
            int hours = (int) ((timeOfDay / 1000 + 6) % 24);
            int minutes = (int) ((timeOfDay % 1000) * 60 / 1000);
            sb.append("Time:       Day ").append(dayCount).append(" (").append(String.format("%02d:%02d", hours, minutes)).append(")\n");
        } else {
            sb.append("Time:       --\n");
        }
        if (pluginScanInProgress) {
            sb.append("AntiCheats: Scanning...\n");
        } else if (!pluginScanDone) {
            sb.append("AntiCheats: Probe Plugins First\n");
        } else if (detectedAcs.isEmpty()) {
            sb.append("AntiCheats: None detected\n");
        } else {
            sb.append("AntiCheats: ").append(String.join(", ", detectedAcs)).append("\n");
        }
        MC.keyboardHandler.setClipboard(sb.toString());
        AutismNotifications.copied("Server info copied.");
    }

    private void copyPluginList() {
        if (detectedPlugins.isEmpty()) {
            AutismNotifications.error("No plugins detected.");
            return;
        }
        List<String> copyPlugins = new ArrayList<>();
        for (String plugin : detectedPlugins) {
            if (plugin == null || plugin.isBlank()) continue;
            PluginEvidence evidence = pluginEvidence.getOrDefault(normalizePluginKey(plugin),
                pluginCommands.getOrDefault(plugin, List.of()).isEmpty() ? PluginEvidence.UNKNOWN : PluginEvidence.ROOT_HINT);
            if (evidence == PluginEvidence.HELP_HINT) continue;
            copyPlugins.add(plugin);
        }
        if (copyPlugins.isEmpty()) {
            AutismNotifications.error("No non-hint plugins to copy.");
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Plugins (").append(copyPlugins.size()).append("):\n");
        List<PluginListRow> rows = buildPluginRows(copyPlugins);
        for (PluginListRow row : rows) {
            if (row.header) {
                sb.append("\n[").append(row.title).append("]\n");
                continue;
            }
            boolean ac = ANTICHEATS.contains(row.plugin.toLowerCase(Locale.ROOT));
            List<String> commands = pluginCommands.getOrDefault(row.plugin, List.of());
            sb.append(ac ? "[AC] " : "- ").append(row.plugin);
            if (!commands.isEmpty()) {
                sb.append(" (").append(commands.size()).append(" cmds)");
            }
            sb.append("\n");
        }
        MC.keyboardHandler.setClipboard(sb.toString());
        AutismNotifications.copied("Plugin list copied.");
    }
}
