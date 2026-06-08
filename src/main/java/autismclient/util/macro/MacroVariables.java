package autismclient.util.macro;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;

public final class MacroVariables {
    private static final ThreadLocal<Map<String, String>> RUN_VALUES = ThreadLocal.withInitial(ConcurrentHashMap::new);

    private MacroVariables() {}

    public static void clear() {
        RUN_VALUES.get().clear();
    }

    public static void set(String name, Object value) {
        if (name == null || name.isBlank()) return;
        RUN_VALUES.get().put(cleanName(name), value == null ? "" : String.valueOf(value));
    }

    public static String get(String name, Minecraft mc) {
        String key = cleanName(name);
        String explicit = RUN_VALUES.get().get(key);
        if (explicit != null) return explicit;
        return builtIn(key, mc);
    }

    public static String expand(String text, Minecraft mc) {
        if (text == null || text.indexOf('{') < 0) return text == null ? "" : text;
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{') {
                int end = text.indexOf('}', i + 1);
                if (end > i) {
                    out.append(get(text.substring(i + 1, end), mc));
                    i = end;
                    continue;
                }
            }
            out.append(c);
        }
        return out.toString();
    }

    private static String cleanName(String name) {
        return name == null ? "" : name.trim().replace("{", "").replace("}", "");
    }

    private static String builtIn(String name, Minecraft mc) {
        if (mc == null || mc.player == null) {
            return switch (name) {
                case "timestamp" -> Instant.now().toString();
                default -> "";
            };
        }
        return switch (name) {
            case "player" -> mc.player.getName().getString();
            case "x" -> String.format(java.util.Locale.ROOT, "%.3f", mc.player.getX());
            case "y" -> String.format(java.util.Locale.ROOT, "%.3f", mc.player.getY());
            case "z" -> String.format(java.util.Locale.ROOT, "%.3f", mc.player.getZ());
            case "yaw" -> String.format(java.util.Locale.ROOT, "%.2f", mc.player.getYRot());
            case "pitch" -> String.format(java.util.Locale.ROOT, "%.2f", mc.player.getXRot());
            case "selected_slot", "target_slot" -> String.valueOf(mc.player.getInventory().getSelectedSlot());
            case "server" -> {
                ServerData server = mc.getCurrentServer();
                yield server == null || server.ip == null ? "" : server.ip;
            }
            case "timestamp" -> Instant.now().toString();
            default -> "";
        };
    }
}
