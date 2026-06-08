package autismclient.gui.screen;

import net.minecraft.SharedConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.client.gui.screens.ShareToLanScreen;
import net.minecraft.client.gui.screens.achievement.StatsScreen;
import net.minecraft.client.gui.screens.advancements.AdvancementsScreen;
import net.minecraft.client.gui.screens.social.SocialInteractionsScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.CommonLinks;

public class AutismPauseScreen extends Screen {
    private static final Component RETURN_TO_GAME = Component.translatable("menu.returnToGame");
    private static final Component ADVANCEMENTS = Component.translatable("gui.advancements");
    private static final Component STATS = Component.translatable("gui.stats");
    private static final Component SEND_FEEDBACK = Component.translatable("menu.sendFeedback");
    private static final Component REPORT_BUGS = Component.translatable("menu.reportBugs");
    private static final Component OPTIONS = Component.translatable("menu.options");
    private static final Component SHARE_TO_LAN = Component.translatable("menu.shareToLan");
    private static final Component PLAYER_REPORTING = Component.translatable("menu.playerReporting");
    private static final Component GAME = Component.translatable("menu.game");

    public AutismPauseScreen() {
        super(GAME);
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int y = this.height / 4 + 8;

        this.addRenderableWidget(Button.builder(RETURN_TO_GAME, b -> {
            this.minecraft.setScreen(null);
            this.minecraft.mouseHandler.grabMouse();
        }).bounds(cx - 102, y, 204, 20).build());

        y += 24;
        this.addRenderableWidget(Button.builder(ADVANCEMENTS,
            b -> this.minecraft.setScreen(new AdvancementsScreen(this.minecraft.player.connection.getAdvancements(), this)))
            .bounds(cx - 102, y, 98, 20).build());
        this.addRenderableWidget(Button.builder(STATS,
            b -> this.minecraft.setScreen(new StatsScreen(this, this.minecraft.player.getStats())))
            .bounds(cx + 4, y, 98, 20).build());

        y += 24;
        this.addRenderableWidget(Button.builder(SEND_FEEDBACK, ConfirmLinkScreen.confirmLink(this,
            SharedConstants.getCurrentVersion().stable() ? CommonLinks.RELEASE_FEEDBACK : CommonLinks.SNAPSHOT_FEEDBACK))
            .bounds(cx - 102, y, 98, 20).build());
        this.addRenderableWidget(Button.builder(REPORT_BUGS, ConfirmLinkScreen.confirmLink(this, CommonLinks.SNAPSHOT_BUGS_FEEDBACK))
            .bounds(cx + 4, y, 98, 20).build());

        y += 24;
        this.addRenderableWidget(Button.builder(OPTIONS,
            b -> this.minecraft.setScreen(new OptionsScreen(this, this.minecraft.options, true)))
            .bounds(cx - 102, y, 98, 20).build());
        boolean canShareToLan = this.minecraft.hasSingleplayerServer() && !this.minecraft.getSingleplayerServer().isPublished();
        if (canShareToLan) {
            this.addRenderableWidget(Button.builder(SHARE_TO_LAN, b -> this.minecraft.setScreen(new ShareToLanScreen(this)))
                .bounds(cx + 4, y, 98, 20).build());
        } else {
            this.addRenderableWidget(Button.builder(PLAYER_REPORTING, b -> this.minecraft.setScreen(new SocialInteractionsScreen(this)))
                .bounds(cx + 4, y, 98, 20).build());
        }

        y += 24;
        this.addRenderableWidget(Button.builder(CommonComponents.disconnectButtonLabel(this.minecraft.isLocalServer()), b -> {
            b.active = false;
            this.minecraft.disconnectFromWorld(ClientLevel.DEFAULT_QUIT_MESSAGE);
        }).bounds(cx - 102, y, 204, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        int textWidth = this.font.width(this.title);
        graphics.text(this.font, this.title, this.width / 2 - textWidth / 2, 40, -1);
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(null);
        this.minecraft.mouseHandler.grabMouse();
    }
}
