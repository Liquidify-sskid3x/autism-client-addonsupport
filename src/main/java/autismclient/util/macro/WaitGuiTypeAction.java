package autismclient.util.macro;

import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;

public class WaitGuiTypeAction implements MacroAction {
    public enum WaitMode { OPEN, CLOSE }

    public WaitMode waitMode = WaitMode.OPEN;
    public String guiType = "ANY";
    public String title = "";
    public int timeoutMs = 0;
    public boolean listenDuringPreviousAction = false;

    @Override public void execute(Minecraft mc) {}
    @Override public MacroActionType getType() { return MacroActionType.WAIT_GUI_TYPE; }

    public boolean matches(Minecraft mc) {
        return mc != null && MacroGuiMatcher.matches(mc.screen, guiType, title);
    }

    @Override
    public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        tag.putString("type", "WAIT_GUI_TYPE");
        tag.putString("waitMode", waitMode.name());
        tag.putString("guiType", guiType);
        tag.putString("title", title);
        tag.putInt("timeoutMs", timeoutMs);
        MacroWaitOptions.write(tag, this);
        return tag;
    }

    @Override
    public void fromTag(CompoundTag tag) {
        waitMode = MacroStringList.enumValue(WaitMode.class, tag.getStringOr("waitMode", "OPEN"), WaitMode.OPEN);
        guiType = tag.getStringOr("guiType", "ANY");
        title = tag.getStringOr("title", "");
        timeoutMs = tag.getIntOr("timeoutMs", 0);
        MacroWaitOptions.read(tag, this);
    }

    @Override public String getDisplayName() { return "Wait GUI " + guiType; }
    @Override public String getIcon() { return "W"; }
}
