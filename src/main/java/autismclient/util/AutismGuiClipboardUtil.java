package autismclient.util;

import net.minecraft.client.Minecraft;

public final class AutismGuiClipboardUtil {
    private static final Minecraft MC = Minecraft.getInstance();

    private AutismGuiClipboardUtil() {
    }

    public static void copyGuiTitleJson() {
        if (MC.screen == null || MC.keyboardHandler == null) {
            AutismNotifications.error("Copy failed: no screen.");
            return;
        }

        String title = MC.screen.getTitle() == null ? "" : MC.screen.getTitle().getString();
        MC.keyboardHandler.setClipboard(title);
        AutismNotifications.copied("GUI title copied.");
    }
}
