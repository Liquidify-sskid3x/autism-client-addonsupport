package autismclient.gui.screen;

import autismclient.gui.vanillaui.UiBounds;
import autismclient.gui.vanillaui.UiContext;
import autismclient.gui.vanillaui.UiContexts;
import autismclient.gui.vanillaui.UiRenderer;
import autismclient.gui.vanillaui.components.Button;
import autismclient.gui.vanillaui.components.CompactScreenPanel;
import autismclient.gui.vanillaui.components.CompactTextInput;
import autismclient.gui.vanillaui.components.CompactTheme;
import autismclient.gui.vanillaui.direct.DirectRenderContext;
import autismclient.gui.vanillaui.direct.DirectViewport;
import autismclient.modules.PackModule;
import autismclient.modules.PackModuleOption;
import autismclient.modules.PackBuiltinModules;
import autismclient.util.AutismNotifications;
import autismclient.util.AutismUiScale;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

public final class AutismAdminItemOptionScreen extends Screen {
    private static final CompactTheme THEME = new CompactTheme();
    private static final int PANEL_W = 430;
    private static final int PANEL_H = 286;
    private static final int HEADER_H = 18;
    private static final int FOOTER_H = 24;

    public enum Mode {
        LORE("Lore", "One lore line per row. Order is preserved."),
        ENCHANTMENTS("Enchantments", "One enchantment per row: minecraft:id:level"),
        ATTRIBUTES("Attributes", "Raw attribute component rows. Advanced values stay exact."),
        RAW("Raw Data", "Exact raw value. Multiline editing does not discard data.");

        private final String title;
        private final String tip;

        Mode(String title, String tip) {
            this.title = title;
            this.tip = tip;
        }
    }

    private final Screen parent;
    private final PackModule module;
    private final PackModuleOption option;
    private final Mode mode;
    private final CompactTextInput editor = new CompactTextInput()
        .setMultiline(true)
        .setMaxLength(65535)
        .setHorizontalPadding(5)
        .setHoverEffectsEnabled(false)
        .setFocusEffectsEnabled(true);
    private DirectRenderContext lastDirectContext;
    private UiBounds closeBounds = UiBounds.of(0, 0, 0, 0);
    private UiBounds saveBounds = UiBounds.of(0, 0, 0, 0);
    private UiBounds cancelBounds = UiBounds.of(0, 0, 0, 0);

    public AutismAdminItemOptionScreen(Screen parent, PackModule module, PackModuleOption option, Mode mode) {
        super(Component.literal("Edit " + (mode == null ? "Value" : mode.title)));
        this.parent = parent;
        this.module = module;
        this.option = option;
        this.mode = mode == null ? Mode.RAW : mode;
        editor.setText(decode(module == null || option == null ? "" : module.value(option.id())));
        editor.setFocused(true);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        int mx = AutismUiScale.toVirtualInt(mouseX);
        int my = AutismUiScale.toVirtualInt(mouseY);
        AutismUiScale.pushOverlayScale(graphics);
        try {
            int sw = screenWidth();
            int sh = screenHeight();
            int w = Math.min(PANEL_W, Math.max(180, sw - 8));
            int h = Math.min(PANEL_H, Math.max(130, sh - 8));
            int x = Math.max(4, (sw - w) / 2);
            int y = Math.max(4, (sh - h) / 2);
            UiContext ui = UiContexts.overlay(graphics, font, mx, my);
            UiRenderer.rect(graphics, UiBounds.of(0, 0, sw, sh), 0x99000000);
            UiBounds panel = UiBounds.of(x, y, w, h);
            CompactScreenPanel.render(ui, panel, HEADER_H, "Edit " + mode.title, mx >= x && mx < x + w && my >= y && my < y + HEADER_H);
            closeBounds = CompactScreenPanel.closeButton(panel, HEADER_H);

            int footerTop = y + h - FOOTER_H;
            int tipY = y + HEADER_H + 5;
            List<String> tipLines = ui.text().wrapFully(mode.tip, Math.max(1, w - 14));
            int maxTipLines = Math.min(2, tipLines.size());
            int lineH = ui.theme().typography().lineHeight;
            for (int i = 0; i < maxTipLines; i++) {
                ui.text().draw(graphics, tipLines.get(i), x + 7, tipY + i * lineH, ui.theme().colors().muted);
            }

            int editorY = tipY + Math.max(1, maxTipLines) * lineH + 4;
            int editorH = Math.max(42, footerTop - editorY - 5);
            editor.setBounds(x + 6, editorY, w - 12, editorH);
            DirectViewport viewport = DirectViewport.current(1.0f);
            lastDirectContext = new DirectRenderContext(graphics, font, viewport, THEME, mx, my, delta);
            editor.render(lastDirectContext);

            int buttonY = footerTop + 4;
            saveBounds = UiBounds.of(x + w - 110, buttonY, 50, 16);
            cancelBounds = UiBounds.of(x + w - 56, buttonY, 50, 16);
            Button.render(ui, saveBounds, "Save", Button.Tone.SUCCESS, saveBounds.contains(mx, my), false);
            Button.render(ui, cancelBounds, "Cancel", Button.Tone.NORMAL, cancelBounds.contains(mx, my), false);
        } finally {
            AutismUiScale.popOverlayScale(graphics);
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        int mx = AutismUiScale.toVirtualInt(event.x());
        int my = AutismUiScale.toVirtualInt(event.y());
        if (event.button() == 0 && closeBounds.contains(mx, my)) {
            onClose();
            return true;
        }
        if (event.button() == 0 && saveBounds.contains(mx, my)) {
            save();
            return true;
        }
        if (event.button() == 0 && cancelBounds.contains(mx, my)) {
            onClose();
            return true;
        }
        return lastDirectContext == null || editor.mouseClicked(lastDirectContext, mx, my, event.button());
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        return lastDirectContext == null || editor.mouseReleased(lastDirectContext,
            AutismUiScale.toVirtualInt(event.x()), AutismUiScale.toVirtualInt(event.y()), event.button());
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        return lastDirectContext == null || editor.mouseDragged(lastDirectContext,
            AutismUiScale.toVirtualInt(event.x()), AutismUiScale.toVirtualInt(event.y()), event.button(), (float) dx, (float) dy);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        return lastDirectContext == null || editor.mouseScrolled(lastDirectContext,
            AutismUiScale.toVirtualInt(mouseX), AutismUiScale.toVirtualInt(mouseY), (float) scrollY);
    }

    @Override
    public boolean keyPressed(KeyEvent input) {
        if (input.key() == GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            return true;
        }
        return lastDirectContext == null || editor.keyPressed(lastDirectContext, input.key(), input.scancode(), input.modifiers());
    }

    @Override
    public boolean charTyped(CharacterEvent input) {
        return lastDirectContext == null || editor.charTyped(lastDirectContext, (char) input.codepoint(), 0);
    }

    @Override
    public void onClose() {
        if (minecraft != null) minecraft.setScreen(parent);
    }

    private void save() {
        if (module != null && option != null) {
            String value = encode(editor.text());
            if ("nbt-item-components".equals(option.id()) && module instanceof PackBuiltinModules.AdminToolsModule adminTools) {
                adminTools.setRawItemComponents(value);
            } else {
                module.setValue(option.id(), value);
            }
        }
        AutismNotifications.show(mode.title + " updated.", 0xFF35D873);
        onClose();
    }

    private String decode(String raw) {
        String value = raw == null ? "" : raw;
        return switch (mode) {
            case LORE -> value.replace("|", "\n");
            case ENCHANTMENTS -> String.join("\n", splitTopLevel(value));
            default -> value;
        };
    }

    private String encode(String displayed) {
        String value = displayed == null ? "" : displayed;
        return switch (mode) {
            case LORE -> String.join("|", nonEmptyLines(value));
            case ENCHANTMENTS -> String.join(",", nonEmptyLines(value));
            default -> value;
        };
    }

    private static List<String> nonEmptyLines(String value) {
        List<String> out = new ArrayList<>();
        for (String line : value.split("\\R", -1)) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) out.add(trimmed);
        }
        return out;
    }

    private static List<String> splitTopLevel(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) return out;
        StringBuilder current = new StringBuilder();
        int depth = 0;
        boolean quoted = false;
        char quote = 0;
        boolean escaped = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (escaped) {
                current.append(c);
                escaped = false;
            } else if (c == '\\') {
                current.append(c);
                escaped = true;
            } else if (quoted) {
                current.append(c);
                if (c == quote) quoted = false;
            } else if (c == '\'' || c == '"') {
                current.append(c);
                quoted = true;
                quote = c;
            } else {
                if (c == '{' || c == '[' || c == '(') depth++;
                else if (c == '}' || c == ']' || c == ')') depth = Math.max(0, depth - 1);
                if (c == ',' && depth == 0) {
                    String entry = current.toString().trim();
                    if (!entry.isEmpty()) out.add(entry);
                    current.setLength(0);
                    continue;
                }
                current.append(c);
            }
        }
        String entry = current.toString().trim();
        if (!entry.isEmpty()) out.add(entry);
        return out;
    }

    private int screenWidth() {
        int virtual = AutismUiScale.getVirtualScreenWidth();
        return virtual <= 0 ? width : virtual;
    }

    private int screenHeight() {
        int virtual = AutismUiScale.getVirtualScreenHeight();
        return virtual <= 0 ? height : virtual;
    }
}
