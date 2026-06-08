package autismclient.util.macro;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import autismclient.AutismClientAddon;
import autismclient.modules.AutismModule;
import autismclient.util.PacketRegenerator;
import autismclient.util.AutismCompatManager;
import autismclient.util.AutismClientMessaging;
import autismclient.util.AutismCraftingHelper;
import autismclient.util.AutismGuiActions;
import autismclient.util.AutismInstaBreakRenderer;
import autismclient.util.AutismInventoryClickHelper;
import autismclient.util.AutismInventoryHelper;
import autismclient.util.AutismMacro;
import autismclient.util.AutismPacketNamer;
import autismclient.util.AutismPacketRegistry;
import autismclient.util.AutismSharedState;
import autismclient.util.AutismAutoTool;
import autismclient.mixin.accessor.AutismMultiPlayerGameModeAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.tags.ItemTags;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.text.Normalizer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

public class MacroExecutor {
    private static final Gson GSON = new Gson();

    private static final long MACRO_WAIT_POLL_NANOS = 500_000L;
    private static final AtomicBoolean isRunning = new AtomicBoolean(false);
    private static final int MAX_CONCURRENT_MACROS = 2;
    private static final Object RUN_LOCK = new Object();
    private enum SharedClientResource { INVENTORY, GUI, INPUT, ROTATION, BARITONE, NETWORK, PACKET_DELAY, WORLD_ACTION }
    private static final EnumMap<SharedClientResource, ReentrantLock> SHARED_RESOURCE_LOCKS = new EnumMap<>(SharedClientResource.class);
    static {
        for (SharedClientResource resource : SharedClientResource.values()) {
            SHARED_RESOURCE_LOCKS.put(resource, new ReentrantLock(true));
        }
    }
    private static final AtomicLong NEXT_RUN_ID = new AtomicLong(1L);
    private static final java.util.LinkedHashMap<Long, RunState> ACTIVE_RUNS = new java.util.LinkedHashMap<>();

    private static final java.util.concurrent.ConcurrentHashMap<Long, RunState> RUNS_BY_ID = new java.util.concurrent.ConcurrentHashMap<>();
    private static final AtomicLong RUN_STATE_REVISION = new AtomicLong();
    private static final ThreadLocal<RunState> CURRENT_RUN = new ThreadLocal<>();
    private static volatile RunState primaryRun = null;
    private static AutismMacro currentMacro = null;
    private static Thread macroThread = null;

    private static final AtomicReference<String> lastReceivedPacket = new AtomicReference<>("");
    private static String currentStatus = "";

    private static volatile int currentStepIndex = -1;
    private static volatile int totalSteps = 0;

    private static final AtomicBoolean isWaitingForPacket = new AtomicBoolean(false);
    private static final AtomicReference<String> waitingPacketName = new AtomicReference<>("");
    private static final Object packetWaitLock = new Object();

    private static final AtomicBoolean isWaitingForChat = new AtomicBoolean(false);
    private static final AtomicReference<String> waitingChatPattern = new AtomicReference<>("");
    private static final AtomicBoolean waitingChatIsRegex = new AtomicBoolean(false);
    private static final AtomicReference<String> waitingChatPatternJson = new AtomicReference<>("");
    private static final java.util.concurrent.atomic.AtomicInteger waitingChatFuzzyPercent = new java.util.concurrent.atomic.AtomicInteger(100);
    private static final AtomicReference<String> lastMatchedChat = new AtomicReference<>("");
    private static final Object recentChatLock = new Object();
    private static final Deque<RecentChatMessage> recentChatMessages = new ArrayDeque<>();
    private static final int MAX_RECENT_CHAT_MESSAGES = 60;
    private static String recentChatServerKey = "";

    private static volatile CompletableFuture<Void> activePacketFuture = null;
    private static volatile CompletableFuture<Void> activeChatFuture = null;

    private static volatile CompletableFuture<Void> activeBaritoneGoalFuture = null;

    private static final AtomicBoolean isRotating = new AtomicBoolean(false);
    private static float targetYaw, targetPitch;
    private static double rotationSpeed;
    private static int rotationAlignedFrames;

    private static final java.util.concurrent.CopyOnWriteArrayList<MacroAction> persistentActions =
        new java.util.concurrent.CopyOnWriteArrayList<>();

    private static final java.util.concurrent.ConcurrentHashMap<MoveAction, int[]> backgroundMoves =
        new java.util.concurrent.ConcurrentHashMap<>();
    private static final ThreadLocal<RepeatPacketContext> REPEAT_PACKET_CONTEXT = new ThreadLocal<>();
    private static final ThreadLocal<Integer> REPEAT_PACKET_SKIP = ThreadLocal.withInitial(() -> 0);

    private static final class RunState {
        final long id;
        final AutismMacro macro;
        final AtomicBoolean running = new AtomicBoolean(true);
        final CopyOnWriteArrayList<MacroAction> persistentActions = new CopyOnWriteArrayList<>();
        final ConcurrentHashMap<MoveAction, int[]> backgroundMoves = new ConcurrentHashMap<>();
        final CopyOnWriteArrayList<MacroAction> finallyActions = new CopyOnWriteArrayList<>();
        final Object packetWaitLock = new Object();
        final AtomicBoolean waitingForPacket = new AtomicBoolean(false);
        final AtomicReference<String> waitingPacketName = new AtomicReference<>("");
        volatile CompletableFuture<Void> packetFuture = null;
        final AtomicBoolean waitingForChat = new AtomicBoolean(false);
        volatile WaitForChatAction waitingChatAction = null;
        volatile CompletableFuture<Void> chatFuture = null;
        volatile CompletableFuture<Void> baritoneGoalFuture = null;
        volatile MineProgressTracker activeMineTracker = null;
        volatile Thread macroThread = null;
        volatile String status = "";
        volatile int currentStepIndex = -1;
        volatile int totalSteps = 0;
        volatile int lastCompletedStep = 0;
        volatile int branchElseStart = -1;
        volatile int branchElseCount = 0;
        final java.util.IdentityHashMap<MacroAction, PrearmedWait> prearmedWaits = new java.util.IdentityHashMap<>();
        final RunOptions options;

        RunState(long id, AutismMacro macro, RunOptions options) {
            this.id = id;
            this.macro = macro;
            this.options = options == null ? RunOptions.NORMAL : options;
            this.totalSteps = macro != null ? macro.actions.size() : 0;
        }
    }

    private static final class PrearmedWait {
        final MacroAction action;
        final CompletableFuture<?> future;
        final Runnable cleanup;
        final int completedPacketTargets;

        PrearmedWait(MacroAction action, CompletableFuture<?> future, Runnable cleanup, int completedPacketTargets) {
            this.action = action;
            this.future = future;
            this.cleanup = cleanup == null ? () -> {} : cleanup;
            this.completedPacketTargets = Math.max(0, completedPacketTargets);
        }

        void cleanup() {
            try {
                cleanup.run();
            } catch (Throwable ignored) {
            }
        }
    }

    public enum ChatSource { PLAYER, SERVER }

    public record RecentChatMessage(String sender, String message, String displayText, Component displayComponent, ChatSource source, long timestampMs) {}
    public record MacroRunSnapshot(AutismMacro macro, String name, String status, int currentStepIndex, int totalSteps, int lastCompletedStep) {}
    public record RunOptions(boolean silentLifecycle, boolean hiddenFromProgressHud, boolean skipLanProgress, boolean backgroundRun) {
        public static final RunOptions NORMAL = new RunOptions(false, false, false, false);

        public static RunOptions silentBackground() {
            return new RunOptions(true, true, true, true);
        }
    }

    private record ChatCapture(String sender, String message, String displayText, Component displayComponent, ChatSource source) {}
    private record RepeatPacketContext(AutismMacro macro, int startIdx, int endIdx) {}
    private record MacroEvent(Packet<?> packet, String direction, long nanoTime, ChatCapture chatCapture) {
        String label() {
            if (packet == null) return direction == null || direction.isBlank() ? "client event" : direction;
            String dir = direction == null || direction.isBlank() ? "" : direction + " ";
            return dir + AutismPacketNamer.getFriendlyName(packet, direction == null ? "" : direction);
        }
    }

    private static boolean isCurrentRunActive() {
        RunState run = CURRENT_RUN.get();
        if (run != null) return run.running.get();
        return isRunning.get();
    }

    public static boolean isCurrentActionRunActive() {
        return isCurrentRunActive();
    }

    private static CopyOnWriteArrayList<MacroAction> currentPersistentActions() {
        RunState run = CURRENT_RUN.get();
        return run != null ? run.persistentActions : persistentActions;
    }

    private static ConcurrentHashMap<MoveAction, int[]> currentBackgroundMoves() {
        RunState run = CURRENT_RUN.get();
        return run != null ? run.backgroundMoves : backgroundMoves;
    }

    private static void setCurrentStatus(String status) {
        RunState run = CURRENT_RUN.get();
        if (run != null) run.status = status == null ? "" : status;
        currentStatus = status == null ? "" : status;
    }

    private static void setCurrentStep(int step, int total) {
        RunState run = CURRENT_RUN.get();
        if (run != null) {
            run.currentStepIndex = step;
            run.totalSteps = total;
        }
        currentStepIndex = step;
        totalSteps = total;
    }

    private static void markStepCompleted(int stepOneBased) {
        RunState run = CURRENT_RUN.get();
        if (run != null) {
            run.lastCompletedStep = Math.max(run.lastCompletedStep, stepOneBased);
        }
    }

    private static void stopCurrentRun() {
        RunState run = CURRENT_RUN.get();
        if (run != null) {
            run.running.set(false);
        } else {
            stop();
        }
    }

    private static EnumSet<SharedClientResource> resourcesForAction(MacroAction action) {
        EnumSet<SharedClientResource> resources = EnumSet.noneOf(SharedClientResource.class);
        if (action instanceof ClickAction
            || action instanceof InventoryAction
            || action instanceof ItemAction
            || action instanceof DropAction
            || action instanceof CraftAction
            || action instanceof StoreItemAction
            || action instanceof InventoryAuditAction
            || action instanceof XCarryAction
            || action instanceof SwapSlotsAction
            || action instanceof SelectSlotAction) {
            resources.add(SharedClientResource.INVENTORY);
            resources.add(SharedClientResource.GUI);
        }
        if (action instanceof WaitDurabilityAction durability && durability.useNext) {
            resources.add(SharedClientResource.INVENTORY);
            resources.add(SharedClientResource.GUI);
        }
        if (action instanceof OpenContainerAction
            || action instanceof InteractEntityAction
            || action instanceof CloseGuiAction
            || action instanceof SaveGuiAction
            || action instanceof RestoreGuiAction
            || action instanceof DesyncAction
            || action instanceof NbtBookAction) {
            resources.add(SharedClientResource.GUI);
            resources.add(SharedClientResource.NETWORK);
        }
        if (action instanceof SendPacketAction
            || action instanceof PacketAction
            || action instanceof PacketClickAction
            || action instanceof PayloadAction
            || action instanceof PayAction
            || action instanceof SendChatAction
            || action instanceof DisconnectAction
            || action instanceof ReportAction) {
            resources.add(SharedClientResource.NETWORK);
        }
        if (action instanceof DelayPacketsAction) {
            resources.add(SharedClientResource.NETWORK);
            resources.add(SharedClientResource.PACKET_DELAY);
        }
        if (action instanceof UseItemAction) {
            resources.add(SharedClientResource.INPUT);
            resources.add(SharedClientResource.NETWORK);
        }
        if (action instanceof MoveAction
            || action instanceof SneakAction
            || action instanceof SprintAction
            || action instanceof JumpAction) {
            resources.add(SharedClientResource.INPUT);
        }
        if (action instanceof RotateAction || action instanceof LookAtBlockAction) {
            resources.add(SharedClientResource.ROTATION);
        }
        if (action instanceof GoToAction || action instanceof MineAction || action instanceof InstaBreakAction
            || action instanceof BreakAction) {
            resources.add(SharedClientResource.BARITONE);
            resources.add(SharedClientResource.INPUT);
            resources.add(SharedClientResource.ROTATION);
            resources.add(SharedClientResource.NETWORK);
        }
        if (action instanceof ToggleModuleAction) {
            resources.add(SharedClientResource.WORLD_ACTION);
        }
        return resources;
    }

    private static EnumSet<SharedClientResource> acquireClientResources(MacroAction action) throws InterruptedException {
        return acquireClientResources(resourcesForAction(action));
    }

    private static EnumSet<SharedClientResource> acquireClientResources(EnumSet<SharedClientResource> resources) throws InterruptedException {
        EnumSet<SharedClientResource> acquired = EnumSet.noneOf(SharedClientResource.class);
        try {
            for (SharedClientResource resource : SharedClientResource.values()) {
                if (resources.contains(resource)) {
                    if (!isCurrentRunActive()) throw new InterruptedException("Macro stopped");
                    SHARED_RESOURCE_LOCKS.get(resource).lockInterruptibly();
                    acquired.add(resource);
                    if (!isCurrentRunActive()) throw new InterruptedException("Macro stopped");
                }
            }
        } catch (InterruptedException e) {
            releaseClientResources(acquired);
            throw e;
        }
        return acquired;
    }

    private static EnumSet<SharedClientResource> resourcesForActions(java.util.List<MacroAction> actions) {
        EnumSet<SharedClientResource> resources = EnumSet.noneOf(SharedClientResource.class);
        if (actions == null) return resources;
        for (MacroAction action : actions) {
            if (action != null && action.isEnabled() && RaceAction.isBodyAction(action)) {
                resources.addAll(resourcesForAction(action));
            }
        }
        return resources;
    }

    private static void releaseClientResources(EnumSet<SharedClientResource> resources) {
        if (resources == null || resources.isEmpty()) return;
        SharedClientResource[] ordered = SharedClientResource.values();
        for (int i = ordered.length - 1; i >= 0; i--) {
            SharedClientResource resource = ordered[i];
            if (resources.contains(resource)) {
                ReentrantLock lock = SHARED_RESOURCE_LOCKS.get(resource);
                if (lock.isHeldByCurrentThread()) lock.unlock();
            }
        }
    }

    private static java.util.List<RunState> activeRunSnapshot() {
        synchronized (RUN_LOCK) {
            return new ArrayList<>(ACTIVE_RUNS.values());
        }
    }

    private static void finishCurrentRun() {
        RunState run = CURRENT_RUN.get();
        if (run == null) return;
        requestStop(run, false);

        synchronized (RUN_LOCK) {
            ACTIVE_RUNS.remove(run.id);
            RUNS_BY_ID.remove(run.id);
            RUN_STATE_REVISION.incrementAndGet();
            refreshPrimaryRunLocked();
        }
        if (!isRunning.get()) AutismInstaBreakRenderer.clear();
        CURRENT_RUN.remove();
    }

    private static boolean hasRunningRunsLocked() {
        for (RunState run : ACTIVE_RUNS.values()) {
            if (run.running.get()) return true;
        }
        return false;
    }

    private static RunState firstVisibleRunLocked() {
        for (RunState run : ACTIVE_RUNS.values()) {
            if (run.running.get() && !run.options.hiddenFromProgressHud()) return run;
        }
        return null;
    }

    private static int visibleRunCountLocked() {
        int count = 0;
        for (RunState run : ACTIVE_RUNS.values()) {
            if (run.running.get() && !run.options.backgroundRun()) count++;
        }
        return count;
    }

    private static void refreshPrimaryRunLocked() {
        primaryRun = firstVisibleRunLocked();
        currentMacro = primaryRun != null ? primaryRun.macro : null;
        currentStatus = primaryRun != null ? primaryRun.status : "";
        currentStepIndex = primaryRun != null ? primaryRun.currentStepIndex : -1;
        totalSteps = primaryRun != null ? primaryRun.totalSteps : 0;
        isRunning.set(hasRunningRunsLocked());
    }

    private static boolean hasRunningRuns() {
        synchronized (RUN_LOCK) {
            return hasRunningRunsLocked();
        }
    }

    public static boolean isVisibleRunning() {
        synchronized (RUN_LOCK) {
            return firstVisibleRunLocked() != null;
        }
    }

    private static void broadcastStepProgress(RunState run, int step, int total, String macroName) {
        if (run != null && run.options.skipLanProgress()) return;
        try {
            autismclient.util.AutismLANSync sync = autismclient.util.AutismLANSync.getInstance();
            if (sync.isInSession()) {
                sync.broadcastStepProgress(step, total, macroName);
            }
        } catch (Exception ignored) {}
    }

    private static void requestStop(RunState run, boolean interruptThread) {
        if (run == null) return;
        if (run.running.getAndSet(false)) {
            RUN_STATE_REVISION.incrementAndGet();
        }
        run.persistentActions.clear();
        for (PrearmedWait wait : new ArrayList<>(run.prearmedWaits.values())) {
            wait.cleanup();
        }
        run.prearmedWaits.clear();
        releaseBackgroundMoves(run.backgroundMoves, Minecraft.getInstance());
        run.backgroundMoves.clear();
        synchronized (run.packetWaitLock) {
            if (run.packetFuture != null) run.packetFuture.cancel(true);
            run.packetFuture = null;
            run.waitingForPacket.set(false);
            run.waitingPacketName.set("");
        }
        CompletableFuture<Void> chatFuture = run.chatFuture;
        if (chatFuture != null) chatFuture.cancel(true);
        run.chatFuture = null;
        run.waitingForChat.set(false);
        run.waitingChatAction = null;
        CompletableFuture<Void> baritoneFuture = run.baritoneGoalFuture;
        if (baritoneFuture != null) baritoneFuture.cancel(true);
        run.baritoneGoalFuture = null;
        run.activeMineTracker = null;
        requestBaritoneStop(Minecraft.getInstance());
        Thread thread = run.macroThread;
        if (interruptThread && thread != null && thread != Thread.currentThread()) {
            thread.interrupt();
        }
    }

    private static void releaseBackgroundMoves(ConcurrentHashMap<MoveAction, int[]> moves, Minecraft mc) {
        if (moves == null || moves.isEmpty() || mc == null) return;
        List<MoveAction> toRelease = new ArrayList<>(moves.keySet());
        mc.execute(() -> {
            for (MoveAction move : toRelease) {
                try {
                    move.release(mc);
                } catch (Exception ignored) {
                }
            }
        });
    }

    private static String macroKey(String macroName) {
        return macroName == null ? "" : macroName.trim().toLowerCase(Locale.ROOT);
    }

    private static String macroName(AutismMacro macro) {
        return macro != null && macro.name != null ? macro.name : "";
    }

    private static boolean matchesMacro(RunState run, AutismMacro macro, String macroName) {
        if (run == null || !run.running.get()) return false;
        if (macro != null && run.macro == macro) return true;
        String targetKey = macroKey(macroName);
        if (targetKey.isEmpty() && macro != null) targetKey = macroKey(macro.name);
        return !targetKey.isEmpty() && macroKey(macroName(run.macro)).equals(targetKey);
    }

    public static void execute(AutismMacro macro) {
        executeTracked(macro);
    }

    public static long executeTracked(AutismMacro macro) {
        return executeTracked(macro, RunOptions.NORMAL);
    }

    public static long executeTracked(AutismMacro macro, RunOptions options) {
        RunOptions runOptions = options == null ? RunOptions.NORMAL : options;
        if (macro == null || macro.actions == null || macro.actions.isEmpty()) {
            if (runOptions.silentLifecycle()) return -1L;
            AutismClientMessaging.sendPrefixed("§cMacro has no actions!");
            return -1L;
        }

        RunState runState;
        synchronized (RUN_LOCK) {
            String name = macro.name == null ? "" : macro.name;
            String nameKey = macroKey(name);
            if (!runOptions.backgroundRun()) {
                for (RunState active : ACTIVE_RUNS.values()) {
                    if (active.options.backgroundRun()) continue;
                    String activeName = active.macro != null && active.macro.name != null ? active.macro.name : "";
                    if (active.running.get() && macroKey(activeName).equals(nameKey)) {
                        if (!runOptions.silentLifecycle()) AutismClientMessaging.sendPrefixed("§eMacro already running: " + name);
                        return -1L;
                    }
                }
                if (visibleRunCountLocked() >= MAX_CONCURRENT_MACROS) {
                    if (!runOptions.silentLifecycle()) AutismClientMessaging.sendPrefixed("§cOnly " + MAX_CONCURRENT_MACROS + " macros can run at the same time.");
                    return -1L;
                }
            }
            runState = new RunState(NEXT_RUN_ID.getAndIncrement(), macro, runOptions);
            ACTIVE_RUNS.put(runState.id, runState);
            RUNS_BY_ID.put(runState.id, runState);
            RUN_STATE_REVISION.incrementAndGet();
            refreshPrimaryRunLocked();
        }
        if (!runOptions.silentLifecycle()) AutismClientMessaging.sendPrefixed("§aStarted macro: " + macro.name);

        broadcastStepProgress(runState, 0, macro.actions.size(), macro.name);

        Thread thread = new Thread(() -> run(runState), "Autism-Macro-" + runState.id);
        macroThread = thread;
        runState.macroThread = thread;
        try {
            thread.start();
        } catch (Throwable startError) {

            requestStop(runState, false);
            synchronized (RUN_LOCK) {
                ACTIVE_RUNS.remove(runState.id);
                RUNS_BY_ID.remove(runState.id);
                RUN_STATE_REVISION.incrementAndGet();
                refreshPrimaryRunLocked();
            }
            return -1L;
        }
        return runState.id;
    }

    public static boolean isRunActive(long id) {
        if (id < 0L) return false;
        RunState run = RUNS_BY_ID.get(id);
        return run != null && run.running.get();
    }

    public static long runStateRevision() {
        return RUN_STATE_REVISION.get();
    }

    public static long currentRunId() {
        RunState run = CURRENT_RUN.get();
        return run == null ? -1L : run.id;
    }

    public static void stopRun(long id) {
        if (id < 0L) return;
        RunState run;
        synchronized (RUN_LOCK) {
            run = ACTIVE_RUNS.get(id);
        }
        if (run == null) return;
        requestStop(run, true);
        AutismInstaBreakRenderer.clear();
        synchronized (RUN_LOCK) {
            refreshPrimaryRunLocked();
        }
    }

    private static void run(RunState runState) {
        CURRENT_RUN.set(runState);
        try {
            runInternal(runState);
        } catch (Throwable fatal) {

            if (!runState.options.silentLifecycle()) {
                try { AutismClientMessaging.sendPrefixed("§cMacro crashed: " + fatal.getMessage()); } catch (Throwable ignored) {}
            }
        } finally {
            finishCurrentRun();
            CURRENT_RUN.remove();
        }
    }

    private static void runInternal(RunState runState) {
        CURRENT_RUN.set(runState);
        MacroVariables.clear();
        AutismMacro macro = runState.macro;
        Minecraft mc = Minecraft.getInstance();
        int loopCount = 0;
        int maxLoops = macro.loop ? (macro.loopCount == -1 ? Integer.MAX_VALUE : macro.loopCount) : 1;

        currentPersistentActions().clear();
        currentBackgroundMoves().clear();
        Thread persistentThread = new Thread(() -> {
            CURRENT_RUN.set(runState);
            try {
                while (isCurrentRunActive()) {
                    if (!currentPersistentActions().isEmpty() || !currentBackgroundMoves().isEmpty()) {
                        mc.execute(() -> {
                            ReentrantLock inputLock = SHARED_RESOURCE_LOCKS.get(SharedClientResource.INPUT);
                            inputLock.lock();
                            try {

                            for (MacroAction pa : currentPersistentActions()) {
                                try { pa.execute(mc); } catch (Exception ignored) {}
                            }

                            java.util.Iterator<java.util.Map.Entry<MoveAction, int[]>> it = currentBackgroundMoves().entrySet().iterator();
                            while (it.hasNext()) {
                                java.util.Map.Entry<MoveAction, int[]> entry = it.next();
                                MoveAction bma = entry.getKey();
                                int[] ticks = entry.getValue();
                                if (ticks[0] > 0) {
                                    bma.execute(mc);
                                    ticks[0]--;
                                } else {
                                    bma.release(mc);
                                    currentBackgroundMoves().remove(bma);
                                }
                            }
                            } finally {
                                inputLock.unlock();
                            }
                        });
                    }

                    try {
                        CompletableFuture<Void> tf = MacroConditionRegistry.waitForNextTick();
                        tf.get(100, java.util.concurrent.TimeUnit.MILLISECONDS);
                    } catch (Exception e) {
                        if (!isCurrentRunActive()) break;
                    }
                }
            } finally {
                releaseBackgroundMoves(currentBackgroundMoves(), mc);
                currentBackgroundMoves().clear();
                CURRENT_RUN.remove();
            }
        }, "Autism-Persistent-Thread");
        persistentThread.setDaemon(true);
        persistentThread.start();

        try {
            while (isCurrentRunActive() && loopCount < maxLoops) {
                if (macro.actions.isEmpty()) {
                    if (!runState.options.silentLifecycle()) AutismClientMessaging.sendPrefixed("§cMacro stopped: no actions left.");
                    stopCurrentRun();
                    break;
                }
                setCurrentStatus("Loop " + (loopCount + 1) + (maxLoops == Integer.MAX_VALUE ? "" : "/" + maxLoops));

                AutismModule module = AutismModule.get();
                boolean burstMode = module.usePacketBurstMode();

                totalSteps = macro.actions.size();
                for (int i = 0; i < macro.actions.size(); i++) {
                    if (!isCurrentRunActive()) break;

                    setCurrentStep(i, macro.actions.size());
                    if (runState.branchElseStart == i && runState.branchElseCount > 0) {
                        i += runState.branchElseCount - 1;
                        runState.branchElseStart = -1;
                        runState.branchElseCount = 0;
                        continue;
                    }
                    MacroAction action = macro.actions.get(i);

                    if (!action.isEnabled()) {
                        if (action instanceof RaceAction disabledRace) {
                            i += disabledRace.normalizedBodyCount(macro.actions, i);
                        } else if (action instanceof ReportAction disabledReport) {
                            i += disabledReport.normalizedConditionCount(macro.actions, i);
                        }
                        continue;
                    }

                    if (action instanceof BranchAction branch) {
                        boolean branchMatches = branch.matches(mc);
                        if (branchMatches) {
                            runState.branchElseStart = i + 1 + Math.max(0, branch.thenSteps);
                            runState.branchElseCount = Math.max(0, branch.elseSteps);
                        } else {
                            i += Math.max(0, branch.thenSteps);
                        }
                        continue;
                    }

                    if (action instanceof FinallyAction cleanup) {
                        int bodyCount = Math.max(0, cleanup.bodyCount);
                        runState.finallyActions.clear();
                        for (int c = 1; c <= bodyCount && i + c < macro.actions.size(); c++) {
                            MacroAction body = macro.actions.get(i + c);
                            if (body != null && body.isEnabled()) runState.finallyActions.add(body);
                        }
                        i += bodyCount;
                        continue;
                    }

                    prearmNextActionWait(mc, macro.actions, i);

                    EnumSet<SharedClientResource> heldResources = acquireClientResources(action);
                    try {

                        waitForGuiBeforeAction(mc, action);
                        java.util.concurrent.CompletableFuture<Void> postGuiFuture =
                                (action instanceof ItemAction || action instanceof DropAction || isTargetWaitInteractionAction(action)) ? null : createPostGuiFuture(mc, action);

                        if (burstMode && action instanceof SendPacketAction) {

                        java.util.List<SendPacketAction> packetBatch = new java.util.ArrayList<>();
                        int batchEnd = i;

                        while (batchEnd < macro.actions.size() &&
                               macro.actions.get(batchEnd) instanceof SendPacketAction) {
                            packetBatch.add((SendPacketAction) macro.actions.get(batchEnd));
                            batchEnd++;
                        }

                        executeBurstPackets(mc, packetBatch);

                        i = batchEnd - 1;
                        continue;
                    }

                    if (action instanceof DelayAction) {
                        DelayAction da = (DelayAction) action;
                        if (da.useTicks) {

                            for (int t = 0; t < da.delayTicks; t++) {
                                if (!isCurrentRunActive()) break;

                                CompletableFuture<Void> future = MacroConditionRegistry.waitForNextTick();
                                waitForCondition(future);
                            }
                        } else {

                            Thread.sleep(da.delayMs);
                        }
                    }
                    else if (action instanceof WaitForPacketAction) {
                        java.util.List<String> targets = ((WaitForPacketAction) action).effectiveList();
                        PrearmedWait prearmed = consumePrearmedWait(action);
                        int startTarget = 0;
                        if (prearmed != null) {
                            try {
                                waitForFutureDone(prearmed.future, 0);
                                startTarget = prearmed.completedPacketTargets;
                            } finally {
                                prearmed.cleanup();
                            }
                        }
                        if (targets.isEmpty()) {
                            if (prearmed != null) continue;
                            awaitPacket("");
                        } else {
                            for (int targetIndex = Math.min(startTarget, targets.size()); targetIndex < targets.size(); targetIndex++) {
                                if (!isCurrentRunActive()) break;
                                awaitPacket(targets.get(targetIndex));
                            }
                        }
                    }
                    else if (action instanceof WaitPacketMatchAction wpma) {
                        setCurrentStatus(wpma.getDisplayName());
                        if (!waitForPrearmed(wpma, wpma.timeoutMs)) waitForPacketMatch(wpma);
                    }
                    else if (action instanceof WaitInventoryPredicateAction wipa) {
                        setCurrentStatus(wipa.getDisplayName());
                        if (!waitForPrearmed(wipa, wipa.timeoutMs)) waitForPredicate(() -> wipa.matches(mc), wipa.timeoutMs);
                    }
                    else if (action instanceof WaitDurabilityAction wda) {
                        setCurrentStatus(wda.getDisplayName());
                        if (wda.useNext) waitForDurabilityUseNext(mc, wda);
                        else if (!waitForPrearmed(wda, wda.timeoutMs)) waitForPredicate(() -> wda.matches(mc), wda.timeoutMs);
                    }
                    else if (action instanceof WaitFreeSlotsAction wfsa) {
                        setCurrentStatus(wfsa.getDisplayName());
                        if (!waitForPrearmed(wfsa, wfsa.timeoutMs)) waitForPredicate(() -> wfsa.matches(mc), wfsa.timeoutMs);
                    }
                    else if (action instanceof WaitEntityTargetAction weta) {
                        setCurrentStatus(weta.getDisplayName());
                        if (!waitForPrearmed(weta, weta.timeoutMs)) waitForPredicate(() -> weta.matches(mc), weta.timeoutMs);
                    }
                    else if (action instanceof WaitGuiTypeAction wgta) {
                        setCurrentStatus(wgta.getDisplayName());
                        if (waitForPrearmed(wgta, wgta.timeoutMs)) {
                        } else if (wgta.waitMode == WaitGuiTypeAction.WaitMode.CLOSE) {
                            waitForPredicate(guiClosePredicate(wgta, mc), wgta.timeoutMs);
                        } else {
                            waitForPredicate(() -> wgta.matches(mc), wgta.timeoutMs);
                        }
                    }
                    else if (action instanceof WaitForHealthAction) {
                        WaitForHealthAction wh = (WaitForHealthAction) action;
                        setCurrentStatus(wh.waitingStatusText());
                        try {
                            if (!waitForPrearmed(wh, 0)) {
                                CompletableFuture<Void> future = MacroConditionRegistry.waitForHealth(
                                    wh.healthThreshold, wh.below);
                                waitForCondition(future);
                            }
                        } catch (CancellationException e) {

                        }
                    }
                    else if (action instanceof WaitForBlockAction) {
                        WaitForBlockAction wba = (WaitForBlockAction) action;
                        setCurrentStatus(wba.getDisplayName());
                        try {
                            if (!waitForPrearmed(wba, 0)) {
                                CompletableFuture<Void> future = MacroConditionRegistry.waitForBlock(wba);
                                waitForCondition(future);
                            }
                        } catch (CancellationException e) {

                        }
                    }
                    else if (action instanceof WaitForGuiAction) {
                        WaitForGuiAction wga = (WaitForGuiAction) action;
                        if (waitForPrearmed(wga, 0)) {
                        } else if (wga.waitMode == WaitForGuiAction.WaitMode.CLOSE) {
                            setCurrentStatus("Waiting for GUI close: " + wga.guiTitle);
                            waitForPredicate(guiClosePredicate(wga, mc), wga.timeoutMs);
                        } else {
                            setCurrentStatus("Waiting for GUI: " + wga.guiTitle);
                            waitForPredicate(() -> wga.checkGui(mc), wga.timeoutMs);
                        }
                    }
                    else if (action instanceof WaitForCooldownAction) {
                        WaitForCooldownAction wca = (WaitForCooldownAction) action;
                        ItemTarget cooldownTarget = resolveItemTarget(wca.itemTarget, wca.itemName);
                        String cooldownLabel = describeItemTarget(cooldownTarget);
                        setCurrentStatus("Waiting for cooldown: " +
                            (!cooldownLabel.isEmpty() ? cooldownLabel : (wca.checkMainInteractionHand ? "Main Hand" : "Off Hand")));
                        try {
                            if (!waitForPrearmed(wca, 0)) {
                                CompletableFuture<Void> future = MacroConditionRegistry.waitForCooldown(cooldownTarget, wca.checkMainInteractionHand);
                                waitForCondition(future);
                            }
                        } catch (CancellationException e) {

                        }
                    }
                    else if (action instanceof WaitPosAction) {
                        WaitPosAction wpa = (WaitPosAction) action;
                        setCurrentStatus("Waiting for Pos: " + String.format("%.0f, %.0f, %.0f", wpa.x, wpa.y, wpa.z));
                        try {
                            if (!waitForPrearmed(wpa, 0)) {
                                CompletableFuture<Void> future = MacroConditionRegistry.waitForPos(
                                    wpa.x, wpa.y, wpa.z, wpa.leeway, wpa.checkRotation, wpa.yaw, wpa.pitch, wpa.rotLeeway);
                                waitForCondition(future);
                            }
                        } catch (CancellationException e) {

                        }
                    }
                    else if (action instanceof JumpAction) {
                        JumpAction ja = (JumpAction) action;
                        mc.execute(() -> ja.execute(mc));
                        if (ja.tap) {
                            CompletableFuture<Void> tickFuture = MacroConditionRegistry.waitForNextTick();
                            waitForCondition(tickFuture);
                        } else {
                            for (int t = 0; t < ja.durationTicks && isCurrentRunActive(); t++) {
                                CompletableFuture<Void> tickFuture = MacroConditionRegistry.waitForNextTick();
                                waitForCondition(tickFuture);
                            }
                        }
                        mc.execute(() -> ja.release(mc));
                    }
                    else if (action instanceof SneakAction) {
                        SneakAction sna = (SneakAction) action;
                        mc.execute(() -> sna.execute(mc));
                        if (sna.persistent && sna.sneak) {
                            currentPersistentActions().removeIf(p -> p instanceof SneakAction);
                            currentPersistentActions().add(sna);
                        } else if (!sna.sneak) {
                            currentPersistentActions().removeIf(p -> p instanceof SneakAction);
                        }
                    }
                    else if (action instanceof SprintAction) {
                        SprintAction spa = (SprintAction) action;
                        mc.execute(() -> spa.execute(mc));
                        if (spa.persistent && spa.sprint) {
                            currentPersistentActions().removeIf(p -> p instanceof SprintAction);
                            currentPersistentActions().add(spa);
                        } else if (!spa.sprint) {
                            currentPersistentActions().removeIf(p -> p instanceof SprintAction);
                        }
                    }
                    else if (action instanceof DisconnectAction) {
                        DisconnectAction da2 = (DisconnectAction) action;

                        broadcastStepProgress(runState, i + 1, macro.actions.size(), macro.name);
                        if (da2.mode == DisconnectAction.DisconnectMode.DISCONNECT) {
                            if (da2.delayMs > 0) Thread.sleep(da2.delayMs);
                            mc.execute(() -> da2.execute(mc));
                        } else if (da2.mode == DisconnectAction.DisconnectMode.AUTO_DISCONNECT) {

                            setCurrentStatus("Auto Disconnect: waiting for " + da2.trigger.name() + "...");
                            da2.execute(mc);
                            setCurrentStatus("Auto Disconnect: executed");
                        } else if (da2.mode == DisconnectAction.DisconnectMode.KICK_DUPE && da2.useNextAction) {

                            java.util.List<MacroAction> nextActs = new java.util.ArrayList<>();
                            int lastSkip = -1;
                            for (int j = i + 1; j < macro.actions.size(); j++) {
                                MacroAction candidate = macro.actions.get(j);

                                if (candidate instanceof DisconnectAction) break;
                                if (!candidate.isEnabled()) continue;

                                if (candidate instanceof DelayAction
                                    || candidate instanceof WaitForHealthAction
                                    || candidate instanceof WaitForBlockAction
                                    || candidate instanceof WaitForGuiAction
                                    || candidate instanceof WaitForCooldownAction
                                    || candidate instanceof WaitPosAction
                                    || candidate instanceof WaitMovementAction
                                    || candidate instanceof WaitForEntityAction
                                    || candidate instanceof WaitForSoundAction
                                    || candidate instanceof WaitForSlotChangeAction
                                    || candidate instanceof WaitForPacketAction
                                    || candidate instanceof WaitForChatAction
                                    || candidate instanceof GoToAction) {
                                    continue;
                                }
                                nextActs.add(candidate);
                                lastSkip = j;
                            }
                            da2.setNextActions(nextActs);
                            mc.execute(() -> da2.execute(mc));

                            if (lastSkip > i) i = lastSkip;
                        } else {

                            mc.execute(() -> da2.execute(mc));
                        }
                        stopCurrentRun();
                        break;
                    }
                    else if (action instanceof RaceAction race) {
                        int bodyCount = runRaceGroup(mc, module, macro.actions, i, race);
                        if (bodyCount > 0) i += bodyCount;
                    }
                    else if (action instanceof ReportAction reportAction) {
                        int conditionCount = runReportGroup(mc, macro.actions, i, reportAction);
                        if (conditionCount > 0) i += conditionCount;
                    }
                    else if (action instanceof NbtBookAction nba) {
                        int totalBooks = Math.max(1, nba.bookCount);
                        long delayMs = Math.max(1, nba.delayTicks) * 50L;
                        boolean signedAny = false;
                        for (int b = 0; b < totalBooks; b++) {
                            if (!isCurrentRunActive()) break;
                            final int bookIdx = b;
                            java.util.concurrent.CompletableFuture<Boolean> result = new java.util.concurrent.CompletableFuture<>();
                            mc.execute(() -> result.complete(nba.executeSingleBook(mc, bookIdx, totalBooks)));
                            Boolean success = result.get();
                            if (!success) break;
                            signedAny = true;

                            if (b < totalBooks - 1) {
                                Thread.sleep(delayMs);
                            }
                        }
                        if (signedAny && nba.disconnectAfter) {
                            mc.execute(() -> nba.afterSigning(mc));
                        }
                    }
                    else if (action instanceof GoToAction) {
                        GoToAction ga = (GoToAction) action;
                        runGoToAction(mc, ga);
                    }

                    else if (action instanceof MoveAction) {
                        MoveAction ma = (MoveAction) action;
                        if (ma.nonBlocking) {

                            mc.execute(() -> ma.execute(mc));
                            currentBackgroundMoves().put(ma, new int[]{ma.durationTicks - 1});

                        } else {

                            for (int t = 0; t < ma.durationTicks && isCurrentRunActive(); t++) {
                                mc.execute(() -> ma.execute(mc));
                                CompletableFuture<Void> tickFuture = MacroConditionRegistry.waitForNextTick();
                                waitForCondition(tickFuture);
                            }
                            mc.execute(() -> ma.release(mc));
                        }
                    }
                    else if (action instanceof LookAtBlockAction) {
                        LookAtBlockAction la = (LookAtBlockAction) action;
                        LookAtBlockAction.RotationTarget rotationTarget = resolveLookAtTargetOnClient(mc, la);
                        if (rotationTarget == null) {
                            continue;
                        }
                        if (la.smooth) {
                            startSmoothRotation(rotationTarget.yaw(), rotationTarget.pitch(), la.getRotationStep());
                            if (la.waitForCompletion) {
                                waitForSmoothRotationCompletion();
                            }
                        } else {
                            mc.execute(() -> {
                                if (mc.player == null) return;
                                mc.player.setYRot(rotationTarget.yaw());
                                mc.player.setXRot(rotationTarget.pitch());
                            });
                        }
                    }
                    else if (action instanceof RepeatAction) {
                        RepeatAction ra = (RepeatAction) action;
                        int startIdx = i + 1;
                        int endIdx = Math.min(startIdx + ra.stepCount, macro.actions.size());

                        for (int rep = 0; rep < ra.repeatCount && isCurrentRunActive(); rep++) {
                            setCurrentStatus("Repeat " + (rep + 1) + "/" + ra.repeatCount);
                            for (int j = startIdx; j < endIdx && isCurrentRunActive(); j++) {
                                MacroAction subAction = macro.actions.get(j);
                                if (!subAction.isEnabled()) continue;

                                REPEAT_PACKET_CONTEXT.set(new RepeatPacketContext(macro, j + 1, endIdx));
                                REPEAT_PACKET_SKIP.set(0);
                                try {
                                    executeSingleActionWithWaits(mc, subAction, module);
                                    j += Math.max(0, REPEAT_PACKET_SKIP.get());
                                } finally {
                                    REPEAT_PACKET_SKIP.set(0);
                                    REPEAT_PACKET_CONTEXT.remove();
                                }
                            }
                        }
                        i = endIdx - 1;
                    }
                    else if (action instanceof WaitForChatAction) {
                        WaitForChatAction wca = (WaitForChatAction) action;
                        setCurrentStatus("Waiting for chat: " + wca.pattern);
                        if (!waitForPrearmed(wca, wca.timeoutMs)) awaitChat(wca);
                    }
                    else if (action instanceof WaitForEntityAction) {
                        WaitForEntityAction wea = (WaitForEntityAction) action;
                        setCurrentStatus(wea.getDisplayName());
                        try {
                            if (!waitForPrearmed(wea, 0)) {
                                waitForPredicate(() -> wea.matchesEntityTarget(mc), wea.timeoutMs);
                            }
                        } catch (CancellationException e) {
                        }
                    }
                    else if (action instanceof WaitForSoundAction) {
                        WaitForSoundAction wsa = (WaitForSoundAction) action;
                        String sndDesc = wsa.soundIds.isEmpty() ? "any" : wsa.soundIds.get(0);
                        setCurrentStatus("Waiting for sound: " + sndDesc);
                        try {
                            if (!waitForPrearmed(wsa, 0)) {
                                CompletableFuture<Void> future = MacroConditionRegistry.waitForSound(wsa);
                                waitForCondition(future);
                            }
                        } catch (CancellationException e) {
                        }
                    }
                    else if (action instanceof MineAction) {
                        MineAction mna = (MineAction) action;
                        runMineAction(mc, mna);
                    }
                    else if (action instanceof InstaBreakAction) {
                        runInstaBreakAction(mc, (InstaBreakAction) action);
                    }
                    else if (action instanceof BreakAction) {
                        runBreakAction(mc, (BreakAction) action);
                    }

                    else if (action instanceof WaitForSlotChangeAction) {
                        WaitForSlotChangeAction wsca = (WaitForSlotChangeAction) action;
                        setCurrentStatus(wsca.getDisplayName());
                        try {
                            if (!waitForPrearmed(wsca, 0)) {
                                CompletableFuture<Void> future = MacroConditionRegistry.waitForSlotChange(wsca);
                                waitForCondition(future);
                            }
                        } catch (CancellationException e) {
                        }
                    }
                    else if (action instanceof WaitForWorldChangeAction world) {
                        setCurrentStatus(world.getDisplayName());
                        try {
                            if (!waitForPrearmed(world, 0)) {
                                CompletableFuture<Void> future = MacroConditionRegistry.waitForWorldChange(world);
                                waitForCondition(future);
                            }
                        } catch (CancellationException e) {
                        }
                    }
                    else if (action instanceof WaitForPositionDeltaAction wpda) {
                        setCurrentStatus(wpda.getDisplayName());
                        try {
                            if (!waitForPrearmed(wpda, 0)) {
                                CompletableFuture<Void> future = MacroConditionRegistry.waitForPositionDelta(wpda.distance, wpda.horizontalOnly);
                                waitForCondition(future);
                            }
                        } catch (CancellationException e) {
                        }
                    }
                    else if (action instanceof WaitForTeleportAction teleport) {
                        setCurrentStatus(teleport.getDisplayName());
                        try {
                            if (!waitForPrearmed(teleport, 0)) {
                                CompletableFuture<?> future = raceConditionFuture(mc, teleport);
                                waitForFutureDone(future, 0);
                            }
                        } catch (CancellationException e) {
                        }
                    }
                    else if (action instanceof WaitGamemodeChangeAction gm) {
                        setCurrentStatus(gm.getDisplayName());
                        try {
                            if (!waitForPrearmed(gm, gm.timeoutMs)) {
                                CompletableFuture<Void> future = MacroConditionRegistry.waitForGamemodeChange(gm);
                                waitForFutureDone(future, gm.timeoutMs);
                            }
                        } catch (CancellationException e) {
                        }
                    }
                    else if (action instanceof WaitMovementAction wm) {
                        setCurrentStatus(wm.getDisplayName());
                        try {
                            if (!waitForPrearmed(wm, 0)) {
                                CompletableFuture<?> future = raceConditionFuture(mc, wm.resolveSubAction());
                                waitForFutureDone(future, 0);
                            }
                        } catch (CancellationException e) {
                        }
                    }
                    else if (action instanceof WaitForLanStepAction) {
                        WaitForLanStepAction wla = (WaitForLanStepAction) action;
                        setCurrentStatus(wla.getDisplayName());
                        try {
                            if (!waitForPrearmed(wla, 0)) {
                                CompletableFuture<Void> future = MacroConditionRegistry.waitForLanStep(wla);
                                waitForCondition(future);
                            }
                        } catch (CancellationException e) {
                        }
                    }
                    else if (action instanceof WaitForMacroStepAction) {
                        WaitForMacroStepAction wma = (WaitForMacroStepAction) action;
                        setCurrentStatus(wma.getDisplayName());
                        if (!waitForPrearmed(wma, wma.timeoutMs)) waitForMacroStep(wma);
                    }
                    else if (action instanceof StopMacroAction) {
                        setCurrentStatus("Stopping Macro");
                        StopMacroAction stopAction = (StopMacroAction) action;
                        if (stopAction.target != StopMacroAction.StopTarget.SELF) {
                            stopAction.execute(mc);
                            break;
                        }

                        broadcastStepProgress(runState, i + 1, macro.actions.size(), macro.name);
                        stopCurrentRun();
                        break;
                    }

                    else if (action instanceof TickSyncAction) {
                        TickSyncAction tsa = (TickSyncAction) action;

                        if (mc.level == null) continue;

                        java.util.List<net.minecraft.network.protocol.Packet<?>> preGenerated =
                            preGeneratePackets(macro, i + 1, tsa.preGenCount);

                        long targetTick = mc.level.getGameTime() + tsa.tickOffset;
                        setCurrentStatus("Tick Sync -> " + targetTick + " (" + preGenerated.size() + " pkts)");

                        while (isCurrentRunActive() && mc.level.getGameTime() < targetTick) {
                            java.util.concurrent.locks.LockSupport.parkNanos(1_000_000L);
                        }

                        if (isCurrentRunActive()) {
                            executePreGeneratedBurst(mc, preGenerated);
                            i += countPreGeneratedActions(macro, i + 1, preGenerated.size());
                            AutismClientMessaging.sendPrefixed("Tick sync: " + preGenerated.size() + " pkts sent");
                        }
                    }
                    else if (action instanceof RevisionSyncAction) {
                        RevisionSyncAction rsa = (RevisionSyncAction) action;

                        if (mc.player == null || mc.player.containerMenu == null) continue;

                        java.util.List<net.minecraft.network.protocol.Packet<?>> preGenerated =
                            preGeneratePackets(macro, i + 1, rsa.preGenCount);

                        int baseRevision = mc.player.containerMenu.getStateId();
                        int targetRevision = baseRevision + rsa.revisionOffset;
                        setCurrentStatus("Rev Sync -> " + targetRevision + " (" + preGenerated.size() + " pkts)");

                        while (isCurrentRunActive()) {
                            if (mc.player == null || mc.player.containerMenu == null) break;
                            if (mc.player.containerMenu.getStateId() >= targetRevision) break;
                            java.util.concurrent.locks.LockSupport.parkNanos(500_000L);
                        }

                        if (isCurrentRunActive() && mc.player != null && mc.player.containerMenu != null) {
                            executePreGeneratedBurst(mc, preGenerated);
                            i += countPreGeneratedActions(macro, i + 1, preGenerated.size());
                            AutismClientMessaging.sendPrefixed("Rev sync: " + preGenerated.size() + " pkts sent");
                        }
                    }
                    else if (action instanceof ServerTickSyncAction) {
                        ServerTickSyncAction stsa = (ServerTickSyncAction) action;

                        if (mc.getConnection() == null) continue;

                        java.util.List<net.minecraft.network.protocol.Packet<?>> preGenerated =
                            preGeneratePackets(macro, i + 1, stsa.preGenCount);

                        int sampleWaitMs = 0;
                        while (isCurrentRunActive() && !ServerTickTracker.isReady() && sampleWaitMs < stsa.maxWaitMs) {

                            float progress = ServerTickTracker.getWarmupProgress();
                            setCurrentStatus(String.format("ServerSync warmup %.0f%% (%d samples, %dms)",
                                progress * 100, ServerTickTracker.getSampleCount(), ServerTickTracker.getTrackingTimeMs()));
                            Thread.sleep(50);
                            sampleWaitMs += 50;
                        }

                        if (!isCurrentRunActive()) continue;

                        long optimalTime = ServerTickTracker.getOptimalSendTime(stsa.bufferMs, stsa.ignorePing);
                        long msUntil = (optimalTime - System.nanoTime()) / 1_000_000L;
                        int ping = ServerTickTracker.getPingMs();
                        String pingStr = stsa.ignorePing ? " (no ping)" : " ping:" + ping + "ms";
                        setCurrentStatus("ServerSync in " + Math.max(0, msUntil) + "ms" + pingStr + " (" + preGenerated.size() + " pkts)");

                        long startWait = System.nanoTime();
                        long maxWaitNanos = stsa.maxWaitMs * 1_000_000L;

                        while (isCurrentRunActive()) {
                            long now = System.nanoTime();
                            if ((now - startWait) >= maxWaitNanos) break;
                            if (now >= optimalTime) break;

                            long remaining = optimalTime - now;
                            if (remaining > 2_000_000L) {
                                java.util.concurrent.locks.LockSupport.parkNanos(remaining - 2_000_000L);
                            } else {
                                Thread.onSpinWait();
                            }
                        }

                        if (isCurrentRunActive()) {
                            executePreGeneratedBurst(mc, preGenerated);
                            i += countPreGeneratedActions(macro, i + 1, preGenerated.size());
                            long actualDelay = (System.nanoTime() - optimalTime) / 1_000_000L;
                            AutismClientMessaging.sendPrefixed("ServerSync: " + preGenerated.size() + " pkts (+" + actualDelay + "ms)");
                        }
                    }
                    else if (action instanceof RotateAction) {
                        RotateAction ra = (RotateAction) action;
                        if (ra.smooth) {
                            startSmoothRotation(ra.yaw, ra.pitch, ra.getRotationStep());
                            if (ra.waitForCompletion) {
                                waitForSmoothRotationCompletion();
                            }
                        } else {
                            mc.execute(() -> action.execute(mc));
                        }
                    }
                    else if (action instanceof LookAtBlockAction la) {
                        LookAtBlockAction.RotationTarget rotationTarget = resolveLookAtTargetOnClient(mc, la);
                        if (rotationTarget == null) {
                            continue;
                        }
                        if (la.smooth) {
                            startSmoothRotation(rotationTarget.yaw(), rotationTarget.pitch(), la.getRotationStep());
                            if (la.waitForCompletion) {
                                waitForSmoothRotationCompletion();
                            }
                        } else {
                            mc.execute(() -> {
                                if (mc.player == null) return;
                                mc.player.setYRot(rotationTarget.yaw());
                                mc.player.setXRot(rotationTarget.pitch());
                            });
                        }
                    }
                    else if (action instanceof UseItemAction) {
                        UseItemAction ua = (UseItemAction) action;
                        if (ua.useMode == UseItemAction.UseMode.CUSTOM_HOLD) {

                            java.util.concurrent.CountDownLatch uaLatch = new java.util.concurrent.CountDownLatch(1);
                            mc.execute(() -> {
                                try { ua.execute(mc); }
                                catch (Exception e) { AutismClientMessaging.sendPrefixed("§cError in UseItem: " + e.getMessage()); }
                                finally { uaLatch.countDown(); }
                            });
                            uaLatch.await(200, TimeUnit.MILLISECONDS);
                            if (ua.holdTicks > 0) {
                                for (int t = 0; t < ua.holdTicks && isCurrentRunActive(); t++) {
                                    CompletableFuture<Void> tf = MacroConditionRegistry.waitForNextTick();
                                    waitForCondition(tf);
                                }
                                java.util.concurrent.CountDownLatch relLatch = new java.util.concurrent.CountDownLatch(1);
                                mc.execute(() -> {
                                    try { ua.sendRelease(mc); }
                                    catch (Exception e) { AutismClientMessaging.sendPrefixed("§cError in UseItem release: " + e.getMessage()); }
                                    finally { relLatch.countDown(); }
                                });
                                relLatch.await(200, TimeUnit.MILLISECONDS);
                                if (ua.waitForFinish) {
                                    waitForUseItemFinish(mc, ua.holdTicks + 8);
                                }
                            }
                        } else {

                            int times = Math.max(1, ua.useCount);
                            for (int u = 0; u < times && isCurrentRunActive(); u++) {
                                java.util.concurrent.CountDownLatch uaLatch = new java.util.concurrent.CountDownLatch(1);
                                mc.execute(() -> {
                                    try { ua.execute(mc); }
                                    catch (Exception e) { AutismClientMessaging.sendPrefixed("§cError in UseItem: " + e.getMessage()); }
                                    finally { uaLatch.countDown(); }
                                });
                                uaLatch.await(200, TimeUnit.MILLISECONDS);
                                if (ua.waitForFinish) {
                                    waitForUseItemFinish(mc, 0);
                                }

                                if (u < times - 1 && isCurrentRunActive()) {
                                    CompletableFuture<Void> tf = MacroConditionRegistry.waitForNextTick();
                                    waitForCondition(tf);
                                }
                            }
                        }
                    }
                    else if (action instanceof ItemAction) {
                        ItemAction ia = (ItemAction) action;
                        if (ia.waitForItem) {
                            try {
                                waitForItemActionTargets(mc, ia);
                            } catch (java.util.concurrent.CancellationException e) { }
                        }
                        postGuiFuture = createPostGuiFuture(mc, ia);
                        runMacroActionOnClientThread(mc, action, "ItemAction");
                    }
                    else if (action instanceof DropAction || action instanceof SwapSlotsAction) {
                        if (action instanceof DropAction dropAction) {
                            postGuiFuture = createPostGuiFuture(mc, dropAction);
                        }
                        runMacroActionOnClientThread(mc, action, action.getDisplayName());
                    }
                    else if (action instanceof CraftAction craftAction) {
                        runCraftAction(mc, craftAction);
                    }
                    else if (action instanceof SendPacketAction) {

                        if (module != null && module.useInstantExecutionMode()) {

                            try {
                                action.execute(mc);
                            } catch (Exception e) {
                                AutismClientMessaging.sendPrefixed("§cError in SendPacket: " + e.getMessage());
                            }
                        } else {

                            mc.execute(() -> {
                                try {
                                    action.execute(mc);
                                } catch (Exception e) {
                                    AutismClientMessaging.sendPrefixed("§cError in SendPacket: " + e.getMessage());
                                }
                            });
                        }
                    }
                    else if (action instanceof autismclient.util.macro.StoreItemAction) {
                        autismclient.util.macro.StoreItemAction sia = (autismclient.util.macro.StoreItemAction) action;
                        setCurrentStatus((sia.mode == autismclient.util.macro.StoreItemAction.Mode.LOOT ? "Looting" : "Storing")
                            + (sia.persistent ? " \u221e" : ""));
                        if (sia.persistent) {

                            while (isCurrentRunActive()) {
                                try { runStoreItemAction(mc, sia); }
                                catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                                catch (Exception e) { AutismClientMessaging.sendPrefixed("Store error: " + e.getMessage()); }

                                CompletableFuture<Void> siTick = MacroConditionRegistry.waitForNextTick();
                                waitForCondition(siTick);
                            }
                        } else {
                            try { runStoreItemAction(mc, sia); }
                            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                            catch (Exception e) { AutismClientMessaging.sendPrefixed("Store error: " + e.getMessage()); }
                        }
                    }
                    else if (action instanceof ClickAction) {
                        ClickAction ca = (ClickAction) action;
                        runClickActionBurst(mc, ca);
                    }
                    else if (action instanceof PayAction) {
                        runPayAction(mc, (PayAction) action);
                    }
                    else if (action instanceof XCarryAction xsa) {
                        if (xsa.mode == XCarryAction.Mode.PUT_IN
                                && xsa.transferMode == XCarryAction.TransferMode.CLICK) {
                            setCurrentStatus("XCarry Click");
                            runXCarryClickPutIn(mc, xsa);
                        } else {
                            setCurrentStatus(switch (xsa.mode) {
                                case PUT_IN -> "XCarry";
                                case TAKE_OUT -> "XCarry Out";
                                case DROP -> "XCarry Drop";
                            });
                            runOnClientThread(mc, () -> {
                                try {
                                    xsa.execute(mc);
                                } catch (Exception e) {
                                    AutismClientMessaging.sendPrefixed("§cXCarry: " + e.getMessage());
                                }
                            });
                        }
                    }
                    else if (action instanceof InventoryAuditAction auditAction
                            && (auditAction.mode == InventoryAuditAction.Mode.DUPE
                                || auditAction.mode == InventoryAuditAction.Mode.DUPE_SPAM)) {
                        setCurrentStatus("Dupe: " + auditAction.dupeVector.name() + " " + auditAction.openMode.name());
                        try {
                            auditAction.executeDupe(mc);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        } catch (Exception e) {
                            AutismClientMessaging.sendPrefixed("§cDupe error: " + e.getMessage());
                        }
                    }
                    else if (action instanceof BundleDupeV2Action bundleV2) {
                        setCurrentStatus("Bundle Dupe V2");
                        bundleV2.execute(mc);
                    }
                    else if (action instanceof PacketGateAction pga) {
                        runPacketGateAction(mc, pga);
                    }
                    else if (isTargetWaitInteractionAction(action)) {
                        postGuiFuture = runTargetWaitInteractionAction(mc, action);
                    }
                    else if (action instanceof MacroVariablesAction || action instanceof AssertAction) {
                        action.execute(mc);
                    }
                    else if (action instanceof PlaceAction placeInteract && placeInteract.interact) {

                        runPlaceAction(mc, placeInteract);
                    }
                    else if (action instanceof PlaceAction place && place.waitForItem) {

                        ItemTarget placeTarget = place.resolvedItemTarget();
                        if (placeTarget != null && (placeTarget.hasIdentity() || placeTarget.hasSlot())) {
                            setCurrentStatus("Waiting for " + describeItemTarget(placeTarget));
                            try {
                                waitForItemActionEntry(mc, placeTarget);
                            } catch (java.util.concurrent.CancellationException e) {
                            }
                        }
                        if (isCurrentRunActive()) {
                            final PlaceAction fPlace = place;
                            mc.execute(() -> {
                                try {
                                    fPlace.execute(mc);
                                } catch (Exception e) {
                                    AutismClientMessaging.sendPrefixed("§cError in Place: " + e.getMessage());
                                }
                            });
                        }
                    }
                    else {

                        if (module != null && module.useInstantExecutionMode()) {

                            java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
                            mc.execute(() -> {
                                try {
                                    action.execute(mc);
                                } catch (Exception e) {
                                    AutismClientMessaging.sendPrefixed("§cError in action: " + e.getMessage());
                                } finally {
                                    latch.countDown();
                                }
                            });

                            try {
                                latch.await(100, TimeUnit.MILLISECONDS);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        } else {

                            mc.execute(() -> {
                                try {
                                    action.execute(mc);
                                } catch (Exception e) {
                                    AutismClientMessaging.sendPrefixed("§cError in action: " + e.getMessage());
                                }
                            });
                        }
                    }

                    if (postGuiFuture != null && isCurrentRunActive()) {
                        try {
                            waitForCondition(postGuiFuture);
                        } catch (java.util.concurrent.CancellationException e) { }
                    }

                    if (isCurrentRunActive()) {
                        markStepCompleted(i + 1);
                        broadcastStepProgress(runState, i + 1, macro.actions.size(), macro.name);
                    }

                    if (isCurrentRunActive() && !isInstantInventoryAction(action)) {
                        if (module != null && module.useInstantExecutionMode()) {
                            int delayUs = module.getActionDelayUs();
                            if (delayUs > 0) {

                                java.util.concurrent.locks.LockSupport.parkNanos(delayUs * 1000L);
                            } else {

                                Thread.onSpinWait();
                            }
                        }
                    }
                    } finally {
                        releaseClientResources(heldResources);
                    }
                }

                if (macro.actions.isEmpty()) {
                    if (!runState.options.silentLifecycle()) AutismClientMessaging.sendPrefixed("§cMacro stopped: no actions left.");
                    stopCurrentRun();
                    break;
                }

                if (isCurrentRunActive()) {
                    PacketGateManager.clearAllAndFlushConfigured(mc == null ? null : mc.getConnection());
                }

                if (macro.loop && loopCount < maxLoops - 1 && isCurrentRunActive()) {
                    if (module != null && module.useInstantExecutionMode()) {

                        int delayUs = module.getActionDelayUs();
                        if (delayUs > 0) {
                            java.util.concurrent.locks.LockSupport.parkNanos(delayUs * 1000L);
                        } else {
                            Thread.onSpinWait();
                        }
                    } else if (module != null && module.useMsSleepMode()) {

                        int sleepMs = module.getMsSleepInterval();
                        Thread.sleep(sleepMs);
                    } else {

                        try {
                            CompletableFuture<Void> syncFuture = MacroConditionRegistry.waitForNextTick();
                            waitForCondition(syncFuture);
                        } catch (CancellationException e) {

                        }
                    }
                }

                loopCount++;
            }

            if (isCurrentRunActive()) {
                if (!runState.options.silentLifecycle()) AutismClientMessaging.sendPrefixed("§aMacro finished: " + macro.name);
            }

        } catch (InterruptedException e) {

        } catch (Exception e) {
            if (!runState.options.silentLifecycle()) AutismClientMessaging.sendPrefixed("§cMacro crashed: " + e.getMessage());
            AutismClientAddon.LOG.warn("[Autism] Macro crashed: {}", macro.name, e);
        } finally {
            runFinallyActions(runState, mc);
            PacketGateManager.clearAllAndFlushConfigured(mc == null ? null : mc.getConnection());
            finishCurrentRun();
            isRotating.set(false);
            rotationAlignedFrames = 0;
            if (!isRunning.get()) MacroConditionRegistry.cancelAll();

            broadcastStepProgress(runState, -1, 0, "");
        }
    }

    private static String normalizePacketKey(String value) {
        if (value == null || value.isEmpty()) return "";
        return value.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static boolean packetNameMatches(String expected, String candidate) {
        if (expected == null || expected.isEmpty() || candidate == null || candidate.isEmpty()) return false;

        String normalizedExpected = normalizePacketKey(expected);
        String normalizedCandidate = normalizePacketKey(candidate);
        if (normalizedExpected.isEmpty() || normalizedCandidate.isEmpty()) return false;
        if (normalizedExpected.equals(normalizedCandidate)) return true;

        if (normalizedCandidate.endsWith("packet")) {
            String strippedCandidate = normalizedCandidate.substring(0, normalizedCandidate.length() - "packet".length());
            if (normalizedExpected.equals(strippedCandidate)) return true;
        }

        return normalizedExpected.endsWith(normalizedCandidate) || normalizedCandidate.endsWith(normalizedExpected);
    }

    private static boolean matchesPacketTarget(String target, Packet<?> packet, String direction) {
        if (packet == null) return false;

        String expectedDirection = WaitForPacketAction.getDirection(target);
        if (!expectedDirection.isEmpty() && !expectedDirection.equalsIgnoreCase(direction)) return false;

        String expectedName = WaitForPacketAction.getPacketName(target);
        if (expectedName.isEmpty()) return true;

        String friendlyDirectional = AutismPacketNamer.getFriendlyName(packet, direction);
        if (packetNameMatches(expectedName, friendlyDirectional)) return true;

        String friendlyGeneric = AutismPacketNamer.getFriendlyName(packet);
        if (packetNameMatches(expectedName, friendlyGeneric)) return true;

        String simpleName = packet.getClass().getSimpleName();
        if (packetNameMatches(expectedName, simpleName)) return true;

        @SuppressWarnings("unchecked")
        String fullName = AutismPacketRegistry.getName((Class<? extends Packet<?>>) packet.getClass());
        return packetNameMatches(expectedName, fullName);
    }

    private static void awaitPacket(String target) {
        RunState run = CURRENT_RUN.get();
        if (run != null) {
            CompletableFuture<Void> future = new CompletableFuture<>();
            String normalizedTarget = WaitForPacketAction.normalizeTarget(target);
            synchronized (run.packetWaitLock) {
                run.waitingPacketName.set(normalizedTarget);
                run.packetFuture = future;
                run.waitingForPacket.set(true);
            }
            try {
                setCurrentStatus(normalizedTarget.isEmpty()
                    ? "Waiting for packet: Any"
                    : "Waiting for packet: " + WaitForPacketAction.getDisplayLabel(normalizedTarget));
                waitForCondition(future);
            } finally {
                synchronized (run.packetWaitLock) {
                    if (run.packetFuture == future) {
                        run.waitingForPacket.set(false);
                        run.waitingPacketName.set("");
                        run.packetFuture = null;
                    }
                }
            }
            return;
        }
        CompletableFuture<Void> future = new CompletableFuture<>();
        String normalizedTarget = WaitForPacketAction.normalizeTarget(target);
        synchronized (packetWaitLock) {
            waitingPacketName.set(normalizedTarget);
            lastReceivedPacket.set("");
            activePacketFuture = future;
            isWaitingForPacket.set(true);
        }
        try {
            setCurrentStatus(normalizedTarget.isEmpty()
                ? "Waiting for packet: Any"
                : "Waiting for packet: " + WaitForPacketAction.getDisplayLabel(normalizedTarget));
            waitForCondition(future);
        } finally {
            synchronized (packetWaitLock) {
                if (activePacketFuture == future) {
                    isWaitingForPacket.set(false);
                    waitingPacketName.set("");
                    activePacketFuture = null;
                }
            }
        }
    }

    private static void awaitChat(WaitForChatAction wca) {
        RunState run = CURRENT_RUN.get();
        if (run != null) {
            CompletableFuture<Void> future = new CompletableFuture<>();
            run.chatFuture = future;
            run.waitingChatAction = wca;
            run.waitingForChat.set(true);
            try {
                if (wca.timeoutMs > 0) future.completeOnTimeout(null, wca.timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
                waitForCondition(future);
            } finally {
                if (run.chatFuture == future) {
                    run.waitingForChat.set(false);
                    run.waitingChatAction = null;
                    run.chatFuture = null;
                }
            }
            return;
        }
        CompletableFuture<Void> future = new CompletableFuture<>();
        activeChatFuture = future;
        isWaitingForChat.set(true);
        waitingChatPattern.set(normalizeManualText(wca.pattern));
        waitingChatPatternJson.set(wca.patternJson == null ? "" : wca.patternJson);
        waitingChatIsRegex.set(wca.useRegex);
        waitingChatFuzzyPercent.set(WaitForChatAction.clampFuzzyPercent(wca.fuzzyPercent));
        lastMatchedChat.set("");
        try {
            if (wca.timeoutMs > 0) future.completeOnTimeout(null, wca.timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            waitForCondition(future);
        } finally {
            isWaitingForChat.set(false);
            waitingChatPattern.set("");
            waitingChatPatternJson.set("");
            waitingChatIsRegex.set(false);
            waitingChatFuzzyPercent.set(100);
            activeChatFuture = null;
        }
    }

    private static void waitForCondition(CompletableFuture<Void> future) {

        Thread t = Thread.currentThread();
        future.whenComplete((v, e) -> java.util.concurrent.locks.LockSupport.unpark(t));
        while (!future.isDone() && isCurrentRunActive()) {

            java.util.concurrent.locks.LockSupport.parkNanos(MACRO_WAIT_POLL_NANOS);
            if (Thread.interrupted()) break;
        }
        if (!isCurrentRunActive()) {
            future.cancel(true);
        }
    }

    private static void waitForPredicate(java.util.function.BooleanSupplier predicate, int timeoutMs) {
        long start = System.currentTimeMillis();
        while (isCurrentRunActive()) {
            try {
                if (predicate.getAsBoolean()) return;
            } catch (Throwable ignored) {}
            if (timeoutMs > 0 && System.currentTimeMillis() - start >= timeoutMs) return;
            CompletableFuture<Void> tick = MacroConditionRegistry.waitForNextTick();
            waitForCondition(tick);
        }
    }

    private static java.util.function.BooleanSupplier guiClosePredicate(WaitForGuiAction action, Minecraft mc) {
        AtomicBoolean sawMatchingGui = new AtomicBoolean(action.checkGui(mc));
        return () -> {
            boolean matches = action.checkGui(mc);
            if (matches) {
                sawMatchingGui.set(true);
                return false;
            }
            return sawMatchingGui.get();
        };
    }

    private static java.util.function.BooleanSupplier guiClosePredicate(WaitGuiTypeAction action, Minecraft mc) {
        AtomicBoolean sawMatchingGui = new AtomicBoolean(action.matches(mc));
        return () -> {
            boolean matches = action.matches(mc);
            if (matches) {
                sawMatchingGui.set(true);
                return false;
            }
            return sawMatchingGui.get();
        };
    }

    private static void waitForDurabilityUseNext(Minecraft mc, WaitDurabilityAction action) throws InterruptedException {
        AtomicReference<DurabilityUseNextSession> sessionRef = new AtomicReference<>();
        long start = System.currentTimeMillis();
        while (isCurrentRunActive()) {
            if (Boolean.TRUE.equals(callOnClientThread(mc, () -> tickDurabilityUseNext(mc, action, sessionRef), Boolean.FALSE))) {
                return;
            }
            if (action.timeoutMs > 0 && System.currentTimeMillis() - start >= action.timeoutMs) return;
            waitOneTick();
        }
    }

    private static boolean durabilityObservationMatches(Minecraft mc, WaitDurabilityAction action) {
        if (action == null || !action.useNext) return action != null && action.matches(mc);
        DurabilityUseNextSession session = createDurabilityUseNextSession(mc, action);
        if (session == null || session.fallbackOnly) return action.matches(mc);
        if (session.firstSlotPending) {
            ItemStack stack = stackForVisibleSlot(mc, session.activeSlot);
            if (session.lockedRegistryId.isBlank() && canLockDurabilityInitialStack(action, session, stack)) {
                session.lockedRegistryId = stackRegistryId(stack);
            }
            if (session.lockedRegistryId.isBlank()) return false;
            return isSameDamageableItem(stack, session.lockedRegistryId)
                && action.matchesStack(stack)
                && findDurabilityReplacement(mc, action, session, session.activeSlot) == null;
        }
        if (session.lockedRegistryId.isBlank()) return action.matches(mc);
        return findDurabilityReplacement(mc, action, session, -1) == null;
    }

    private static boolean tickDurabilityUseNext(Minecraft mc, WaitDurabilityAction action, AtomicReference<DurabilityUseNextSession> sessionRef) {
        if (mc == null || mc.player == null || action == null) return false;
        DurabilityUseNextSession session = sessionRef.get();
        if (session == null) {
            session = createDurabilityUseNextSession(mc, action);
            sessionRef.set(session);
        }
        if (session == null || session.fallbackOnly) return action.matches(mc);

        if (session.firstSlotPending) {
            return tickDurabilityInitialSlot(mc, action, session);
        }

        adoptCurrentSameItem(mc, session);
        ItemStack active = stackForVisibleSlot(mc, session.activeSlot);
        if (isSameDamageableItem(active, session.lockedRegistryId) && !action.matchesStack(active)) {
            return false;
        }
        return switchToNextDurabilityItemOrComplete(mc, action, session, session.activeSlot);
    }

    private static boolean tickDurabilityInitialSlot(Minecraft mc, WaitDurabilityAction action, DurabilityUseNextSession session) {
        ItemStack stack = stackForVisibleSlot(mc, session.activeSlot);
        if (session.lockedRegistryId.isBlank() && canLockDurabilityInitialStack(action, session, stack)) {
            session.lockedRegistryId = stackRegistryId(stack);
        }

        if (!session.lockedRegistryId.isBlank() && isSameDamageableItem(stack, session.lockedRegistryId)) {
            session.observedInitialSlot = true;
            if (!action.matchesStack(stack)) return false;
            session.firstSlotPending = false;
            return switchToNextDurabilityItemOrComplete(mc, action, session, session.activeSlot);
        }

        if (session.observedInitialSlot) {
            session.firstSlotPending = false;
            return switchToNextDurabilityItemOrComplete(mc, action, session, session.activeSlot);
        }
        return false;
    }

    private static boolean switchToNextDurabilityItemOrComplete(Minecraft mc, WaitDurabilityAction action, DurabilityUseNextSession session, int excludeSlot) {
        DurabilityCandidate replacement = findDurabilityReplacement(mc, action, session, excludeSlot);
        if (replacement == null) return true;
        if (!switchToDurabilityReplacement(mc, session, replacement)) return false;
        return false;
    }

    private static DurabilityUseNextSession createDurabilityUseNextSession(Minecraft mc, WaitDurabilityAction action) {
        if (mc == null || mc.player == null || action == null) return null;
        DurabilityUseNextSession session = new DurabilityUseNextSession();
        session.preferredHotbarSlot = Math.max(0, Math.min(8, mc.player.getInventory().getSelectedSlot()));
        ItemTarget target = action.targetMode == WaitDurabilityAction.TargetMode.ITEM
            ? ItemTarget.fromLegacyEntry(action.itemName)
            : new ItemTarget();
        session.initialTarget = target;

        switch (action.targetMode) {
            case SLOT -> {
                session.activeSlot = Math.max(0, Math.min(40, action.slot));
                session.firstSlotPending = true;
                ItemStack stack = stackForVisibleSlot(mc, session.activeSlot);
                if (canLockDurabilityInitialStack(action, session, stack)) {
                    session.lockedRegistryId = stackRegistryId(stack);
                    session.observedInitialSlot = true;
                }
            }
            case HELD -> initializeHeldDurabilitySession(mc, action, session);
            case ITEM -> initializeItemDurabilitySession(mc, action, session, target);
        }

        if (session.lockedRegistryId == null) session.lockedRegistryId = "";
        if (session.lockedRegistryId.isBlank() && !session.firstSlotPending) {
            session.fallbackOnly = true;
        }
        return session;
    }

    private static void initializeHeldDurabilitySession(Minecraft mc, WaitDurabilityAction action, DurabilityUseNextSession session) {
        int selected = Math.max(0, Math.min(8, mc.player.getInventory().getSelectedSlot()));
        ItemStack main = mc.player.getInventory().getItem(selected);
        if (main != null && !main.isEmpty() && main.isDamageableItem()) {
            session.activeSlot = selected;
            session.lockedRegistryId = stackRegistryId(main);
            return;
        }
        ItemStack offhand = mc.player.getOffhandItem();
        if (offhand != null && !offhand.isEmpty() && offhand.isDamageableItem()) {
            session.activeSlot = 40;
            session.lockedRegistryId = stackRegistryId(offhand);
            return;
        }
        session.fallbackOnly = true;
    }

    private static void initializeItemDurabilitySession(Minecraft mc, WaitDurabilityAction action, DurabilityUseNextSession session, ItemTarget target) {
        if (target != null && target.hasSlot()) {
            session.activeSlot = Math.max(0, Math.min(40, target.slot));
            session.firstSlotPending = true;
            String explicitId = target.hasRegistryId() ? canonicalItemRegistryId(target.registryId) : "";
            if (!explicitId.isBlank()) session.lockedRegistryId = explicitId;
            ItemStack stack = stackForVisibleSlot(mc, session.activeSlot);
            if (canLockDurabilityInitialStack(action, session, stack)) {
                session.lockedRegistryId = stackRegistryId(stack);
                session.observedInitialSlot = true;
            }
            return;
        }

        if (target != null && target.hasRegistryId()) {
            session.lockedRegistryId = canonicalItemRegistryId(target.registryId);
            session.activeSlot = findCurrentUsableDurabilitySlot(mc, action, session.lockedRegistryId, target);
            return;
        }

        if (target == null || !target.hasIdentity()) {
            initializeHeldDurabilitySession(mc, action, session);
            return;
        }

        int slot = findInitialDurabilitySlot(mc, action, "", target);
        if (slot >= 0) {
            session.activeSlot = slot;
            session.lockedRegistryId = stackRegistryId(stackForVisibleSlot(mc, slot));
        } else {
            session.fallbackOnly = true;
        }
    }

    private static int findCurrentUsableDurabilitySlot(Minecraft mc, WaitDurabilityAction action, String lockedRegistryId, ItemTarget target) {
        if (mc == null || mc.player == null) return -1;
        int selected = Math.max(0, Math.min(8, mc.player.getInventory().getSelectedSlot()));
        if (initialDurabilitySlotMatches(mc, action, lockedRegistryId, target, selected)) return selected;
        return initialDurabilitySlotMatches(mc, action, lockedRegistryId, target, 40) ? 40 : -1;
    }

    private static boolean canLockDurabilityInitialStack(WaitDurabilityAction action, DurabilityUseNextSession session, ItemStack stack) {
        if (stack == null || stack.isEmpty() || !stack.isDamageableItem()) return false;
        if (action != null && action.targetMode == WaitDurabilityAction.TargetMode.ITEM
            && session != null && session.initialTarget != null && session.initialTarget.hasIdentity()) {
            return session.initialTarget.score(stack, session.activeSlot) >= 0;
        }
        return true;
    }

    private static int findInitialDurabilitySlot(Minecraft mc, WaitDurabilityAction action, String lockedRegistryId, ItemTarget target) {
        if (mc == null || mc.player == null) return -1;
        int selected = Math.max(0, Math.min(8, mc.player.getInventory().getSelectedSlot()));
        if (initialDurabilitySlotMatches(mc, action, lockedRegistryId, target, selected)) return selected;
        for (int slot = 0; slot < 9; slot++) {
            if (slot != selected && initialDurabilitySlotMatches(mc, action, lockedRegistryId, target, slot)) return slot;
        }
        for (int slot = 9; slot < Math.min(36, mc.player.getInventory().getContainerSize()); slot++) {
            if (initialDurabilitySlotMatches(mc, action, lockedRegistryId, target, slot)) return slot;
        }
        return initialDurabilitySlotMatches(mc, action, lockedRegistryId, target, 40) ? 40 : -1;
    }

    private static boolean initialDurabilitySlotMatches(Minecraft mc, WaitDurabilityAction action, String lockedRegistryId, ItemTarget target, int slot) {
        ItemStack stack = stackForVisibleSlot(mc, slot);
        if (stack == null || stack.isEmpty() || !stack.isDamageableItem()) return false;
        if (lockedRegistryId != null && !lockedRegistryId.isBlank() && !isSameDamageableItem(stack, lockedRegistryId)) return false;
        return target == null || !target.hasIdentity() || target.score(stack, slot) >= 0;
    }

    private static void adoptCurrentSameItem(Minecraft mc, DurabilityUseNextSession session) {
        if (mc == null || mc.player == null || session == null || session.lockedRegistryId.isBlank()) return;
        int selected = Math.max(0, Math.min(8, mc.player.getInventory().getSelectedSlot()));
        if (isSameDamageableItem(mc.player.getInventory().getItem(selected), session.lockedRegistryId)) {
            session.activeSlot = selected;
            session.preferredHotbarSlot = selected;
            return;
        }
        if (isSameDamageableItem(mc.player.getOffhandItem(), session.lockedRegistryId)) {
            ItemStack active = stackForVisibleSlot(mc, session.activeSlot);
            if (!isSameDamageableItem(active, session.lockedRegistryId)) session.activeSlot = 40;
        }
    }

    private static DurabilityCandidate findDurabilityReplacement(Minecraft mc, WaitDurabilityAction action, DurabilityUseNextSession session, int excludeSlot) {
        if (mc == null || mc.player == null || action == null || session == null || session.lockedRegistryId.isBlank()) return null;
        DurabilityCandidate hotbar = null;
        DurabilityCandidate inventory = null;
        DurabilityCandidate offhand = null;
        for (int slot = 0; slot < 9; slot++) {
            DurabilityCandidate candidate = durabilityCandidate(mc, action, session.lockedRegistryId, slot, excludeSlot);
            if (candidate != null && (hotbar == null || candidate.score() > hotbar.score())) hotbar = candidate;
        }
        int size = Math.min(36, mc.player.getInventory().getContainerSize());
        for (int slot = 9; slot < size; slot++) {
            DurabilityCandidate candidate = durabilityCandidate(mc, action, session.lockedRegistryId, slot, excludeSlot);
            if (candidate != null && (inventory == null || candidate.score() > inventory.score())) inventory = candidate;
        }
        offhand = durabilityCandidate(mc, action, session.lockedRegistryId, 40, excludeSlot);
        if (hotbar != null) return hotbar;
        if (inventory != null) return inventory;
        return offhand;
    }

    private static DurabilityCandidate durabilityCandidate(Minecraft mc, WaitDurabilityAction action, String registryId, int slot, int excludeSlot) {
        if (slot == excludeSlot) return null;
        ItemStack stack = stackForVisibleSlot(mc, slot);
        if (!isSameDamageableItem(stack, registryId)) return null;
        if (action.matchesStack(stack)) return null;
        return new DurabilityCandidate(slot, remainingDurability(stack));
    }

    private static boolean switchToDurabilityReplacement(Minecraft mc, DurabilityUseNextSession session, DurabilityCandidate replacement) {
        if (mc == null || mc.player == null || replacement == null) return false;
        int slot = replacement.slot();
        if (slot >= 0 && slot < 9) {
            AutismInventoryHelper.selectHotbarSlot(mc, slot);
            session.activeSlot = slot;
            session.preferredHotbarSlot = slot;
            return true;
        }
        if (slot >= 9 && slot < 36) {
            int target = Math.max(0, Math.min(8, session.preferredHotbarSlot));
            if (!AutismInventoryHelper.swapInventorySlots(mc, slot, target)) return false;
            AutismInventoryHelper.selectHotbarSlot(mc, target);
            session.activeSlot = target;
            session.preferredHotbarSlot = target;
            return true;
        }
        if (slot == 40) {
            session.activeSlot = 40;
            return true;
        }
        return false;
    }

    private static ItemStack stackForVisibleSlot(Minecraft mc, int visibleSlot) {
        if (mc == null || mc.player == null) return ItemStack.EMPTY;
        if (visibleSlot == 40) return mc.player.getOffhandItem();
        if (visibleSlot < 0 || visibleSlot >= mc.player.getInventory().getContainerSize()) return ItemStack.EMPTY;
        return mc.player.getInventory().getItem(visibleSlot);
    }

    private static boolean isSameDamageableItem(ItemStack stack, String registryId) {
        return stack != null && !stack.isEmpty() && stack.isDamageableItem() && stackRegistryId(stack).equals(registryId);
    }

    private static int remainingDurability(ItemStack stack) {
        return stack == null || !stack.isDamageableItem() ? 0 : Math.max(0, stack.getMaxDamage() - stack.getDamageValue());
    }

    private static String stackRegistryId(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return "";
        Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return id == null ? "" : id.toString();
    }

    private static String canonicalItemRegistryId(String raw) {
        if (raw == null || raw.isBlank()) return "";
        String trimmed = raw.trim().toLowerCase(Locale.ROOT);
        Identifier parsed = Identifier.tryParse(trimmed.contains(":") ? trimmed : "minecraft:" + trimmed);
        if (parsed == null) return "";
        return BuiltInRegistries.ITEM.getOptional(parsed)
            .map(item -> BuiltInRegistries.ITEM.getKey(item).toString())
            .orElse("");
    }

    private static final class DurabilityUseNextSession {
        private String lockedRegistryId = "";
        private int activeSlot = -1;
        private int preferredHotbarSlot = 0;
        private boolean firstSlotPending = false;
        private boolean observedInitialSlot = false;
        private boolean fallbackOnly = false;
        private ItemTarget initialTarget = new ItemTarget();
    }

    private record DurabilityCandidate(int slot, int score) {}

    private static CompletableFuture<Void> predicateFuture(java.util.function.BooleanSupplier predicate, int timeoutMs) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        Thread worker = new Thread(() -> {
            long start = System.currentTimeMillis();
            while (!future.isDone() && isCurrentRunActive()) {
                try {
                    if (predicate.getAsBoolean()) {
                        future.complete(null);
                        return;
                    }
                } catch (Throwable ignored) {
                }
                if (timeoutMs > 0 && System.currentTimeMillis() - start >= timeoutMs) {
                    future.complete(null);
                    return;
                }
                java.util.concurrent.locks.LockSupport.parkNanos(MACRO_WAIT_POLL_NANOS);
                if (Thread.interrupted()) return;
            }
        }, "MacroPredicateWait");
        worker.setDaemon(true);
        worker.start();
        return future.whenComplete((v, e) -> worker.interrupt());
    }

    private static void prearmNextActionWait(Minecraft mc, java.util.List<MacroAction> actions, int currentIndex) {
        RunState run = CURRENT_RUN.get();
        if (run == null || actions == null) return;
        int nextIndex = currentIndex + 1;
        if (nextIndex < 0 || nextIndex >= actions.size()) return;
        MacroAction next = actions.get(nextIndex);
        if (next == null || !next.isEnabled() || !next.listensDuringPreviousAction()) return;
        if (!isWaitConditionAction(next)) return;
        synchronized (run.prearmedWaits) {
            if (run.prearmedWaits.containsKey(next)) return;
            PrearmedWait wait = createPrearmedWait(mc, next);
            if (wait != null) run.prearmedWaits.put(next, wait);
        }
    }

    private static boolean isWaitConditionAction(MacroAction action) {
        return action instanceof WaitForPacketAction
            || action instanceof WaitPacketMatchAction
            || action instanceof WaitForChatAction
            || action instanceof WaitForGuiAction
            || action instanceof WaitGuiTypeAction
            || action instanceof WaitForSoundAction
            || action instanceof WaitForSlotChangeAction
            || action instanceof WaitInventoryPredicateAction
            || action instanceof WaitDurabilityAction
            || action instanceof WaitFreeSlotsAction
            || action instanceof WaitForHealthAction
            || action instanceof WaitForBlockAction
            || action instanceof WaitForEntityAction
            || action instanceof WaitEntityTargetAction
            || action instanceof WaitForCooldownAction
            || action instanceof WaitPosAction
            || action instanceof WaitForPositionDeltaAction
            || action instanceof WaitForTeleportAction
            || action instanceof WaitGamemodeChangeAction
            || action instanceof WaitForWorldChangeAction
            || action instanceof WaitMovementAction
            || action instanceof WaitForLanStepAction
            || action instanceof WaitForMacroStepAction;
    }

    private static PrearmedWait consumePrearmedWait(MacroAction action) {
        RunState run = CURRENT_RUN.get();
        if (run == null || action == null) return null;
        synchronized (run.prearmedWaits) {
            return run.prearmedWaits.remove(action);
        }
    }

    private static boolean waitForPrearmed(MacroAction action, long timeoutMs) {
        PrearmedWait wait = consumePrearmedWait(action);
        if (wait == null) return false;
        try {
            waitForFutureDone(wait.future, timeoutMs);
            return true;
        } finally {
            wait.cleanup();
        }
    }

    private static PrearmedWait createPrearmedWait(Minecraft mc, MacroAction action) {
        if (action instanceof WaitForPacketAction packetAction) {
            java.util.List<String> targets = packetAction.effectiveList();
            String firstTarget = targets.isEmpty() ? "" : targets.get(0);
            return packetTargetFuture(packetAction, firstTarget, 1);
        }
        if (action instanceof WaitPacketMatchAction packetMatch) {
            return packetMatchFuture(packetMatch);
        }
        if (action instanceof WaitInventoryPredicateAction inv) {
            inv.resetBaseline();
            return predicatePrearm(action, () -> inv.matches(mc), inv.timeoutMs);
        }
        if (action instanceof WaitDurabilityAction durability) {
            if (durability.useNext) return null;
            return predicatePrearm(action, () -> durabilityObservationMatches(mc, durability), durability.timeoutMs);
        }
        if (action instanceof WaitFreeSlotsAction freeSlots) {
            return predicatePrearm(action, () -> freeSlots.matches(mc), freeSlots.timeoutMs);
        }
        if (action instanceof WaitEntityTargetAction entityTarget) {
            return predicatePrearm(action, () -> entityTarget.matches(mc), entityTarget.timeoutMs);
        }
        if (action instanceof WaitGuiTypeAction guiType) {
            return predicatePrearm(action,
                guiType.waitMode == WaitGuiTypeAction.WaitMode.CLOSE
                    ? guiClosePredicate(guiType, mc)
                    : () -> guiType.matches(mc),
                guiType.timeoutMs);
        }
        if (action instanceof WaitForMacroStepAction macroStep) {
            return predicatePrearm(action, () -> isMacroStepSatisfied(macroStep), macroStep.timeoutMs);
        }
        if (action instanceof WaitForLanStepAction lanStep) {
            CompletableFuture<Void> future = MacroConditionRegistry.waitForLanStep(lanStep);
            return new PrearmedWait(action, future, () -> {
                if (!future.isDone()) future.cancel(true);
            }, 0);
        }
        CompletableFuture<?> future = raceConditionFuture(mc, action);
        if (future == null) return null;
        int timeout = action instanceof WaitForChatAction chat ? chat.timeoutMs : 0;
        if (timeout > 0) future.completeOnTimeout(null, timeout, TimeUnit.MILLISECONDS);
        return new PrearmedWait(action, future, () -> {
            if (!future.isDone()) future.cancel(true);
        }, 0);
    }

    private static PrearmedWait predicatePrearm(MacroAction action, java.util.function.BooleanSupplier predicate, int timeoutMs) {
        java.util.concurrent.atomic.AtomicBoolean active = new java.util.concurrent.atomic.AtomicBoolean(true);
        CompletableFuture<Void> future = new CompletableFuture<>();
        Thread worker = new Thread(() -> {
            long start = System.currentTimeMillis();
            while (active.get() && isCurrentRunActive() && !future.isDone()) {
                try {
                    if (predicate.getAsBoolean()) {
                        future.complete(null);
                        return;
                    }
                } catch (Throwable ignored) {
                }
                if (timeoutMs > 0 && System.currentTimeMillis() - start >= timeoutMs) {
                    future.complete(null);
                    return;
                }
                java.util.concurrent.locks.LockSupport.parkNanos(MACRO_WAIT_POLL_NANOS);
                if (Thread.interrupted()) return;
            }
        }, "MacroPrearmedWait");
        worker.setDaemon(true);
        worker.start();
        return new PrearmedWait(action, future, () -> {
            active.set(false);
            if (!future.isDone()) future.cancel(true);
            worker.interrupt();
        }, 0);
    }

    private static PrearmedWait packetTargetFuture(MacroAction action, String target, int completedPacketTargets) {
        String normalizedTarget = WaitForPacketAction.normalizeTarget(target);
        String direction = WaitForPacketAction.getDirection(normalizedTarget);
        CompletableFuture<Packet<?>> matched = new CompletableFuture<>();
        OneShotPacketListener send = null;
        OneShotPacketListener recv = null;
        if (!"S2C".equalsIgnoreCase(direction)) {
            send = awaitSend(packet -> matchesPacketTarget(normalizedTarget, packet, "C2S"));
            send.future.thenAccept(matched::complete);
        }
        if (!"C2S".equalsIgnoreCase(direction)) {
            recv = awaitReceive(packet -> matchesPacketTarget(normalizedTarget, packet, "S2C"));
            recv.future.thenAccept(matched::complete);
        }
        OneShotPacketListener finalSend = send;
        OneShotPacketListener finalRecv = recv;
        return new PrearmedWait(action, matched, () -> {
            if (finalSend != null) finalSend.cancel();
            if (finalRecv != null) finalRecv.cancel();
            if (!matched.isDone()) matched.cancel(true);
        }, completedPacketTargets);
    }

    private static PrearmedWait packetMatchFuture(WaitPacketMatchAction action) {
        OneShotPacketListener send = null;
        OneShotPacketListener recv = null;
        CompletableFuture<Packet<?>> matched = new CompletableFuture<>();
        if (action.direction != WaitPacketMatchAction.Direction.S2C) {
            send = awaitSend(packet -> action.matches(packet, "C2S"));
            send.future.thenAccept(matched::complete);
        }
        if (action.direction != WaitPacketMatchAction.Direction.C2S) {
            recv = awaitReceive(packet -> action.matches(packet, "S2C"));
            recv.future.thenAccept(matched::complete);
        }
        if (send == null && recv == null) return null;
        if (action.timeoutMs > 0) matched.completeOnTimeout(null, action.timeoutMs, TimeUnit.MILLISECONDS);
        OneShotPacketListener finalSend = send;
        OneShotPacketListener finalRecv = recv;
        return new PrearmedWait(action, matched, () -> {
            if (finalSend != null) finalSend.cancel();
            if (finalRecv != null) finalRecv.cancel();
            if (!matched.isDone()) matched.cancel(true);
        }, 0);
    }

    private static void waitForPacketMatch(WaitPacketMatchAction action) {
        if (action == null) return;
        OneShotPacketListener send = null;
        OneShotPacketListener recv = null;
        try {
            if (action.direction != WaitPacketMatchAction.Direction.S2C) {
                send = awaitSend(packet -> action.matches(packet, "C2S"));
            }
            if (action.direction != WaitPacketMatchAction.Direction.C2S) {
                recv = awaitReceive(packet -> action.matches(packet, "S2C"));
            }
            CompletableFuture<?> future;
            if (send != null && recv != null) future = CompletableFuture.anyOf(send.future, recv.future);
            else if (send != null) future = send.future;
            else if (recv != null) future = recv.future;
            else return;
            if (action.timeoutMs > 0) future.completeOnTimeout(null, action.timeoutMs, TimeUnit.MILLISECONDS);
            waitForFutureDone(future, action.timeoutMs);
        } finally {
            if (send != null) send.cancel();
            if (recv != null) recv.cancel();
        }
    }

    private static void runPacketGateAction(Minecraft mc, PacketGateAction action) {
        if (action == null) return;
        action.execute(mc);
        if (action.mode == PacketGateAction.GateMode.DISABLE_GATE) return;

        switch (action.durationMode) {
            case UNTIL_PACKET -> {
                WaitPacketMatchAction wait = new WaitPacketMatchAction();
                wait.direction = switch (action.direction) {
                    case C2S -> WaitPacketMatchAction.Direction.C2S;
                    case S2C -> WaitPacketMatchAction.Direction.S2C;
                    case ANY -> WaitPacketMatchAction.Direction.ANY;
                };
                wait.packetName = action.untilPacketName == null || action.untilPacketName.isBlank()
                    ? (action.packetNames.isEmpty() ? "" : action.packetNames.get(0))
                    : action.untilPacketName;
                wait.fieldName = action.untilPacketField;
                wait.operator = MacroStringList.enumValue(WaitPacketMatchAction.Operator.class, action.untilPacketOperator, WaitPacketMatchAction.Operator.EXISTS);
                wait.value = action.untilPacketValue;
                wait.timeoutMs = Math.max(0, action.durationValue);
                waitForPacketMatch(wait);
                disablePacketGate(mc, action);
            }
            case UNTIL_GUI -> {
                waitForPredicate(() -> MacroGuiMatcher.matches(mc.screen, action.untilGuiType, action.untilGuiTitle), Math.max(0, action.durationValue));
                disablePacketGate(mc, action);
            }
            case UNTIL_INVENTORY -> {
                WaitInventoryPredicateAction wait = new WaitInventoryPredicateAction();
                wait.condition = MacroStringList.enumValue(WaitInventoryPredicateAction.InventoryCondition.class, action.untilInventoryCondition, WaitInventoryPredicateAction.InventoryCondition.ITEM_EXISTS);
                wait.itemName = action.untilInventoryItem;
                wait.count = action.untilInventoryCount;
                wait.slot = action.untilInventorySlot;
                wait.timeoutMs = Math.max(0, action.durationValue);
                wait.resetBaseline();
                waitForPredicate(() -> wait.matches(mc), wait.timeoutMs);
                disablePacketGate(mc, action);
            }
            default -> {}
        }
    }

    private static void disablePacketGate(Minecraft mc, PacketGateAction action) {
        PacketGateManager.disableAndFlushConfigured(
            action.gateId == null || action.gateId.isBlank() ? "auto" : action.gateId,
            mc == null ? null : mc.getConnection(),
            action.flushOnDisable);
    }

    private static void runFinallyActions(RunState run, Minecraft mc) {
        if (run == null || run.finallyActions.isEmpty()) return;
        ArrayList<MacroAction> actions = new ArrayList<>(run.finallyActions);
        run.finallyActions.clear();
        for (MacroAction action : actions) {
            if (action == null || !action.isEnabled()) continue;
            try {
                executeSingleActionWithWaits(mc, action, AutismModule.get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable t) {
                AutismClientMessaging.sendPrefixed("\u00a7cCleanup failed: " + safeThrowableMessage(t));
            }
        }
    }

    private static void waitForMacroStep(WaitForMacroStepAction action) {
        long startMs = System.currentTimeMillis();
        while (isCurrentRunActive()) {
            if (isMacroStepSatisfied(action)) return;
            if (action.timeoutMs > 0 && System.currentTimeMillis() - startMs >= action.timeoutMs) return;
            CompletableFuture<Void> tick = MacroConditionRegistry.waitForNextTick();
            waitForCondition(tick);
        }
    }

    private static boolean isMacroStepSatisfied(WaitForMacroStepAction action) {
        String target = action.macroName == null ? "" : action.macroName.trim();
        if (target.isEmpty()) return true;
        synchronized (RUN_LOCK) {
            for (RunState run : ACTIVE_RUNS.values()) {
                String name = run.macro != null && run.macro.name != null ? run.macro.name : "";
                if (!name.equalsIgnoreCase(target)) continue;
                return switch (action.mode) {
                    case STARTED_STEP -> run.currentStepIndex + 1 >= Math.max(1, action.step);
                    case COMPLETED_STEP -> run.lastCompletedStep >= Math.max(1, action.step);
                    case FINISHED -> !run.running.get();
                };
            }
        }
        return action.mode == WaitForMacroStepAction.WaitMode.FINISHED;
    }

    private static int runRaceGroup(Minecraft mc, AutismModule module, java.util.List<MacroAction> actions, int headerIndex, RaceAction race) throws InterruptedException {
        int bodyCount = race.normalizedBodyCount(actions, headerIndex);

        java.util.List<MacroAction> conditions = new ArrayList<>();
        java.util.List<MacroAction> bodyActions = new ArrayList<>();
        for (int offset = 1; offset <= bodyCount && headerIndex + offset < actions.size(); offset++) {
            MacroAction child = actions.get(headerIndex + offset);
            if (child == null || !child.isEnabled()) continue;
            if (RaceAction.isConditionAction(child)) conditions.add(child);
            else if (RaceAction.isBodyAction(child)) bodyActions.add(child);
        }

        setCurrentStatus(conditions.isEmpty()
            ? "Race: dispatching"
            : "Race: waiting for " + (conditions.size() == 1 ? conditions.get(0).getDisplayName() : conditions.size() + " conditions"));

        RunState run = CURRENT_RUN.get();
        CompletableFuture<Void> bodyFuture = new CompletableFuture<>();
        AtomicBoolean dispatched = new AtomicBoolean(false);
        CompletableFuture<?> triggerFuture = raceTriggerBarrier(mc, conditions);
        triggerFuture.thenRun(() -> {
            if (!dispatched.compareAndSet(false, true)) return;
            if (run != null && !run.running.get()) {
                bodyFuture.cancel(true);
                return;
            }
            dispatchRaceBody(mc, module, bodyActions, run, bodyFuture);
        }).exceptionally(error -> {
            if (!bodyFuture.isDone()) bodyFuture.completeExceptionally(error);
            return null;
        });

        boolean fired = waitForTimedFuture(triggerFuture, Math.max(0, race.timeoutMs));
        if (!fired) {
            triggerFuture.cancel(true);
            bodyFuture.cancel(true);
            AutismClientMessaging.sendPrefixed("\u00A7eRace timed out: " + (race.label == null || race.label.isBlank() ? "Race" : race.label));
            setCurrentStatus("Race: timeout");
            return bodyCount;
        }

        setCurrentStatus("Race: fired");
        waitForTimedFuture(bodyFuture, 250);
        return bodyCount;
    }

    private static CompletableFuture<?> raceTriggerBarrier(Minecraft mc, java.util.List<MacroAction> conditions) {
        if (conditions == null || conditions.isEmpty()) return CompletableFuture.completedFuture(null);
        java.util.List<CompletableFuture<MacroEvent>> futures = new ArrayList<>();
        for (MacroAction condition : conditions) futures.add(timedConditionFuture(mc, condition));
        CompletableFuture<?> all = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
        return all.whenComplete((v, e) -> {
            for (CompletableFuture<MacroEvent> future : futures) {
                if (!future.isDone()) future.cancel(true);
            }
        });
    }

    private static int runReportGroup(Minecraft mc, java.util.List<MacroAction> actions, int headerIndex, ReportAction report) throws InterruptedException {
        int conditionCount = report.normalizedConditionCount(actions, headerIndex);
        if (conditionCount < 2 || headerIndex + 2 >= actions.size()) {
            setCurrentStatus(report.getDisplayName());
            report.execute(mc);
            return conditionCount;
        }

        MacroAction startCondition = actions.get(headerIndex + 1);
        MacroAction endCondition = actions.get(headerIndex + 2);
        if (RaceAction.isBodyAction(startCondition) && RaceAction.isConditionAction(endCondition)) {
            int timeoutMs = Math.max(100, report.timeoutMs);
            String label = report.reportLabel == null || report.reportLabel.isBlank() ? "Report" : report.reportLabel.trim();
            setCurrentStatus("Report: start " + startCondition.getDisplayName());
            CompletableFuture<MacroEvent> startAnchor = startActionAnchorFuture(startCondition);
            executeSingleActionWithWaits(mc, startCondition, null);
            if (!isCurrentRunActive()) return conditionCount;
            MacroEvent startEvent = waitForAnchorOrNow(startAnchor, 250, startCondition.getDisplayName(), "client action");

            setCurrentStatus("Report: waiting end " + endCondition.getDisplayName());
            CompletableFuture<MacroEvent> endFuture = timedConditionFuture(mc, endCondition);
            MacroEvent endEvent = waitForTimedEvent(endFuture, timeoutMs);
            if (endEvent == null) {
                endFuture.cancel(true);
                AutismClientMessaging.sendPrefixed("\u00A7eReport \"" + label + "\": timeout waiting for end condition");
                return conditionCount;
            }

            long deltaNanos = Math.max(0L, endEvent.nanoTime() - startEvent.nanoTime());
            long deltaMs = deltaNanos / 1_000_000L;
            String start = startEvent.label();
            String end = endEvent.label();
            AutismClientMessaging.sendPrefixed("\u00A7b" + label + ": " + formatNanos(deltaNanos) + " (" + start + " -> " + end + ")");
            if (report.stashToSharedState) {
                AutismSharedState.get().pushReport(new AutismSharedState.ReportSample(label, deltaMs, start, end, System.currentTimeMillis()));
            }
            return conditionCount;
        }

        if (!RaceAction.isConditionAction(startCondition) || !RaceAction.isConditionAction(endCondition)) {
            report.execute(mc);
            return conditionCount;
        }

        int timeoutMs = Math.max(100, report.timeoutMs);
        long startWaitNanos = System.nanoTime();
        setCurrentStatus("Report: waiting start " + startCondition.getDisplayName());
        boolean startFired = waitForMacroCondition(mc, startCondition, timeoutMs);
        if (!startFired) {
            AutismClientMessaging.sendPrefixed("\u00A7eReport \"" + report.reportLabel + "\": timeout waiting for start condition");
            return conditionCount;
        }

        MacroEvent startEvent = new MacroEvent(null, "condition " + startCondition.getDisplayName(), System.nanoTime(), null);
        long spentMs = (startEvent.nanoTime() - startWaitNanos) / 1_000_000L;
        int remainingMs = (int) Math.max(1, timeoutMs - spentMs);
        setCurrentStatus("Report: waiting end " + endCondition.getDisplayName());
        CompletableFuture<MacroEvent> endFuture = timedConditionFuture(mc, endCondition);
        MacroEvent endEvent = waitForTimedEvent(endFuture, remainingMs);
        if (endEvent == null) {
            endFuture.cancel(true);
            AutismClientMessaging.sendPrefixed("\u00A7eReport \"" + report.reportLabel + "\": timeout waiting for end condition");
            return conditionCount;
        }

        long deltaNanos = Math.max(0L, endEvent.nanoTime() - startEvent.nanoTime());
        long deltaMs = deltaNanos / 1_000_000L;
        String label = report.reportLabel == null || report.reportLabel.isBlank() ? "Report" : report.reportLabel.trim();
        String start = startEvent.label();
        String end = endEvent.label();
        AutismClientMessaging.sendPrefixed("\u00A7b" + label + ": " + formatNanos(deltaNanos) + " (" + start + " -> " + end + ")");
        if (report.stashToSharedState) {
            AutismSharedState.get().pushReport(new AutismSharedState.ReportSample(label, deltaMs, start, end, System.currentTimeMillis()));
        }
        return conditionCount;
    }

    private static boolean waitForRaceTrigger(Minecraft mc, RaceAction race) throws InterruptedException {
        MacroAction trigger = race.triggerAction == null ? new DelayAction(0) : race.triggerAction;
        int timeoutMs = Math.max(0, race.timeoutMs);
        return waitForMacroCondition(mc, trigger, timeoutMs);
    }

    public static boolean waitForMacroCondition(Minecraft mc, MacroAction trigger, int timeoutMs) throws InterruptedException {
        if (trigger == null) return true;
        CompletableFuture<?> future = raceConditionFuture(mc, trigger);
        if (future == null) {
            executeSingleActionWithWaits(mc, trigger, null);
            return true;
        }
        boolean fired = waitForFutureDone(future, timeoutMs);
        if (!fired) future.cancel(true);
        return fired;
    }

    private static void dispatchRaceBody(Minecraft mc, AutismModule module, java.util.List<MacroAction> bodyActions,
                                         RunState run, CompletableFuture<Void> bodyFuture) {
        if (bodyActions == null || bodyActions.isEmpty()) {
            bodyFuture.complete(null);
            return;
        }
        Runnable task = () -> {
            RunState previous = CURRENT_RUN.get();
            if (run != null) CURRENT_RUN.set(run);
            EnumSet<SharedClientResource> heldResources = EnumSet.noneOf(SharedClientResource.class);
            try {
                heldResources = acquireClientResources(resourcesForActions(bodyActions));
                Throwable firstFailure = null;
                for (MacroAction body : bodyActions) {
                    if (body == null || !body.isEnabled() || !RaceAction.isBodyAction(body)) continue;
                    if (run != null && !run.running.get()) {
                        bodyFuture.cancel(true);
                        return;
                    }
                    try {
                        executeRaceBodyActionFast(mc, body);
                    } catch (InterruptedException e) {
                        throw e;
                    } catch (Throwable t) {
                        if (firstFailure == null) firstFailure = t;
                        AutismClientMessaging.sendPrefixed("\u00A7cRace action failed (" + body.getDisplayName() + "): " + safeThrowableMessage(t));
                    }
                }
                if (firstFailure == null) bodyFuture.complete(null);
                else bodyFuture.completeExceptionally(firstFailure);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                bodyFuture.cancel(true);
            } catch (Throwable t) {
                AutismClientMessaging.sendPrefixed("\u00A7cRace action failed: " + safeThrowableMessage(t));
                bodyFuture.completeExceptionally(t);
            } finally {
                releaseClientResources(heldResources);
                if (previous != null) CURRENT_RUN.set(previous);
                else CURRENT_RUN.remove();
            }
        };

        if (bodyActions.stream().allMatch(MacroExecutor::isRaceDirectPacketAction)) {
            task.run();
        } else {
            mc.execute(task);
        }
    }

    private static void executeRaceBodyActionFast(Minecraft mc, MacroAction body) throws InterruptedException {
        if (body == null || !body.isEnabled() || !RaceAction.isBodyAction(body)) return;
        body.execute(mc);
    }

    private static boolean isRaceDirectPacketAction(MacroAction action) {
        if (action instanceof SendChatAction chat) return !chat.isWaitForGuiBefore() && !chat.isWaitForGuiAfter();
        if (action instanceof SendPacketAction sendPacket) return !sendPacket.isWaitForGuiBefore() && !sendPacket.isWaitForGuiAfter();
        return action instanceof PacketClickAction;
    }

    private static CompletableFuture<MacroEvent> startActionAnchorFuture(MacroAction action) {
        if (action instanceof PacketClickAction) {
            return awaitTimedSend(packet -> packet instanceof ServerboundContainerClickPacket).future;
        }
        if (action instanceof ItemAction) {
            return awaitTimedSend(packet -> packet instanceof ServerboundContainerClickPacket).future;
        }
        if (action instanceof CloseGuiAction) {
            return awaitTimedSend(packet -> packet instanceof net.minecraft.network.protocol.game.ServerboundContainerClosePacket).future;
        }
        if (action instanceof SendChatAction) {
            return awaitTimedSend(packet -> packet instanceof net.minecraft.network.protocol.game.ServerboundChatPacket
                || packet instanceof net.minecraft.network.protocol.game.ServerboundChatCommandPacket
                || packet instanceof net.minecraft.network.protocol.game.ServerboundChatCommandSignedPacket).future;
        }
        if (action instanceof SendPacketAction || action instanceof PayloadAction) {
            return awaitTimedSend(packet -> true).future;
        }
        return null;
    }

    private static MacroEvent waitForAnchorOrNow(CompletableFuture<MacroEvent> anchor, int timeoutMs, String label, String fallbackDirection) {
        if (anchor != null) {
            MacroEvent event = waitForTimedEvent(anchor, timeoutMs);
            if (event != null) return event;
            anchor.cancel(true);
        }
        return new MacroEvent(null, fallbackDirection + " " + (label == null ? "" : label), System.nanoTime(), null);
    }

    private static MacroEvent waitForTimedEvent(CompletableFuture<MacroEvent> future, long timeoutMs) {
        if (future == null) return null;
        try {
            if (timeoutMs <= 0) return future.get();
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            return null;
        } catch (ExecutionException | TimeoutException | CancellationException e) {
            return null;
        }
    }

    private static boolean waitForTimedFuture(CompletableFuture<?> future, long timeoutMs) {
        if (future == null) return true;
        try {
            if (timeoutMs <= 0) future.get();
            else future.get(timeoutMs, TimeUnit.MILLISECONDS);
            return !future.isCancelled() && isCurrentRunActive();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            return false;
        } catch (ExecutionException | TimeoutException | CancellationException e) {
            return false;
        }
    }

    private static String formatNanos(long nanos) {
        return String.format(Locale.ROOT, "%.3f ms", nanos / 1_000_000.0);
    }

    private static String safeThrowableMessage(Throwable throwable) {
        if (throwable == null) return "unknown error";
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
    }

    private static boolean isWorldTransitionPacket(Packet<?> packet) {
        return packet instanceof net.minecraft.network.protocol.game.ClientboundRespawnPacket
            || packet instanceof net.minecraft.network.protocol.game.ClientboundLoginPacket;
    }

    private static CompletableFuture<MacroEvent> timedConditionFuture(Minecraft mc, MacroAction trigger) {
        if (trigger == null) {
            return CompletableFuture.completedFuture(new MacroEvent(null, "condition", System.nanoTime(), null));
        }
        if (trigger instanceof WaitForChatAction chat) {
            TimedOneShotPacketListener listener = awaitTimedReceive((packet, direction, now) -> {
                ChatCapture capture = extractIncomingChat(packet);
                return capture != null && matchesChatAction(chat, capture);
            });
            return listener.future.whenComplete((v, e) -> listener.cancel());
        }
        if (trigger instanceof WaitForPacketAction packetAction) {
            java.util.List<String> targets = packetAction.effectiveList();
            CompletableFuture<MacroEvent> matched = new CompletableFuture<>();
            TimedOneShotPacketListener recv = awaitTimedReceive((packet, direction, now) -> {
                if (targets.isEmpty()) return true;
                for (String target : targets) {
                    if (matchesPacketTarget(target, packet, "S2C")) return true;
                }
                return false;
            });
            TimedOneShotPacketListener send = awaitTimedSend((packet, direction, now) -> {
                if (targets.isEmpty()) return true;
                for (String target : targets) {
                    if (matchesPacketTarget(target, packet, "C2S")) return true;
                }
                return false;
            });
            recv.future.thenAccept(matched::complete);
            send.future.thenAccept(matched::complete);
            return matched.whenComplete((v, e) -> {
                recv.cancel();
                send.cancel();
            });
        }
        if (trigger instanceof WaitForWorldChangeAction world) {
            CompletableFuture<MacroEvent> done = new CompletableFuture<>();
            CompletableFuture<Void> tickFuture = MacroConditionRegistry.waitForWorldChange(world);
            TimedOneShotPacketListener worldListener = awaitTimedReceive((packet, direction, now) -> isWorldTransitionPacket(packet));
            tickFuture.whenComplete((v, e) -> {
                if (e != null) {
                    if (!done.isDone()) done.cancel(true);
                } else {
                    done.complete(new MacroEvent(null, "world change", System.nanoTime(), null));
                }
            });
            worldListener.future.whenComplete((event, error) -> {
                if (error != null) {
                    if (!done.isDone()) done.cancel(true);
                    return;
                }
                if (world.targetDimension == null || world.targetDimension.isBlank()) {
                    done.complete(event);
                }
            });
            return done.whenComplete((v, e) -> {
                worldListener.cancel();
                if (!tickFuture.isDone()) tickFuture.cancel(true);
            });
        }
        if (trigger instanceof WaitForTeleportAction teleport) {
            net.minecraft.world.phys.Vec3 origin = mc.player == null ? net.minecraft.world.phys.Vec3.ZERO : mc.player.position();
            CompletableFuture<MacroEvent> done = new CompletableFuture<>();
            TimedOneShotPacketListener teleListener = awaitTimedReceive((packet, direction, now) ->
                packet instanceof ClientboundPlayerPositionPacket || isWorldTransitionPacket(packet));
            teleListener.future.whenComplete((event, error) -> {
                teleListener.cancel();
                if (error != null) {
                    done.cancel(true);
                    return;
                }
                if (teleport.minDistance <= 0.0) {
                    done.complete(event);
                    return;
                }
                MacroConditionRegistry.waitForPositionDeltaFrom(origin, teleport.minDistance, teleport.horizontalOnly)
                    .whenComplete((v, e) -> {
                        if (e != null) done.cancel(true);
                        else done.complete(new MacroEvent(event.packet(), event.direction(), System.nanoTime(), event.chatCapture()));
                    });
            });
            return done;
        }

        CompletableFuture<?> base = raceConditionFuture(mc, trigger);
        if (base == null) {
            CompletableFuture<MacroEvent> done = new CompletableFuture<>();
            mc.execute(() -> {
                try {
                    executeSingleActionWithWaits(mc, trigger, null);
                    done.complete(new MacroEvent(null, trigger.getDisplayName(), System.nanoTime(), null));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    done.cancel(true);
                } catch (Throwable t) {
                    done.completeExceptionally(t);
                }
            });
            return done;
        }
        CompletableFuture<MacroEvent> timed = new CompletableFuture<>();
        base.whenComplete((v, e) -> {
            if (e != null) timed.cancel(true);
            else timed.complete(new MacroEvent(null, trigger.getDisplayName(), System.nanoTime(), null));
        });
        return timed.whenComplete((v, e) -> {
            if (!base.isDone()) base.cancel(true);
        });
    }

    private static CompletableFuture<?> raceConditionFuture(Minecraft mc, MacroAction trigger) {
        if (trigger instanceof WaitMovementAction wm) {
            return raceConditionFuture(mc, wm.resolveSubAction());
        }
        if (trigger instanceof WaitGamemodeChangeAction gm) {
            return MacroConditionRegistry.waitForGamemodeChange(gm);
        }
        if (trigger instanceof DelayAction delay) {
            return CompletableFuture.runAsync(() -> {
                try {
                    if (delay.useTicks) {
                        for (int i = 0; i < delay.delayTicks && isCurrentRunActive(); i++) {
                            waitForCondition(MacroConditionRegistry.waitForNextTick());
                        }
                    } else {
                        Thread.sleep(Math.max(0, delay.delayMs));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        if (trigger instanceof WaitForGuiAction gui) {
            return predicateFuture(gui.waitMode == WaitForGuiAction.WaitMode.CLOSE
                ? guiClosePredicate(gui, Minecraft.getInstance())
                : () -> gui.checkGui(Minecraft.getInstance()), gui.timeoutMs);
        }
        if (trigger instanceof WaitForChatAction chat) {
            OneShotPacketListener listener = awaitReceive(packet -> {
                ChatCapture capture = extractIncomingChat(packet);
                return capture != null && matchesChatAction(chat, capture);
            });
            return listener.future.whenComplete((v, e) -> listener.cancel());
        }
        if (trigger instanceof WaitForPacketAction packetAction) {
            java.util.List<String> targets = packetAction.effectiveList();
            CompletableFuture<Packet<?>> matched = new CompletableFuture<>();
            OneShotPacketListener recv = awaitReceive(packet -> {
                if (targets.isEmpty()) return true;
                for (String target : targets) {
                    if (matchesPacketTarget(target, packet, "S2C")) return true;
                }
                return false;
            });
            OneShotPacketListener send = awaitSend(packet -> {
                if (targets.isEmpty()) return true;
                for (String target : targets) {
                    if (matchesPacketTarget(target, packet, "C2S")) return true;
                }
                return false;
            });
            recv.future.thenAccept(matched::complete);
            send.future.thenAccept(matched::complete);
            return matched.whenComplete((v, e) -> {
                recv.cancel();
                send.cancel();
            });
        }
        if (trigger instanceof WaitForHealthAction health) {
            return MacroConditionRegistry.waitForHealth(health.healthThreshold, health.below);
        }
        if (trigger instanceof WaitForBlockAction block) {
            return MacroConditionRegistry.waitForBlock(block);
        }
        if (trigger instanceof WaitForCooldownAction cooldown) {
            ItemTarget target = resolveItemTarget(cooldown.itemTarget, cooldown.itemName);
            return MacroConditionRegistry.waitForCooldown(target, cooldown.checkMainInteractionHand);
        }
        if (trigger instanceof WaitPosAction pos) {
            return MacroConditionRegistry.waitForPos(pos.x, pos.y, pos.z, pos.leeway, pos.checkRotation, pos.yaw, pos.pitch, pos.rotLeeway);
        }
        if (trigger instanceof WaitForEntityAction entity) {
            return predicateFuture(() -> entity.matchesEntityTarget(Minecraft.getInstance()), entity.timeoutMs);
        }
        if (trigger instanceof WaitForSoundAction sound) {
            return MacroConditionRegistry.waitForSound(sound);
        }
        if (trigger instanceof WaitForSlotChangeAction slot) {
            return MacroConditionRegistry.waitForSlotChange(slot);
        }
        if (trigger instanceof WaitDurabilityAction durability) {
            return predicateFuture(() -> durabilityObservationMatches(Minecraft.getInstance(), durability), durability.timeoutMs);
        }
        if (trigger instanceof WaitFreeSlotsAction freeSlots) {
            return predicateFuture(() -> freeSlots.matches(Minecraft.getInstance()), freeSlots.timeoutMs);
        }
        if (trigger instanceof TickSyncAction) {
            return MacroConditionRegistry.waitForNextTick();
        }
        if (trigger instanceof ServerTickSyncAction serverTick) {
            return MacroConditionRegistry.waitForServerTick(serverTick.bufferMs, serverTick.maxWaitMs, serverTick.ignorePing);
        }
        if (trigger instanceof WaitForWorldChangeAction world) {
            CompletableFuture<Void> done = new CompletableFuture<>();
            CompletableFuture<Void> tickFuture = MacroConditionRegistry.waitForWorldChange(world);
            OneShotPacketListener worldListener = awaitReceive(MacroExecutor::isWorldTransitionPacket);

            tickFuture.whenComplete((v, e) -> {
                if (e != null) {
                    if (!done.isDone()) done.cancel(true);
                } else {
                    done.complete(null);
                }
            });

            worldListener.future.whenComplete((packet, error) -> {
                if (error != null) {
                    if (!done.isDone()) done.cancel(true);
                    return;
                }
                if (world.targetDimension == null || world.targetDimension.isBlank()) {
                    done.complete(null);
                }
            });

            return done.whenComplete((v, e) -> {
                worldListener.cancel();
                if (!tickFuture.isDone()) tickFuture.cancel(true);
            });
        }
        if (trigger instanceof WaitForPositionDeltaAction delta) {
            return MacroConditionRegistry.waitForPositionDelta(delta.distance, delta.horizontalOnly);
        }
        if (trigger instanceof WaitForTeleportAction teleport) {
            net.minecraft.world.phys.Vec3 origin = mc.player == null ? net.minecraft.world.phys.Vec3.ZERO : mc.player.position();
            CompletableFuture<Void> done = new CompletableFuture<>();

            OneShotPacketListener teleListener = awaitReceive(packet ->
                packet instanceof ClientboundPlayerPositionPacket
                || isWorldTransitionPacket(packet));
            teleListener.future.whenComplete((packet, error) -> {
                teleListener.cancel();
                if (error != null) {
                    done.cancel(true);
                    return;
                }

                if (teleport.minDistance <= 0.0) {
                    done.complete(null);
                    return;
                }
                MacroConditionRegistry.waitForPositionDeltaFrom(origin, teleport.minDistance, teleport.horizontalOnly)
                    .whenComplete((v, e) -> {
                        if (e != null) done.cancel(true);
                        else done.complete(null);
                    });
            });
            return done;
        }
        if (trigger instanceof RevisionSyncAction) {
            return CompletableFuture.runAsync(() -> {
                try {
                    executeSingleActionWithWaits(mc, trigger, null);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        return null;
    }

    private static boolean waitForFutureDone(CompletableFuture<?> future, long timeoutMs) {
        Thread t = Thread.currentThread();
        long start = System.nanoTime();
        long timeoutNanos = timeoutMs <= 0 ? Long.MAX_VALUE : timeoutMs * 1_000_000L;
        future.whenComplete((v, e) -> java.util.concurrent.locks.LockSupport.unpark(t));
        while (!future.isDone() && isCurrentRunActive()) {
            if (System.nanoTime() - start >= timeoutNanos) break;
            java.util.concurrent.locks.LockSupport.parkNanos(MACRO_WAIT_POLL_NANOS);
            if (Thread.interrupted()) break;
        }
        return future.isDone() && !future.isCancelled() && isCurrentRunActive();
    }

    private static void executeSingleActionWithWaits(Minecraft mc, MacroAction action, AutismModule module) throws InterruptedException {
        EnumSet<SharedClientResource> heldResources = acquireClientResources(action);
        try {

        waitForGuiBeforeAction(mc, action);
        java.util.concurrent.CompletableFuture<Void> postGuiFuture =
                (action instanceof ItemAction || action instanceof DropAction || isTargetWaitInteractionAction(action)) ? null : createPostGuiFuture(mc, action);

        if (action instanceof DelayAction) {
            DelayAction da = (DelayAction) action;
            if (da.useTicks) {
                for (int t = 0; t < da.delayTicks && isCurrentRunActive(); t++) {
                    CompletableFuture<Void> tf = MacroConditionRegistry.waitForNextTick();
                    waitForCondition(tf);
                }
            } else {
                Thread.sleep(da.delayMs);
            }
        } else if (action instanceof WaitForPacketAction) {
            java.util.List<String> targets = ((WaitForPacketAction) action).effectiveList();
            if (targets.isEmpty()) {
                awaitPacket("");
            } else {
                for (String target : targets) {
                    if (!isCurrentRunActive()) break;
                    awaitPacket(target);
                }
            }
        } else if (action instanceof WaitPacketMatchAction wpma) {
            setCurrentStatus(wpma.getDisplayName());
            waitForPacketMatch(wpma);
        } else if (action instanceof WaitInventoryPredicateAction wipa) {
            setCurrentStatus(wipa.getDisplayName());
            waitForPredicate(() -> wipa.matches(mc), wipa.timeoutMs);
        } else if (action instanceof WaitDurabilityAction wda) {
            setCurrentStatus(wda.getDisplayName());
            if (wda.useNext) waitForDurabilityUseNext(mc, wda);
            else waitForPredicate(() -> wda.matches(mc), wda.timeoutMs);
        } else if (action instanceof WaitFreeSlotsAction wfsa) {
            setCurrentStatus(wfsa.getDisplayName());
            waitForPredicate(() -> wfsa.matches(mc), wfsa.timeoutMs);
        } else if (action instanceof WaitEntityTargetAction weta) {
            setCurrentStatus(weta.getDisplayName());
            waitForPredicate(() -> weta.matches(mc), weta.timeoutMs);
        } else if (action instanceof WaitGuiTypeAction wgta) {
            setCurrentStatus(wgta.getDisplayName());
            if (wgta.waitMode == WaitGuiTypeAction.WaitMode.CLOSE) waitForPredicate(guiClosePredicate(wgta, mc), wgta.timeoutMs);
            else waitForPredicate(() -> wgta.matches(mc), wgta.timeoutMs);
        } else if (action instanceof PacketGateAction pga) {
            runPacketGateAction(mc, pga);
        } else if (isTargetWaitInteractionAction(action)) {
            postGuiFuture = runTargetWaitInteractionAction(mc, action);
        } else if (action instanceof WaitForHealthAction) {
            WaitForHealthAction wh = (WaitForHealthAction) action;
            setCurrentStatus(wh.waitingStatusText());
            try {
                CompletableFuture<Void> future = MacroConditionRegistry.waitForHealth(wh.healthThreshold, wh.below);
                waitForCondition(future);
            } catch (CancellationException e) {}
        } else if (action instanceof WaitForBlockAction) {
            WaitForBlockAction wba = (WaitForBlockAction) action;
            setCurrentStatus(wba.getDisplayName());
            try {
                CompletableFuture<Void> future = MacroConditionRegistry.waitForBlock(wba);
                waitForCondition(future);
            } catch (CancellationException e) {}
        } else if (action instanceof WaitForGuiAction) {
            WaitForGuiAction wga = (WaitForGuiAction) action;
            if (wga.waitMode == WaitForGuiAction.WaitMode.CLOSE) {
                setCurrentStatus("Waiting for GUI close: " + wga.guiTitle);
                waitForPredicate(guiClosePredicate(wga, mc), wga.timeoutMs);
            } else {
                setCurrentStatus("Waiting for GUI: " + wga.guiTitle);
                waitForPredicate(() -> wga.checkGui(mc), wga.timeoutMs);
            }
        } else if (action instanceof WaitForCooldownAction) {
            WaitForCooldownAction wca = (WaitForCooldownAction) action;
            ItemTarget cooldownTarget = resolveItemTarget(wca.itemTarget, wca.itemName);
            String cooldownLabel = describeItemTarget(cooldownTarget);
            setCurrentStatus("Waiting for cooldown: " +
                (!cooldownLabel.isEmpty() ? cooldownLabel : (wca.checkMainInteractionHand ? "Main Hand" : "Off Hand")));
            try {
                CompletableFuture<Void> future = MacroConditionRegistry.waitForCooldown(cooldownTarget, wca.checkMainInteractionHand);
                waitForCondition(future);
            } catch (CancellationException e) {}
        } else if (action instanceof WaitPosAction) {
            WaitPosAction wpa = (WaitPosAction) action;
            setCurrentStatus("Waiting for Pos: " + String.format("%.0f, %.0f, %.0f", wpa.x, wpa.y, wpa.z));
            try {
                CompletableFuture<Void> future = MacroConditionRegistry.waitForPos(
                    wpa.x, wpa.y, wpa.z, wpa.leeway, wpa.checkRotation, wpa.yaw, wpa.pitch, wpa.rotLeeway);
                waitForCondition(future);
            } catch (CancellationException e) {}
        } else if (action instanceof WaitForChatAction) {
            WaitForChatAction wca = (WaitForChatAction) action;
            setCurrentStatus("Waiting for chat: " + wca.pattern);
            awaitChat(wca);
        } else if (action instanceof WaitForEntityAction) {
            WaitForEntityAction wea = (WaitForEntityAction) action;
            setCurrentStatus(wea.getDisplayName());
            try {
                waitForPredicate(() -> wea.matchesEntityTarget(mc), wea.timeoutMs);
            } catch (CancellationException e) {}
        } else if (action instanceof WaitForSoundAction) {
            WaitForSoundAction wsa = (WaitForSoundAction) action;
            String sndDesc = wsa.soundIds.isEmpty() ? "any" : wsa.soundIds.get(0);
            setCurrentStatus("Waiting for sound: " + sndDesc);
            try {
                CompletableFuture<Void> future = MacroConditionRegistry.waitForSound(wsa);
                waitForCondition(future);
            } catch (CancellationException e) {}
        } else if (action instanceof WaitForSlotChangeAction) {
            WaitForSlotChangeAction wsca = (WaitForSlotChangeAction) action;
            setCurrentStatus(wsca.getDisplayName());
            try {
                CompletableFuture<Void> future = MacroConditionRegistry.waitForSlotChange(wsca);
                waitForCondition(future);
            } catch (CancellationException e) {}
        } else if (action instanceof WaitForWorldChangeAction world) {
            setCurrentStatus(world.getDisplayName());
            try {
                CompletableFuture<?> future = raceConditionFuture(mc, world);
                waitForFutureDone(future, 0);
            } catch (CancellationException e) {}
        } else if (action instanceof WaitForPositionDeltaAction wpda) {
            setCurrentStatus(wpda.getDisplayName());
            try {
                CompletableFuture<Void> future = MacroConditionRegistry.waitForPositionDelta(wpda.distance, wpda.horizontalOnly);
                waitForCondition(future);
            } catch (CancellationException e) {}
        } else if (action instanceof WaitForTeleportAction teleport) {
            setCurrentStatus(teleport.getDisplayName());
            try {
                CompletableFuture<?> future = raceConditionFuture(mc, teleport);
                waitForFutureDone(future, 0);
            } catch (CancellationException e) {}
        } else if (action instanceof WaitGamemodeChangeAction gm) {
            setCurrentStatus(gm.getDisplayName());
            try {
                CompletableFuture<Void> future = MacroConditionRegistry.waitForGamemodeChange(gm);
                waitForFutureDone(future, gm.timeoutMs);
            } catch (CancellationException e) {}
        } else if (action instanceof WaitMovementAction wm) {
            setCurrentStatus(wm.getDisplayName());
            try {
                CompletableFuture<?> future = raceConditionFuture(mc, wm.resolveSubAction());
                waitForFutureDone(future, 0);
            } catch (CancellationException e) {}
        } else if (action instanceof WaitForLanStepAction) {
            WaitForLanStepAction wla = (WaitForLanStepAction) action;
            setCurrentStatus(wla.getDisplayName());
            try {
                CompletableFuture<Void> future = MacroConditionRegistry.waitForLanStep(wla);
                waitForCondition(future);
            } catch (CancellationException e) {}
        } else if (action instanceof WaitForMacroStepAction) {
            WaitForMacroStepAction wma = (WaitForMacroStepAction) action;
            setCurrentStatus(wma.getDisplayName());
            waitForMacroStep(wma);
        } else if (action instanceof GoToAction) {
            GoToAction ga = (GoToAction) action;
            runGoToAction(mc, ga);
        } else if (action instanceof MoveAction) {
            MoveAction ma = (MoveAction) action;
            if (ma.nonBlocking) {
                mc.execute(() -> ma.execute(mc));
                currentBackgroundMoves().put(ma, new int[]{ma.durationTicks - 1});
            } else {
                for (int t = 0; t < ma.durationTicks && isCurrentRunActive(); t++) {
                    mc.execute(() -> ma.execute(mc));
                    CompletableFuture<Void> tickFuture = MacroConditionRegistry.waitForNextTick();
                    waitForCondition(tickFuture);
                }
                mc.execute(() -> ma.release(mc));
            }
        } else if (action instanceof LookAtBlockAction) {
            LookAtBlockAction la = (LookAtBlockAction) action;
            LookAtBlockAction.RotationTarget rotationTarget = resolveLookAtTargetOnClient(mc, la);
            if (rotationTarget == null) {
                return;
            }
            if (la.smooth) {
                startSmoothRotation(rotationTarget.yaw(), rotationTarget.pitch(), la.getRotationStep());
                if (la.waitForCompletion) {
                    waitForSmoothRotationCompletion();
                }
            } else {
                mc.execute(() -> {
                    if (mc.player == null) return;
                    mc.player.setYRot(rotationTarget.yaw());
                    mc.player.setXRot(rotationTarget.pitch());
                });
            }
        } else if (action instanceof RotateAction) {
            RotateAction ra = (RotateAction) action;
            if (ra.smooth) {
                startSmoothRotation(ra.yaw, ra.pitch, ra.getRotationStep());
                if (ra.waitForCompletion) {
                    waitForSmoothRotationCompletion();
                }
            } else {
                mc.execute(() -> action.execute(mc));
            }
        } else if (action instanceof MineAction) {
            MineAction mna = (MineAction) action;
            runMineAction(mc, mna);
        } else if (action instanceof InstaBreakAction) {
            runInstaBreakAction(mc, (InstaBreakAction) action);
        } else if (action instanceof BreakAction) {
            runBreakAction(mc, (BreakAction) action);
        } else if (action instanceof PlaceAction) {
            runPlaceAction(mc, (PlaceAction) action);
        } else if (action instanceof UseItemAction) {
            UseItemAction ua = (UseItemAction) action;
            if (ua.useMode == UseItemAction.UseMode.CUSTOM_HOLD) {

                java.util.concurrent.CountDownLatch uaLatch = new java.util.concurrent.CountDownLatch(1);
                mc.execute(() -> {
                    try { ua.execute(mc); }
                    catch (Exception e) { AutismClientMessaging.sendPrefixed("§cError in UseItem: " + e.getMessage()); }
                    finally { uaLatch.countDown(); }
                });
                try { uaLatch.await(200, TimeUnit.MILLISECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                if (ua.holdTicks > 0) {
                    for (int t = 0; t < ua.holdTicks && isCurrentRunActive(); t++) {
                        CompletableFuture<Void> tf = MacroConditionRegistry.waitForNextTick();
                        waitForCondition(tf);
                    }
                    java.util.concurrent.CountDownLatch relLatch = new java.util.concurrent.CountDownLatch(1);
                    mc.execute(() -> {
                        try { ua.sendRelease(mc); }
                        catch (Exception e) { AutismClientMessaging.sendPrefixed("§cError in UseItem release: " + e.getMessage()); }
                        finally { relLatch.countDown(); }
                    });
                    try { relLatch.await(200, TimeUnit.MILLISECONDS); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    if (ua.waitForFinish) {
                        waitForUseItemFinish(mc, ua.holdTicks + 8);
                    }
                }
            } else {

                int times = Math.max(1, ua.useCount);
                for (int u = 0; u < times && isCurrentRunActive(); u++) {
                    java.util.concurrent.CountDownLatch uaLatch = new java.util.concurrent.CountDownLatch(1);
                    mc.execute(() -> {
                        try { ua.execute(mc); }
                        catch (Exception e) { AutismClientMessaging.sendPrefixed("§cError in UseItem: " + e.getMessage()); }
                        finally { uaLatch.countDown(); }
                    });
                    try { uaLatch.await(200, TimeUnit.MILLISECONDS); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    if (ua.waitForFinish) {
                        waitForUseItemFinish(mc, 0);
                    }
                    if (u < times - 1 && isCurrentRunActive()) {
                        CompletableFuture<Void> tf = MacroConditionRegistry.waitForNextTick();
                        waitForCondition(tf);
                    }
                }
            }
        } else if (action instanceof JumpAction) {
            JumpAction ja = (JumpAction) action;
            mc.execute(() -> ja.execute(mc));
            if (ja.tap) {
                CompletableFuture<Void> tickFuture = MacroConditionRegistry.waitForNextTick();
                waitForCondition(tickFuture);
            } else {
                for (int t = 0; t < ja.durationTicks && isCurrentRunActive(); t++) {
                    CompletableFuture<Void> tickFuture = MacroConditionRegistry.waitForNextTick();
                    waitForCondition(tickFuture);
                }
            }
            mc.execute(() -> ja.release(mc));
        } else if (action instanceof SneakAction) {
            SneakAction sna = (SneakAction) action;
            mc.execute(() -> sna.execute(mc));
            if (sna.persistent && sna.sneak) {
                currentPersistentActions().removeIf(p -> p instanceof SneakAction);
                currentPersistentActions().add(sna);
            } else if (!sna.sneak) {
                currentPersistentActions().removeIf(p -> p instanceof SneakAction);
            }
        } else if (action instanceof SprintAction) {
            SprintAction spa = (SprintAction) action;
            mc.execute(() -> spa.execute(mc));
            if (spa.persistent && spa.sprint) {
                currentPersistentActions().removeIf(p -> p instanceof SprintAction);
                currentPersistentActions().add(spa);
            } else if (!spa.sprint) {
                currentPersistentActions().removeIf(p -> p instanceof SprintAction);
            }
        } else if (action instanceof NbtBookAction nba) {
            int totalBooks = Math.max(1, nba.bookCount);
            long delayMs = Math.max(1, nba.delayTicks) * 50L;
            boolean signedAny = false;
            for (int b = 0; b < totalBooks; b++) {
                if (!isCurrentRunActive()) break;
                final int bookIdx = b;
                java.util.concurrent.CompletableFuture<Boolean> result = new java.util.concurrent.CompletableFuture<>();
                mc.execute(() -> result.complete(nba.executeSingleBook(mc, bookIdx, totalBooks)));
                try {
                    Boolean success = result.get();
                    if (!success) break;
                    signedAny = true;
                } catch (java.util.concurrent.ExecutionException e) {
                    break;
                }
                if (b < totalBooks - 1) {
                    Thread.sleep(delayMs);
                }
            }
            if (signedAny && nba.disconnectAfter) {
                mc.execute(() -> nba.afterSigning(mc));
            }
        } else if (action instanceof ItemAction) {
            ItemAction ia = (ItemAction) action;
            if (ia.waitForItem) {
                try {
                    waitForItemActionTargets(mc, ia);
                } catch (java.util.concurrent.CancellationException e) {}
            }
            postGuiFuture = createPostGuiFuture(mc, ia);
            runMacroActionOnClientThread(mc, action, "ItemAction");
        } else if (action instanceof DropAction || action instanceof SwapSlotsAction) {
            if (action instanceof DropAction dropAction) {
                postGuiFuture = createPostGuiFuture(mc, dropAction);
            }
            runMacroActionOnClientThread(mc, action, action.getDisplayName());
        } else if (action instanceof SendPacketAction) {
            if (module != null && module.useInstantExecutionMode()) {
                try {
                    action.execute(mc);
                } catch (Exception e) {
                    AutismClientMessaging.sendPrefixed("§cError in SendPacket: " + e.getMessage());
                }
            } else {
                mc.execute(() -> {
                    try {
                        action.execute(mc);
                    } catch (Exception e) {
                        AutismClientMessaging.sendPrefixed("§cError in SendPacket: " + e.getMessage());
                    }
                });
            }
        } else if (action instanceof ClickAction) {
            ClickAction ca = (ClickAction) action;
            runClickActionBurst(mc, ca);
        } else if (action instanceof PayAction) {
            runPayAction(mc, (PayAction) action);
        } else if (action instanceof XCarryAction xsa) {
            if (xsa.mode == XCarryAction.Mode.PUT_IN
                    && xsa.transferMode == XCarryAction.TransferMode.CLICK) {
                setCurrentStatus("XCarry Click");
                runXCarryClickPutIn(mc, xsa);
            } else {
                setCurrentStatus(switch (xsa.mode) {
                    case PUT_IN -> "XCarry";
                    case TAKE_OUT -> "XCarry Out";
                    case DROP -> "XCarry Drop";
                });
                runOnClientThread(mc, () -> {
                    try {
                        xsa.execute(mc);
                    } catch (Exception e) {
                        AutismClientMessaging.sendPrefixed("§cXCarry: " + e.getMessage());
                    }
                });
            }
        } else if (action instanceof CraftAction) {
            runCraftAction(mc, (CraftAction) action);
        } else if (action instanceof autismclient.util.macro.StoreItemAction) {
            autismclient.util.macro.StoreItemAction sia = (autismclient.util.macro.StoreItemAction) action;
            setCurrentStatus((sia.mode == autismclient.util.macro.StoreItemAction.Mode.LOOT ? "Looting" : "Storing")
                + (sia.persistent ? " \u221e" : ""));
            if (sia.persistent) {
                while (isCurrentRunActive()) {
                    try { runStoreItemAction(mc, sia); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                    catch (Exception e) { AutismClientMessaging.sendPrefixed("Store error: " + e.getMessage()); }
                    CompletableFuture<Void> siTick = MacroConditionRegistry.waitForNextTick();
                    waitForCondition(siTick);
                }
            } else {
                try { runStoreItemAction(mc, sia); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                catch (Exception e) { AutismClientMessaging.sendPrefixed("Store error: " + e.getMessage()); }
            }
        } else if (action instanceof InventoryAuditAction auditAction
                && (auditAction.mode == InventoryAuditAction.Mode.DUPE
                    || auditAction.mode == InventoryAuditAction.Mode.DUPE_SPAM)) {
            setCurrentStatus("Dupe: " + auditAction.dupeVector.name() + " " + auditAction.openMode.name());
            try {
                auditAction.executeDupe(mc);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                AutismClientMessaging.sendPrefixed("§cDupe error: " + e.getMessage());
            }
        } else if (action instanceof TickSyncAction tsa) {
            if (mc.level == null) return;
            java.util.List<net.minecraft.network.protocol.Packet<?>> preGenerated =
                preGeneratePacketsForRepeat(tsa.preGenCount, mc);
            long targetTick = mc.level.getGameTime() + tsa.tickOffset;
            setCurrentStatus("Tick Sync -> " + targetTick + " (" + preGenerated.size() + " pkts)");
            while (isCurrentRunActive() && mc.level.getGameTime() < targetTick) {
                java.util.concurrent.locks.LockSupport.parkNanos(1_000_000L);
            }
            if (isCurrentRunActive()) {
                executePreGeneratedBurst(mc, preGenerated);
                RepeatPacketContext context = REPEAT_PACKET_CONTEXT.get();
                if (context != null) REPEAT_PACKET_SKIP.set(countPreGeneratedActions(context.macro(), context.startIdx(), preGenerated.size(), context.endIdx()));
                AutismClientMessaging.sendPrefixed("Tick sync: " + preGenerated.size() + " pkts sent");
            }
        } else if (action instanceof RevisionSyncAction rsa) {
            if (mc.player == null || mc.player.containerMenu == null) return;
            java.util.List<net.minecraft.network.protocol.Packet<?>> preGenerated =
                preGeneratePacketsForRepeat(rsa.preGenCount, mc);
            int baseRevision = mc.player.containerMenu.getStateId();
            int targetRevision = baseRevision + rsa.revisionOffset;
            setCurrentStatus("Rev Sync -> " + targetRevision + " (" + preGenerated.size() + " pkts)");
            while (isCurrentRunActive()) {
                if (mc.player == null || mc.player.containerMenu == null) break;
                if (mc.player.containerMenu.getStateId() >= targetRevision) break;
                java.util.concurrent.locks.LockSupport.parkNanos(500_000L);
            }
            if (isCurrentRunActive() && mc.player != null && mc.player.containerMenu != null) {
                executePreGeneratedBurst(mc, preGenerated);
                RepeatPacketContext context = REPEAT_PACKET_CONTEXT.get();
                if (context != null) REPEAT_PACKET_SKIP.set(countPreGeneratedActions(context.macro(), context.startIdx(), preGenerated.size(), context.endIdx()));
                AutismClientMessaging.sendPrefixed("Rev sync: " + preGenerated.size() + " pkts sent");
            }
        } else if (action instanceof ServerTickSyncAction stsa) {
            if (mc.getConnection() == null) return;
            java.util.List<net.minecraft.network.protocol.Packet<?>> preGenerated =
                preGeneratePacketsForRepeat(stsa.preGenCount, mc);
            int sampleWaitMs = 0;
            while (isCurrentRunActive() && !ServerTickTracker.isReady() && sampleWaitMs < stsa.maxWaitMs) {
                float progress = ServerTickTracker.getWarmupProgress();
                setCurrentStatus(String.format("ServerSync warmup %.0f%% (%d samples, %dms)",
                    progress * 100, ServerTickTracker.getSampleCount(), ServerTickTracker.getTrackingTimeMs()));
                Thread.sleep(50);
                sampleWaitMs += 50;
            }
            if (!isCurrentRunActive()) return;
            long optimalTime = ServerTickTracker.getOptimalSendTime(stsa.bufferMs, stsa.ignorePing);
            long msUntil = (optimalTime - System.nanoTime()) / 1_000_000L;
            int ping = ServerTickTracker.getPingMs();
            String pingStr = stsa.ignorePing ? " (no ping)" : " ping:" + ping + "ms";
            setCurrentStatus("ServerSync in " + Math.max(0, msUntil) + "ms" + pingStr + " (" + preGenerated.size() + " pkts)");
            long startWait = System.nanoTime();
            long maxWaitNanos = stsa.maxWaitMs * 1_000_000L;
            while (isCurrentRunActive()) {
                long now = System.nanoTime();
                if ((now - startWait) >= maxWaitNanos) break;
                if (now >= optimalTime) break;
                long remaining = optimalTime - now;
                if (remaining > 2_000_000L) {
                    java.util.concurrent.locks.LockSupport.parkNanos(remaining - 2_000_000L);
                } else {
                    Thread.onSpinWait();
                }
            }
            if (isCurrentRunActive()) {
                executePreGeneratedBurst(mc, preGenerated);
                RepeatPacketContext context = REPEAT_PACKET_CONTEXT.get();
                if (context != null) REPEAT_PACKET_SKIP.set(countPreGeneratedActions(context.macro(), context.startIdx(), preGenerated.size(), context.endIdx()));
                long actualDelay = (System.nanoTime() - optimalTime) / 1_000_000L;
                AutismClientMessaging.sendPrefixed("ServerSync: " + preGenerated.size() + " pkts (+" + actualDelay + "ms)");
            }
        } else if (action instanceof BundleDupeV2Action bundleV2) {
            setCurrentStatus("Bundle Dupe V2");
            bundleV2.execute(mc);
        } else if (action instanceof StopMacroAction) {
            StopMacroAction stopAction = (StopMacroAction) action;
            if (stopAction.target == StopMacroAction.StopTarget.SELF) stopCurrentRun();
            else stopAction.execute(mc);
        } else {

            if (module != null && module.useInstantExecutionMode()) {
                java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
                mc.execute(() -> {
                    try {
                        action.execute(mc);
                    } catch (Exception e) {
                        AutismClientMessaging.sendPrefixed("§cError in action: " + e.getMessage());
                    } finally {
                        latch.countDown();
                    }
                });
                try {
                    latch.await(100, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            } else {
                mc.execute(() -> {
                    try {
                        action.execute(mc);
                    } catch (Exception e) {
                        AutismClientMessaging.sendPrefixed("§cError in action: " + e.getMessage());
                    }
                });
            }
        }

        if (postGuiFuture != null && isCurrentRunActive()) {
            try {
                waitForCondition(postGuiFuture);
            } catch (java.util.concurrent.CancellationException e) {}
        }

        if (isCurrentRunActive() && !isInstantInventoryAction(action) && module != null && module.useInstantExecutionMode()) {
            int delayUs = module.getActionDelayUs();
            if (delayUs > 0) java.util.concurrent.locks.LockSupport.parkNanos(delayUs * 1000L);
            else Thread.onSpinWait();
        }
        } finally {
            releaseClientResources(heldResources);
        }
    }

    private static java.util.List<net.minecraft.network.protocol.Packet<?>> preGeneratePacketsForRepeat(
            int count, Minecraft mc) {
        RepeatPacketContext context = REPEAT_PACKET_CONTEXT.get();
        if (context == null || context.macro() == null) return new java.util.ArrayList<>();
        return preGeneratePackets(context.macro(), context.startIdx(), count, context.endIdx());
    }

    private static boolean isTargetWaitInteractionAction(MacroAction action) {
        return action instanceof OpenContainerAction || action instanceof InteractEntityAction;
    }

    private static CompletableFuture<Void> runTargetWaitInteractionAction(Minecraft mc, MacroAction action) throws InterruptedException {
        boolean waitForTarget = switch (action) {
            case OpenContainerAction open -> open.waitForTarget;
            case InteractEntityAction interact -> interact.waitForTarget;
            default -> false;
        };

        if (!waitForTarget) {
            CompletableFuture<Void> postGuiFuture = createPostGuiFuture(mc, action);
            runTargetInteractionAttempt(mc, action);
            return postGuiFuture;
        }

        setCurrentStatus("Waiting target: " + action.getDisplayName());
        while (isCurrentRunActive()) {
            if (!canRunTargetInteractionNow(mc, action)) {
                java.util.concurrent.locks.LockSupport.parkNanos(MACRO_WAIT_POLL_NANOS);
                if (Thread.interrupted()) throw new InterruptedException();
                continue;
            }

            CompletableFuture<Void> postGuiFuture = createPostGuiFuture(mc, action);
            if (runTargetInteractionAttempt(mc, action)) {
                return postGuiFuture;
            }
            if (postGuiFuture != null) postGuiFuture.cancel(true);
            java.util.concurrent.locks.LockSupport.parkNanos(MACRO_WAIT_POLL_NANOS);
            if (Thread.interrupted()) throw new InterruptedException();
        }
        return null;
    }

    private static boolean canRunTargetInteractionNow(Minecraft mc, MacroAction action) throws InterruptedException {
        return callOnClientThread(mc, () -> {
            if (action instanceof OpenContainerAction open) return open.canExecuteNow(mc);
            if (action instanceof InteractEntityAction interact) return interact.canExecuteNow(mc);
            return true;
        }, Boolean.FALSE);
    }

    private static boolean runTargetInteractionAttempt(Minecraft mc, MacroAction action) throws InterruptedException {
        return callOnClientThread(mc, () -> {
            try {
                if (action instanceof OpenContainerAction open) return open.tryExecute(mc);
                if (action instanceof InteractEntityAction interact) return interact.tryExecute(mc);
                action.execute(mc);
                return true;
            } catch (Exception e) {
                AutismClientMessaging.sendPrefixed("§cError in " + action.getDisplayName() + ": " + e.getMessage());
                return true;
            }
        }, Boolean.FALSE);
    }

    private static CompletableFuture<Void> createPostGuiFuture(Minecraft mc, MacroAction action) {
        if (!(action instanceof WaitsForGui wfg) || !wfg.isWaitForGuiAfter()) return null;
        return createWaitsForGuiFuture(mc, wfg);
    }

    private static CompletableFuture<Void> createWaitsForGuiFuture(Minecraft mc, WaitsForGui wfg) {
        return wfg.isWaitForGuiChange()
                ? MacroConditionRegistry.waitForGuiChange(mc.screen)
                : MacroConditionRegistry.waitForGui(wfg.getWaitGuiName());
    }

    private static void waitForGuiBeforeAction(Minecraft mc, MacroAction action) throws InterruptedException {
        if (!(action instanceof WaitsForGui wfg)) return;
        waitForGuiBeforeAction(mc, wfg);
    }

    private static void waitForGuiBeforeAction(Minecraft mc, WaitsForGui action) throws InterruptedException {
        if (mc == null || action == null || !action.isWaitForGuiBefore()) return;
        if (isWaitGuiPresent(mc, action.getWaitGuiName())) {
            waitForCurrentGuiReady(mc);
            return;
        }
        CompletableFuture<Void> future = MacroConditionRegistry.waitForGui(action.getWaitGuiName());
        waitForCondition(future);
        waitForCurrentGuiReady(mc);
    }

    private static boolean isWaitGuiPresent(Minecraft mc, String guiName) {
        if (mc == null || mc.screen == null) return false;
        if (MacroGuiMatcher.isOwnScreen(mc.screen)) return false;
        String expected = guiName == null ? "" : guiName.trim();
        if (expected.isEmpty()) return true;
        String actual = mc.screen.getTitle() == null ? "" : mc.screen.getTitle().getString();
        return matchesWaitGuiTitle(expected, actual);
    }

    private static boolean matchesWaitGuiTitle(String expected, String actual) {
        if (expected == null || expected.isBlank() || actual == null || actual.isBlank()) return false;
        String expectedLower = expected.toLowerCase(Locale.ROOT);
        String actualLower = actual.toLowerCase(Locale.ROOT);
        if (expectedLower.equals(actualLower) || actualLower.contains(expectedLower)) return true;
        for (String word : expectedLower.split("\\s+")) {
            if (!word.isBlank() && !actualLower.contains(word)) return false;
        }
        return true;
    }

    private static void waitForCurrentGuiReady(Minecraft mc) throws InterruptedException {

        long deadline = System.nanoTime() + 500_000_000L;
        while (isCurrentRunActive()) {
            Boolean ready = callOnClientThread(mc, () -> {
                if (mc.player == null || mc.screen == null) return false;
                if (mc.player.containerMenu != null && mc.player.containerMenu != mc.player.inventoryMenu) {
                    return mc.player.containerMenu.getStateId() > 0;
                }
                return true;
            }, Boolean.FALSE);
            if (Boolean.TRUE.equals(ready)) return;
            if (System.nanoTime() >= deadline) return;
            java.util.concurrent.locks.LockSupport.parkNanos(MACRO_WAIT_POLL_NANOS);
            if (Thread.interrupted()) return;
        }
    }

    private static void runMacroActionOnClientThread(Minecraft mc, MacroAction action, String label) throws InterruptedException {
        runOnClientThread(mc, () -> {
            try {
                action.execute(mc);
            } catch (Exception e) {
                AutismClientMessaging.sendPrefixed("§cError in " + label + ": " + e.getMessage());
            }
        });
    }

    private static void runClickActionBurst(Minecraft mc, ClickAction action) throws InterruptedException {
        int times = Math.max(1, action.clickCount);
        runOnClientThread(mc, () -> {
            for (int c = 0; c < times && isCurrentRunActive(); c++) {
                try {
                    action.execute(mc);
                } catch (Exception e) {
                    AutismClientMessaging.sendPrefixed("§cError in Click: " + e.getMessage());
                    break;
                }
            }
        });
    }

    private static boolean isInstantInventoryAction(MacroAction action) {
        if (action instanceof ItemAction
                || action instanceof DropAction
                || action instanceof SwapSlotsAction
                || action instanceof ClickAction
                || action instanceof XCarryAction) {
            return true;
        }
        return action instanceof StoreItemAction storeItemAction && !storeItemAction.persistent;
    }

    private static void runStoreItemAction(Minecraft mc, StoreItemAction action) throws InterruptedException {
        if (action == null) return;
        int delayTicks = StoreItemAction.clampDelayTicks(action.delayTicks);
        if (delayTicks <= 0) {
            runOnClientThread(mc, () -> action.doTransfer(mc));
            return;
        }

        List<Integer> slots = callOnClientThread(mc, () -> action.collectTransferSlots(mc), List.of());
        for (int i = 0; i < slots.size() && isCurrentRunActive(); i++) {
            AutismInventoryClickHelper.click(mc, slots.get(i), 0, net.minecraft.world.inventory.ContainerInput.QUICK_MOVE);
            if (i + 1 < slots.size()) waitTicks(delayTicks);
        }

        if (action.closeAfter && !action.persistent) {
            runOnClientThread(mc, () -> AutismGuiActions.closeCurrentScreen(mc, action.closeSendPkt, false));
        }
    }

    private static void waitTicks(int ticks) {
        for (int i = 0; i < ticks && isCurrentRunActive(); i++) {
            CompletableFuture<Void> tickFuture = MacroConditionRegistry.waitForNextTick();
            waitForCondition(tickFuture);
        }
    }

    private static void waitForUseItemFinish(Minecraft mc, int fallbackTicks) throws InterruptedException {
        if (mc == null || !isCurrentRunActive()) return;

        if (!Boolean.TRUE.equals(callOnClientThread(mc,
                () -> mc.player != null && mc.player.isUsingItem(),
                Boolean.FALSE))) {
            return;
        }

        int remaining = callOnClientThread(mc,
                () -> mc.player == null ? 0 : Math.max(0, mc.player.getUseItemRemainingTicks()),
                0);
        int maxTicks = Math.max(8, remaining > 0 ? remaining + 8 : fallbackTicks + 8);
        maxTicks = Math.min(maxTicks, 72000);
        for (int i = 0; i < maxTicks && isCurrentRunActive(); i++) {
            if (!Boolean.TRUE.equals(callOnClientThread(mc,
                    () -> mc.player != null && mc.player.isUsingItem(),
                    Boolean.FALSE))) {
                return;
            }
            CompletableFuture<Void> tickFuture = MacroConditionRegistry.waitForNextTick();
            waitForCondition(tickFuture);
        }
    }

    private static void runXCarryClickPutIn(Minecraft mc, XCarryAction xsa) throws InterruptedException {
        if (mc == null || mc.player == null || mc.gameMode == null) return;

        if (xsa.requiresCursorStorage()) {
            setCurrentStatus("XCarry Cursor");
            runOnClientThread(mc, () -> xsa.execute(mc));
            return;
        }

        AtomicReference<net.minecraft.world.inventory.AbstractContainerMenu> containerHandlerRef = new AtomicReference<>();
        AtomicReference<autismclient.util.AutismContainerTarget> containerTargetRef = new AtomicReference<>();
        AtomicReference<List<Integer>> containerSlotIdsRef = new AtomicReference<>(List.of());
        AtomicBoolean usingContainer = new AtomicBoolean(false);

        runOnClientThread(mc, () -> {
            if (mc.player == null) return;
            net.minecraft.world.inventory.AbstractContainerMenu current = mc.player.containerMenu;
            if (current != null && current != mc.player.inventoryMenu) {
                usingContainer.set(true);
                containerHandlerRef.set(current);
                containerTargetRef.set(autismclient.util.AutismSharedState.get().getLastContainerTarget());
                containerSlotIdsRef.set(new ArrayList<>(xsa.collectContainerTransferSlots(current)));
            }
        });

        if (usingContainer.get() && containerTargetRef.get() == null) {
            AutismClientMessaging.sendPrefixed("XCarry click mode: missing container target, using fast mode.");
            runOnClientThread(mc, () -> xsa.execute(mc));
            return;
        }

        if (usingContainer.get()) {
            for (int slotId : containerSlotIdsRef.get()) {
                if (!isCurrentRunActive()) return;
                setCurrentStatus("XCarry Click: collect");
                runOnClientThread(mc, () -> {
                    if (mc.player == null || mc.gameMode == null) return;
                    net.minecraft.world.inventory.AbstractContainerMenu handler = containerHandlerRef.get();
                    if (handler == null || mc.player.containerMenu != handler) return;
                    if (slotId < 0 || slotId >= handler.slots.size()) return;
                    if (handler.slots.get(slotId).getItem().isEmpty()) return;
                    mc.gameMode.handleContainerInput(handler.containerId, slotId, 0, net.minecraft.world.inventory.ContainerInput.QUICK_MOVE, mc.player);
                });
                waitOneTick();
            }

            runOnClientThread(mc, () -> {
                if (mc.player == null || mc.getConnection() == null) return;
                net.minecraft.world.inventory.AbstractContainerMenu handler = containerHandlerRef.get();
                if (handler == null) return;
                mc.getConnection().send(new net.minecraft.network.protocol.game.ServerboundContainerClosePacket(handler.containerId));
            });
            waitOneTick();

            runOnClientThread(mc, () -> {
                if (mc.player != null) {
                    mc.player.containerMenu = mc.player.inventoryMenu;
                }
            });
            waitOneTick();
        }

        int xCarryAttempts = 0;
        java.util.HashSet<String> attemptedMoves = new java.util.HashSet<>();
        while (isCurrentRunActive() && xCarryAttempts++ < xsa.maxConfiguredEntries()) {
            XCarryAction.PutInMove move = callOnClientThread(mc, () -> {
                if (mc.player == null) return null;
                return xsa.findNextPutInMove(mc.player.inventoryMenu);
            }, null);
            if (move == null) break;
            String moveKey = move.sourceSlotId + ">" + move.targetSlotId;
            if (!attemptedMoves.add(moveKey)) break;

            setCurrentStatus("XCarry Click -> " + move.targetSlotId);
            runOnClientThread(mc, () -> {
                if (mc.player == null || mc.gameMode == null) return;
                net.minecraft.world.inventory.InventoryMenu handler = mc.player.inventoryMenu;
                mc.player.containerMenu = handler;
                if (!handler.getCarried().isEmpty()) return;
                if (move.sourceSlotId < 0 || move.sourceSlotId >= handler.slots.size()) return;
                if (move.targetSlotId < 0 || move.targetSlotId >= handler.slots.size()) return;
                if (handler.slots.get(move.sourceSlotId).getItem().isEmpty()) return;
                if (!handler.slots.get(move.targetSlotId).getItem().isEmpty()) return;
                mc.gameMode.handleContainerInput(handler.containerId, move.sourceSlotId, 0, net.minecraft.world.inventory.ContainerInput.PICKUP, mc.player);
            });

            boolean pickedUp = waitForXCarryCondition(mc, 400L, () -> {
                if (mc.player == null) return false;
                net.minecraft.world.inventory.InventoryMenu handler = mc.player.inventoryMenu;
                if (move.sourceSlotId < 0 || move.sourceSlotId >= handler.slots.size()) return false;
                return !handler.getCarried().isEmpty()
                        && handler.slots.get(move.sourceSlotId).getItem().isEmpty();
            });
            if (!pickedUp) {
                rollbackXCarryCursor(mc, move.sourceSlotId);
                break;
            }

            waitOneTick();

            runOnClientThread(mc, () -> {
                if (mc.player == null || mc.gameMode == null) return;
                net.minecraft.world.inventory.InventoryMenu handler = mc.player.inventoryMenu;
                mc.player.containerMenu = handler;
                if (handler.getCarried().isEmpty()) return;
                if (move.targetSlotId < 0 || move.targetSlotId >= handler.slots.size()) return;
                if (!handler.slots.get(move.targetSlotId).getItem().isEmpty()) return;
                mc.gameMode.handleContainerInput(handler.containerId, move.targetSlotId, 0, net.minecraft.world.inventory.ContainerInput.PICKUP, mc.player);
            });

            boolean placed = waitForXCarryCondition(mc, 500L, () -> {
                if (mc.player == null) return false;
                net.minecraft.world.inventory.InventoryMenu handler = mc.player.inventoryMenu;
                if (move.targetSlotId < 0 || move.targetSlotId >= handler.slots.size()) return false;
                return handler.getCarried().isEmpty()
                        && !handler.slots.get(move.targetSlotId).getItem().isEmpty();
            });
            if (!placed) {
                rollbackXCarryCursor(mc, move.sourceSlotId);
                break;
            }

            waitOneTick();
        }

        runOnClientThread(mc, () -> {
            if (mc.player != null) {
                boolean active = XCarryAction.hasStoredItems(mc.player.inventoryMenu, xsa.carryCursor, xsa.activeStorageSlotIds());
                autismclient.util.AutismSharedState shared = autismclient.util.AutismSharedState.get();
                if (active) {
                    shared.mergeXCarryForcedTargets(xsa.activeStorageSlotIds(), xsa.carryCursor);
                }
                shared.setXCarryForced(active);
                shared.setXCarryActive(active);
            }
        });

        if (usingContainer.get()) {
            runOnClientThread(mc, () -> {
                if (mc.player == null) return;
                net.minecraft.world.inventory.AbstractContainerMenu containerHandler = containerHandlerRef.get();
                if (containerHandler != null) {
                    mc.player.containerMenu = containerHandler;
                }
                autismclient.util.AutismContainerTarget containerTarget = containerTargetRef.get();
                if (containerTarget != null) {
                    XCarryAction.sendOpenTarget(mc, containerTarget);
                }
            });
            waitOneTick();
        }
    }

    private static void rollbackXCarryCursor(Minecraft mc, int sourceSlotId) throws InterruptedException {
        runOnClientThread(mc, () -> {
            if (mc.player == null || mc.gameMode == null) return;
            net.minecraft.world.inventory.InventoryMenu handler = mc.player.inventoryMenu;
            mc.player.containerMenu = handler;
            if (handler.getCarried().isEmpty()) return;
            if (sourceSlotId < 0 || sourceSlotId >= handler.slots.size()) return;
            mc.gameMode.handleContainerInput(handler.containerId, sourceSlotId, 0, net.minecraft.world.inventory.ContainerInput.PICKUP, mc.player);
        });
        waitForXCarryCondition(mc, 300L, () -> mc.player != null && mc.player.inventoryMenu.getCarried().isEmpty());
    }

    private static boolean waitForXCarryCondition(Minecraft mc, long timeoutMs, java.util.function.Supplier<Boolean> condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (isCurrentRunActive()) {
            if (Boolean.TRUE.equals(callOnClientThread(mc, condition, Boolean.FALSE))) {
                return true;
            }
            if (System.nanoTime() >= deadline) break;
            waitOneTick();
        }
        return Boolean.TRUE.equals(callOnClientThread(mc, condition, Boolean.FALSE));
    }

    private static void waitOneTick() {
        CompletableFuture<Void> tickFuture = MacroConditionRegistry.waitForNextTick();
        waitForCondition(tickFuture);
    }

    private static void runInstaBreakAction(Minecraft mc, InstaBreakAction action) throws InterruptedException {
        if (mc == null || mc.player == null || mc.level == null || mc.getConnection() == null || mc.gameMode == null) {
            AutismClientMessaging.sendPrefixed("§cInstaBreak: missing world or connection");
            return;
        }

        BlockPos target = action.blockPos == null ? BlockPos.ZERO : action.blockPos;
        Direction direction = action.direction == null ? Direction.UP : action.direction;
        int delayTicks = Math.max(0, action.delayTicks);
        int targetTimes = Math.max(0, action.times);
        int completed = 0;
        boolean wasSolid = !isInstaBreakAir(mc, target);

        if (!ensureInstaBreakPickaxe(mc, action)) return;

        AutismInstaBreakRenderer.setTarget(target);
        try {
            primeInstaBreakTarget(mc, target, direction);
            long lastAttemptTick = AutismSharedState.get().getClientTickCounter();
            long lastSneakTick = Long.MIN_VALUE;
            while (isCurrentRunActive() && isActionStillInCurrentMacro(action)) {

                if (action.sneak) {
                    long sneakTick = AutismSharedState.get().getClientTickCounter();
                    if (sneakTick != lastSneakTick) {
                        lastSneakTick = sneakTick;
                        final String sneakMode = action.sneakMode;
                        runOnClientThread(mc, () -> holdMacroSneak(mc, sneakMode));
                    }
                }

                boolean isAir = isInstaBreakAir(mc, target);
                if (wasSolid && isAir) {
                    completed++;
                    setCurrentStatus("InstaBreak " + completed + "/" + (targetTimes == 0 ? "∞" : targetTimes));
                    if (targetTimes > 0 && completed >= targetTimes) return;
                    wasSolid = false;
                } else if (!isAir) {
                    wasSolid = true;
                }

                if (!isAir) {
                    long currentTick = AutismSharedState.get().getClientTickCounter();
                    if ((currentTick - lastAttemptTick) >= delayTicks) {
                        lastAttemptTick = currentTick;
                        if (!ensureInstaBreakPickaxe(mc, action)) return;
                        if (action.interact) {

                            InteractTiming t = action.interactTiming;
                            boolean interactFirst = t == InteractTiming.BEFORE || t == InteractTiming.WITH
                                || (t == InteractTiming.CUSTOM && action.interactCustomMs < 0);
                            long ns = t == InteractTiming.CUSTOM
                                ? Math.abs((long) action.interactCustomMs) * 1_000_000L : 0L;
                            if (interactFirst) {
                                runOnClientThread(mc, () -> sendBlockInteract(mc, target, direction));
                                if (ns > 0) java.util.concurrent.locks.LockSupport.parkNanos(ns);
                                sendInstaBreakAttempt(mc, target, direction);
                            } else if (t == InteractTiming.AFTER_PLUS) {
                                sendInstaBreakAttempt(mc, target, direction);
                                waitOneTick();
                                runOnClientThread(mc, () -> sendBlockInteract(mc, target, direction));
                            } else {
                                sendInstaBreakAttempt(mc, target, direction);
                                if (ns > 0) java.util.concurrent.locks.LockSupport.parkNanos(ns);
                                runOnClientThread(mc, () -> sendBlockInteract(mc, target, direction));
                            }
                        } else {
                            sendInstaBreakAttempt(mc, target, direction);
                        }
                    }
                }

                setCurrentStatus("InstaBreak " + target.getX() + "," + target.getY() + "," + target.getZ()
                    + " " + completed + "/" + (targetTimes == 0 ? "∞" : targetTimes));

                Thread.sleep(1);
            }
        } finally {
            if (action.sneak) {
                final String sneakMode = action.sneakMode;
                try {
                    runOnClientThread(mc, () -> releaseMacroSneak(mc, sneakMode));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            AutismInstaBreakRenderer.clearTarget(target);
        }
    }

    private static boolean isInstaBreakAir(Minecraft mc, BlockPos pos) {

        return mc.level == null || mc.level.isOutsideBuildHeight(pos) || mc.level.getBlockState(pos).isAir();
    }

    private static boolean isActionStillInCurrentMacro(MacroAction action) {
        RunState run = CURRENT_RUN.get();
        if (run == null || run.macro == null || run.macro.actions == null) return true;
        return run.macro.actions.contains(action);
    }

    private static boolean ensureInstaBreakPickaxe(Minecraft mc, InstaBreakAction action) throws InterruptedException {
        return callOnClientThread(mc, () -> {
            if (mc.player == null) return false;
            if (mc.player.getMainHandItem().is(ItemTags.PICKAXES)) return true;
            if (!action.autoPickaxe) return false;

            int preferred = Math.max(0, Math.min(8, mc.player.getInventory().getSelectedSlot()));
            for (int slot = 0; slot < 9; slot++) {
                if (mc.player.getInventory().getItem(slot).is(ItemTags.PICKAXES)) {
                    AutismInventoryHelper.selectHotbarSlot(mc, slot);
                    return true;
                }
            }

            for (int slot = 9; slot < 36; slot++) {
                if (mc.player.getInventory().getItem(slot).is(ItemTags.PICKAXES)) {
                    if (!AutismInventoryHelper.swapInventorySlots(mc, slot, preferred)) return false;
                    AutismInventoryHelper.selectHotbarSlot(mc, preferred);
                    return true;
                }
            }

            return false;
        }, false);
    }

    private static void sendInstaBreakAttempt(Minecraft mc, BlockPos pos, Direction direction) throws InterruptedException {
        runOnClientThread(mc, () -> {
            if (mc.level == null || mc.gameMode == null || mc.getConnection() == null) return;
            mc.getConnection().send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, pos, direction));
        });
    }

    private static BlockPos lastInstaBreakPos = null;

    private static void primeInstaBreakTarget(Minecraft mc, BlockPos pos, Direction direction) throws InterruptedException {
        if (pos != null && pos.equals(lastInstaBreakPos)) return;
        lastInstaBreakPos = pos;
        runOnClientThread(mc, () -> {
            if (mc.level == null || mc.gameMode == null || mc.getConnection() == null) return;
            mc.gameMode.startDestroyBlock(pos, direction);
            mc.getConnection().send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, pos, direction));
        });
    }

    private static void runBreakAction(Minecraft mc, BreakAction action) throws InterruptedException {
        if (mc == null || mc.player == null || mc.level == null || mc.getConnection() == null || mc.gameMode == null) {
            AutismClientMessaging.sendPrefixed("§cBreak: missing world or connection");
            return;
        }

        final BlockPos target = action.blockPos == null ? BlockPos.ZERO : action.blockPos;
        final Direction direction = action.direction == null ? Direction.UP : action.direction;
        int startDelay = Math.max(0, action.delayTicks);
        int targetTimes = Math.max(0, action.times);
        int completed = 0;

        if (startDelay > 0) waitBreakClientTicks(startDelay);

        final boolean[] started = { false };
        AutismInstaBreakRenderer.setTarget(target);
        long lastTick = -1L;
        try {
            while (isCurrentRunActive() && isActionStillInCurrentMacro(action)) {
                long now = AutismSharedState.get().getClientTickCounter();
                if (now == lastTick) {
                    Thread.sleep(1);
                    continue;
                }
                lastTick = now;

                int result;
                if (!started[0]) {
                    result = callOnClientThread(mc, () -> startBreakOnClient(mc, action, target, direction, started), -1);
                } else if (action.interact && action.interactTiming == InteractTiming.CUSTOM) {
                    result = breakCustomTick(mc, action, target, direction);
                } else {
                    result = callOnClientThread(mc, () -> breakContinueOnClient(mc, action, target, direction), -1);
                }

                if (result < 0) {
                    return;
                } else if (result == 1) {
                    if (action.interact && action.interactTiming == InteractTiming.AFTER_PLUS) {
                        waitOneTick();
                        runOnClientThread(mc, () -> sendBlockInteract(mc, target, direction));
                    }
                    completed++;
                    setCurrentStatus("Break " + completed + "/" + (targetTimes == 0 ? "∞" : targetTimes));
                    if (targetTimes > 0 && completed >= targetTimes) return;
                    started[0] = false;
                    waitForBreakSolid(mc, target);
                } else {
                    setCurrentStatus("Break " + target.getX() + "," + target.getY() + "," + target.getZ()
                        + " " + completed + "/" + (targetTimes == 0 ? "∞" : targetTimes));
                }
            }
        } finally {
            final boolean wasMining = started[0];
            runOnClientThread(mc, () -> {
                if (wasMining && mc.gameMode != null && mc.gameMode.isDestroying()) mc.gameMode.stopDestroyBlock();
                if (action.sneak) releaseMacroSneak(mc, action.sneakMode);
            });
            AutismInstaBreakRenderer.clearTarget(target);
        }
    }

    private static int startBreakOnClient(Minecraft mc, BreakAction action, BlockPos pos, Direction dir, boolean[] started) {
        if (mc.level == null || mc.gameMode == null || mc.player == null || mc.getConnection() == null) return -1;
        BlockState state = mc.level.getBlockState(pos);
        if (state.isAir() || mc.level.isOutsideBuildHeight(pos)) return 1;

        if (action.sneak) holdMacroSneak(mc, action.sneakMode);
        if (action.autoTool) {
            AutismAutoTool.equipBestTool(mc, state, action.considerInventory, true);
            state = mc.level.getBlockState(pos);
        }

        float startDelta = state.getDestroyProgress(mc.player, mc.player.level(), pos);
        boolean instant = startDelta >= 1.0f;
        boolean interactFirst = action.interact && (action.interactTiming == InteractTiming.BEFORE
            || action.interactTiming == InteractTiming.WITH
            || (action.interactTiming == InteractTiming.CUSTOM && action.interactCustomMs < 0));
        if (instant && interactFirst) sendBlockInteract(mc, pos, dir);
        mc.gameMode.startDestroyBlock(pos, dir);
        mc.player.swing(InteractionHand.MAIN_HAND);
        started[0] = true;
        if (instant && action.interact && !interactFirst && action.interactTiming != InteractTiming.AFTER_PLUS) sendBlockInteract(mc, pos, dir);
        return mc.level.getBlockState(pos).isAir() ? 1 : 0;
    }

    private static int breakContinueOnClient(Minecraft mc, BreakAction action, BlockPos pos, Direction dir) {
        if (mc.level == null || mc.gameMode == null || mc.player == null || mc.getConnection() == null) return -1;
        BlockState state = mc.level.getBlockState(pos);
        if (state.isAir()) return 1;
        if (action.sneak) holdMacroSneak(mc, action.sneakMode);

        boolean breaksThisTick = willBreakThisTick(mc, pos, state);
        boolean breaksNextTick = !breaksThisTick && willBreakNextTick(mc, pos, state);
        InteractTiming t = action.interactTiming;
        if (action.interact && t == InteractTiming.BEFORE && breaksNextTick) sendBlockInteract(mc, pos, dir);
        if (action.interact && t == InteractTiming.WITH && breaksThisTick) sendBlockInteract(mc, pos, dir);

        mc.gameMode.continueDestroyBlock(pos, dir);
        mc.player.swing(InteractionHand.MAIN_HAND);

        if (action.interact && t == InteractTiming.AFTER && breaksThisTick) sendBlockInteract(mc, pos, dir);
        return mc.level.getBlockState(pos).isAir() ? 1 : 0;
    }

    private static int breakCustomTick(Minecraft mc, BreakAction action, BlockPos pos, Direction dir) throws InterruptedException {
        boolean willBreak = Boolean.TRUE.equals(callOnClientThread(mc, () -> {
            if (mc.level == null || mc.player == null) return Boolean.FALSE;
            BlockState s = mc.level.getBlockState(pos);
            if (s.isAir()) return Boolean.FALSE;
            return willBreakThisTick(mc, pos, s);
        }, Boolean.FALSE));

        if (!willBreak) {

            return callOnClientThread(mc, () -> {
                if (mc.level == null || mc.gameMode == null || mc.player == null) return -1;
                if (mc.level.getBlockState(pos).isAir()) return 1;
                if (action.sneak) holdMacroSneak(mc, action.sneakMode);
                mc.gameMode.continueDestroyBlock(pos, dir);
                mc.player.swing(InteractionHand.MAIN_HAND);
                return mc.level.getBlockState(pos).isAir() ? 1 : 0;
            }, -1);
        }

        long ns = Math.abs((long) action.interactCustomMs) * 1_000_000L;
        int broke;
        if (action.interactCustomMs < 0) {

            runOnClientThread(mc, () -> sendBlockInteract(mc, pos, dir));
            if (ns > 0) java.util.concurrent.locks.LockSupport.parkNanos(ns);
            broke = callOnClientThread(mc, () -> breakAndDetectOnClient(mc, pos, dir), -1);
        } else {

            broke = callOnClientThread(mc, () -> breakAndDetectOnClient(mc, pos, dir), -1);
            if (ns > 0) java.util.concurrent.locks.LockSupport.parkNanos(ns);
            runOnClientThread(mc, () -> sendBlockInteract(mc, pos, dir));
        }
        return broke;
    }

    private static int breakAndDetectOnClient(Minecraft mc, BlockPos pos, Direction dir) {
        if (mc.level == null || mc.gameMode == null || mc.player == null) return -1;
        if (mc.level.getBlockState(pos).isAir()) return 1;
        mc.gameMode.continueDestroyBlock(pos, dir);
        mc.player.swing(InteractionHand.MAIN_HAND);
        return mc.level.getBlockState(pos).isAir() ? 1 : 0;
    }

    private static boolean willBreakThisTick(Minecraft mc, BlockPos pos, BlockState state) {
        float progress = (mc.gameMode instanceof AutismMultiPlayerGameModeAccessor acc) ? acc.autism$getDestroyProgress() : 0f;
        float delta = state.getDestroyProgress(mc.player, mc.player.level(), pos);
        return progress > 0f && (progress + delta) >= 1.0f;
    }

    private static boolean willBreakNextTick(Minecraft mc, BlockPos pos, BlockState state) {
        float progress = (mc.gameMode instanceof AutismMultiPlayerGameModeAccessor acc) ? acc.autism$getDestroyProgress() : 0f;
        float delta = state.getDestroyProgress(mc.player, mc.player.level(), pos);
        return progress > 0f && (progress + 2f * delta) >= 1.0f;
    }

    private static void sendBlockInteract(Minecraft mc, BlockPos pos, Direction dir) {
        if (mc.gameMode == null || mc.player == null) return;
        Vec3 hit = Vec3.atCenterOf(pos).add(dir.getStepX() * 0.5, dir.getStepY() * 0.5, dir.getStepZ() * 0.5);
        BlockHitResult blockHit = new BlockHitResult(hit, dir, pos, false);
        mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, blockHit);
    }

    private static void waitForBreakSolid(Minecraft mc, BlockPos pos) throws InterruptedException {
        while (isCurrentRunActive()) {
            if (!isInstaBreakAir(mc, pos)) return;
            Thread.sleep(1);
        }
    }

    private static void waitBreakClientTicks(int ticks) throws InterruptedException {
        long start = AutismSharedState.get().getClientTickCounter();
        while (isCurrentRunActive() && (AutismSharedState.get().getClientTickCounter() - start) < ticks) {
            Thread.sleep(1);
        }
    }

    private static void holdMacroSneak(Minecraft mc, String mode) {
        autismclient.util.AutismMacroSneak.hold(mc, mode);
    }

    private static void releaseMacroSneak(Minecraft mc, String mode) {
        autismclient.util.AutismMacroSneak.release(mc, mode);
    }

    private static void runOnClientThread(Minecraft mc, Runnable runnable) throws InterruptedException {
        if (mc.isSameThread()) {
            runnable.run();
            return;
        }
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        mc.execute(() -> {
            try {
                runnable.run();
            } finally {
                latch.countDown();
            }
        });
        latch.await(2000, TimeUnit.MILLISECONDS);
    }

    private static <T> T callOnClientThread(Minecraft mc, java.util.function.Supplier<T> supplier, T fallback) throws InterruptedException {
        if (mc.isSameThread()) {
            return supplier.get();
        }
        AtomicReference<T> result = new AtomicReference<>(fallback);
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        mc.execute(() -> {
            try {
                result.set(supplier.get());
            } finally {
                latch.countDown();
            }
        });
        latch.await(2000, TimeUnit.MILLISECONDS);
        return result.get();
    }

    private static void runPlaceAction(Minecraft mc, PlaceAction action) throws InterruptedException {
        if (mc == null || mc.player == null || mc.level == null) return;

        ItemTarget target = action.resolvedItemTarget();
        if (action.waitForItem && target != null && (target.hasIdentity() || target.hasSlot())) {
            setCurrentStatus("Waiting for " + describeItemTarget(target));
            waitForItemActionEntry(mc, target);
        }

        if (!action.interact) {

            runMacroActionOnClientThread(mc, action, "Place");
            return;
        }

        final BlockPos placePos = action.blockPos == null ? BlockPos.ZERO : action.blockPos;
        final Direction face = action.manualDirection && action.direction != null ? action.direction : Direction.UP;
        InteractTiming t = action.interactTiming;
        boolean interactFirst = t == InteractTiming.BEFORE || (t == InteractTiming.CUSTOM && action.interactCustomMs < 0);
        long ns = t == InteractTiming.CUSTOM ? Math.abs((long) action.interactCustomMs) * 1_000_000L : 0L;

        if (interactFirst) {

            runOnClientThread(mc, () -> sendBlockInteract(mc, placePos, face));
            if (ns > 0) java.util.concurrent.locks.LockSupport.parkNanos(ns);
            runMacroActionOnClientThread(mc, action, "Place");
        } else if (t == InteractTiming.WITH) {

            runOnClientThread(mc, () -> {
                try { action.execute(mc); } catch (Exception ignored) {}
                sendBlockInteract(mc, placePos, face);
            });
        } else if (t == InteractTiming.AFTER_PLUS) {
            runMacroActionOnClientThread(mc, action, "Place");
            waitOneTick();
            runOnClientThread(mc, () -> sendBlockInteract(mc, placePos, face));
        } else {

            runMacroActionOnClientThread(mc, action, "Place");
            if (ns > 0) java.util.concurrent.locks.LockSupport.parkNanos(ns);
            runOnClientThread(mc, () -> sendBlockInteract(mc, placePos, face));
        }
    }

    private static void waitForItemInSpecificHandlerSlot(Minecraft mc, int slotId, ItemTarget target) throws InterruptedException {
        while (isCurrentRunActive()) {
            AtomicBoolean matched = new AtomicBoolean(false);
            java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
            mc.execute(() -> {
                try {
                    matched.set(handlerSlotMatchesItem(mc, slotId, target));
                } finally {
                    latch.countDown();
                }
            });
            latch.await(200, TimeUnit.MILLISECONDS);
            if (matched.get()) return;

            java.util.concurrent.locks.LockSupport.parkNanos(MACRO_WAIT_POLL_NANOS);
            if (Thread.interrupted()) return;
        }
    }

    private static void waitForAnyItemInSpecificHandlerSlot(Minecraft mc, int slotId) throws InterruptedException {
        while (isCurrentRunActive()) {
            AtomicBoolean matched = new AtomicBoolean(false);
            java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
            mc.execute(() -> {
                try {
                    matched.set(handlerSlotHasAnyItem(mc, slotId));
                } finally {
                    latch.countDown();
                }
            });
            latch.await(200, TimeUnit.MILLISECONDS);
            if (matched.get()) return;
            java.util.concurrent.locks.LockSupport.parkNanos(MACRO_WAIT_POLL_NANOS);
            if (Thread.interrupted()) return;
        }
    }

    private static boolean handlerSlotMatchesItem(Minecraft mc, int slotId, ItemTarget target) {
        if (mc.player == null || mc.player.containerMenu == null) return false;
        int handlerSlot = autismclient.util.AutismInventoryHelper.resolveConfiguredHandlerSlot(mc, slotId);
        if (handlerSlot < 0 || handlerSlot >= mc.player.containerMenu.slots.size()) return false;
        var slot = mc.player.containerMenu.slots.get(handlerSlot);
        if (slot == null || slot.getItem().isEmpty()) return false;
        ItemTarget resolvedTarget = target == null ? ItemTarget.slotOnly(slotId) : target.copy();
        if (!resolvedTarget.hasSlot()) resolvedTarget.slot = slotId;
        return resolvedTarget.matches(slot.getItem(), slotId);
    }

    private static boolean handlerSlotHasAnyItem(Minecraft mc, int slotId) {
        if (mc.player == null || mc.player.containerMenu == null) return false;
        int handlerSlot = autismclient.util.AutismInventoryHelper.resolveConfiguredHandlerSlot(mc, slotId);
        if (handlerSlot < 0 || handlerSlot >= mc.player.containerMenu.slots.size()) return false;
        var slot = mc.player.containerMenu.slots.get(handlerSlot);
        return slot != null && !slot.getItem().isEmpty();
    }

    private static void waitForItemActionTargets(Minecraft mc, ItemAction ia) throws InterruptedException {
        if (ia == null) return;

        List<ItemTarget> targets = resolveItemTargets(ia.itemTargets, ia.itemNames);
        if (!targets.isEmpty()) {
            for (ItemTarget target : targets) {
                waitForItemActionEntry(mc, target);
            }
            return;
        }

        if (ia.useSlot && ia.targetSlot >= 0) {
            setCurrentStatus("Waiting for slot " + ia.targetSlot);
            waitForAnyItemInSpecificHandlerSlot(mc, ia.targetSlot);
        }
    }

    private static void waitForItemActionEntry(Minecraft mc, ItemTarget target) throws InterruptedException {
        ItemTarget resolvedTarget = target == null ? new ItemTarget() : target.copy();
        String itemLabel = describeItemTarget(resolvedTarget);
        int slot = resolvedTarget.hasSlot() ? resolvedTarget.slot : -1;

        if (slot >= 0 && resolvedTarget.hasIdentity()) {
            setCurrentStatus("Waiting for " + itemLabel);
            waitForItemInSpecificHandlerSlot(mc, slot, resolvedTarget);
            return;
        }

        if (slot >= 0) {
            setCurrentStatus("Waiting for slot " + slot);
            waitForAnyItemInSpecificHandlerSlot(mc, slot);
            return;
        }

        if (resolvedTarget.hasIdentity()) {
            setCurrentStatus("Waiting for item in GUI: " + itemLabel);
            CompletableFuture<Void> waitFuture = MacroConditionRegistry.waitForItemInHandler(resolvedTarget);
            waitForCondition(waitFuture);
        }
    }

    private static ItemTarget resolveItemTarget(ItemTarget target, String legacyEntry) {
        if (target != null && (target.hasSlot() || target.hasIdentity())) return target.copy();
        return ItemTarget.fromLegacyEntry(legacyEntry);
    }

    private static List<ItemTarget> resolveItemTargets(List<ItemTarget> targets, List<String> legacyEntries) {
        List<ItemTarget> resolved = new ArrayList<>();
        if (targets != null) {
            for (ItemTarget target : targets) {
                if (target == null || (!target.hasSlot() && !target.hasIdentity())) continue;
                resolved.add(target.copy());
            }
        }
        if (!resolved.isEmpty()) return resolved;
        if (legacyEntries == null) return resolved;
        for (String entry : legacyEntries) {
            ItemTarget parsed = ItemTarget.fromLegacyEntry(entry);
            if (parsed.hasSlot() || parsed.hasIdentity()) resolved.add(parsed);
        }
        return resolved;
    }

    private static String describeItemTarget(ItemTarget target) {
        if (target == null) return "";
        String summary = target.summaryText();
        if (summary != null && !summary.isBlank()) return summary;
        String legacy = target.toLegacyEntry();
        return legacy == null ? "" : legacy;
    }

    private static void runCraftAction(Minecraft mc, CraftAction craftAction) throws InterruptedException {
        if (craftAction == null || !craftAction.hasEntries()) {
            setCurrentStatus("Crafting");
            AutismClientMessaging.sendPrefixed("§cNo craft recipes selected.");
            return;
        }

        for (int index = 0; index < craftAction.entries.size(); index++) {
            CraftAction.CraftEntry entry = craftAction.entries.get(index);
            if (entry == null || !entry.hasRecipe()) continue;

            setCurrentStatus(entry.useMaxAmount
                ? "Crafting " + entry.resultName + " [Max] (" + (index + 1) + "/" + craftAction.entries.size() + ")"
                : "Crafting " + entry.resultName + " x" + Math.max(1, entry.amount) + " (" + (index + 1) + "/" + craftAction.entries.size() + ")");

            AutismCraftingHelper.CraftableRecipeOption option = AutismCraftingHelper.findCraftableRecipe(mc, entry.recipeKey);
            if (option == null) option = AutismCraftingHelper.findCraftableRecipe(mc, entry.recipeId);
            if (option == null) {
                setCurrentStatus("Recipe not found");
                AutismClientMessaging.sendPrefixed("§cRecipe not found: " + (entry.resultName == null || entry.resultName.isBlank() ? "unknown" : entry.resultName));
                return;
            }

            int desiredAmount = AutismCraftingHelper.getEffectiveRequestedOutput(option, entry.amount, entry.useMaxAmount);
            if (desiredAmount <= 0) {
                setCurrentStatus("No space or materials");
                AutismClientMessaging.sendPrefixed("§cNo space or materials for " + entry.resultName + ".");
                return;
            }

            AutismCraftingHelper.CraftExecutionResult result = AutismCraftingHelper.executeCraftImmediately(
                mc,
                entry.recipeKey,
                entry.recipeId,
                desiredAmount
            );

            setCurrentStatus(result.message);
            if (!result.success) {
                AutismClientMessaging.sendPrefixed("§c" + result.message);
                return;
            }
            if (isCurrentRunActive()) {
                CompletableFuture<Void> tickFuture = MacroConditionRegistry.waitForNextTick();
                waitForCondition(tickFuture);
            }
        }
    }

    private static void runGoToAction(Minecraft mc, GoToAction goToAction) throws InterruptedException {
        setCurrentStatus(String.format("Going to %.0f,%.0f,%.0f", goToAction.x, goToAction.y, goToAction.z));

        if (!AutismCompatManager.isBaritoneAvailable()) {
            AutismClientMessaging.sendPrefixed("Baritone not installed; skipping goto action.");
            return;
        }

        int goalX = (int) Math.floor(goToAction.x);
        int goalY = (int) Math.floor(goToAction.y);
        int goalZ = (int) Math.floor(goToAction.z);

        if (!runBaritoneStart(mc, () -> AutismCompatManager.startBaritoneGoTo(mc, goalX, goalY, goalZ))) {
            AutismClientMessaging.sendPrefixed("Failed to start Baritone goto.");
            return;
        }

        try {
            if (!goToAction.waitForArrival) {
                setCurrentStatus("Goto started");
                return;
            }

            long startMs = System.currentTimeMillis();
            boolean sawBaritoneActivity = false;

            while (isCurrentRunActive()) {
                boolean goalActive = AutismCompatManager.isBaritoneGoalActive();
                boolean baritoneBusy = AutismCompatManager.isBaritoneBusy();
                if (goalActive || baritoneBusy) sawBaritoneActivity = true;

                if (sawBaritoneActivity && !goalActive && !baritoneBusy) {
                    setCurrentStatus("Goto finished");
                    return;
                }

                if (!sawBaritoneActivity && System.currentTimeMillis() - startMs >= 1500 && !goalActive && !baritoneBusy) {
                    setCurrentStatus("Goto finished");
                    return;
                }

                CompletableFuture<Void> tickFuture = MacroConditionRegistry.waitForNextTick();
                waitForCondition(tickFuture);
            }
        } finally {
            if (!isCurrentRunActive()) requestBaritoneStop(mc);
        }
    }

    private static void runMineAction(Minecraft mc, MineAction mineAction) throws InterruptedException {
        String mineDesc = mineAction.targetBlocks.isEmpty() ? "none" : mineAction.targetBlocks.get(0);
        setCurrentStatus("Mining: " + mineDesc);

        if (mineAction.targetBlocks.isEmpty()) return;
        if (!AutismCompatManager.isBaritoneAvailable()) {
            AutismClientMessaging.sendPrefixed("Baritone not installed; skipping mine action.");
            return;
        }

        java.util.List<String> blockNames = new java.util.ArrayList<>();
        for (String id : mineAction.targetBlocks) {
            if (id == null || id.isBlank()) continue;
            blockNames.add(id.startsWith("minecraft:") ? id.substring(10) : id);
        }
        if (blockNames.isEmpty()) return;

        RunState run = CURRENT_RUN.get();
        MineProgressTracker mineTracker = mineAction.stopMinedCount ? MineProgressTracker.create(mc, mineAction.targetBlocks) : null;
        if (mineTracker != null) {
            mineTracker.poll(mc);
            if (run != null) run.activeMineTracker = mineTracker;
        }

        try {
            if (!runBaritoneStart(mc, () -> AutismCompatManager.startBaritoneMine(mc, blockNames))) {
                AutismClientMessaging.sendPrefixed("Failed to start Baritone mine.");
                return;
            }

            long mineStartMs = System.currentTimeMillis();
            boolean sawBaritoneActivity = false;

            while (isCurrentRunActive()) {
                boolean mineActive = AutismCompatManager.isBaritoneMineActive();
                boolean baritoneBusy = AutismCompatManager.isBaritoneBusy();
                if (mineActive || baritoneBusy) sawBaritoneActivity = true;

                String stopReason = null;
                int minedDelta = 0;

                if (mineTracker != null) {
                    mineTracker.poll(mc);
                    minedDelta = mineTracker.destroyedCount();
                    setCurrentStatus("Mining: " + mineDesc + " (" + minedDelta + "/" + mineAction.minedCountTarget + ")");
                    if (minedDelta >= mineAction.minedCountTarget) {
                        stopReason = "Mine target reached";
                    }
                }

                if (stopReason == null && mineAction.stopInventoryFull && isInventoryFull(mc)) {
                    stopReason = "Mine stop: inventory full";
                }

                if (stopReason == null && mineAction.stopSlotsUsed) {
                    int used = countUsedMainInventorySlots(mc);
                    if (used >= mineAction.slotsUsedThreshold) {
                        stopReason = "Mine stop: slots used";
                    }
                }

                if (stopReason == null && mineAction.stopAfterTime
                    && System.currentTimeMillis() - mineStartMs >= mineAction.timeoutSeconds * 1000L) {
                    stopReason = "Mine timeout";
                }

                if (stopReason != null) {
                    setCurrentStatus(stopReason);
                    requestBaritoneStop(mc);
                    waitForBaritoneIdle();
                    return;
                }

                if (sawBaritoneActivity && !mineActive && !baritoneBusy) {
                    setCurrentStatus("Mine finished");
                    return;
                }

                if (!sawBaritoneActivity && System.currentTimeMillis() - mineStartMs >= 1500 && !mineActive && !baritoneBusy) {
                    setCurrentStatus("Mine finished");
                    return;
                }

                CompletableFuture<Void> tickFuture = MacroConditionRegistry.waitForNextTick();
                waitForCondition(tickFuture);
            }
        } finally {
            if (run != null && run.activeMineTracker == mineTracker) run.activeMineTracker = null;
            if (!isCurrentRunActive()) requestBaritoneStop(mc);
        }
    }

    private static boolean runBaritoneStart(Minecraft mc, java.util.function.BooleanSupplier starter) throws InterruptedException {
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        AtomicBoolean started = new AtomicBoolean(false);

        mc.execute(() -> {
            try {
                started.set(starter.getAsBoolean());
            } finally {
                latch.countDown();
            }
        });

        latch.await(2, TimeUnit.SECONDS);
        return started.get();
    }

    private static void requestBaritoneStop(Minecraft mc) {
        if (mc == null || !AutismCompatManager.isBaritoneAvailable()) return;
        mc.execute(() -> AutismCompatManager.stopBaritone(mc));
    }

    private static void waitForBaritoneIdle() {
        long waitStartMs = System.currentTimeMillis();
        while (isCurrentRunActive() && AutismCompatManager.isBaritoneBusy() && System.currentTimeMillis() - waitStartMs < 3000L) {
            CompletableFuture<Void> tickFuture = MacroConditionRegistry.waitForNextTick();
            waitForCondition(tickFuture);
        }
    }

    private static boolean isInventoryFull(Minecraft mc) {
        if (mc.player == null) return false;

        for (int slot = 9; slot < 36; slot++) {
            if (mc.player.getInventory().getItem(slot).isEmpty()) return false;
        }
        return true;
    }

    private static int countUsedMainInventorySlots(Minecraft mc) {
        if (mc.player == null) return 0;

        int used = 0;
        for (int slot = 9; slot < 36; slot++) {
            if (!mc.player.getInventory().getItem(slot).isEmpty()) used++;
        }
        return used;
    }

    private static final class MineProgressTracker {
        private static final int POLL_RADIUS = 8;
        private final java.util.Set<net.minecraft.world.level.block.Block> targetBlocks;
        private final java.util.HashMap<Long, Boolean> observedTargetPositions = new java.util.HashMap<>();
        private final java.util.HashSet<Long> countedPositions = new java.util.HashSet<>();
        private int destroyed;

        private MineProgressTracker(java.util.Set<net.minecraft.world.level.block.Block> targetBlocks) {
            this.targetBlocks = targetBlocks;
        }

        static MineProgressTracker create(Minecraft mc, java.util.List<String> rawTargets) {
            java.util.Set<net.minecraft.world.level.block.Block> blocks = new java.util.LinkedHashSet<>();
            if (rawTargets != null) {
                for (String raw : rawTargets) {
                    net.minecraft.world.level.block.Block block = resolveBlock(raw);
                    if (block != null && block != net.minecraft.world.level.block.Blocks.AIR) blocks.add(block);
                }
            }
            if (blocks.isEmpty()) return null;
            return new MineProgressTracker(blocks);
        }

        private static net.minecraft.world.level.block.Block resolveBlock(String raw) {
            if (raw == null || raw.isBlank()) return null;
            String value = raw.trim();
            try {
                net.minecraft.resources.Identifier id = value.contains(":")
                    ? net.minecraft.resources.Identifier.parse(value)
                    : net.minecraft.resources.Identifier.fromNamespaceAndPath("minecraft", value);
                if (!net.minecraft.core.registries.BuiltInRegistries.BLOCK.containsKey(id)) return null;
                return net.minecraft.core.registries.BuiltInRegistries.BLOCK.getValue(id);
            } catch (Throwable ignored) {
                return null;
            }
        }

        synchronized int destroyedCount() {
            return destroyed;
        }

        synchronized void poll(Minecraft mc) {
            if (mc == null || mc.player == null || mc.level == null) return;
            BlockPos center = mc.player.blockPosition();
            int minY = Math.max(mc.level.getMinY(), center.getY() - POLL_RADIUS);
            int maxY = Math.min(mc.level.getMaxY() - 1, center.getY() + POLL_RADIUS);
            for (int x = center.getX() - POLL_RADIUS; x <= center.getX() + POLL_RADIUS; x++) {
                for (int y = minY; y <= maxY; y++) {
                    for (int z = center.getZ() - POLL_RADIUS; z <= center.getZ() + POLL_RADIUS; z++) {
                        BlockPos pos = new BlockPos(x, y, z);
                        updateObservedPosition(pos, mc.level.getBlockState(pos));
                    }
                }
            }
        }

        synchronized void observePacket(Minecraft mc, Packet<?> packet) {
            if (mc == null || mc.level == null || packet == null) return;
            String simpleName = packet.getClass().getSimpleName();
            if ("ClientboundBlockUpdatePacket".equals(simpleName)) {
                Object posValue = firstNonNull(invokeFirstNoArg(packet, "getPos", "pos"), getRecordComponentValue(packet, 0));
                Object stateValue = firstNonNull(
                    invokeFirstNoArg(packet, "getBlockState", "blockState", "state"),
                    findRecordComponentByValueType(packet, BlockState.class),
                    getRecordComponentValue(packet, 1)
                );
                if (posValue instanceof BlockPos pos && stateValue instanceof BlockState state) {
                    observeBlockChange(mc, pos, state);
                }
            } else if ("ClientboundSectionBlocksUpdatePacket".equals(simpleName)) {
                observeSectionUpdate(mc, packet);
            }
        }

        private void observeSectionUpdate(Minecraft mc, Packet<?> packet) {
            try {
                java.lang.reflect.Method targetMethod = null;
                for (java.lang.reflect.Method method : packet.getClass().getMethods()) {
                    if ("runUpdates".equals(method.getName()) && method.getParameterCount() == 1) {
                        targetMethod = method;
                        break;
                    }
                }
                if (targetMethod == null) return;
                targetMethod.setAccessible(true);
                java.util.function.BiConsumer<BlockPos, BlockState> consumer = (pos, state) -> {
                    if (pos != null && state != null) observeBlockChange(mc, pos, state);
                };
                targetMethod.invoke(packet, consumer);
            } catch (Throwable ignored) {
            }
        }

        private void observeBlockChange(Minecraft mc, BlockPos pos, BlockState newState) {
            if (mc == null || mc.level == null || pos == null || newState == null) return;
            if (!isNearPlayer(mc, pos)) return;
            BlockPos stablePos = new BlockPos(pos.getX(), pos.getY(), pos.getZ());
            BlockState oldState = mc.level.getBlockState(stablePos);
            if (isTarget(oldState) && !isTarget(newState)) {
                countDestroyed(stablePos.asLong());
            }
            updateObservedPosition(stablePos, newState);
        }

        private boolean isNearPlayer(Minecraft mc, BlockPos pos) {
            if (mc == null || mc.player == null || pos == null) return false;
            BlockPos playerPos = mc.player.blockPosition();
            int radius = POLL_RADIUS + 4;
            return Math.abs(pos.getX() - playerPos.getX()) <= radius
                && Math.abs(pos.getY() - playerPos.getY()) <= radius
                && Math.abs(pos.getZ() - playerPos.getZ()) <= radius;
        }

        private void updateObservedPosition(BlockPos pos, BlockState state) {
            if (pos == null || state == null) return;
            long key = pos.asLong();
            boolean nowTarget = isTarget(state);
            boolean wasTarget = Boolean.TRUE.equals(observedTargetPositions.get(key));
            if (nowTarget) {
                observedTargetPositions.put(key, Boolean.TRUE);
            } else {
                if (wasTarget) countDestroyed(key);
                observedTargetPositions.remove(key);
            }
        }

        private boolean isTarget(BlockState state) {
            return state != null && targetBlocks.contains(state.getBlock());
        }

        private void countDestroyed(long posKey) {
            if (countedPositions.add(posKey)) destroyed++;
        }
    }

    private static void runPayAction(Minecraft mc, PayAction payAction) throws InterruptedException {
        payAction.delayMs = PayAction.normalizeDelay(payAction.delayMs);
        java.util.List<String> targets = new java.util.ArrayList<>();
        for (String player : payAction.players) {
            if (player == null) continue;
            String trimmed = player.trim();
            if (!trimmed.isEmpty() && !targets.contains(trimmed)) targets.add(trimmed);
        }
        if (targets.isEmpty() || mc.getConnection() == null) return;

        long resolvedAmount = Math.max(0L, payAction.resolvedAmount());
        String amountValue = String.valueOf(resolvedAmount);
        String amountLabel = PayAction.formatAmount(resolvedAmount);
        int sent = 0;

        for (String player : targets) {
            if (!isCurrentRunActive()) break;

            String template = (payAction.commandTemplate == null || payAction.commandTemplate.isBlank())
                ? "/pay <player> <amount>"
                : payAction.commandTemplate;
            String command = template
                .replace("<player>", player)
                .replace("{player}", player)
                .replace("<amount>", amountValue)
                .replace("{amount}", amountValue)
                .trim();
            if (command.isEmpty()) continue;

            setCurrentStatus("Paying " + player + " " + amountLabel + " (" + (sent + 1) + "/" + targets.size() + ")");

            java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
            mc.execute(() -> {
                try {
                    if (mc.getConnection() == null) return;
                    if (command.startsWith("/") && command.length() > 1) {
                        mc.getConnection().sendCommand(command.substring(1));
                    } else {
                        mc.getConnection().sendChat(command);
                    }
                } finally {
                    latch.countDown();
                }
            });

            try {
                latch.await(250, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            }

            sent++;
            if (sent < targets.size() && payAction.delayEnabled && payAction.delayMs > 0) Thread.sleep(payAction.delayMs);
        }
    }

    public static void stop() {
        String macroName = (currentMacro != null && currentMacro.name != null) ? currentMacro.name : "";
        for (RunState run : activeRunSnapshot()) {
            requestStop(run, true);
        }
        synchronized (RUN_LOCK) {
            ACTIVE_RUNS.clear();
            RUNS_BY_ID.clear();
            primaryRun = null;
        }
        isRunning.set(false);
        try {
            Minecraft mcInst = Minecraft.getInstance();
            autismclient.util.AutismContainerHold.releaseAllAndFlush(mcInst == null ? null : mcInst.getConnection());
        } catch (Throwable ignored) {
        }
        persistentActions.clear();
        releaseBackgroundMoves(backgroundMoves, Minecraft.getInstance());
        backgroundMoves.clear();
        MacroConditionRegistry.cancelAll();
        isWaitingForChat.set(false);
        isWaitingForPacket.set(false);
        synchronized (packetWaitLock) {
            waitingPacketName.set("");
            CompletableFuture<Void> pf = activePacketFuture;
            if (pf != null) { pf.cancel(true); activePacketFuture = null; }
        }
        CompletableFuture<Void> cf = activeChatFuture;
        if (cf != null) { cf.cancel(true); activeChatFuture = null; }
        CompletableFuture<Void> bf = activeBaritoneGoalFuture;
        if (bf != null) { bf.cancel(true); activeBaritoneGoalFuture = null; }
        AutismCompatManager.stopBaritone(Minecraft.getInstance());
        AutismInstaBreakRenderer.clear();
        Minecraft mc = Minecraft.getInstance();
        PacketGateManager.clearAllAndFlushConfigured(mc == null ? null : mc.getConnection());
        MacroVariables.clear();

        try {
            autismclient.util.AutismLANSync sync = autismclient.util.AutismLANSync.getInstance();
            if (sync.isInSession()) {
                sync.broadcastStepProgress(-1, 0, "");
            }
        } catch (Exception ignored) {}

        macroThread = null;
        currentMacro = null;
        setCurrentStatus("");
        isRotating.set(false);
        rotationAlignedFrames = 0;
        if (!macroName.isEmpty()) {
            AutismClientMessaging.sendPrefixed("§cMacro stopped: " + macroName);
        } else {
            AutismClientMessaging.sendPrefixed("§cMacro stopped.");
        }
    }

    public static boolean isRunning() {
        return isRunning.get();
    }

    public static boolean hasPacketObservationWork() {
        return isRunning.get()
            || activePacketFuture != null
            || isWaitingForPacket.get()
            || !ONE_SHOT_RECV.isEmpty()
            || !ONE_SHOT_SEND.isEmpty()
            || !TIMED_ONE_SHOT_RECV.isEmpty()
            || !TIMED_ONE_SHOT_SEND.isEmpty();
    }

    public static boolean isMacroRunning(String macroName) {
        if (macroName == null) return false;
        String targetKey = macroKey(macroName);
        for (RunState run : RUNS_BY_ID.values()) {
            if (run.running.get() && macroKey(macroName(run.macro)).equals(targetKey)) return true;
        }
        return false;
    }

    public static boolean isMacroRunning(AutismMacro macro) {
        if (macro == null) return false;
        for (RunState run : RUNS_BY_ID.values()) {
            if (matchesMacro(run, macro, macro.name)) return true;
        }
        return false;
    }

    public static List<MacroRunSnapshot> getActiveRunSnapshots() {
        synchronized (RUN_LOCK) {
            ArrayList<MacroRunSnapshot> snapshots = new ArrayList<>(ACTIVE_RUNS.size());
            for (RunState run : ACTIVE_RUNS.values()) {
                if (!run.running.get()) continue;
                if (run.options.hiddenFromProgressHud()) continue;
                AutismMacro macro = run.macro;
                String name = macro != null && macro.name != null ? macro.name : "";
                snapshots.add(new MacroRunSnapshot(
                    macro,
                    name,
                    run.status == null ? "" : run.status,
                    run.currentStepIndex,
                    run.totalSteps,
                    run.lastCompletedStep
                ));
            }
            return snapshots;
        }
    }

    public static void stopMacro(String macroName) {
        if (macroName == null || macroName.isBlank()) return;
        String targetKey = macroKey(macroName);
        boolean stopped = false;
        for (RunState run : activeRunSnapshot()) {
            if (run.running.get() && macroKey(macroName(run.macro)).equals(targetKey)) {
                requestStop(run, true);
                stopped = true;
            }
        }
        if (stopped) {
            AutismInstaBreakRenderer.clear();
            synchronized (RUN_LOCK) {
                refreshPrimaryRunLocked();
            }
            AutismClientMessaging.sendPrefixed("§cMacro stopped: " + macroName);
        }
    }

    public static void stopMacro(AutismMacro macro) {
        if (macro == null) return;
        boolean stopped = false;
        String displayName = macroName(macro);
        for (RunState run : activeRunSnapshot()) {
            if (matchesMacro(run, macro, displayName)) {
                requestStop(run, true);
                stopped = true;
            }
        }
        if (stopped) {
            AutismInstaBreakRenderer.clear();
            synchronized (RUN_LOCK) {
                refreshPrimaryRunLocked();
            }
            AutismClientMessaging.sendPrefixed("§cMacro stopped: " + (displayName.isBlank() ? "macro" : displayName));
        }
    }

    public static String getCurrentMacroName() {
        AutismMacro m = currentMacro;
        return (m != null && m.name != null) ? m.name : "";
    }

    public static AutismMacro getCurrentMacro() {
        return currentMacro;
    }

    public static String getCurrentStatus() {
        RunState run = primaryRun;
        return run != null ? run.status : currentStatus;
    }

    public static int getCurrentStepIndex() {
        RunState run = primaryRun;
        return run != null ? run.currentStepIndex : currentStepIndex;
    }

    public static int getTotalSteps() {
        RunState run = primaryRun;
        return run != null ? run.totalSteps : totalSteps;
    }

    public static List<RecentChatMessage> getRecentChatMessages() {
        synchronized (recentChatLock) {
            return new ArrayList<>(recentChatMessages);
        }
    }

    public static final class OneShotPacketListener {
        public final java.util.concurrent.CompletableFuture<Packet<?>> future = new java.util.concurrent.CompletableFuture<>();
        private final java.util.List<java.util.function.Predicate<Packet<?>>> registry;
        private final java.util.function.Predicate<Packet<?>> wrapper;
        OneShotPacketListener(java.util.List<java.util.function.Predicate<Packet<?>>> registry,
                              java.util.function.Predicate<Packet<?>> match) {
            this.registry = registry;
            this.wrapper = p -> {
                if (future.isDone()) return true;
                if (match.test(p)) { future.complete(p); return true; }
                return false;
            };
            registry.add(wrapper);
        }
        public boolean isDone() { return future.isDone(); }
        public Packet<?> getDoneOrNull() {
            if (!future.isDone()) return null;
            try { return future.getNow(null); } catch (Throwable ignored) { return null; }
        }
        public void cancel() {
            registry.remove(wrapper);
            if (!future.isDone()) future.cancel(true);
        }
    }

    private static final class TimedOneShotPacketListener {
        public final java.util.concurrent.CompletableFuture<MacroEvent> future = new java.util.concurrent.CompletableFuture<>();
        private final java.util.List<TimedPacketObserver> registry;
        private final TimedPacketObserver wrapper;
        TimedOneShotPacketListener(java.util.List<TimedPacketObserver> registry, TimedPacketMatch match) {
            this.registry = registry;
            this.wrapper = event -> {
                if (future.isDone()) return true;
                if (match.test(event.packet(), event.direction(), event.nanoTime())) {
                    future.complete(event);
                    return true;
                }
                return false;
            };
            registry.add(wrapper);
        }
        public void cancel() {
            registry.remove(wrapper);
            if (!future.isDone()) future.cancel(true);
        }
    }

    private interface TimedPacketMatch {
        boolean test(Packet<?> packet, String direction, long nanoTime);
    }

    private interface TimedPacketObserver {
        boolean test(MacroEvent event);
    }

    private static final java.util.List<java.util.function.Predicate<Packet<?>>> ONE_SHOT_RECV =
        new java.util.concurrent.CopyOnWriteArrayList<>();
    private static final java.util.List<java.util.function.Predicate<Packet<?>>> ONE_SHOT_SEND =
        new java.util.concurrent.CopyOnWriteArrayList<>();
    private static final java.util.List<TimedPacketObserver> TIMED_ONE_SHOT_RECV =
        new java.util.concurrent.CopyOnWriteArrayList<>();
    private static final java.util.List<TimedPacketObserver> TIMED_ONE_SHOT_SEND =
        new java.util.concurrent.CopyOnWriteArrayList<>();

    public static OneShotPacketListener awaitReceive(java.util.function.Predicate<Packet<?>> match) {
        return new OneShotPacketListener(ONE_SHOT_RECV, match);
    }

    public static OneShotPacketListener awaitSend(java.util.function.Predicate<Packet<?>> match) {
        return new OneShotPacketListener(ONE_SHOT_SEND, match);
    }

    private static TimedOneShotPacketListener awaitTimedReceive(TimedPacketMatch match) {
        return new TimedOneShotPacketListener(TIMED_ONE_SHOT_RECV, match);
    }

    private static TimedOneShotPacketListener awaitTimedSend(java.util.function.Predicate<Packet<?>> match) {
        return awaitTimedSend((packet, direction, nanoTime) -> match.test(packet));
    }

    private static TimedOneShotPacketListener awaitTimedSend(TimedPacketMatch match) {
        return new TimedOneShotPacketListener(TIMED_ONE_SHOT_SEND, match);
    }

    private static void fireOneShotObservers(java.util.List<java.util.function.Predicate<Packet<?>>> list, Packet<?> packet) {
        if (list.isEmpty()) return;

        java.util.ArrayList<java.util.function.Predicate<Packet<?>>> matched = null;
        for (java.util.function.Predicate<Packet<?>> pred : list) {
            boolean drop;
            try { drop = pred.test(packet); }
            catch (Throwable ignored) { drop = true; }
            if (drop) {
                if (matched == null) matched = new java.util.ArrayList<>(4);
                matched.add(pred);
            }
        }
        if (matched != null) list.removeAll(matched);
    }

    private static void fireTimedObservers(java.util.List<TimedPacketObserver> list, MacroEvent event) {
        if (list.isEmpty()) return;
        java.util.ArrayList<TimedPacketObserver> matched = null;
        for (TimedPacketObserver pred : list) {
            boolean drop;
            try { drop = pred.test(event); }
            catch (Throwable ignored) { drop = true; }
            if (drop) {
                if (matched == null) matched = new java.util.ArrayList<>(4);
                matched.add(pred);
            }
        }
        if (matched != null) list.removeAll(matched);
    }

    public static void onPacketSent(Packet<?> packet) {
        long now = System.nanoTime();
        fireTimedObservers(TIMED_ONE_SHOT_SEND, new MacroEvent(packet, "C2S", now, null));
        fireOneShotObservers(ONE_SHOT_SEND, packet);
        onPacketObserved(packet, "C2S");
    }

    public static void onPacketReceived(Packet<?> packet) {
        long now = System.nanoTime();
        fireTimedObservers(TIMED_ONE_SHOT_RECV, new MacroEvent(packet, "S2C", now, null));
        fireOneShotObservers(ONE_SHOT_RECV, packet);

        if (packet instanceof net.minecraft.network.protocol.game.ClientboundRespawnPacket
            || packet instanceof net.minecraft.network.protocol.game.ClientboundLoginPacket) {
            MacroConditionRegistry.onRespawnPacket();
        }
        if (packet instanceof net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket) {
            MacroConditionRegistry.onTeleportPacket();
        }
        onPacketObserved(packet, "S2C");
    }

    public static void onPacketObserved(Packet<?> packet, String direction) {
        syncRecentChatServerContext();

        java.util.List<RunState> runs = null;

        if ("S2C".equals(direction)) {
            ChatCapture capture = extractIncomingChat(packet);
            if (capture != null && capture.message() != null && !capture.message().isBlank()) {
                recordRecentChat(capture);
                if (runs == null) runs = activeRunSnapshot();
                for (RunState run : runs) {
                    if (run.running.get() && run.waitingForChat.get() && matchesChatAction(run.waitingChatAction, capture)) {
                        CompletableFuture<Void> f = run.chatFuture;
                        if (f != null) f.complete(null);
                    }
                }
                if (isCurrentRunActive() && isWaitingForChat.get() && matchesWaitingChat(capture)) {
                    lastMatchedChat.set(capture.displayText());
                    CompletableFuture<Void> f = activeChatFuture;
                    if (f != null) f.complete(null);
                }
            }
        }

        if ("S2C".equals(direction)) {
            if (runs == null) runs = activeRunSnapshot();
            Minecraft mc = Minecraft.getInstance();
            for (RunState run : runs) {
                MineProgressTracker tracker = run.running.get() ? run.activeMineTracker : null;
                if (tracker != null) tracker.observePacket(mc, packet);
            }
        }

        if (!isCurrentRunActive()) return;

        if (runs == null) runs = activeRunSnapshot();
        for (RunState run : runs) {
            if (!run.running.get() || !run.waitingForPacket.get()) continue;
            CompletableFuture<Void> f = null;
            synchronized (run.packetWaitLock) {
                if (run.waitingForPacket.get() && matchesPacketTarget(run.waitingPacketName.get(), packet, direction)) {
                    f = run.packetFuture;
                }
            }
            if (f != null) f.complete(null);
        }

        if (isWaitingForPacket.get()) {
            CompletableFuture<Void> f = null;
            synchronized (packetWaitLock) {
                if (!isWaitingForPacket.get()) return;

                String target = waitingPacketName.get();
                if (matchesPacketTarget(target, packet, direction)) {
                    lastReceivedPacket.set(direction + " " + AutismPacketNamer.getFriendlyName(packet, direction));
                    f = activePacketFuture;
                }
            }
            if (f != null) f.complete(null);
        }
    }

    private static void recordRecentChat(ChatCapture capture) {
        RecentChatMessage message = new RecentChatMessage(
            capture.sender() == null ? "" : capture.sender(),
            capture.message(),
            capture.displayText(),
            capture.displayComponent(),
            capture.source(),
            System.currentTimeMillis()
        );
        synchronized (recentChatLock) {
            recentChatMessages.removeIf(existing -> existing.displayText().equals(message.displayText()));
            recentChatMessages.addFirst(message);
            while (recentChatMessages.size() > MAX_RECENT_CHAT_MESSAGES) {
                recentChatMessages.removeLast();
            }
        }
    }

    private static void syncRecentChatServerContext() {
        String currentServerKey = getCurrentMultiplayerServerKey();
        if (currentServerKey.isEmpty()) return;
        synchronized (recentChatLock) {
            if (recentChatServerKey.isEmpty()) {
                recentChatServerKey = currentServerKey;
                return;
            }
            if (!recentChatServerKey.equals(currentServerKey)) {
                recentChatMessages.clear();
                recentChatServerKey = currentServerKey;
            }
        }
    }

    private static String getCurrentMultiplayerServerKey() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.isLocalServer()) return "";

        ServerData entry = mc.getCurrentServer();
        if (entry != null && entry.ip != null && !entry.ip.isBlank()) {
            return normalizeServerKey(entry.ip);
        }

        if (mc.getConnection() != null && mc.getConnection().getConnection() != null) {
            SocketAddress address = mc.getConnection().getConnection().getRemoteAddress();
            if (address instanceof InetSocketAddress inet) {
                String host = inet.getHostString();
                if ((host == null || host.isBlank()) && inet.getAddress() != null) {
                    host = inet.getAddress().getHostAddress();
                }
                if (host != null && !host.isBlank()) {
                    return normalizeServerKey(host + ":" + inet.getPort());
                }
            } else if (address != null) {
                String raw = address.toString();
                if (raw != null && !raw.isBlank()) {
                    return normalizeServerKey(raw);
                }
            }
        }

        return "";
    }

    private static String normalizeServerKey(String address) {
        if (address == null) return "";
        String trimmed = address.replaceFirst("^/", "").trim().toLowerCase(Locale.ROOT);
        return trimmed;
    }

    private static boolean matchesWaitingChat(ChatCapture capture) {
        String patternJson = waitingChatPatternJson.get();
        if (!waitingChatIsRegex.get() && patternJson != null && !patternJson.isBlank() && capture.displayComponent() != null) {
            String captureJson = serializeTextComponent(capture.displayComponent());
            if (patternJson.equals(captureJson)) return true;
        }
        String pattern = normalizeManualText(waitingChatPattern.get());
        if (pattern == null || pattern.isBlank()) return true;
        if (waitingChatIsRegex.get()) {
            try {
                return capture.message().matches(pattern) || capture.displayText().matches(pattern);
            } catch (Exception ignored) {
                return false;
            }
        }
        int threshold = waitingChatFuzzyPercent.get();
        return fuzzyChatMatch(pattern, capture.message(), threshold)
            || fuzzyChatMatch(pattern, capture.displayText(), threshold);
    }

    private static boolean matchesChatAction(WaitForChatAction action, ChatCapture capture) {
        if (action == null || capture == null) return false;
        if (!action.useRegex && action.patternJson != null && !action.patternJson.isBlank() && capture.displayComponent() != null) {
            String captureJson = serializeTextComponent(capture.displayComponent());
            if (action.patternJson.equals(captureJson)) return true;
        }
        String pattern = normalizeManualText(action.pattern);
        if (pattern == null || pattern.isBlank()) return true;
        if (action.useRegex) {
            try {
                return capture.message().matches(pattern) || capture.displayText().matches(pattern);
            } catch (Exception ignored) {
                return false;
            }
        }
        int threshold = WaitForChatAction.clampFuzzyPercent(action.fuzzyPercent);
        return fuzzyChatMatch(pattern, capture.message(), threshold)
            || fuzzyChatMatch(pattern, capture.displayText(), threshold);
    }

    private static ChatCapture extractIncomingChat(Packet<?> packet) {
        if (packet instanceof net.minecraft.network.protocol.game.ClientboundSystemChatPacket gameMessagePacket) {
            String raw = gameMessagePacket.content().getString();
            if (raw == null || raw.isBlank()) return null;
            return buildChatCapture("", raw, raw, gameMessagePacket.content(), ChatSource.PLAYER);
        }

        if (packet instanceof net.minecraft.network.protocol.game.ClientboundDisguisedChatPacket profilelessChatMessagePacket) {
            Object message = invokeFirstNoArg(profilelessChatMessagePacket, "message", "getMessage");
            Object chatType = invokeFirstNoArg(profilelessChatMessagePacket, "chatType", "getChatType");
            String text = extractTextValue(message);
            if (text == null || text.isBlank()) return null;
            String sender = extractChatName(chatType);
            return buildChatCapture(sender, text, null, extractTextComponent(message), ChatSource.PLAYER);
        }

        if (packet instanceof net.minecraft.network.protocol.game.ClientboundPlayerChatPacket chatMessagePacket) {
            Object body = firstNonNull(
                invokeFirstNoArg(chatMessagePacket, "body", "getBody"),
                getRecordComponentValue(chatMessagePacket, 4),
                findRecordComponentByMethodNames(chatMessagePacket, 2, "content", "timestamp", "salt")
            );
            Object serializedParameters = firstNonNull(
                invokeFirstNoArg(chatMessagePacket, "serializedParameters", "getSerializedParameters"),
                getRecordComponentValue(chatMessagePacket, 7),
                findRecordComponentByMethodNames(chatMessagePacket, 2, "name", "targetName", "type")
            );
            Object senderValue = firstNonNull(
                invokeFirstNoArg(chatMessagePacket, "sender", "getSender"),
                getRecordComponentValue(chatMessagePacket, 1),
                findRecordComponentByValueType(chatMessagePacket, UUID.class)
            );
            Object unsignedContent = firstNonNull(
                invokeFirstNoArg(chatMessagePacket, "unsignedContent", "getUnsignedContent"),
                getRecordComponentValue(chatMessagePacket, 5),
                findRecordTextComponent(chatMessagePacket, body, serializedParameters, senderValue)
            );

            String message = extractTextValue(unsignedContent);
            Component messageComponent = extractTextComponent(unsignedContent);
            if ((message == null || message.isBlank()) && body != null) {
                Object bodyContent = firstNonNull(
                    invokeFirstNoArg(body, "content", "getContent"),
                    getRecordComponentValue(body, 0),
                    findRecordTextComponent(body)
                );
                message = extractTextValue(bodyContent);
                if (messageComponent == null) messageComponent = extractTextComponent(bodyContent);
            }
            if (message == null || message.isBlank()) return null;

            String sender = senderValue instanceof UUID uuid ? resolvePlayerName(uuid) : null;
            if (sender == null || sender.isBlank()) sender = extractChatName(serializedParameters);
            return buildChatCapture(sender, message, null, messageComponent, ChatSource.PLAYER);
        }

        return null;
    }

    private static ChatCapture buildChatCapture(String sender, String message, String displayText, Component displayComponent, ChatSource source) {
        String cleanMessage = sanitizeChatText(message);
        if (cleanMessage.isBlank()) return null;
        String cleanSender = sanitizeChatText(sender);
        String cleanDisplay = sanitizeChatText(displayText);
        Component rendered = literalizeTextComponent(displayComponent);
        String display = cleanDisplay;
        if (rendered == null || rendered.getString().isBlank()) {
            rendered = cleanSender.isBlank()
                    ? Component.literal(cleanMessage)
                    : Component.literal("<" + cleanSender + "> ").append(Component.literal(cleanMessage));
        }
        if (display == null || display.isBlank()) {
            display = sanitizeChatText(rendered.getString());
        }
        ChatSource resolvedSource = source != null ? source : ChatSource.PLAYER;
        return new ChatCapture(cleanSender, cleanMessage, sanitizeChatText(display), rendered, resolvedSource);
    }

    private static String sanitizeChatText(String text) {
        if (text == null) return "";
        String stripped = stripLegacyFormatting(text).replace('\n', ' ').replace('\r', ' ');
        StringBuilder out = new StringBuilder(stripped.length());
        for (int offset = 0; offset < stripped.length();) {
            int cp = stripped.codePointAt(offset);
            offset += Character.charCount(cp);
            int type = Character.getType(cp);
            if (type == Character.FORMAT
                    || type == Character.CONTROL
                    || cp == 0x200B
                    || cp == 0x200C
                    || cp == 0x200D
                    || cp == 0x2060
                    || cp == 0xFE0E
                    || cp == 0xFE0F) {
                continue;
            }
            out.appendCodePoint(cp);
        }
        return out.toString().trim();
    }

    public static String normalizeManualText(String text) {
        if (text == null || text.isBlank()) return "";
        String stripped = stripLegacyFormatting(text).trim();
        String flattened = flattenManualTextComponentJson(stripped);
        return sanitizeChatText(flattened != null ? flattened : stripped);
    }

    private static String flattenManualTextComponentJson(String text) {
        if (text == null || text.isBlank()) return null;
        String trimmed = text.trim();
        if (!(trimmed.startsWith("{") || trimmed.startsWith("[") || trimmed.startsWith("\""))) return null;
        try {
            com.google.gson.JsonElement element = JsonParser.parseString(trimmed);
            if (element == null || element.isJsonNull()) return "";
            if (element.isJsonArray()) {
                StringBuilder out = new StringBuilder();
                for (com.google.gson.JsonElement child : element.getAsJsonArray()) {
                    Component component = parseTextComponentElement(child);
                    if (component != null) out.append(component.getString());
                }
                return out.toString();
            }
            Component component = parseTextComponentElement(element);
            return component == null ? null : component.getString();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static Component parseTextComponentElement(com.google.gson.JsonElement element) {
        try {
            Component component = ComponentSerialization.CODEC.parse(JsonOps.INSTANCE, element).getOrThrow();
            return literalizeTextComponent(component);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String stripLegacyFormatting(String text) {
        if (text == null || text.isEmpty()) return "";
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if ((ch == '\u00A7' || ch == '&') && i + 1 < text.length()) {
                char next = Character.toLowerCase(text.charAt(i + 1));
                if ((next >= '0' && next <= '9')
                        || (next >= 'a' && next <= 'f')
                        || (next >= 'k' && next <= 'o')
                        || next == 'r'
                        || next == 'x') {
                    i++;
                    continue;
                }
            }
            out.append(ch);
        }
        return out.toString();
    }

    private static boolean isLikelyPlayerSender(String sender) {
        String cleanSender = sanitizeChatText(sender);
        if (cleanSender.isBlank()) return false;
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.getConnection() != null) {
                for (var entry : mc.getConnection().getListedOnlinePlayers()) {
                    String playerName = entry != null && entry.getProfile() != null ? extractTextValue(entry.getProfile()) : null;
                    if (playerName != null && cleanSender.equalsIgnoreCase(playerName)) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level != null) {
                for (var player : mc.level.players()) {
                    String playerName = player != null && player.getGameProfile() != null ? extractTextValue(player.getGameProfile()) : null;
                    if (playerName != null && cleanSender.equalsIgnoreCase(playerName)) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static boolean fuzzyChatMatch(String pattern, String candidate, int thresholdPercent) {
        String normalizedPattern = normalizeChatText(pattern);
        String normalizedCandidate = normalizeChatText(candidate);
        if (normalizedPattern.isBlank()) return true;
        if (normalizedCandidate.isBlank()) return false;
        if (normalizedCandidate.contains(normalizedPattern)) return true;

        List<String> patternTokens = tokenizeChat(normalizedPattern);
        List<String> candidateTokens = tokenizeChat(normalizedCandidate);
        if (patternTokens.isEmpty()) return true;

        if (thresholdPercent >= 100) {
            return allPatternTokensPresent(patternTokens, normalizedCandidate);
        }

        double tokenScore = computeTokenCoverage(patternTokens, candidateTokens);
        double charScore = similarityRatio(normalizedPattern.replace(" ", ""), normalizedCandidate.replace(" ", ""));
        double score = Math.max(tokenScore, tokenScore * 0.7 + charScore * 0.3);
        return score + 1.0e-9 >= (Math.max(40, Math.min(100, thresholdPercent)) / 100.0);
    }

    private static String normalizeChatForCompare(String text) {
        if (text == null) return "";
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(normalized.length());
        for (int i = 0; i < normalized.length(); i++) {
            char ch = normalized.charAt(i);
            if (Character.isLetterOrDigit(ch)) out.append(ch);
            else if (Character.isWhitespace(ch)) out.append(' ');
            else if (ch == '\'' || ch == '"' || ch == '`' || ch == '-' || ch == '_' || ch == '\u2019') {

            } else {
                out.append(' ');
            }
        }
        return out.toString().trim().replaceAll("\\s+", " ");
    }

    public static String normalizeChatText(String text) {
        if (text == null) return "";
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKD).toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(normalized.length());
        boolean lastWasSpace = true;
        for (int offset = 0; offset < normalized.length();) {
            int cp = normalized.codePointAt(offset);
            offset += Character.charCount(cp);

            cp = foldStyledLatinCodePoint(cp);
            int type = Character.getType(cp);
            if (type == Character.NON_SPACING_MARK
                    || type == Character.COMBINING_SPACING_MARK
                    || type == Character.ENCLOSING_MARK
                    || type == Character.FORMAT
                    || type == Character.CONTROL
                    || type == Character.PRIVATE_USE
                    || type == Character.SURROGATE) {
                continue;
            }

            if (Character.isLetterOrDigit(cp)) {
                out.appendCodePoint(cp);
                lastWasSpace = false;
            } else if (Character.isWhitespace(cp)) {
                if (!lastWasSpace) out.append(' ');
                lastWasSpace = true;
            } else if (isJoinerPunctuation(cp)) {

            } else {
                if (!lastWasSpace) out.append(' ');
                lastWasSpace = true;
            }
        }
        int len = out.length();
        if (len > 0 && out.charAt(len - 1) == ' ') out.setLength(len - 1);
        return out.toString();
    }

    private static boolean isJoinerPunctuation(int cp) {
        return switch (cp) {
            case '\'', '"', '`', '-', '_', '\u2019', '\u2018', '\u201B', '\u2032', '\u02BC', '\uFF07',
                 '\u2010', '\u2011', '\u2012', '\u2013', '\u2014', '\u2015', '\u2212' -> true;
            default -> false;
        };
    }

    private static int foldStyledLatinCodePoint(int cp) {
        return switch (cp) {
            case '\u1D00' -> 'a';
            case '\u0299' -> 'b';
            case '\u1D04' -> 'c';
            case '\u1D05' -> 'd';
            case '\u1D07' -> 'e';
            case '\uA730' -> 'f';
            case '\u0262' -> 'g';
            case '\u029C' -> 'h';
            case '\u026A' -> 'i';
            case '\u1D0A' -> 'j';
            case '\u1D0B' -> 'k';
            case '\u029F' -> 'l';
            case '\u1D0D' -> 'm';
            case '\u0274' -> 'n';
            case '\u1D0F' -> 'o';
            case '\u1D18' -> 'p';
            case '\u01EB' -> 'q';
            case '\u0280' -> 'r';
            case '\uA731' -> 's';
            case '\u1D1B' -> 't';
            case '\u1D1C' -> 'u';
            case '\u1D20' -> 'v';
            case '\u1D21' -> 'w';
            case '\u02E3' -> 'x';
            case '\u028F' -> 'y';
            case '\u1D22' -> 'z';
            case '\u00DF' -> 's';
            case '\u00E6' -> 'a';
            case '\u0153' -> 'o';
            default -> cp;
        };
    }

    private static List<String> tokenizeChat(String normalized) {
        if (normalized == null || normalized.isBlank()) return List.of();
        return List.of(normalized.split(" "));
    }

    private static boolean allPatternTokensPresent(List<String> patternTokens, String normalizedCandidate) {
        for (String token : patternTokens) {
            if (!normalizedCandidate.contains(token)) return false;
        }
        return true;
    }

    private static double computeTokenCoverage(List<String> patternTokens, List<String> candidateTokens) {
        if (patternTokens.isEmpty()) return 1.0;
        if (candidateTokens.isEmpty()) return 0.0;
        double total = 0.0;
        for (String patternToken : patternTokens) {
            double best = 0.0;
            for (String candidateToken : candidateTokens) {
                best = Math.max(best, tokenSimilarity(patternToken, candidateToken));
                if (best >= 1.0) break;
            }
            total += best;
        }
        return total / patternTokens.size();
    }

    private static double tokenSimilarity(String left, String right) {
        if (left.equals(right)) return 1.0;
        if (left.isBlank() || right.isBlank()) return 0.0;
        if (left.contains(right) || right.contains(left)) {
            return (double) Math.min(left.length(), right.length()) / Math.max(left.length(), right.length());
        }
        return similarityRatio(left, right);
    }

    private static double similarityRatio(String left, String right) {
        if (left.equals(right)) return 1.0;
        int maxLen = Math.max(left.length(), right.length());
        if (maxLen == 0) return 1.0;
        int distance = levenshteinDistance(left, right);
        return Math.max(0.0, 1.0 - (distance / (double) maxLen));
    }

    private static int levenshteinDistance(String left, String right) {
        int[] previous = new int[right.length() + 1];
        int[] current = new int[right.length() + 1];
        for (int j = 0; j <= right.length(); j++) previous[j] = j;
        for (int i = 1; i <= left.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= right.length(); j++) {
                int cost = left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
            }
            int[] temp = previous;
            previous = current;
            current = temp;
        }
        return previous[right.length()];
    }

    private static String extractChatName(Object value) {
        Object direct = firstNonNull(
            invokeFirstNoArg(value, "name", "getName", "targetName", "getTargetName"),
            getRecordComponentValue(value, 0),
            findRecordComponentByMethodNames(value, 2, "name", "targetName", "type")
        );
        return sanitizeChatText(extractTextValue(direct));
    }

    private static String resolvePlayerName(UUID uuid) {
        if (uuid == null) return "";
        try {
            if (Minecraft.getInstance().getConnection() != null) {
                var entry = Minecraft.getInstance().getConnection().getPlayerInfo(uuid);
                if (entry != null && entry.getProfile() != null) {
                    String profileName = extractTextValue(entry.getProfile());
                    if (profileName != null && !profileName.isBlank()) return profileName;
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            if (Minecraft.getInstance().level != null) {
                for (var player : Minecraft.getInstance().level.players()) {
                    if (uuid.equals(player.getUUID()) && player.getGameProfile() != null) {
                        String profileName = extractTextValue(player.getGameProfile());
                        if (profileName != null && !profileName.isBlank()) return profileName;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    private static Object firstNonNull(Object... values) {
        if (values == null) return null;
        for (Object value : values) {
            if (value != null) return value;
        }
        return null;
    }

    private static Object invokeFirstNoArg(Object target, String... names) {
        if (target == null || names == null) return null;
        Class<?> type = target.getClass();
        for (String name : names) {
            try {
                Method method = type.getMethod(name);
                method.setAccessible(true);
                return method.invoke(target);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static Object getRecordComponentValue(Object target, int index) {
        if (target == null) return null;
        try {
            RecordComponent[] components = target.getClass().getRecordComponents();
            if (components == null || index < 0 || index >= components.length) return null;
            Method accessor = components[index].getAccessor();
            accessor.setAccessible(true);
            return accessor.invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object findRecordComponentByValueType(Object target, Class<?> type) {
        if (target == null || type == null) return null;
        try {
            RecordComponent[] components = target.getClass().getRecordComponents();
            if (components == null) return null;
            for (RecordComponent component : components) {
                if (type.isAssignableFrom(component.getType())) {
                    Method accessor = component.getAccessor();
                    accessor.setAccessible(true);
                    return accessor.invoke(target);
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Object findRecordComponentByMethodNames(Object target, int fallbackIndex, String... names) {
        Object direct = invokeFirstNoArg(target, names);
        return direct != null ? direct : getRecordComponentValue(target, fallbackIndex);
    }

    private static Object findRecordTextComponent(Object target, Object... excludedValues) {
        if (target == null) return null;
        try {
            RecordComponent[] components = target.getClass().getRecordComponents();
            if (components == null) return null;
            outer:
            for (RecordComponent component : components) {
                Method accessor = component.getAccessor();
                accessor.setAccessible(true);
                Object value = accessor.invoke(target);
                if (value == null) continue;
                for (Object excluded : excludedValues) {
                    if (excluded == value) continue outer;
                }
                if (value instanceof Component || value instanceof CharSequence || value instanceof Optional<?>) {
                    return value;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Component extractTextComponent(Object value) {
        if (value == null) return null;
        if (value instanceof Optional<?> optional) {
            return optional.isPresent() ? extractTextComponent(optional.get()) : null;
        }
        if (value instanceof Component text) return literalizeTextComponent(text);
        if (value instanceof CharSequence chars) return Component.literal(chars.toString());
        if (value instanceof UUID uuid) return Component.literal(resolvePlayerName(uuid));
        try {
            Method getComponent = value.getClass().getMethod("getText");
            getComponent.setAccessible(true);
            Object result = getComponent.invoke(value);
            Component text = extractTextComponent(result);
            if (text != null) return text;
        } catch (Throwable ignored) {
        }
        try {
            Method getString = value.getClass().getMethod("getString");
            getString.setAccessible(true);
            Object result = getString.invoke(value);
            if (result != null) return Component.literal(String.valueOf(result));
        } catch (Throwable ignored) {
        }
        return null;
    }

    public static Component literalizeTextComponent(Component text) {
        if (text == null) return null;
        MutableComponent literalized = Component.empty();
        try {
            text.visit((style, string) -> {
                if (string != null && !string.isEmpty()) {
                    literalized.append(Component.literal(string).setStyle(copyVisualStyle(style)));
                }
                return Optional.empty();
            }, Style.EMPTY);
        } catch (Throwable ignored) {
        }
        if (!literalized.getString().isEmpty()) {
            return literalized;
        }
        return Component.literal(text.getString());
    }

    private static Style copyVisualStyle(Style style) {
        if (style == null || style.isEmpty()) return Style.EMPTY;
        Style safe = Style.EMPTY;
        if (style.getFont() != null) safe = safe.withFont(style.getFont());
        if (style.getColor() != null) safe = safe.withColor(style.getColor());
        if (style.isBold()) safe = safe.withBold(true);
        if (style.isItalic()) safe = safe.withItalic(true);
        if (style.isUnderlined()) safe = safe.withUnderlined(true);
        if (style.isStrikethrough()) safe = safe.withStrikethrough(true);
        if (style.isObfuscated()) safe = safe.withObfuscated(true);
        return safe;
    }

    public static String serializeTextComponent(Component text) {
        if (text == null) return "";
        try {
            Component safeComponent = literalizeTextComponent(text);
            return safeComponent == null ? "" : GSON.toJson(ComponentSerialization.CODEC.encodeStart(JsonOps.INSTANCE, safeComponent).getOrThrow());
        } catch (Throwable ignored) {
            return "";
        }
    }

    public static Component deserializeTextComponent(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return literalizeTextComponent(ComponentSerialization.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json)).getOrThrow().copy());
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String extractTextValue(Object value) {
        if (value == null) return null;
        if (value instanceof Optional<?> optional) {
            return optional.isPresent() ? extractTextValue(optional.get()) : null;
        }
        if (value instanceof Component text) return text.getString();
        if (value instanceof CharSequence chars) return chars.toString();
        if (value instanceof UUID uuid) return resolvePlayerName(uuid);
        try {
            Method getString = value.getClass().getMethod("getString");
            getString.setAccessible(true);
            Object result = getString.invoke(value);
            if (result != null) return String.valueOf(result);
        } catch (Throwable ignored) {
        }
        try {
            Method getName = value.getClass().getMethod("getName");
            getName.setAccessible(true);
            Object result = getName.invoke(value);
            if (result instanceof CharSequence chars) return chars.toString();
        } catch (Throwable ignored) {
        }
        return null;
    }

    public static void onRender(float tickDelta) {
        if (isCurrentRunActive() && isRotating.get()) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) return;

            float currentYaw = mc.player.getYRot();
            float currentPitch = mc.player.getXRot();

            double deltaYaw = net.minecraft.util.Mth.wrapDegrees(targetYaw - currentYaw);
            double deltaPitch = targetPitch - currentPitch;
            double diagonalDistance = Math.hypot(deltaYaw, deltaPitch);
            if (diagonalDistance <= 0.35) {
                mc.player.setYRot(targetYaw);
                mc.player.setXRot(targetPitch);
                rotationAlignedFrames++;
                if (rotationAlignedFrames >= 2) {
                    isRotating.set(false);
                }
                return;
            }
            rotationAlignedFrames = 0;

            double frameStep = Math.min(diagonalDistance, rotationSpeed * Math.max(tickDelta, 1.0f));
            double stepYaw = (deltaYaw / diagonalDistance) * frameStep;
            double stepPitch = (deltaPitch / diagonalDistance) * frameStep;

            mc.player.setYRot(currentYaw + (float) stepYaw);
            mc.player.setXRot(currentPitch + (float) stepPitch);
        }
    }

    private static void startSmoothRotation(float yaw, float pitch, double speed) {
        targetYaw = yaw;
        targetPitch = pitch;
        rotationSpeed = speed;
        rotationAlignedFrames = 0;
        isRotating.set(true);
    }

    private static void waitForSmoothRotationCompletion() {
        while (isCurrentRunActive() && isRotating.get()) {
            java.util.concurrent.locks.LockSupport.parkNanos(1_000_000L);
        }
    }

    private static LookAtBlockAction.RotationTarget resolveLookAtTargetOnClient(Minecraft mc, LookAtBlockAction action) {
        if (mc == null || action == null) return null;
        java.util.concurrent.atomic.AtomicReference<LookAtBlockAction.RotationTarget> targetRef = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        mc.execute(() -> {
            try {
                targetRef.set(action.resolveRotationTarget(mc));
            } finally {
                latch.countDown();
            }
        });
        while (isCurrentRunActive()) {
            try {
                if (latch.await(10, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    break;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return targetRef.get();
    }

    public static void stopAll() {
        stop();
    }

    private static void executeBurstPackets(Minecraft mc, java.util.List<SendPacketAction> batch) {
        if (mc.getConnection() == null) {
            AutismClientMessaging.sendPrefixed("§cNo network connection for burst!");
            return;
        }

        java.util.List<autismclient.util.AutismSharedState.QueuedPacket> allQueuedPackets = new java.util.ArrayList<>();
        for (SendPacketAction action : batch) {
            allQueuedPackets.addAll(action.getPackets());
        }

        if (allQueuedPackets.isEmpty()) {
            return;
        }

        java.util.List<Packet<?>> regeneratedPackets = new java.util.ArrayList<>(allQueuedPackets.size());
        for (autismclient.util.AutismSharedState.QueuedPacket qp : allQueuedPackets) {
            if (qp.packet == null) continue;
            regeneratedPackets.add(qp.packet);
        }

        if (regeneratedPackets.isEmpty()) {
            AutismClientMessaging.sendPrefixed("§cBurst: All packets failed to regenerate!");
            return;
        }

        net.minecraft.client.multiplayer.ClientPacketListener handler = mc.getConnection();

        for (Packet<?> packet : regeneratedPackets) {
            Packet<?> toSend = packet;

            if (packet instanceof ServerboundContainerClickPacket) {
                toSend = PacketRegenerator.regenerate(packet);
                if (toSend == null) {
                    continue;
                }
            }

            handler.send(toSend);
        }

        if (regeneratedPackets.size() > 1) {
            AutismClientMessaging.sendPrefixed("§a Burst sent " + regeneratedPackets.size() + " packets");
        }
    }

    private static java.util.List<Packet<?>> preGeneratePackets(AutismMacro macro, int startIdx, int count) {
        java.util.List<Packet<?>> result = new java.util.ArrayList<>();
        int maxIdx = count < 0 ? macro.actions.size() : Math.min(startIdx + count, macro.actions.size());
        return preGeneratePackets(macro, startIdx, count, maxIdx);
    }

    private static java.util.List<Packet<?>> preGeneratePackets(AutismMacro macro, int startIdx, int count, int endIdx) {
        java.util.List<Packet<?>> result = new java.util.ArrayList<>();
        int maxIdx = Math.min(endIdx, count < 0 ? macro.actions.size() : Math.min(startIdx + count, macro.actions.size()));

        Minecraft mc = Minecraft.getInstance();

        for (int j = startIdx; j < maxIdx; j++) {
            MacroAction act = macro.actions.get(j);
            if (!(act instanceof SendPacketAction)) break;

            SendPacketAction spa = (SendPacketAction) act;
            for (autismclient.util.AutismSharedState.QueuedPacket qp : spa.getPackets()) {
                if (qp.packet == null) continue;

                result.add(qp.packet);
            }
        }

        return result;
    }

    private static void executePreGeneratedBurst(Minecraft mc, java.util.List<Packet<?>> packets) {
        if (packets.isEmpty() || mc.getConnection() == null) return;

        net.minecraft.client.multiplayer.ClientPacketListener handler = mc.getConnection();

        for (Packet<?> p : packets) {
            Packet<?> toSend = p;

            if (p instanceof ServerboundContainerClickPacket) {
                toSend = PacketRegenerator.regenerate(p);
                if (toSend == null) {
                    continue;
                }
            }

            handler.send(toSend);
        }

        AutismModule module = AutismModule.get();
        if (module.shouldForceChannelFlush()) {
            try {
                net.minecraft.network.Connection connection = handler.getConnection();
                if (connection != null) {

                    connection.flushChannel();
                }
            } catch (Exception e) {

            }
        }
    }

    private static int countPreGeneratedActions(AutismMacro macro, int startIdx, int packetCount) {
        return countPreGeneratedActions(macro, startIdx, packetCount, macro.actions.size());
    }

    private static int countPreGeneratedActions(AutismMacro macro, int startIdx, int packetCount, int endIdx) {
        int actionsToSkip = 0;
        int packetsRemaining = packetCount;

        for (int j = startIdx; j < endIdx && packetsRemaining > 0; j++) {
            MacroAction act = macro.actions.get(j);
            if (!(act instanceof SendPacketAction)) break;

            SendPacketAction spa = (SendPacketAction) act;
            int actionPackets = spa.getPackets().size();
            packetsRemaining -= actionPackets;
            actionsToSkip++;
        }

        return actionsToSkip;
    }
}

