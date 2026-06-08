package autismclient.mixin;

import autismclient.modules.AutismModule;
import autismclient.util.AutismPayloadSupport;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.minecraft.network.protocol.common.custom.CustomPacketPayload$1")
public abstract class AutismCustomPayloadCodecMixin {
    @Inject(method = "encode(Lnet/minecraft/network/FriendlyByteBuf;Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload;)V",
            at = @At("HEAD"),
            cancellable = true)
    private void autism$encodeRawCustomPacketPayload(FriendlyByteBuf buf, CustomPacketPayload payload, CallbackInfo ci) {
        if (!autism$packetHooksActive()) return;

        byte[] rememberedBytes = AutismPayloadSupport.getRememberedUnknownPayloadBytes(payload);
        boolean isRaw = payload instanceof AutismPayloadSupport.RawCustomPacketPayload;
        if (rememberedBytes == null && !isRaw) return;

        CustomPacketPayload.Type<?> type = payload.type();
        if (type == null || type.id() == null) return;

        byte[] bytes = rememberedBytes != null
            ? rememberedBytes
            : ((AutismPayloadSupport.RawCustomPacketPayload) payload).bytes();

        buf.writeIdentifier(type.id());
        if (bytes.length > 0) {
            buf.writeBytes(bytes);
        }
        ci.cancel();
    }

    @Unique
    private static boolean autism$packetHooksActive() {
        AutismModule module = AutismModule.get();
        return module != null && (module.arePacketHooksActive() || module.isPassivePayloadCaptureActive());
    }
}
