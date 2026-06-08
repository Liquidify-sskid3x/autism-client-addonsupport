package autismclient;

import autismclient.gui.vanillaui.components.UiText;
import autismclient.modules.PackAutoReconnectState;
import autismclient.modules.AutismModule;
import autismclient.security.AutismProtector;
import autismclient.security.AutismProtectorPackStrip;
import autismclient.security.AutismProtectorServerPackFailureGuard;
import autismclient.security.AutismProtectorTracker;
import autismclient.security.AutismProtectorVanillaKeys;
import autismclient.util.AutismInstaBreakRenderer;
import autismclient.util.AutismFakeGamemode;
import autismclient.util.AutismJoinMacroController;
import autismclient.util.AutismLANSync;
import autismclient.util.AutismOverlayManager;
import autismclient.util.AutismSvgHudLogo;
import autismclient.util.AutismWindowBranding;
import autismclient.util.AutismSharedState;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.minecraft.server.packs.PackType;

public final class AutismClientMod implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        AutismClientAddon.FOLDER.mkdirs();

        AutismModule.get().initialize();

        AutismProtectorTracker.bootstrap();

        if (!AutismProtector.isOverlapExternalProtectorPresent()) {
            AutismProtectorVanillaKeys.primeAsync();
        }
        if (AutismProtector.isFullExternalProtectorPresent()) {
            AutismClientAddon.LOG.info(
                "[AutismProtector] External protection mod detected; deferring all anti-fingerprint mixins to it.");
        } else if (AutismProtector.isExploitPreventerPresent()) {
            AutismClientAddon.LOG.info(
                "[AutismProtector] ExploitPreventer detected; deferring overlapping protections while keeping brand/channel hiding active.");
        } else {
            AutismClientAddon.LOG.info(
                "[AutismProtector] Built-in anti-fingerprint layer active.");
        }
        AutismInstaBreakRenderer.initialize();
        autismclient.commands.AutismCommands.init();
        autismclient.addons.AddonManager.init();

        ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(new net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener() {
            @Override
            public net.minecraft.resources.Identifier getFabricId() {
                return net.minecraft.resources.Identifier.fromNamespaceAndPath("autismclient", "ui_assets");
            }

            @Override
            public void onResourceManagerReload(net.minecraft.server.packs.resources.ResourceManager manager) {
                UiText.onClientResourceReload();
                AutismSvgHudLogo.clear();
            }
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            AutismInstaBreakRenderer.tickPlacePreview();
            AutismWindowBranding.apply(client);
            AutismModule.get().tick();
            AutismJoinMacroController.onClientTick(client);
        });
        ClientTickEvents.END_LEVEL_TICK.register(level -> AutismLANSync.getInstance().onLevelTick(level.getGameTime()));
        ClientConfigurationConnectionEvents.INIT.register((listener, client) -> AutismModule.get().onConfigurationConnectionStarted());
        ClientConfigurationConnectionEvents.DISCONNECT.register((listener, client) -> {
            AutismFakeGamemode.clear();
            AutismInstaBreakRenderer.clear();
            AutismProtectorServerPackFailureGuard.clear();
            AutismOverlayManager.get().hideAllInteractiveOverlays();
            AutismModule.get().onGameLeft();
        });
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            PackAutoReconnectState.remember(client.getCurrentServer());
            AutismModule.get().onGameJoin();
            AutismJoinMacroController.onPlayJoin();
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            AutismFakeGamemode.clear();
            AutismInstaBreakRenderer.clear();
            AutismProtectorPackStrip.clearAll();
            AutismProtectorServerPackFailureGuard.clear();
            AutismOverlayManager.get().hideAllInteractiveOverlays();
            AutismJoinMacroController.onGameLeft();
            AutismModule.get().onGameLeft();
        });
        ItemTooltipCallback.EVENT.register((stack, tooltipContext, tooltipType, lines) ->
            AutismModule.get().appendTooltip(stack, lines)
        );
    }
}
