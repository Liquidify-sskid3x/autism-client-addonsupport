package autismclient.commands.impl;

import autismclient.commands.Command;
import autismclient.commands.AutismCommandSource;
import autismclient.commands.AutismCommands;
import autismclient.commands.args.PacketClassArgumentType;
import autismclient.util.AutismClientMessaging;
import autismclient.util.AutismPacketRegistry;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.Packet;

import java.lang.reflect.Constructor;

public class SendCommand extends Command {
    public SendCommand() { super("send", "Send a raw C2S packet by class name (no-arg constructor only)."); }

    @Override
    public void build(LiteralArgumentBuilder<AutismCommandSource> root) {
        root.executes(ctx -> {
            AutismClientMessaging.sendPrefixed("§eUsage: §f" + AutismCommands.effectivePrefix() + "send <packetName>");
            AutismClientMessaging.sendPrefixed("§7Tip: press Tab after the space to search C2S packet classes.");
            return SUCCESS;
        });
        root.then(RequiredArgumentBuilder.<AutismCommandSource, String>argument("packet", PacketClassArgumentType.packetClass())
            .executes(ctx -> {
                String name = PacketClassArgumentType.get(ctx, "packet");
                Class<? extends Packet<?>> cls = AutismPacketRegistry.getPacket(name);
                if (cls == null) { AutismClientMessaging.sendPrefixed("§cUnknown packet: §f" + name); return SUCCESS; }
                try {
                    Constructor<? extends Packet<?>> ctor = null;
                    for (Constructor<?> c : cls.getDeclaredConstructors()) {
                        if (c.getParameterCount() == 0) { @SuppressWarnings("unchecked") Constructor<? extends Packet<?>> ok = (Constructor<? extends Packet<?>>) c; ctor = ok; break; }
                    }
                    if (ctor == null) { AutismClientMessaging.sendPrefixed("§cPacket " + name + " has no no-arg constructor — build it via Send Packet macro action instead."); return SUCCESS; }
                    ctor.setAccessible(true);
                    Packet<?> packet = ctor.newInstance();
                    var conn = Minecraft.getInstance().getConnection();
                    if (conn == null) { AutismClientMessaging.sendPrefixed("§cNo network connection."); return SUCCESS; }
                    autismclient.util.AutismSharedState.get().sendPacketBypassDelay(conn, packet);
                    AutismClientMessaging.sendPrefixed("§aSent §f" + name);
                } catch (Throwable t) {
                    AutismClientMessaging.sendPrefixed("§cFailed to send §f" + name + "§c: " + t.getMessage());
                }
                return SUCCESS;
            }));
    }
}
