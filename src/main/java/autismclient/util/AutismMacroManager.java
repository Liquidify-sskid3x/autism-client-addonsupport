package autismclient.util;

import autismclient.AutismClientAddon;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.ListTag;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class AutismMacroManager {
    private static AutismMacroManager INSTANCE;
    private List<AutismMacro> macros = new ArrayList<>();
    private final File saveFile;
    private volatile long revision;

    private AutismMacroManager() {
        saveFile = new File(AutismClientAddon.FOLDER, "autism_macros.nbt");
        load();
    }

    public static synchronized AutismMacroManager get() {
        if (INSTANCE == null) {
            INSTANCE = new AutismMacroManager();
        }
        return INSTANCE;
    }

    public synchronized String createUniqueName(String preferredName) {
        String baseName = preferredName == null || preferredName.isBlank() ? "New Macro" : preferredName.trim();
        String candidate = baseName;
        int suffix = 1;
        while (get(candidate) != null) {
            candidate = baseName + " (" + suffix++ + ")";
        }
        return candidate;
    }

    public synchronized AutismMacro addImportedCopy(AutismMacro source, String preferredName) {
        if (source == null) return null;

        AutismMacro copy = source.deepCopy();
        copy.name = createUniqueName(preferredName != null && !preferredName.isBlank() ? preferredName : source.name);
        add(copy);
        return copy;
    }

    public synchronized void add(AutismMacro macro) {
        macros.add(macro);
        save();
    }

    public synchronized AutismMacro get(String name) {
        for (AutismMacro macro : macros) {
            if (macro.name.equalsIgnoreCase(name)) return macro;
        }
        return null;
    }

    public synchronized List<AutismMacro> getAll() {
        return new ArrayList<>(macros);
    }

    public long getRevision() {
        return revision;
    }

    public synchronized void remove(AutismMacro macro) {
        if (autismclient.util.macro.MacroExecutor.isMacroRunning(macro.name)) {
            autismclient.util.macro.MacroExecutor.stopMacro(macro.name);
            AutismClientMessaging.sendPrefixed("§eStopped running macro before deletion: " + macro.name);
        }

        if (macros.remove(macro)) {
            save();
            AutismClientMessaging.sendPrefixed("§aDeleted macro: " + macro.name);

        AutismMacroEditorOverlay editor = AutismMacroEditorOverlay.getSharedOverlay();
            if (editor != null && editor.isEditingMacro(macro)) {
                editor.close();
            }

            if (AutismLANSync.getInstance().isInSession()) {
                AutismLANSync.getInstance().broadcastMacroDeletion(macro.name);
            }
        }
    }

    public void delete(AutismMacro macro) {
        remove(macro);
    }

    public void executeMacro(String name) {
        AutismMacro macro = get(name);
        if (macro != null) {
            macro.execute();
            AutismClientMessaging.sendPrefixed("§aExecuting macro: " + macro.name);
        } else {
            AutismClientMessaging.sendPrefixed("§cMacro not found: " + name);
        }
    }

    public void stopMacro() {
        if (autismclient.util.macro.MacroExecutor.isVisibleRunning()) {
            autismclient.util.macro.MacroExecutor.stop();
        } else {
            AutismClientMessaging.sendPrefixed("§eNo macro is currently running.");
        }
    }

    public synchronized void save() {
        revision++;
        try {
            CompoundTag tag = new CompoundTag();
            ListTag list = new ListTag();
            for (AutismMacro macro : macros) {
                list.add(macro.toTag());
            }
            tag.put("macros", list);
            NbtIo.write(tag, saveFile.toPath());
        } catch (Exception e) {
            AutismClientAddon.LOG.error("Failed to save Autism macros", e);
        }

        if (AutismLANSync.getInstance().isInSession()) {
            AutismLANSync.getInstance().broadcastMacroList();
        }
    }

    public synchronized void load() {
        if (!saveFile.exists()) return;

        try {
            CompoundTag tag = NbtIo.read(saveFile.toPath());
            if (tag != null && tag.contains("macros")) {
                macros.clear();
                ListTag list = (ListTag) tag.get("macros");
                for (int i = 0; i < list.size(); i++) {
                    if (list.get(i) instanceof CompoundTag) {
                        AutismMacro macro = new AutismMacro();
                        macro.fromTag((CompoundTag) list.get(i));
                        macros.add(macro);
                    }
                }
            }
            revision++;
        } catch (Exception e) {
            AutismClientAddon.LOG.error("Failed to load Autism macros", e);
        }
    }
}
