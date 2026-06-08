package autismclient.commands.impl;

import autismclient.commands.Command;
import autismclient.commands.AutismCommandSource;
import autismclient.util.AutismClientMessaging;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

public class DropCommand extends Command {
    public DropCommand() { super("drop", "Drop hand|hotbar|all from inventory."); }

    @Override
    public void build(LiteralArgumentBuilder<AutismCommandSource> root) {
        root.executes(ctx -> { dropHand(); return SUCCESS; });
        root.then(LiteralArgumentBuilder.<AutismCommandSource>literal("hand").executes(ctx -> { dropHand(); return SUCCESS; }));
        root.then(LiteralArgumentBuilder.<AutismCommandSource>literal("hotbar").executes(ctx -> { dropHotbar(); return SUCCESS; }));
        root.then(LiteralArgumentBuilder.<AutismCommandSource>literal("all").executes(ctx -> { dropAll(); return SUCCESS; }));
    }

    private static LocalPlayer player() { return Minecraft.getInstance().player; }

    private static void dropHand() {
        LocalPlayer p = player();
        if (p == null) { AutismClientMessaging.sendPrefixed("§cNot in a world."); return; }
        p.drop(true);
    }

    private static void dropHotbar() {
        LocalPlayer p = player();
        if (p == null) { AutismClientMessaging.sendPrefixed("§cNot in a world."); return; }
        int original = p.getInventory().getSelectedSlot();
        for (int slot = 0; slot < 9; slot++) {
            p.getInventory().setSelectedSlot(slot);
            p.drop(true);
        }
        p.getInventory().setSelectedSlot(original);
    }

    private static void dropAll() {
        LocalPlayer p = player();
        if (p == null) { AutismClientMessaging.sendPrefixed("§cNot in a world."); return; }

        var conn = Minecraft.getInstance().getConnection();
        if (conn == null) return;
        int original = p.getInventory().getSelectedSlot();
        for (int slot = 0; slot < 9; slot++) {
            p.getInventory().setSelectedSlot(slot);
            conn.send(new ServerboundPlayerActionPacket(
                ServerboundPlayerActionPacket.Action.DROP_ALL_ITEMS, BlockPos.ZERO, Direction.DOWN));
        }
        p.getInventory().setSelectedSlot(original);
        AutismClientMessaging.sendPrefixed("§eDropped hotbar. (Main inventory drop requires open inventory — use macro DropAction for that.)");
    }
}
