package autismclient.util;

import autismclient.security.AutismProtector;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.Packet;

public class AutismPacketSender {

    public static void send(Packet<?> packet) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() == null) return;

        AutismProtector.markUserBypass(packet);
        mc.getConnection().send(packet);
    }

    public static void sendPacketDirect(Packet<?> packet) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() == null) return;
        AutismProtector.markUserBypass(packet);
        mc.getConnection().send(packet);
    }

}
