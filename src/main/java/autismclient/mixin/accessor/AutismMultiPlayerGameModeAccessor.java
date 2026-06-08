package autismclient.mixin.accessor;

import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(MultiPlayerGameMode.class)
public interface AutismMultiPlayerGameModeAccessor {
    @Accessor("destroyProgress")
    float autism$getDestroyProgress();

    @Accessor("destroyDelay")
    void autism$setDestroyDelay(int delay);

    @Accessor("isDestroying")
    boolean autism$isDestroying();

    @Accessor("destroyBlockPos")
    BlockPos autism$getDestroyBlockPos();
}
