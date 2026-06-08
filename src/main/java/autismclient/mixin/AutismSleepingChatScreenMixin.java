package autismclient.mixin;

import autismclient.modules.AutismModule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.InBedChatScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(InBedChatScreen.class)
public abstract class AutismSleepingChatScreenMixin extends Screen {
    protected AutismSleepingChatScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init()V", at = @At("TAIL"))
    private void autism$init(CallbackInfo ci) {
        AutismModule module = AutismModule.get();
        if (module == null || !module.isActive()) return;

        this.addRenderableWidget(Button.builder(
            Component.literal("Client wake up"),
            button -> {
                Minecraft mc = Minecraft.getInstance();
                if (mc.player != null) {
                    mc.player.stopSleepInBed(false, true);
                    mc.setScreen(null);
                }
            }
        ).bounds(5, 5, 140, 20).build());
    }
}
