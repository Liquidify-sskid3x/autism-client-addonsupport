package autismclient.compat;

import autismclient.modules.AutismModule;
import autismclient.util.AutismBindUtil;
import autismclient.util.AutismCompatManager;
import autismclient.util.AutismConfig;
import autismclient.util.AutismLinks;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

public class AutismModMenuConfigScreen extends Screen {
    private final Screen parent;

    private final List<Keybind> keybinds = new ArrayList<>();
    private final List<Button> keybindButtons = new ArrayList<>();
    private int capturing = -1;

    private record Keybind(String label, IntSupplier getter, IntConsumer setter) {}

    public AutismModMenuConfigScreen(Screen parent) {
        super(Component.literal("AUTISM Client Settings"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        keybinds.clear();
        keybindButtons.clear();
        capturing = -1;

        AutismConfig cfg = AutismConfig.getGlobal();
        AutismModule module = AutismModule.get();

        int w = 240;
        int h = 18;
        int gap = 2;
        int x = this.width / 2 - w / 2;
        int y = 28;

        addRenderableWidget(CycleButton.onOffBuilder(cfg.keybindInsideGui)
            .create(x, y, w, h, Component.literal("Inside GUI"),
                (b, v) -> { cfg.keybindInsideGui = v; cfg.save(); }));
        y += h + gap;

        addRenderableWidget(CycleButton.onOffBuilder(cfg.customMainMenu)
            .create(x, y, w, h, Component.literal("Custom Menu"),
                (b, v) -> { cfg.customMainMenu = v; cfg.save(); }));
        y += h + gap;

        List<String> prefixes = new ArrayList<>(AutismCompatManager.COMMAND_PREFIX_CHOICES);
        if (AutismCompatManager.isMeteorAvailable()) prefixes.remove(".");
        String currentPrefix = AutismCompatManager.effectiveCommandPrefix();
        if (!prefixes.contains(currentPrefix)) currentPrefix = prefixes.isEmpty() ? "%" : prefixes.get(0);
        addRenderableWidget(CycleButton.<String>builder(Component::literal, currentPrefix)
            .withValues(prefixes)
            .create(x, y, w, h, Component.literal("Command Prefix"),
                (b, value) -> module.setCommandPrefix(value)));
        y += h + gap;

        keybinds.add(new Keybind("Module Menu", () -> cfg.keybindModuleMenu, v -> { cfg.keybindModuleMenu = v; cfg.save(); }));
        keybinds.add(new Keybind("Load GUI", () -> cfg.keybindLoadGui, v -> { cfg.keybindLoadGui = v; cfg.save(); }));
        keybinds.add(new Keybind("Flush Queue", () -> cfg.keybindFlushQueue, v -> { cfg.keybindFlushQueue = v; cfg.save(); }));
        keybinds.add(new Keybind("Clear Queue", () -> cfg.keybindClearQueue, v -> { cfg.keybindClearQueue = v; cfg.save(); }));
        keybinds.add(new Keybind("Toggle Logger", () -> cfg.keybindToggleLogger, v -> { cfg.keybindToggleLogger = v; cfg.save(); }));
        keybinds.add(new Keybind("Toggle Send", () -> cfg.keybindToggleSend, v -> { cfg.keybindToggleSend = v; cfg.save(); }));
        keybinds.add(new Keybind("Toggle Delay", () -> cfg.keybindToggleDelay, v -> { cfg.keybindToggleDelay = v; cfg.save(); }));
        for (int i = 0; i < keybinds.size(); i++) {
            final int idx = i;
            Button btn = Button.builder(keybindLabel(idx), b -> startCapture(idx)).bounds(x, y, w, h).build();
            keybindButtons.add(btn);
            addRenderableWidget(btn);
            y += h + gap;
        }

        int half = (w - gap) / 2;
        addRenderableWidget(Button.builder(Component.literal("Donate"), b -> AutismLinks.open(AutismLinks.KOFI))
            .bounds(x, y, half, h).build());
        addRenderableWidget(Button.builder(Component.literal("Discord"), b -> AutismLinks.open(AutismLinks.DISCORD))
            .bounds(x + half + gap, y, w - half - gap, h).build());
        y += h + gap + 2;

        addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
            .bounds(x, y, w, h).build());
    }

    private Component keybindLabel(int idx) {
        Keybind k = keybinds.get(idx);
        if (capturing == idx) return Component.literal(k.label() + ": press a key…");
        return Component.literal(k.label() + ": " + AutismBindUtil.getBindName(k.getter().getAsInt()));
    }

    private void startCapture(int idx) {
        capturing = idx;
        refreshKeybindLabels();
    }

    private void refreshKeybindLabels() {
        for (int i = 0; i < keybindButtons.size(); i++) {
            keybindButtons.get(i).setMessage(keybindLabel(i));
        }
    }

    private void applyCapture(int code) {
        if (capturing < 0 || capturing >= keybinds.size()) return;
        keybinds.get(capturing).setter().accept(code);
        capturing = -1;
        refreshKeybindLabels();
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (capturing >= 0) {

            applyCapture(event.key() == GLFW.GLFW_KEY_ESCAPE ? -1 : event.key());
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        if (capturing >= 0) {
            applyCapture(AutismBindUtil.encodeMouseButton(event.button()));
            return true;
        }
        return super.mouseClicked(event, doubled);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.centeredText(this.font, this.title, this.width / 2, 12, 0xFFFFFFFF);
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }
}
