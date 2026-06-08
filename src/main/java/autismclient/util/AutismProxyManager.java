package autismclient.util;

import autismclient.AutismClientAddon;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class AutismProxyManager implements Iterable<AutismProxy> {
    private static final AutismProxyManager INSTANCE = new AutismProxyManager();
    private final List<AutismProxy> proxies = new ArrayList<>();
    private final AtomicBoolean refreshing = new AtomicBoolean(false);
    private boolean loaded;
    private int timeoutMs = 5000;
    private int threads = 8;
    private int retries = 1;
    private boolean sortByLatency = true;
    private boolean pruneDead = true;
    private int pruneLatency = 2000;
    private int pruneToCount = 0;

    private static final Pattern PROXY_PATTERN = Pattern.compile("^(?:([\\w\\s]+)=)?((?:0*(?:\\d|[1-9]\\d|1\\d\\d|2[0-4]\\d|25[0-5])(?:\\.(?!:)|)){4}):(?!0)(\\d{1,4}|[1-5]\\d{4}|6[0-4]\\d{3}|65[0-4]\\d{2}|655[0-2]\\d|6553[0-5])(?i:@(socks[45]))?$", Pattern.MULTILINE);
    private static final Pattern PROXY_PATTERN_WEBSHARE = Pattern.compile("^((?:0*(?:\\d|[1-9]\\d|1\\d\\d|2[0-4]\\d|25[0-5])(?:\\.(?!:)|)){4}):(?!0)(\\d{1,4}|[1-5]\\d{4}|6[0-4]\\d{3}|65[0-4]\\d{2}|655[0-2]\\d|6553[0-5]):([^:]+)(?::(.+))?$", Pattern.MULTILINE);
    private static final Pattern PROXY_PATTERN_URI = Pattern.compile("^(?:(?<type>socks|socks4|socks5)://)?(?:(?<user>[\\w~-]+)(:(?<pass>[\\w~-]+))?@)?(?<addr>(?:0*(?:\\d|[1-9]\\d|1\\d\\d|2[0-4]\\d|25[0-5])(?:\\.(?!:)|)){4}):(?!0)(?<port>\\d{1,4}|[1-5]\\d{4}|6[0-4]\\d{3}|65[0-4]\\d{2}|655[0-2]\\d|6553[0-5])$", Pattern.MULTILINE);

    private AutismProxyManager() {
    }

    public static AutismProxyManager get() {
        INSTANCE.ensureLoaded();
        return INSTANCE;
    }

    private File saveFile() {
        return new File(Minecraft.getInstance().gameDirectory, "autism-proxies.nbt");
    }

    private synchronized void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        File file = saveFile();
        if (!file.exists()) return;
        try {
            CompoundTag tag = NbtIo.read(file.toPath());
            if (tag == null) return;
            timeoutMs = tag.getIntOr("timeoutMs", timeoutMs);
            threads = tag.getIntOr("threads", threads);
            retries = tag.getIntOr("retries", retries);
            sortByLatency = tag.getBooleanOr("sortByLatency", sortByLatency);
            pruneDead = tag.getBooleanOr("pruneDead", pruneDead);
            pruneLatency = tag.getIntOr("pruneLatency", pruneLatency);
            pruneToCount = tag.getIntOr("pruneToCount", pruneToCount);
            proxies.clear();
            ListTag list = tag.getListOrEmpty("proxies");
            for (Tag element : list) {
                if (element instanceof CompoundTag compoundTag) proxies.add(new AutismProxy().fromTag(compoundTag));
            }
        } catch (Exception e) {
            AutismClientAddon.LOG.error("Failed to load Autism proxies", e);
        }
    }

    public synchronized void save() {
        try {
            CompoundTag tag = new CompoundTag();
            tag.putInt("timeoutMs", timeoutMs);
            tag.putInt("threads", threads);
            tag.putInt("retries", retries);
            tag.putBoolean("sortByLatency", sortByLatency);
            tag.putBoolean("pruneDead", pruneDead);
            tag.putInt("pruneLatency", pruneLatency);
            tag.putInt("pruneToCount", pruneToCount);
            ListTag list = new ListTag();
            for (AutismProxy proxy : proxies) list.add(proxy.toTag());
            tag.put("proxies", list);
            NbtIo.write(tag, saveFile().toPath());
        } catch (Exception e) {
            AutismClientAddon.LOG.error("Failed to save Autism proxies", e);
        }
    }

    public synchronized List<AutismProxy> all() {
        return new ArrayList<>(proxies);
    }

    public synchronized boolean add(AutismProxy proxy) {
        if (proxy == null || !proxy.isValid() || proxies.contains(proxy)) return false;
        if (proxies.isEmpty()) proxy.enabled = true;
        proxies.add(proxy);
        save();
        return true;
    }

    public synchronized void remove(AutismProxy proxy) {
        if (proxies.remove(proxy)) save();
    }

    public synchronized boolean update(AutismProxy existing, AutismProxy updated) {
        if (existing == null || updated == null || !updated.isValid()) return false;
        int index = -1;
        for (int i = 0; i < proxies.size(); i++) {
            AutismProxy proxy = proxies.get(i);
            if (proxy == existing) {
                index = i;
                break;
            }
        }
        if (index < 0) return false;
        for (int i = 0; i < proxies.size(); i++) {
            if (i != index && proxies.get(i).equals(updated)) return false;
        }
        boolean identityChanged = existing.type != updated.type || existing.port != updated.port || !java.util.Objects.equals(existing.address, updated.address);
        existing.name = updated.name;
        existing.type = updated.type;
        existing.address = updated.address;
        existing.port = updated.port;
        existing.username = updated.username;
        existing.password = updated.password;
        if (identityChanged) {
            existing.status = AutismProxy.Status.UNCHECKED;
            existing.latency = 0L;
        }
        save();
        return true;
    }

    public synchronized void setEnabled(AutismProxy proxy, boolean enabled) {
        for (AutismProxy current : proxies) current.enabled = false;
        if (proxy != null) proxy.enabled = enabled;
        save();
    }

    public synchronized AutismProxy getEnabled() {
        for (AutismProxy proxy : proxies) {
            if (proxy.enabled && proxy.isValid()) return proxy;
        }
        return null;
    }

    public boolean isRefreshing() {
        return refreshing.get();
    }

    public int getTimeoutMs() { return timeoutMs; }
    public void setTimeoutMs(int v) { this.timeoutMs = Math.max(1, v); save(); }
    public int getThreads() { return threads; }
    public void setThreads(int v) { this.threads = Math.max(1, v); save(); }
    public int getRetries() { return retries; }
    public void setRetries(int v) { this.retries = Math.max(0, v); save(); }
    public boolean isSortByLatency() { return sortByLatency; }
    public void setSortByLatency(boolean v) { this.sortByLatency = v; save(); }
    public boolean isPruneDead() { return pruneDead; }
    public void setPruneDead(boolean v) { this.pruneDead = v; save(); }
    public int getPruneLatency() { return pruneLatency; }
    public void setPruneLatency(int v) { this.pruneLatency = Math.max(0, v); save(); }
    public int getPruneToCount() { return pruneToCount; }
    public void setPruneToCount(int v) { this.pruneToCount = Math.max(0, v); save(); }

    public void checkProxies(boolean all) {
        List<AutismProxy> snapshot;
        synchronized (this) {
            if (refreshing.get() || proxies.isEmpty()) return;
            snapshot = new ArrayList<>(proxies);
        }
        refreshing.set(true);
        Thread thread = new Thread(() -> {
            try (ExecutorService executor = Executors.newFixedThreadPool(Math.max(1, Math.min(threads, snapshot.size())))) {
                for (AutismProxy proxy : snapshot) {
                    if (all || proxy.status == AutismProxy.Status.UNCHECKED) {
                        executor.execute(() -> {
                            int result = proxy.checkStatus(timeoutMs);
                            int attempts = 0;
                            while (result == 3 && attempts < retries) {
                                result = proxy.checkStatus(timeoutMs);
                                attempts++;
                            }
                        });
                    }
                }
            } finally {
                refreshing.set(false);
            }
        }, "Autism-Proxy-Refresh");
        thread.setDaemon(true);
        thread.start();
    }

    public synchronized void clean() {
        if (refreshing.get()) return;
        proxies.removeIf(proxy -> pruneDead && proxy.status == AutismProxy.Status.DEAD);
        proxies.removeIf(proxy -> pruneLatency > 0 && proxy.status == AutismProxy.Status.ALIVE && proxy.latency >= pruneLatency);
        List<AutismProxy> sorted = new ArrayList<>(proxies);
        sorted.sort((a, b) -> Long.compare(a.status == AutismProxy.Status.ALIVE ? a.latency : Long.MAX_VALUE, b.status == AutismProxy.Status.ALIVE ? b.latency : Long.MAX_VALUE));
        if (pruneToCount > 0 && sorted.size() > pruneToCount) {
            sorted.subList(pruneToCount, sorted.size()).clear();
            proxies.removeIf(proxy -> !sorted.contains(proxy));
        }
        if (sortByLatency) {
            proxies.clear();
            proxies.addAll(sorted);
        }
        save();
    }

    public synchronized int importFromFile(File file) {
        int added = 0;
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                AutismProxy proxy = parseProxyLine(line.trim());
                if (proxy != null && add(proxy)) added++;
            }
        } catch (Exception e) {
            AutismClientAddon.LOG.error("Failed to import proxies", e);
        }
        return added;
    }

    private static AutismProxy parseProxyLine(String line) {
        if (line.isBlank() || line.startsWith("#")) return null;
        Matcher m = PROXY_PATTERN.matcher(line);
        if (m.find()) return buildProxy(m.group(1), normalizeAddress(m.group(2)), Integer.parseInt(m.group(3)), m.group(4), AutismProxyType.Socks4);
        m = PROXY_PATTERN_WEBSHARE.matcher(line);
        if (m.find()) {
            AutismProxy proxy = buildProxy(null, normalizeAddress(m.group(1)), Integer.parseInt(m.group(2)), null, AutismProxyType.Socks5);
            if (m.group(3) != null) proxy.username = m.group(3);
            if (m.group(4) != null) proxy.password = m.group(4);
            return proxy;
        }
        m = PROXY_PATTERN_URI.matcher(line);
        if (m.find()) {
            String typeName = m.group("type");
            AutismProxyType defaultType = m.group("pass") != null || "socks".equals(typeName) ? AutismProxyType.Socks5 : AutismProxyType.Socks4;
            AutismProxy proxy = buildProxy(null, normalizeAddress(m.group("addr")), Integer.parseInt(m.group("port")), typeName, defaultType);
            if (m.group("user") != null) proxy.username = m.group("user");
            if (m.group("pass") != null) proxy.password = m.group("pass");
            return proxy;
        }
        return null;
    }

    private static String normalizeAddress(String address) {
        return address == null ? "" : address.replaceAll("\\b0+\\B", "");
    }

    private static AutismProxy buildProxy(String name, String address, int port, String typeName, AutismProxyType defaultType) {
        AutismProxy proxy = new AutismProxy();
        proxy.name = name == null ? "" : name.trim();
        proxy.address = address;
        proxy.port = port;
        proxy.type = defaultType == null ? AutismProxyType.Socks5 : defaultType;
        if (typeName != null) {
            String lower = typeName.toLowerCase();
            if (lower.contains("4")) proxy.type = AutismProxyType.Socks4;
            else if (lower.contains("5")) proxy.type = AutismProxyType.Socks5;
        }
        return proxy;
    }

    @Override
    public Iterator<AutismProxy> iterator() {
        return all().iterator();
    }
}
