package autismclient.util.macro;

import autismclient.util.AutismPacketNamer;
import autismclient.util.AutismPacketRegistry;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.network.protocol.Packet;

public final class PacketGateManager {
    public enum Result { PASS, CANCEL, DELAY }

    public static final class Gate {
        public final String id;
        public final PacketGateAction.GateMode mode;
        public final ArrayList<String> packets;
        public final boolean flushOnDisable;

        Gate(String id, PacketGateAction.GateMode mode, ArrayList<String> packets, boolean flushOnDisable) {
            this.id = id;
            this.mode = mode;
            this.packets = packets;
            this.flushOnDisable = flushOnDisable;
        }
    }

    private static final ConcurrentHashMap<String, Gate> GATES = new ConcurrentHashMap<>();
    private static String lastOpenedGateId = null;

    private PacketGateManager() {}

    public static void install(PacketGateAction action) {
        if (action == null) return;
        String id = action.gateId == null || action.gateId.isBlank() ? "auto" : action.gateId.trim();
        if (action.mode == PacketGateAction.GateMode.DISABLE_GATE) {
            disable(id);
            return;
        }
        GATES.put(id, new Gate(id, action.mode, action.effectivePackets(), action.flushOnDisable));
        lastOpenedGateId = id;
    }

    public static void disable(String id) {
        if (id == null || id.isBlank() || id.equalsIgnoreCase("all")) GATES.clear();
        else GATES.remove(id.trim());
    }

    public static void disableAndFlushConfigured(String id, net.minecraft.client.multiplayer.ClientPacketListener connection, boolean flushRequested) {
        if (id == null || id.isBlank() || id.equalsIgnoreCase("all")) {
            clearAllAndFlushConfigured(connection);
            return;
        }
        Gate removed = GATES.remove(id.trim());
        if (removed != null && lastOpenedGateId != null && lastOpenedGateId.equals(removed.id)) {
            lastOpenedGateId = newestGateIdOrNull();
        }
        boolean shouldFlush = flushRequested || (removed != null && removed.flushOnDisable);
        if (shouldFlush && !hasActiveDelayGate()) {
            autismclient.util.AutismSharedState.get().flushDelayedPackets(connection);
        }
    }

    public static void disableCurrentGate() {
        if (lastOpenedGateId != null) {
            disableAndFlushConfigured(lastOpenedGateId, net.minecraft.client.Minecraft.getInstance().getConnection(), true);
            lastOpenedGateId = null;
        }
    }

    public static void clearAll() {
        GATES.clear();
        lastOpenedGateId = null;
    }

    public static void clearAllAndFlushConfigured(net.minecraft.client.multiplayer.ClientPacketListener connection) {
        boolean flush = GATES.values().stream().anyMatch(g -> g.mode == PacketGateAction.GateMode.DELAY && g.flushOnDisable);
        clearAll();
        if (flush) autismclient.util.AutismSharedState.get().flushDelayedPackets(connection);
    }

    private static boolean hasActiveDelayGate() {
        return GATES.values().stream().anyMatch(g -> g.mode == PacketGateAction.GateMode.DELAY);
    }

    private static String newestGateIdOrNull() {
        String newest = null;
        for (String id : GATES.keySet()) newest = id;
        return newest;
    }

    public static Result handle(Packet<?> packet, String direction) {
        if (packet == null || GATES.isEmpty()) return Result.PASS;
        Result result = Result.PASS;
        for (Gate gate : GATES.values()) {
            boolean packetMatches = gate.packets.isEmpty() || gate.packets.stream().anyMatch(p -> matchesPacket(p, packet, direction));
            switch (gate.mode) {
                case CANCEL -> { if (packetMatches) return Result.CANCEL; }
                case DELAY -> { if (packetMatches) result = Result.DELAY; }
                case ALLOW_ONLY -> { if (!packetMatches) return Result.CANCEL; }
                case DISABLE_GATE -> {}
            }
        }
        return result;
    }

    public static boolean hasActiveGates() {
        return !GATES.isEmpty();
    }

    public static boolean matchesPacket(String expected, Packet<?> packet, String direction) {
        if (expected == null || expected.isBlank()) return true;
        String want = normalize(expected);
        String friendlyDirectional = normalize(AutismPacketNamer.getFriendlyName(packet, direction));
        String friendly = normalize(AutismPacketNamer.getFriendlyName(packet));
        String simple = normalize(packet.getClass().getSimpleName());
        @SuppressWarnings("unchecked")
        String registry = normalize(AutismPacketRegistry.getName((Class<? extends Packet<?>>) packet.getClass()));
        return contains(want, friendlyDirectional) || contains(want, friendly) || contains(want, simple) || contains(want, registry);
    }

    private static boolean contains(String a, String b) {
        return !a.isEmpty() && !b.isEmpty() && (a.equals(b) || a.endsWith(b) || b.endsWith(a));
    }

    private static String normalize(String value) {
        if (value == null) return "";
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
