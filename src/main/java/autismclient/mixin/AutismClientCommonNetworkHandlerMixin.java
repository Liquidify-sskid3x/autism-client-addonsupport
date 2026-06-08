package autismclient.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import autismclient.modules.AutismModule;
import autismclient.security.AutismProtectorPackStrip;
import autismclient.util.AutismClientMessaging;
import autismclient.util.AutismSharedState;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ServerboundResourcePackPacket;
import net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket;

@Mixin(ClientCommonPacketListenerImpl.class)
public abstract class AutismClientCommonNetworkHandlerMixin {
    @Shadow @Final protected Minecraft minecraft;

    @Shadow public abstract void send(Packet<?> packet);

    @Inject(method = "handleResourcePackPush", at = @At("HEAD"), cancellable = true)
    private void yang$onResourcePackSend(ClientboundResourcePackPushPacket packet, CallbackInfo ci) {
        AutismSharedState shared = AutismSharedState.get();
        AutismModule module = AutismModule.get();
        boolean shouldForceDeny = shared.shouldForceDenyResourcePack() || (module != null && module.isForceDenyResourcePack());
        boolean shouldBypass = shared.shouldBypassResourcePack() || (module != null && module.isBypassResourcePack());

        if (!(shouldForceDeny || shouldBypass)) return;

        AutismProtectorPackStrip.onPop(packet.id());
        if (shouldBypass) {
            send(new ServerboundResourcePackPacket(packet.id(), ServerboundResourcePackPacket.Action.ACCEPTED));
            send(new ServerboundResourcePackPacket(packet.id(), ServerboundResourcePackPacket.Action.DOWNLOADED));
            send(new ServerboundResourcePackPacket(packet.id(), ServerboundResourcePackPacket.Action.SUCCESSFULLY_LOADED));
        } else {
            send(new ServerboundResourcePackPacket(packet.id(), ServerboundResourcePackPacket.Action.DECLINED));
            AutismClientMessaging.sendPrefixed("Autism denied server resource pack.");
        }

        ci.cancel();
    }
}
