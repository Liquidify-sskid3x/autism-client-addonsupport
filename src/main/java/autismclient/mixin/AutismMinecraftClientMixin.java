package autismclient.mixin;

import autismclient.gui.AutismLoadingOverlay;
import autismclient.gui.macro.editor.ActionEditorOverlay;
import autismclient.gui.screen.AutismTitleScreen;
import autismclient.modules.AutismModule;
import autismclient.modules.PackHideState;
import autismclient.modules.PackModuleMovementUtil;
import autismclient.modules.PackModuleRegistry;
import autismclient.util.AutismInputClicker;
import autismclient.util.AutismSharedState;
import autismclient.util.AutismWindowBranding;
import net.minecraft.util.ModCheck;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.LoadingOverlay;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.server.packs.resources.ReloadInstance;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;
import java.util.function.Consumer;

@Mixin(Minecraft.class)
public class AutismMinecraftClientMixin {
    @Unique
    private static final String PACKUTIL_WINDOW_TITLE = AutismWindowBranding.WINDOW_TITLE;

    @Unique
    private boolean autism$escapeWasDown;
    @Unique
    private boolean autism$inventoryWasDown;
    @Unique
    private boolean autism$replacingTitleScreen;

    @Redirect(
        method = "*",
        at = @At(value = "NEW", target = "net/minecraft/client/gui/screens/LoadingOverlay")
    )
    private static LoadingOverlay autism$replaceLoadingOverlay(
        Minecraft minecraft, ReloadInstance reload,
        Consumer<Optional<Throwable>> onFinish, boolean fadeIn
    ) {
        return new AutismLoadingOverlay(minecraft, reload, onFinish, fadeIn);
    }

    @Inject(method = "createTitle", at = @At("HEAD"), cancellable = true)
    private void autism$createCustomWindowTitle(CallbackInfoReturnable<String> cir) {

        if (PackHideState.isActive()) return;
        cir.setReturnValue(PACKUTIL_WINDOW_TITLE);
    }

    @Inject(method = "checkModStatus", at = @At("HEAD"), cancellable = true)
    private static void autism$reportVanillaWhileHidden(CallbackInfoReturnable<ModCheck> cir) {

        if (PackHideState.isActive()) {
            cir.setReturnValue(new ModCheck(ModCheck.Confidence.PROBABLY_NOT, "Client jar signature and brand is untouched"));
        }
    }

    @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
    private void autism$replacePauseScreen(Screen screen, CallbackInfo ci) {

        if (!PackHideState.isActive()) return;
        if (screen instanceof net.minecraft.client.gui.screens.PauseScreen ps && ps.showsPauseMenu()) {
            ((Minecraft) (Object) this).setScreen(new autismclient.gui.screen.AutismPauseScreen());
            ci.cancel();
        }
    }

    @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
    private void autism$replaceTitleScreen(Screen screen, CallbackInfo ci) {

        if (autism$replacingTitleScreen || !(screen instanceof TitleScreen)) {
            return;
        }
        Minecraft client = (Minecraft) (Object) this;

        if (!PackHideState.isActive() && autismclient.util.AutismWelcomeGate.shouldShow(autismclient.util.AutismConfig.getGlobal())) {
            autismclient.util.AutismWelcomeGate.markShown(autismclient.util.AutismConfig.getGlobal());
            client.setScreen(new autismclient.gui.screen.AutismWelcomeScreen());
            ci.cancel();
            return;
        }

        if (PackHideState.isActive()) {
            autism$setMenuScreen(client, new autismclient.gui.screen.AutismPanicTitleScreen());
            ci.cancel();
            return;
        }

        if (!autismclient.util.AutismMenuPrefs.customMainMenuEnabled()) return;

        autism$setMenuScreen(client, new AutismTitleScreen());
        ci.cancel();
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void autism$onTickHead(CallbackInfo ci) {
        AutismWindowBranding.tick((Minecraft) (Object) this);
        AutismSharedState.get().onClientTickStart();
        AutismInputClicker.onClientTickStart();
        PackModuleMovementUtil.preMovementTick();
        if (autismclient.util.AutismContainerHold.hasExpiryWork()) autismclient.util.AutismContainerHold.tickExpiry();
        if (!PackHideState.isActive()) autism$pollCommandBinds();
    }

    @Unique
    private final java.util.Map<Integer, Boolean> autism$commandBindWasDown = new java.util.HashMap<>();

    @Unique
    private void autism$pollCommandBinds() {
        Minecraft client = (Minecraft) (Object) this;
        if (client.getWindow() == null) return;

        if (client.screen instanceof net.minecraft.client.gui.screens.ChatScreen) return;
        if (client.screen instanceof net.minecraft.client.gui.screens.inventory.SignEditScreen) return;
        java.util.Map<Integer, String> binds = AutismModule.get().getCommandBinds();
        if (binds.isEmpty()) return;
        long handle = client.getWindow().handle();
        for (java.util.Map.Entry<Integer, String> entry : binds.entrySet()) {
            int key = entry.getKey();
            boolean down = GLFW.glfwGetKey(handle, key) == GLFW.GLFW_PRESS;
            boolean wasDown = autism$commandBindWasDown.getOrDefault(key, false);
            autism$commandBindWasDown.put(key, down);
            if (down && !wasDown) {
                String cmd = entry.getValue();
                if (cmd != null && !cmd.isBlank()) {
                    autismclient.commands.AutismCommands.dispatch(cmd);
                }
            }
        }
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void autism$repairStrayTitleScreen(CallbackInfo ci) {
        Minecraft client = (Minecraft) (Object) this;

        if (autism$replacingTitleScreen) return;
        if (!(client.screen instanceof TitleScreen)) return;
        if (PackHideState.isActive()) {
            autism$setMenuScreen(client, new autismclient.gui.screen.AutismPanicTitleScreen());
        } else if (autismclient.util.AutismMenuPrefs.customMainMenuEnabled()) {
            autism$setMenuScreen(client, new AutismTitleScreen());
        }
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void autism$swapMenuOnPanicChange(CallbackInfo ci) {
        if (autism$replacingTitleScreen) return;
        Minecraft client = (Minecraft) (Object) this;

        if (PackHideState.isActive() && client.screen instanceof AutismTitleScreen) {
            autism$setMenuScreen(client, new autismclient.gui.screen.AutismPanicTitleScreen());
        } else if (!PackHideState.isActive()
                && client.screen instanceof autismclient.gui.screen.AutismPanicTitleScreen
                && autismclient.util.AutismMenuPrefs.customMainMenuEnabled()) {
            autism$setMenuScreen(client, new AutismTitleScreen());
        }
    }

    @Unique
    private void autism$setMenuScreen(Minecraft client, Screen menu) {
        autism$replacingTitleScreen = true;
        try {
            client.setScreen(menu);
        } finally {
            autism$replacingTitleScreen = false;
        }
    }

    @Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
    private void autism$onDoAttack(CallbackInfoReturnable<Boolean> cir) {
        Minecraft client = (Minecraft) (Object) this;

        if (AutismSharedState.get().hasEntityCaptureCallback()) {
            cir.setReturnValue(false);
            return;
        }
        for (autismclient.modules.PackModule module : PackModuleRegistry.activeModules()) {
            if (module.shouldCancelAttack(client.hitResult)) {
                cir.setReturnValue(false);
                return;
            }
        }
        if (AutismSharedState.get().hasAttackCaptureCallback()) {
            AutismSharedState.get().consumeAttackCaptureCallback();
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
    private void autism$cancelUseForModules(CallbackInfo ci) {
        Minecraft client = (Minecraft) (Object) this;

        if (AutismSharedState.get().hasEntityCaptureCallback()
                && !(client.hitResult instanceof net.minecraft.world.phys.EntityHitResult)) {
            ci.cancel();
            return;
        }
        for (autismclient.modules.PackModule module : PackModuleRegistry.activeModules()) {
            if (module.shouldCancelUse(client.hitResult, net.minecraft.world.InteractionHand.MAIN_HAND)) {
                ci.cancel();
                return;
            }
        }
    }

    @Inject(method = "handleKeybinds", at = @At("HEAD"), cancellable = true)
    private void autism$cancelCaptureOnEscape(CallbackInfo ci) {
        AutismInputClicker.beforeHandleKeybinds();
        Minecraft client = (Minecraft) (Object) this;
        if (client.getWindow() == null) {
            AutismInputClicker.afterHandleKeybinds();
            return;
        }

        boolean escapeDown = GLFW.glfwGetKey(client.getWindow().handle(), GLFW.GLFW_KEY_ESCAPE) == GLFW.GLFW_PRESS;
        boolean justPressed = escapeDown && !autism$escapeWasDown;
        autism$escapeWasDown = escapeDown;
        boolean inventoryDown = client.options != null && client.options.keyInventory.isDown();
        boolean inventoryJustPressed = inventoryDown && !autism$inventoryWasDown;
        autism$inventoryWasDown = inventoryDown;

        if (justPressed || inventoryJustPressed) {
            if (AutismSharedState.get().consumeCaptureCancelCallback()) {
                AutismInputClicker.afterHandleKeybinds();
                ci.cancel();
                return;
            }

            ActionEditorOverlay actionEditor = ActionEditorOverlay.getSharedOverlayIfExists();
            if (actionEditor != null && actionEditor.cancelCaptureIfActive()) {
                AutismInputClicker.afterHandleKeybinds();
                ci.cancel();
            }
        }
    }

    @Inject(method = "handleKeybinds", at = @At("TAIL"))
    private void autism$releaseQueuedClicks(CallbackInfo ci) {
        AutismInputClicker.afterHandleKeybinds();
    }

    @Inject(method = "pauseGame", at = @At("HEAD"), cancellable = true)
    private void autism$cancelLostFocusPause(boolean suppressPauseMenuIfWeReallyArePausing, CallbackInfo ci) {
        Minecraft client = (Minecraft) (Object) this;

        if (AutismSharedState.get().consumeCaptureCancelCallback()) {
            ci.cancel();
            return;
        }
        ActionEditorOverlay actionEditor = ActionEditorOverlay.getSharedOverlayIfExists();
        if (actionEditor != null && actionEditor.hasActiveCaptureSession()) {
            if (actionEditor.cancelCaptureIfActive()) {
                ci.cancel();
                return;
            }

            ci.cancel();
            return;
        }

        if (client.getWindow() != null && !client.getWindow().isFocused()) {
            AutismModule module = AutismModule.get();
            if (module != null && module.isActive() && module.isNoPauseOnLostFocus()) {
                ci.cancel();
            }
        }
    }

    @Inject(method = "getTickTargetMillis", at = @At("RETURN"), cancellable = true)
    private void autism$applySpeedTimer(float defaultTickTargetMillis, CallbackInfoReturnable<Float> cir) {

        if (((Minecraft) (Object) this).player == null) return;
        float multiplier = PackModuleMovementUtil.speedTimerMultiplier();
        if (multiplier != 1.0f) cir.setReturnValue(cir.getReturnValue() / multiplier);
    }
}
