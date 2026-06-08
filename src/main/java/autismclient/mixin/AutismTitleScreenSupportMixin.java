package autismclient.mixin;

import autismclient.modules.PackHideState;
import autismclient.util.AutismLinks;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TitleScreen.class)
public abstract class AutismTitleScreenSupportMixin extends Screen {
    protected AutismTitleScreenSupportMixin() {
        super(null);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void autism$addSupportButtons(CallbackInfo ci) {
        if (PackHideState.isActive()) return;

        Component donateLabel = Component.literal("Donate");
        this.addRenderableWidget(Button.builder(donateLabel, b -> AutismLinks.open(AutismLinks.KOFI))
            .bounds(4, 4, supportWidth(donateLabel), 20).build());

        Component incDiscordLabel = Component.literal("AUTISM INC");
        this.addRenderableWidget(Button.builder(incDiscordLabel, b -> AutismLinks.open(AutismLinks.AUTISM_INC_DISCORD))
            .bounds(4, 28, supportWidth(incDiscordLabel), 20).build());

        Component clientDiscordLabel = Component.literal("AUTISM Client");
        this.addRenderableWidget(Button.builder(clientDiscordLabel, b -> AutismLinks.open(AutismLinks.DISCORD))
            .bounds(4, 52, supportWidth(clientDiscordLabel), 20).build());
    }

    private int supportWidth(Component label) {
        return Math.max(78, Math.min(this.width - 8, this.font.width(label) + 20));
    }
}
