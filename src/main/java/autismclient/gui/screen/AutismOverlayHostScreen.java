package autismclient.gui.screen;

import autismclient.util.AutismOverlayManager;
import autismclient.util.AutismUiScale;
import autismclient.util.IAutismOverlay;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

public class AutismOverlayHostScreen extends Screen {

    private final IAutismOverlay tiedOverlay;
    private final Screen returnScreen;

    public AutismOverlayHostScreen() {
        this(null, null);
    }

    public AutismOverlayHostScreen(IAutismOverlay tiedOverlay) {
        this(tiedOverlay, null);
    }

    public AutismOverlayHostScreen(IAutismOverlay tiedOverlay, Screen returnScreen) {
        super(Component.literal("Autism Overlays"));
        this.tiedOverlay = tiedOverlay;
        this.returnScreen = returnScreen;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void tick() {
        super.tick();

        if (tiedOverlay != null && !tiedOverlay.isVisible() && minecraft != null && minecraft.screen == this) {
            minecraft.setScreen(returnScreen);
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {

        AutismOverlayManager.get().renderAll(graphics, mouseX, mouseY, delta);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        return AutismOverlayManager.get().handleMouseClicked(event.x(), event.y(), event.button());
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        return AutismOverlayManager.get().handleMouseReleased(event.x(), event.y(), event.button());
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        return AutismOverlayManager.get().handleMouseDragged(event.x(), event.y(), event.button(), dx, dy);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        return AutismOverlayManager.get().handleMouseScrolled(mouseX, mouseY, scrollY);
    }

    @Override
    public boolean keyPressed(KeyEvent input) {

        boolean inventoryKey = minecraft != null && minecraft.options != null
            && minecraft.options.keyInventory != null
            && minecraft.options.keyInventory.matches(input);
        boolean closeKey = input.key() == GLFW.GLFW_KEY_ESCAPE || inventoryKey;

        if (closeKey && minecraft != null && !AutismOverlayManager.get().isAnyTextFieldFocused()) {
            minecraft.setScreen(returnScreen);
            return true;
        }

        if (AutismOverlayManager.get().handleKeyPressed(input.key(), input.scancode(), input.modifiers())) {
            return true;
        }
        if (closeKey && minecraft != null) {
            minecraft.setScreen(returnScreen);
            return true;
        }
        return false;
    }

    @Override
    public boolean charTyped(CharacterEvent input) {
        return AutismOverlayManager.get().handleCharTyped((char) input.codepoint(), 0);
    }
}
