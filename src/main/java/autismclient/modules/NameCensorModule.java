package autismclient.modules;

import autismclient.mixin.accessor.AutismChatComponentAccessor;
import com.mojang.authlib.GameProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class NameCensorModule extends PackModule {
    private static final Random RANDOM = new Random();
    private static final Map<String, String> ALIASES = new ConcurrentHashMap<>();
    private static final Set<String> USED_ALIASES = ConcurrentHashMap.newKeySet();
    private static final List<String> CENSOR_WORDS = buildCensorWords();

    private int lastSkinState;

    public NameCensorModule() {
        super("name-censor", "Name Censor", PackModuleCategory.MISC, "Replaces player names with aliases.");
        option(PackModuleOption.bool("censor-self", "Censor Self", true).description("Hide your own name."));
        option(PackModuleOption.stringList("names", "Names", "").description("Extra names to hide.").playerNameList());
        option(PackModuleOption.text("self-alias", "Self Alias", "").description("Your alias; blank = random."));
        option(PackModuleOption.stringList("custom-aliases", "Custom Aliases", "").description("name=alias pairs.").playerNameList());
        option(PackModuleOption.bool("censor-everyone", "Censor Everyone", false).description("Hide every online player."));
        option(PackModuleOption.bool("hide-skins-all", "Hide All Skins", false).description("Default skin for everyone."));

        option(PackModuleOption.bool("hide-skin-self", "Hide My Skin", false).description("Default skin for you only.")
                .visible(m -> !Boolean.parseBoolean(m.value("hide-skins-all"))));
    }

    @Override
    public void onEnable() {
        lastSkinState = skinState();
        refreshChat();
        refreshVisuals();
    }

    @Override
    public void onDisable() {
        lastSkinState = 0;
        refreshChat();
        refreshVisuals();
    }

    @Override
    public void tick() {
        int currentSkinState = skinState();
        if (currentSkinState != lastSkinState) {
            lastSkinState = currentSkinState;
            refreshVisuals();
        }
    }

    private static int skinState() {
        return (hideAllSkins() ? 2 : 0) | (hideSelfSkin() ? 1 : 0);
    }

    public static Component censorComponent(Component component) {
        if (!isActive() || component == null) return component;
        List<String> names = targetNames();
        if (names.isEmpty()) return component;

        List<TextPart> parts = new ArrayList<>();
        StringBuilder raw = new StringBuilder();
        for (Component part : component.toFlatList()) {
            String text = part.getString();
            if (text.isEmpty()) continue;
            int start = raw.length();
            raw.append(text);
            parts.add(new TextPart(text, part.getStyle(), start, raw.length()));
        }

        NormalizedText normalized = normalizeFormattingCodes(raw.toString());
        List<Replacement> replacements = replacements(normalized, names);
        if (replacements.isEmpty()) return component;

        MutableComponent out = Component.empty();
        int cursor = 0;
        for (Replacement replacement : replacements) {
            appendOriginal(out, parts, cursor, replacement.start());
            out.append(Component.literal(replacement.text()).withStyle(styleAt(parts, replacement.start())));
            cursor = replacement.end();
        }
        appendOriginal(out, parts, cursor, raw.length());
        return out;
    }

    public static String censorText(String text) {
        if (!isActive() || text == null || text.isEmpty()) return text;
        return censorText(text, targetNames());
    }

    private static boolean hideAllSkins() {
        PackModule module = module();
        return module != null && module.isEnabled() && Boolean.parseBoolean(module.value("hide-skins-all"));
    }

    private static boolean hideSelfSkin() {
        PackModule module = module();
        return module != null && module.isEnabled() && Boolean.parseBoolean(module.value("hide-skin-self"));
    }

    public static boolean shouldDisableSkinFor(GameProfile profile) {
        if (hideAllSkins()) return true;
        if (!hideSelfSkin() || profile == null) return false;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getUser() == null) return false;
        if (profile.id() != null && profile.id().equals(mc.getUser().getProfileId())) return true;
        String localName = mc.getUser().getName();
        return localName != null && localName.equalsIgnoreCase(profile.name());
    }

    private static String censorText(String text, List<String> names) {
        if (names.isEmpty() || text == null || text.isEmpty()) return text;
        NormalizedText normalized = normalizeFormattingCodes(text);
        List<Replacement> replacements = replacements(normalized, names);
        if (replacements.isEmpty()) return text;

        StringBuilder out = new StringBuilder();
        int cursor = 0;
        for (Replacement replacement : replacements) {
            out.append(text, cursor, replacement.start());
            out.append(replacement.text());
            cursor = replacement.end();
        }
        out.append(text, cursor, text.length());
        return out.toString();
    }

    private static List<Replacement> replacements(NormalizedText normalized, List<String> names) {
        if (normalized.text().isEmpty()) return List.of();
        List<Replacement> replacements = new ArrayList<>();
        boolean[] used = new boolean[normalized.rawLength()];
        for (String name : names) {
            if (name == null || name.isBlank()) continue;
            Pattern pattern = Pattern.compile("(?i)(?<![A-Za-z0-9_])" + Pattern.quote(name) + "(?![A-Za-z0-9_])");
            Matcher matcher = pattern.matcher(normalized.text());
            while (matcher.find()) {
                int rawStart = normalized.rawIndex(matcher.start());
                int rawEnd = normalized.rawIndex(matcher.end() - 1) + 1;
                if (rawStart < 0 || rawEnd <= rawStart || overlaps(used, rawStart, rawEnd)) continue;
                for (int i = rawStart; i < rawEnd; i++) used[i] = true;
                replacements.add(new Replacement(rawStart, rawEnd, replacementFor(name)));
            }
        }
        replacements.sort(Comparator.comparingInt(Replacement::start));
        return replacements;
    }

    private static boolean overlaps(boolean[] used, int start, int end) {
        for (int i = Math.max(0, start); i < Math.min(used.length, end); i++) {
            if (used[i]) return true;
        }
        return false;
    }

    private static NormalizedText normalizeFormattingCodes(String raw) {
        StringBuilder text = new StringBuilder(raw.length());
        List<Integer> rawIndexes = new ArrayList<>(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\u00a7' && i + 1 < raw.length()) {
                i++;
                continue;
            }
            rawIndexes.add(i);
            text.append(c);
        }
        return new NormalizedText(text.toString(), rawIndexes, raw.length());
    }

    private static void appendOriginal(MutableComponent out, List<TextPart> parts, int start, int end) {
        if (end <= start) return;
        for (TextPart part : parts) {
            int overlapStart = Math.max(start, part.start());
            int overlapEnd = Math.min(end, part.end());
            if (overlapEnd <= overlapStart) continue;
            int localStart = overlapStart - part.start();
            int localEnd = overlapEnd - part.start();
            out.append(Component.literal(part.text().substring(localStart, localEnd)).withStyle(part.style()));
        }
    }

    private static Style styleAt(List<TextPart> parts, int rawIndex) {
        for (TextPart part : parts) {
            if (rawIndex >= part.start() && rawIndex < part.end()) return part.style();
        }
        return Style.EMPTY;
    }

    private static String replacementFor(String name) {
        PackModule module = module();
        if (module != null) {
            String custom = customReplacement(module, name);
            if (!custom.isBlank()) return custom;
        }
        return aliasFor(name);
    }

    private static String customReplacement(PackModule module, String name) {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.getUser() != null && Boolean.parseBoolean(module.value("censor-self")) && mc.getUser().getName().equalsIgnoreCase(name)) {
            String selfAlias = module.value("self-alias").trim();
            if (!selfAlias.isEmpty()) return selfAlias;
        }

        for (String entry : module.list("custom-aliases")) {
            int split = aliasSplitIndex(entry);
            if (split <= 0) continue;
            String rawName = entry.substring(0, split).trim();
            String alias = entry.substring(split + 1).trim();
            if (!rawName.isEmpty() && !alias.isEmpty() && rawName.equalsIgnoreCase(name)) return alias;
        }
        return "";
    }

    private static int aliasSplitIndex(String entry) {
        int split = entry.indexOf('=');
        return split >= 0 ? split : entry.indexOf(':');
    }

    private static List<String> targetNames() {
        PackModule module = module();
        if (module == null || !module.isEnabled()) return List.of();

        Set<String> names = new LinkedHashSet<>();
        Minecraft mc = Minecraft.getInstance();
        if (Boolean.parseBoolean(module.value("censor-self")) && mc != null && mc.getUser() != null) {
            addName(names, mc.getUser().getName());
        }
        for (String entry : module.list("names")) addName(names, entry);
        for (String entry : module.list("custom-aliases")) {
            int split = aliasSplitIndex(entry);
            if (split > 0) addName(names, entry.substring(0, split));
        }
        if (Boolean.parseBoolean(module.value("censor-everyone")) && mc != null && mc.getConnection() != null) {
            for (PlayerInfo info : mc.getConnection().getOnlinePlayers()) {
                if (info != null && info.getProfile() != null) addName(names, info.getProfile().name());
            }
        }

        List<String> sorted = new ArrayList<>(names);
        sorted.sort(Comparator.comparingInt(String::length).reversed());
        return sorted;
    }

    private static void addName(Set<String> names, String name) {
        if (name == null) return;
        String trimmed = name.trim();
        if (!trimmed.isEmpty()) names.add(trimmed);
    }

    private static String aliasFor(String name) {
        return ALIASES.computeIfAbsent(name.toLowerCase(Locale.ROOT), ignored -> nextAlias());
    }

    private static String nextAlias() {
        synchronized (USED_ALIASES) {
            if (USED_ALIASES.size() >= CENSOR_WORDS.size()) return CENSOR_WORDS.get(RANDOM.nextInt(CENSOR_WORDS.size()));
            String alias;
            do {
                alias = CENSOR_WORDS.get(RANDOM.nextInt(CENSOR_WORDS.size()));
            } while (!USED_ALIASES.add(alias));
            return alias;
        }
    }

    private static boolean isActive() {
        PackModule module = module();
        return module != null && module.isEnabled();
    }

    private static PackModule module() {
        return PackModuleRegistry.get("name-censor");
    }

    private static void refreshChat() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.gui == null || mc.gui.getChat() == null) return;
        try {
            ((AutismChatComponentAccessor) mc.gui.getChat()).autism$refreshTrimmedMessages();
        } catch (Throwable ignored) {
        }
    }

    private static void refreshVisuals() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        if (mc.gui != null) mc.gui.clearCache();
        if (mc.levelRenderer != null) mc.levelRenderer.allChanged();
    }

    private static List<String> buildCensorWords() {
        String[] first = {
            "\u81ea\u95ed", "\u5305\u5305", "\u6570\u636e", "\u65b9\u5757", "\u6df7\u51dd", "\u7c97\u7b51", "\u786c\u6838", "\u5ef6\u8fdf", "\u7ea2\u77f3", "\u5b57\u8282",
            "\u961f\u5217", "\u63e1\u624b", "\u540c\u6b65", "\u50cf\u7d20", "\u6808\u5e27", "\u7f13\u5b58", "\u62bd\u8c61", "\u6c34\u6ce5", "\u88c2\u7eb9", "\u7070\u5899"
        };
        String[] second = {
            "\u5927\u5e08", "\u72c2\u70ed", "\u5c0f\u5b50", "\u5de5\u5934", "\u6307\u6325", "\u5e7d\u9ed8", "\u7206\u7b11", "\u98de\u5305", "\u786c\u5899", "\u4e71\u6d41",
            "\u795e\u7ecf", "\u7535\u6ce2", "\u94c1\u677f", "\u5947\u89c2", "\u56de\u58f0", "\u5de8\u6784"
        };
        List<String> words = new ArrayList<>(first.length * second.length);
        for (String a : first) {
            for (String b : second) {
                String word = a + b;
                if (word.length() <= 6) words.add(word);
            }
        }
        return List.copyOf(words);
    }

    private record TextPart(String text, Style style, int start, int end) {
    }

    private record Replacement(int start, int end, String text) {
    }

    private record NormalizedText(String text, List<Integer> rawIndexes, int rawLength) {
        int rawIndex(int normalizedIndex) {
            return normalizedIndex < 0 || normalizedIndex >= rawIndexes.size() ? -1 : rawIndexes.get(normalizedIndex);
        }
    }
}
