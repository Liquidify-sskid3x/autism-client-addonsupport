package autismclient.util;

import autismclient.AutismClientAddon;
import autismclient.mixin.accessor.AutismMinecraftAccessor;
import com.mojang.authlib.minecraft.UserApiService;
import com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.client.gui.screens.social.PlayerSocialManager;
import net.minecraft.client.multiplayer.ProfileKeyPairManager;
import net.minecraft.client.multiplayer.chat.report.ReportEnvironment;
import net.minecraft.client.multiplayer.chat.report.ReportingContext;
import net.minecraft.client.renderer.texture.SkinTextureDownloader;
import net.minecraft.client.resources.SkinManager;
import net.minecraft.server.Services;
import net.minecraft.util.Util;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

public final class AutismAccountSessionSwitcher {
    private static User originalUser;

    private AutismAccountSessionSwitcher() {
    }

    public static User getOriginalUser() {
        if (originalUser == null) originalUser = Minecraft.getInstance().getUser();
        return originalUser;
    }

    public static boolean setSession(User user) {
        return setSession(user, new YggdrasilAuthenticationService(Minecraft.getInstance().getProxy()));
    }

    public static boolean setSession(User user, YggdrasilAuthenticationService authService) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (originalUser == null) originalUser = mc.getUser();
            AutismMinecraftAccessor accessor = (AutismMinecraftAccessor) mc;
            Services services = Services.create(authService, mc.gameDirectory);
            UserApiService apiService = authService.createUserApiService(user.getAccessToken());
            Path skinCachePath = mc.gameDirectory.toPath().resolve("assets").resolve("skins");

            accessor.autism$setServices(services);
            accessor.autism$setUser(user);
            accessor.autism$setUserApiService(apiService);
            accessor.autism$setPlayerSocialManager(new PlayerSocialManager(mc, apiService));
            accessor.autism$setProfileKeyPairManager(ProfileKeyPairManager.create(apiService, user, mc.gameDirectory.toPath()));
            accessor.autism$setReportingContext(ReportingContext.create(ReportEnvironment.local(), apiService));
            accessor.autism$setProfileFuture(CompletableFuture.supplyAsync(() -> mc.services().sessionService().fetchProfile(mc.getUser().getProfileId(), true), Util.nonCriticalIoPool()));
            accessor.autism$setSkinManager(new SkinManager(skinCachePath, services, new SkinTextureDownloader(mc.getProxy(), mc.getTextureManager(), mc), mc));
            return true;
        } catch (Exception e) {
            AutismClientAddon.LOG.error("Failed to switch Autism account session", e);
            return false;
        }
    }
}
