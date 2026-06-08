package com.example.testaddon;

import autismclient.modules.PackModule;
import autismclient.modules.PackModuleOption;
import autismclient.util.AutismClientMessaging;
import autismclient.util.AutismInputClicker;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.EntityHitResult;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

public class TriggerBotModule extends PackModule {

    private static final Minecraft MC = Minecraft.getInstance();

    private long lastAttackTime;
    private long nextCpsRollTime;
    private double currentCps = 10.0;
    private double targetCps = 10.0;

    private String cachedEntityListSource = "";
    private Set<String> cachedEntityIds = Set.of();

    public TriggerBotModule() {
        super("trigger-bot", "Trigger Bot", TestAddon.LIQUIDIFY_CATEGORY, "Attacks entities when your crosshair is on them.");

        option(PackModuleOption.registryList(PackModuleOption.Type.ENTITY_TYPE_LIST, "entities", "Entities", "minecraft:player")
            .description("Entity types that fire the trigger.").build());
        option(PackModuleOption.decimal("min-cps", "Min CPS", 8.0, 1.0, 20.0, 0.5)
            .description("Minimum clicks per second.").build());
        option(PackModuleOption.decimal("max-cps", "Max CPS", 12.0, 1.0, 20.0, 0.5)
            .description("Maximum clicks per second.").build());
        option(PackModuleOption.bool("smooth-cps", "Smooth CPS", true)
            .description("Gradually adjust CPS instead of jumping.").build());
        option(PackModuleOption.decimal("smooth-speed", "Smooth Speed", 0.12, 0.02, 0.5, 0.01)
            .description("How fast CPS ramps toward the target.").build());
        option(PackModuleOption.bool("respect-cooldown", "Respect Cooldown", true)
            .description("Only attack when the attack-strength meter is full.").build());
        option(PackModuleOption.bool("randomize-delay", "Randomize Delay", false)
            .description("Add a small random delay after cooldown.").build());
        option(PackModuleOption.bool("focus-target", "Focus Target", true)
            .description("Keep attacking the same target until it dies or moves far.").build());
        option(PackModuleOption.decimal("range", "Range", 4.5, 1.0, 8.0, 0.1)
            .description("Maximum attack range.").build());
        option(PackModuleOption.bool("through-walls", "Through Walls", false)
            .description("Ignore line-of-sight checks.").build());
        option(PackModuleOption.bool("debug", "Debug", false)
            .description("Show debug messages in chat.").build());
    }

    @Override
    public void tick() {
        if (MC.player == null || MC.level == null || MC.screen != null) return;

        if (!(MC.hitResult instanceof EntityHitResult entityHit)) {
            lastAttackTime = 0;
            return;
        }

        Entity target = entityHit.getEntity();
        if (!isValidTarget(target)) return;

        double rangeSq = decimal("range") * decimal("range");
        if (MC.player.distanceToSqr(target) > rangeSq) return;

        long now = System.currentTimeMillis();
        double delay = 1000.0 / nextCps(now);

        if (bool("respect-cooldown") && MC.player.getAttackStrengthScale(0.0f) < 1.0f) {
            if (bool("randomize-delay") && lastAttackTime > 0) {
                long sinceLast = now - lastAttackTime;
                if (sinceLast < delay) return;
            }
            return;
        }

        long sinceLast = lastAttackTime == 0 ? Long.MAX_VALUE : now - lastAttackTime;
        if (sinceLast < delay) return;

        if (!bool("through-walls") && !MC.player.hasLineOfSight(target)) return;

        AutismInputClicker.queueAttackClick();
        lastAttackTime = now;
        debug("Attacked " + target.getName().getString());
    }

    private boolean isValidTarget(Entity entity) {
        if (entity == null || entity == MC.player || !entity.isAlive()) return false;
        if (!(entity instanceof LivingEntity)) return false;

        LivingEntity living = (LivingEntity) entity;
        if (living.getHealth() <= 0.0f) return false;

        if (entity instanceof Player player) {
            if (player.isCreative() || player.isSpectator()) return false;
        }

        return matchesEntityList(entity);
    }

    private boolean matchesEntityList(Entity entity) {
        Set<String> ids = cachedEntityIds();
        if (ids.isEmpty()) return true;
        String key = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
        String simple = key.contains(":") ? key.substring(key.indexOf(':') + 1) : key;
        return ids.contains(key) || ids.contains(simple);
    }

    private Set<String> cachedEntityIds() {
        List<String> entries = list("entities");
        String source = String.join("|", entries);
        if (source.equals(cachedEntityListSource)) return cachedEntityIds;
        Set<String> normalized = new LinkedHashSet<>();
        for (String entry : entries) {
            if (entry == null) continue;
            String value = entry.trim().toLowerCase(java.util.Locale.ROOT);
            if (value.isEmpty()) continue;
            normalized.add(value);
            int split = value.indexOf(':');
            if (split >= 0 && split + 1 < value.length()) normalized.add(value.substring(split + 1));
        }
        cachedEntityListSource = source;
        cachedEntityIds = Set.copyOf(normalized);
        return cachedEntityIds;
    }

    private double nextCps(long now) {
        double min = Math.max(1.0, decimal("min-cps"));
        double max = Math.max(1.0, decimal("max-cps"));
        if (min > max) { double t = min; min = max; max = t; }

        if (!bool("smooth-cps")) return randomCps(min, max);

        if (currentCps < min || currentCps > max) {
            currentCps = randomCps(min, max);
            targetCps = currentCps;
        }

        if (now >= nextCpsRollTime) {
            targetCps = randomCps(min, max);
            nextCpsRollTime = now + ThreadLocalRandom.current().nextLong(350L, 850L);
        }

        double speed = Math.max(0.02, Math.min(0.5, decimal("smooth-speed")));
        currentCps += (targetCps - currentCps) * speed;
        return Math.max(min, Math.min(max, currentCps));
    }

    private static double randomCps(double min, double max) {
        return min == max ? min : ThreadLocalRandom.current().nextDouble(min, max);
    }

    private void debug(String msg) {
        if (!bool("debug")) return;
        AutismClientMessaging.sendPrefixed("TriggerBot: " + msg);
    }
}
