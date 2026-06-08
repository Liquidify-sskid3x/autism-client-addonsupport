package autismclient.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import autismclient.gui.vanillaui.UiBounds;
import autismclient.gui.vanillaui.UiContexts;
import autismclient.gui.vanillaui.UiTextRenderer;
import autismclient.gui.vanillaui.components.Banner;
import autismclient.modules.AutismModule;
import autismclient.modules.PackModule;
import autismclient.modules.PackHideState;
import autismclient.modules.PackModuleRegistry;
import autismclient.modules.PackModuleScreenRenderer;
import autismclient.util.AutismHudManager;
import autismclient.util.AutismCaptureBannerSpec;
import autismclient.util.AutismMacroProgressRenderer;
import autismclient.util.AutismNotifications;
import autismclient.util.AutismOverlayManager;
import autismclient.util.AutismQueueRenderer;
import autismclient.util.AutismServerInfoOverlay;
import autismclient.util.AutismSharedState;
import autismclient.util.AutismUiScale;
import autismclient.util.macro.MacroExecutor;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.DeltaTracker;

@Mixin(Gui.class)
public abstract class AutismInGameHudMixin {
    @Unique private static final Minecraft MC = Minecraft.getInstance();
    @Unique private static final int PACKUTIL_RIGHT_PANEL_W = 172;

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void yang$renderAutismQueue(GuiGraphicsExtractor context, DeltaTracker deltaTracker, CallbackInfo ci) {
        if (!isAutismActive()) return;
        MacroExecutor.onRender(1.0f);
        if (MC.options.hideGui) return;
        if (PackHideState.isActive()) return;

        AutismSharedState shared = AutismSharedState.get();

        Runnable renderHudElements = () -> {

            boolean macroRunning = MacroExecutor.isVisibleRunning();
            boolean queueSending = shared.hasStaggeredPackets();
            boolean queueVisible = shared.shouldDelayGuiPackets()
                || shared.hasDelayedPackets()
                || queueSending;

            int screenWidth = AutismUiScale.getVirtualScreenWidth();
            int x = Math.max(0, screenWidth - PACKUTIL_RIGHT_PANEL_W);
            int y = 0;
            AutismCaptureBannerSpec captureBanner = captureBannerSpec(shared, context);
            java.util.ArrayList<AutismHudManager.ElementBounds> hudOccluders = new java.util.ArrayList<>(3);
            if (captureBanner != null) {
                hudOccluders.add(new AutismHudManager.ElementBounds("capture_banner",
                    captureBanner.x(), captureBanner.y(), captureBanner.width(), captureBanner.height()));
            } else if (hasAnyCaptureSession(shared)) {
                int fallbackW = Math.min(screenWidth - 16, 300);
                hudOccluders.add(new AutismHudManager.ElementBounds("capture_banner",
                    Math.max(0, (screenWidth - fallbackW) / 2), 0, fallbackW, 56));
            }
            if (queueVisible) {
                int queueHeight = AutismQueueRenderer.measureStacked(MC.font, PACKUTIL_RIGHT_PANEL_W, 8);
                if (queueHeight > 0) {
                    hudOccluders.add(new AutismHudManager.ElementBounds("packet_queue", x, y, PACKUTIL_RIGHT_PANEL_W, queueHeight));
                    y += queueHeight;
                }
            }
            if (macroRunning) {
                int macroHeight = AutismMacroProgressRenderer.measureStacked(MC.font, PACKUTIL_RIGHT_PANEL_W, 10);
                if (macroHeight > 0) hudOccluders.add(new AutismHudManager.ElementBounds("macro_queue", x, y, PACKUTIL_RIGHT_PANEL_W, macroHeight));
            }

            renderNativeModuleHud(context, hudOccluders);

            if (captureBanner != null) {
                Banner.render(UiContexts.overlay(context, MC.font, 0, 0),
                    UiBounds.of(captureBanner.x(), captureBanner.y(), captureBanner.width(), captureBanner.height()),
                    captureBanner.title(), captureBanner.line1(), captureBanner.line2());
            }

            y = 0;

            if (queueVisible) {
                int queueHeight = AutismQueueRenderer.renderStacked(context, MC.font, x, y, PACKUTIL_RIGHT_PANEL_W, 8,
                    false, !macroRunning, false);
                y += queueHeight;
            }

            if (macroRunning) {
                AutismMacroProgressRenderer.renderStacked(context, MC.font, x, y, PACKUTIL_RIGHT_PANEL_W, 10,
                    false, true, false);
            }

            PackModuleScreenRenderer.render(context);
        };

        AutismUiScale.pushOverlayScale(context);
        try {
            renderHudElements.run();
        } finally {
            AutismUiScale.popOverlayScale(context);
        }

        if (MC.screen == null) {
            AutismServerInfoOverlay serverInfoOverlay = AutismModule.get().getServerDataOverlayIfExists();
            if (serverInfoOverlay != null && serverInfoOverlay.shouldRenderBackgroundProbeBanner()) {
                AutismUiScale.pushOverlayScale(context);
                try {
                    serverInfoOverlay.renderBackgroundProbeBanner(context);
                } finally {
                    AutismUiScale.popOverlayScale(context);
                }
            }

            AutismOverlayManager.get().renderAll(context, -1, -1, 0f);
        }

        AutismUiScale.pushOverlayScale(context);
        try {
            AutismNotifications.render(context);
        } finally {
            AutismUiScale.popOverlayScale(context);
        }
    }

    @Unique
    private boolean isAutismActive() {
        AutismModule module = AutismModule.get();
        return module != null && module.isActive();
    }

    @Unique
    private void renderNativeModuleHud(GuiGraphicsExtractor context) {
        renderNativeModuleHud(context, java.util.List.of());
    }

    @Unique
    private void renderNativeModuleHud(GuiGraphicsExtractor context, java.util.List<AutismHudManager.ElementBounds> occluders) {
        PackModule hud = PackModuleRegistry.get("hud");
        if (!AutismHudManager.shouldRenderInGame(MC.screen, hud)) return;
        AutismHudManager.render(context, MC.font, false, null, -1, -1, occluders);
    }

    @Unique
    private AutismCaptureBannerSpec captureBannerSpec(AutismSharedState shared, GuiGraphicsExtractor graphics) {
        boolean blockCap = shared.hasBlockCaptureCallback();
        boolean entityCap = shared.hasEntityCaptureCallback();
        boolean attackCap = shared.hasAttackCaptureCallback();
        boolean gbreakCap = shared.isGBreakCapturing();
        if (!blockCap && !entityCap && !attackCap && !gbreakCap) return null;

        String title = gbreakCap
            ? "GBreak Capture"
            : (blockCap ? "Block Capture" : (entityCap ? "Entity Capture" : "Position Capture"));
        String line1 = gbreakCap
            ? "Break a block to capture the insta-break packet. Esc = cancel"
            : (blockCap
                ? "Right-click a block to capture it. Esc = cancel"
                : (entityCap
                    ? "Right-click an entity to capture it. Esc = cancel"
                    : "Left-click to capture the target position. Esc = cancel"));
        String line2 = "";
        if (gbreakCap) {
            line2 = "Waiting for the block-break packet from your next block break";
        } else if (blockCap && MC.hitResult != null
                && MC.hitResult.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK
                && MC.level != null) {
            net.minecraft.world.phys.BlockHitResult bhr = (net.minecraft.world.phys.BlockHitResult) MC.hitResult;
            String bn = MC.level.getBlockState(bhr.getBlockPos()).getBlock().getName().getString();
            net.minecraft.core.BlockPos bp = bhr.getBlockPos();
            line2 = "Aimed at: " + bn + " (" + bp.getX() + ", " + bp.getY() + ", " + bp.getZ() + ")";
        } else if (entityCap && MC.crosshairPickEntity != null && MC.crosshairPickEntity != MC.player) {
            String eName = MC.crosshairPickEntity.getType().getDescription().getString();
            String eId = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(MC.crosshairPickEntity.getType()).toString();
            line2 = "Aimed at: " + eName + " (" + eId + ")";
        }

        int sw = AutismUiScale.getVirtualScreenWidth();
        UiTextRenderer text = UiContexts.textRenderer(MC.font);
        int boxWidth = Math.min(sw - 16, Math.max(270, Math.max(
            text.width(title),
            Math.max(
                text.width(line1),
                line2.isEmpty() ? 0 : text.width(line2)
            )
        ) + 18));
        int height = Banner.height(UiContexts.overlay(graphics, MC.font, 0, 0), boxWidth, line1, line2);
        return new AutismCaptureBannerSpec((sw - boxWidth) / 2, 0, boxWidth, height, title, line1, line2);
    }

    @Unique
    private boolean hasAnyCaptureSession(AutismSharedState shared) {
        if (shared == null) return false;
        if (shared.isCaptureMode() || shared.hasCaptureCancelCallback() || shared.hasAttackCaptureCallback()
            || shared.hasBlockCaptureCallback() || shared.hasEntityCaptureCallback() || shared.isGBreakCapturing()) {
            return true;
        }
        autismclient.gui.macro.editor.ActionEditorOverlay editor =
            autismclient.gui.macro.editor.ActionEditorOverlay.getSharedOverlayIfExists();
        return editor != null && editor.hasActiveCaptureSession();
    }
}
