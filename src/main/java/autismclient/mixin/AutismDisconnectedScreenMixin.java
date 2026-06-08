package autismclient.mixin;

import autismclient.modules.PackAutoReconnectState;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(DisconnectedScreen.class)
public abstract class AutismDisconnectedScreenMixin extends Screen {
    @Shadow @Final private Screen parent;

    protected AutismDisconnectedScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void autism$addReconnectButton(CallbackInfo ci) {
        if (!PackAutoReconnectState.shouldShow()) return;
        if (PackAutoReconnectState.hideButtons()) return;
        this.addRenderableWidget(Button.builder(Component.literal("Reconnect Now"), button -> PackAutoReconnectState.reconnect(parent))
            .bounds(this.width / 2 - 100, this.height - 38, 200, 20)
            .build());
    }
}
