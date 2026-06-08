package autismclient.gui.vanillaui;

import autismclient.util.AutismUiScale;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.IdentityHashMap;
import java.util.Map;

public final class UiContexts {
    private static final UiTheme THEME = new UiTheme();
    private static final Map<Font, UiTextRenderer> TEXT_RENDERERS = new IdentityHashMap<>();

    private UiContexts() {
    }

    public static UiContext overlay(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY) {
        return new UiContext(
            graphics,
            THEME,
            textRenderer(font),
            Math.max(1, AutismUiScale.getVirtualScreenWidth()),
            Math.max(1, AutismUiScale.getVirtualScreenHeight()),
            mouseX,
            mouseY,
            0.0f
        );
    }

    public static UiTextRenderer textRenderer(Font font) {
        synchronized (TEXT_RENDERERS) {
            return TEXT_RENDERERS.computeIfAbsent(font, UiTextRenderer::new);
        }
    }
}
