package autismclient.mixin;

import autismclient.modules.AutismModule;
import autismclient.util.AutismPacketCapture;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.PacketEncoder;
import net.minecraft.network.PacketListener;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PacketEncoder.class)
public abstract class AutismPacketEncoderMixin<T extends PacketListener> {
    @Shadow @Final private ProtocolInfo<T> protocolInfo;

    @Inject(method = "encode(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;Lio/netty/buffer/ByteBuf;)V",
        at = @At("TAIL"))
    private void autism$captureEncodedPlaintext(ChannelHandlerContext ctx, Packet<T> packet, ByteBuf output, CallbackInfo ci) {
        if (!autism$packetHooksActive()) return;
        AutismPacketCapture.capturePlaintext(packet, "C2S", protocolInfo.id().id(), packet.type(), output);
    }

    @Unique
    private static boolean autism$packetHooksActive() {
        AutismModule module = AutismModule.get();
        return module != null && (module.arePacketHooksActive() || module.isPassivePayloadCaptureActive());
    }
}
