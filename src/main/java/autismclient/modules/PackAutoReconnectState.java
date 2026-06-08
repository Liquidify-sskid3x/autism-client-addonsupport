package autismclient.modules;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;

public final class PackAutoReconnectState {
    private static final Minecraft MC = Minecraft.getInstance();
    private static ServerData lastServer;
    private static ServerAddress lastAddress;
    private static Screen countdownScreen;
    private static int ticksLeft;

    private PackAutoReconnectState() {
    }

    public static void remember(ServerData server) {
        if (server == null || server.ip == null || server.ip.isBlank()) return;
        ServerData copy = new ServerData(server.name, server.ip, server.type());
        copy.copyFrom(server);
        lastServer = copy;
        lastAddress = ServerAddress.parseString(server.ip);
        countdownScreen = null;
        ticksLeft = delayTicks();
    }

    public static boolean shouldShow() {
        PackModule module = PackModuleRegistry.get("auto-reconnect");
        return module != null && module.isEnabled() && lastServer != null && lastAddress != null && MC.allowsMultiplayer();
    }

    public static boolean hideButtons() {
        PackModule module = PackModuleRegistry.get("auto-reconnect");
        return module != null && module.isEnabled() && Boolean.parseBoolean(module.value("hide-buttons"));
    }

    public static void tick(Screen screen, Screen parent) {
        if (!shouldShow()) {
            countdownScreen = null;
            return;
        }
        if (countdownScreen != screen) {
            countdownScreen = screen;
            ticksLeft = delayTicks();
        }
        if (ticksLeft > 0) {
            ticksLeft--;
            return;
        }
        reconnect(parent);
    }

    public static void tickCurrentScreen() {
        if (MC.screen instanceof DisconnectedScreen) {
            tick(MC.screen, MC.screen);
        } else {
            countdownScreen = null;
        }
    }

    public static void reconnect(Screen parent) {
        if (!shouldShow()) return;
        Screen nextParent = parent == null ? MC.screen : parent;
        ConnectScreen.startConnecting(nextParent, MC, lastAddress, lastServer, false, null);
        countdownScreen = null;
        ticksLeft = delayTicks();
    }

    public static String statusText() {
        if (!shouldShow()) return "";
        double seconds = Math.max(0.0, ticksLeft / 20.0);
        return String.format(java.util.Locale.ROOT, "Auto reconnect in %.1fs", seconds);
    }

    private static int delayTicks() {
        PackModule module = PackModuleRegistry.get("auto-reconnect");
        if (module == null) return 70;
        try {
            return Math.max(0, (int) Math.round(Double.parseDouble(module.value("delay")) * 20.0));
        } catch (NumberFormatException ignored) {
            return 70;
        }
    }
}
