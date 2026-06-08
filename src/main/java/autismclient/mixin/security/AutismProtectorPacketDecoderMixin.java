package autismclient.mixin.security;

import autismclient.security.AutismProtector;
import autismclient.security.AutismProtectorPacketContext;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.network.PacketDecoder;
import net.minecraft.network.codec.StreamCodec;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(PacketDecoder.class)
public class AutismProtectorPacketDecoderMixin {

    @WrapOperation(
        method = "decode",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/network/codec/StreamCodec;decode(Ljava/lang/Object;)Ljava/lang/Object;")
    )
    private Object autism$wrapDecode(StreamCodec instance, Object buffer, Operation<Object> original) {
        if (!AutismProtector.shouldTagPacketComponents()) return original.call(instance, buffer);
        AutismProtectorPacketContext.setProcessingPacket(true);
        try {
            return original.call(instance, buffer);
        } finally {
            AutismProtectorPacketContext.setProcessingPacket(false);
        }
    }
}
