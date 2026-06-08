package autismclient.addons;

import autismclient.AutismClientAddon;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;
import net.fabricmc.loader.api.metadata.CustomValue;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public final class AddonManager {
    public static final List<AutismAddon> ADDONS = new ArrayList<>();

    private AddonManager() {}

    public static void init() {
        AutismClientAddon.LOG.info("[AddonManager] Discovering addons...");

        for (EntrypointContainer<AutismAddon> container : FabricLoader.getInstance().getEntrypointContainers("autism", AutismAddon.class)) {
            AutismAddon addon = container.getEntrypoint();
            ModContainer mod = container.getProvider();

            addon.name = mod.getMetadata().getName();
            addon.authors = mod.getMetadata().getAuthors().stream()
                    .map(person -> person.getName())
                    .collect(Collectors.joining(", "));

            // Optional color from fabric.mod.json
            CustomValue colorValue = mod.getMetadata().getCustomValue("autism:color");
            if (colorValue != null && colorValue.getType() == CustomValue.CvType.STRING) {
                try {
                    addon.color = Color.decode(colorValue.getAsString());
                } catch (NumberFormatException ignored) {
                    AutismClientAddon.LOG.warn("[AddonManager] Invalid color for addon {}: {}", addon.name, colorValue.getAsString());
                }
            }

            ADDONS.add(addon);
            AutismClientAddon.LOG.info("[AddonManager] Loaded addon: {} by {}", addon.name, addon.authors);
        }

        for (AutismAddon addon : ADDONS) {
            try {
                addon.onRegisterCategories();
            } catch (Exception e) {
                AutismClientAddon.LOG.error("[AddonManager] Failed to register categories for addon: {}", addon.name, e);
            }
        }

        for (AutismAddon addon : ADDONS) {
            try {
                addon.onInitialize();
            } catch (Exception e) {
                AutismClientAddon.LOG.error("[AddonManager] Failed to initialize addon: {}", addon.name, e);
            }
        }
    }
}
