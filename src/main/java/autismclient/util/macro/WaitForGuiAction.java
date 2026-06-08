package autismclient.util.macro;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;

public class WaitForGuiAction implements MacroAction {
    public enum WaitMode { OPEN, CLOSE }

    public WaitMode waitMode = WaitMode.OPEN;
    public String guiType = "ANY";
    public String guiTitle = "";
    public int timeoutMs = 0;
    public boolean listenDuringPreviousAction = false;
    private boolean enabled = true;

    @Override
    public MacroActionType getType() {
        return MacroActionType.WAIT_GUI;
    }

    @Override
    public void execute(Minecraft mc) {

    }

    @Override
    public String getDisplayName() {
        String prefix = waitMode == WaitMode.CLOSE ? "Wait GUI Close: " : "Wait GUI Open: ";
        String type = guiType == null || guiType.isBlank() || "ANY".equals(guiType) ? "Any" : guiType;
        return prefix + type + (guiTitle.isEmpty() ? "" : " \"" + guiTitle + "\"");
    }

    @Override
    public String getIcon() {
        return "GUI";
    }

    public boolean matchesText(String search, String target) {
        if (search.isEmpty() || target.isEmpty()) return false;
        if (search.equals(target)) return true;
        if (target.toLowerCase().contains(search.toLowerCase())) return true;

        String[] searchWords = search.toLowerCase().split("\\s+");
        String[] targetWords = target.toLowerCase().split("\\s+");

        boolean allFound = true;
        for (String word : searchWords) {
            boolean found = false;
            for (String tWord : targetWords) {
                if (tWord.contains(word) || word.contains(tWord)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                allFound = false;
                break;
            }
        }
        return allFound;
    }

    public boolean checkGui(Minecraft mc) {
        Screen screen = mc.screen;
        if (screen == null) return false;
        return MacroGuiMatcher.matches(screen, guiType, guiTitle);
    }

    @Override
    public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        tag.putString("type", getType().name());
        tag.putString("waitMode", waitMode.name());
        tag.putString("guiType", guiType == null ? "ANY" : guiType);
        tag.putString("guiTitle", guiTitle);
        tag.putInt("timeoutMs", timeoutMs);
        tag.putBoolean("enabled", enabled);
        MacroWaitOptions.write(tag, this);
        return tag;
    }

    @Override
    public void fromTag(CompoundTag tag) {
        if (tag.contains("waitMode")) {
            try { waitMode = WaitMode.valueOf(tag.getStringOr("waitMode", "OPEN")); }
            catch (IllegalArgumentException ignored) { waitMode = WaitMode.OPEN; }
        }
        if (tag.contains("guiTitle")) {
            guiTitle = tag.getStringOr("guiTitle", "");
        } else if (tag.contains("title")) {
            guiTitle = tag.getStringOr("title", "");
        }
        guiType = tag.getStringOr("guiType", "ANY");
        timeoutMs = tag.getIntOr("timeoutMs", 0);
        if (tag.contains("enabled")) enabled = tag.getBooleanOr("enabled", true);
        MacroWaitOptions.read(tag, this);
    }

    public void fromGuiTypeTag(CompoundTag tag) {
        fromTag(tag);
        guiType = tag.getStringOr("guiType", "ANY");
        guiTitle = tag.getStringOr("title", tag.getStringOr("guiTitle", ""));
        timeoutMs = tag.getIntOr("timeoutMs", 0);
    }

    @Override public boolean isEnabled() { return enabled; }
    @Override public void setEnabled(boolean e) { this.enabled = e; }
}
