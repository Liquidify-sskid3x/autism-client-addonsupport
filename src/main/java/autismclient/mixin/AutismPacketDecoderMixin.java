package autismclient.mixin;

import autismclient.modules.AutismModule;
import autismclient.util.AutismPacketCapture;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.PacketDecoder;
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

import java.util.List;

@Mixin(PacketDecoder.class)
public abstract class AutismPacketDecoderMixin<T extends PacketListener> {
    @Shadow @Final private ProtocolInfo<T> protocolInfo;
    @Unique private byte[] autism$incomingPlaintext = new byte[0];

    @Inject(method = "decode", at = @At("HEAD"))
    private void autism$captureIncomingPlaintext(ChannelHandlerContext ctx, ByteBuf input, List<Object> out, CallbackInfo ci) {
        autism$incomingPlaintext = autism$packetHooksActive()
            ? AutismPacketCapture.copyReadableBytes(input)
            : new byte[0];
    }

    @Inject(method = "decode", at = @At("TAIL"))
    private void autism$attachIncomingPlaintext(ChannelHandlerContext ctx, ByteBuf input, List<Object> out, CallbackInfo ci) {
        if (!autism$packetHooksActive() || autism$incomingPlaintext.length == 0 || out.isEmpty()) return;
        Object decoded = out.get(out.size() - 1);
        if (decoded instanceof Packet<?> packet) {
            AutismPacketCapture.capturePlaintext(packet, "S2C", protocolInfo.id().id(), packet.type(),
                Unpooled.wrappedBuffer(autism$incomingPlaintext));
        }
    }

    @Unique
    private static boolean autism$packetHooksActive() {
        AutismModule module = AutismModule.get();
        return module != null && (module.arePacketHooksActive() || module.isPassivePayloadCaptureActive());
    }
}
