package autismclient.util;

import autismclient.AutismClientAddon;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;

import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public final class AutismAccountManager implements Iterable<AutismAccount> {
    private static final AutismAccountManager INSTANCE = new AutismAccountManager();
    private final List<AutismAccount> accounts = new ArrayList<>();
    private boolean loaded;

    private AutismAccountManager() {
    }

    public static AutismAccountManager get() {
        INSTANCE.ensureLoaded();
        return INSTANCE;
    }

    private File saveFile() {
        return new File(Minecraft.getInstance().gameDirectory, "autism-accounts.nbt");
    }

    private synchronized void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        File file = saveFile();
        if (!file.exists()) return;
        try {
            CompoundTag tag = NbtIo.read(file.toPath());
            if (tag == null) return;
            accounts.clear();
            ListTag list = tag.getListOrEmpty("accounts");
            for (Tag element : list) {
                if (element instanceof CompoundTag compoundTag) accounts.add(new AutismAccount().fromTag(compoundTag));
            }
        } catch (Exception e) {
            AutismClientAddon.LOG.error("Failed to load Autism accounts", e);
        }
    }

    public synchronized void save() {
        try {
            CompoundTag tag = new CompoundTag();
            ListTag list = new ListTag();
            for (AutismAccount account : accounts) list.add(account.toTag());
            tag.put("accounts", list);
            NbtIo.write(tag, saveFile().toPath());
        } catch (Exception e) {
            AutismClientAddon.LOG.error("Failed to save Autism accounts", e);
        }
    }

    public synchronized List<AutismAccount> all() {
        return new ArrayList<>(accounts);
    }

    public synchronized void add(AutismAccount account) {
        if (account == null) return;
        accounts.add(account);
        save();
    }

    public synchronized boolean contains(AutismAccount account) {
        return accounts.contains(account);
    }

    public synchronized void remove(AutismAccount account) {
        if (accounts.remove(account)) save();
    }

    public void login(AutismAccount account) {
        if (account == null) return;
        Thread thread = new Thread(() -> {
            if (account.fetchInfo() && account.login()) {
                save();
                AutismClientMessaging.sendPrefixed("Logged in as " + account.displayName() + ".");
            } else {
                AutismClientMessaging.sendPrefixed("Failed to login account: " + account.displayName());
            }
        }, "Autism-Account-Login");
        thread.setDaemon(true);
        thread.start();
    }

    public void loginMicrosoft(AutismAccount account) {
        if (account == null || account.type != AutismAccountType.Microsoft) return;
        AutismMicrosoftLogin.getRefreshToken(refreshToken -> {
            if (refreshToken == null) {
                AutismClientMessaging.sendPrefixed("Microsoft login cancelled or failed.");
                return;
            }
            account.label = refreshToken;
            Thread thread = new Thread(() -> {
                if (account.fetchInfo() && account.login()) {
                    synchronized (this) {
                        if (!accounts.contains(account)) accounts.add(account);
                    }
                    save();
                    AutismClientMessaging.sendPrefixed("Logged in as " + account.displayName() + ".");
                } else {
                    AutismClientMessaging.sendPrefixed("Failed to login Microsoft account.");
                }
            }, "Autism-Microsoft-Login");
            thread.setDaemon(true);
            thread.start();
        });
    }

    @Override
    public Iterator<AutismAccount> iterator() {
        return all().iterator();
    }
}
