package dev.screch.regen.mixin;

import dev.screch.regen.RegenMod;
import dev.screch.regen.WorldReset;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.IOException;

@Mixin(LevelStorageSource.LevelStorageAccess.class)
public abstract class LevelStorageAccessMixin {
    @Inject(method = "<init>", at = @At("RETURN"))
    private void regen$applyPending(CallbackInfo callback) throws IOException {
        var access = (LevelStorageSource.LevelStorageAccess) (Object) this;
        var world = access.getLevelPath(LevelResource.ROOT).toAbsolutePath().normalize();
        try {
            if (WorldReset.applyPending(world)) {
                RegenMod.LOGGER.info("Reset world data in {} — generating fresh terrain", world);
            }
        } catch (IOException | RuntimeException failure) {
            access.safeClose();
            throw new IOException("World regeneration failed; the pending request was retained for retry", failure);
        }
    }
}
