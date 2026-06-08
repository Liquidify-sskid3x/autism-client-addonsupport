package autismclient.util;

import net.minecraft.client.Minecraft;

public final class AutismJoinMacroController {
    public enum Timing {
        JOINING("JOINING", "Joining"),
        WORLD("WORLD", "In World"),
        FIRST_TICK("FIRST_TICK", "First Tick"),
        INVENTORY_READY("INVENTORY_READY", "Inventory Ready"),
        PLAYABLE("PLAYABLE", "Playable"),
        FULLY_READY("FULLY_READY", "Fully Ready");

        private final String id;
        private final String label;

        Timing(String id, String label) {
            this.id = id;
            this.label = label;
        }

        public String id() {
            return id;
        }

        public String label() {
            return label;
        }

        public Timing other() {
            Timing[] values = values();
            return values[(ordinal() + 1) % values.length];
        }

        public static Timing fromConfig(String value) {
            if ("JOINING".equalsIgnoreCase(value)) return JOINING;
            if ("FIRST_TICK".equalsIgnoreCase(value) || "FIRSTTICK".equalsIgnoreCase(value)) return FIRST_TICK;
            if ("INVENTORY_READY".equalsIgnoreCase(value) || "INVENTORY".equalsIgnoreCase(value)) return INVENTORY_READY;
            if ("PLAYABLE".equalsIgnoreCase(value)) return PLAYABLE;
            if ("FULLY_READY".equalsIgnoreCase(value) || "FULLYREADY".equalsIgnoreCase(value)) return FULLY_READY;
            return WORLD;
        }
    }

    public enum TriggerJoin {
        ANY("ANY", "Every Join", 0),
        FIRST("FIRST", "1st Join", 1),
        SECOND("SECOND", "2nd Join", 2),
        THIRD("THIRD", "3rd Join", 3),
        FOURTH("FOURTH", "4th Join", 4),
        FIFTH("FIFTH", "5th Join", 5),
        SIXTH_PLUS("SIXTH_PLUS", "6th+ Join", 6);

        private final String id;
        private final String label;
        private final int number;

        TriggerJoin(String id, String label, int number) {
            this.id = id;
            this.label = label;
            this.number = number;
        }

        public String id() {
            return id;
        }

        public String label() {
            return label;
        }

        public String displayLabel(boolean keepEnabled) {
            if (keepEnabled) {
                return switch (this) {
                    case ANY, FIRST -> "Every Join";
                    case SECOND -> "Every 2nd Join";
                    case THIRD -> "Every 3rd Join";
                    case FOURTH -> "Every 4th Join";
                    case FIFTH -> "Every 5th Join";
                    case SIXTH_PLUS -> "Every 6th Join";
                };
            }

            return switch (this) {
                case ANY, FIRST -> "Next Join";
                case SECOND -> "After 1 Transfer";
                case THIRD -> "After 2 Transfers";
                case FOURTH -> "After 3 Transfers";
                case FIFTH -> "After 4 Transfers";
                case SIXTH_PLUS -> "After 5+ Transfers";
            };
        }

        public boolean matches(int joinOrdinal) {
            if (this == ANY) return true;
            if (this == SIXTH_PLUS) return joinOrdinal >= number;
            return joinOrdinal == number;
        }

        public static TriggerJoin fromConfig(String value) {
            if (value != null) {
                for (TriggerJoin target : values()) {
                    if (target.id.equalsIgnoreCase(value)) return target;
                }
                try {
                    int ordinal = Integer.parseInt(value.trim());
                    return switch (ordinal) {
                        case 0 -> ANY;
                        case 2 -> SECOND;
                        case 3 -> THIRD;
                        case 4 -> FOURTH;
                        case 5 -> FIFTH;
                        default -> ordinal >= 6 ? SIXTH_PLUS : FIRST;
                    };
                } catch (NumberFormatException ignored) {
                }
            }
            return FIRST;
        }
    }

    private static boolean executedThisConnection;
    private static boolean playJoinSeen;
    private static boolean worldReadySeen;
    private static int worldTicksSeen;
    private static int playableTicksSeen;
    private static int joinOrdinal;

    private AutismJoinMacroController() {
    }

    public static Timing timing() {
        return Timing.fromConfig(AutismConfig.getGlobal().joinMacroTiming);
    }

    public static void setTiming(Timing timing) {
        AutismConfig config = AutismConfig.getGlobal();
        config.joinMacroTiming = (timing == null ? Timing.WORLD : timing).id();
        resetSequence();
        config.save();
    }

    public static void setSelectedMacro(String macroName) {
        AutismConfig config = AutismConfig.getGlobal();
        config.joinMacroName = macroName == null ? "" : macroName.trim();
        config.joinMacroEnabled = !config.joinMacroName.isBlank();
        resetSequence();
        config.save();
    }

    public static void setEnabled(boolean enabled) {
        AutismConfig config = AutismConfig.getGlobal();
        config.joinMacroEnabled = enabled && config.joinMacroName != null && !config.joinMacroName.isBlank();
        resetSequence();
        config.save();
    }

    public static TriggerJoin triggerJoin() {
        return normalizeTriggerJoin(TriggerJoin.fromConfig(AutismConfig.getGlobal().joinMacroTriggerJoin), keepEnabled());
    }

    public static void setTriggerJoin(TriggerJoin triggerJoin) {
        AutismConfig config = AutismConfig.getGlobal();
        config.joinMacroTriggerJoin = normalizeTriggerJoin(triggerJoin == null ? TriggerJoin.FIRST : triggerJoin, config.joinMacroKeepEnabled).id();
        resetSequence();
        config.save();
    }

    public static boolean keepEnabled() {
        return AutismConfig.getGlobal().joinMacroKeepEnabled;
    }

    public static void setKeepEnabled(boolean keepEnabled) {
        AutismConfig config = AutismConfig.getGlobal();
        config.joinMacroKeepEnabled = keepEnabled;
        config.joinMacroTriggerJoin = normalizeTriggerJoin(TriggerJoin.fromConfig(config.joinMacroTriggerJoin), keepEnabled).id();
        resetSequence();
        config.save();
    }

    public static String selectedMacroName() {
        String name = AutismConfig.getGlobal().joinMacroName;
        return name == null ? "" : name.trim();
    }

    public static boolean enabled() {
        return selectedMacroName().length() > 0;
    }

    public static void resetForJoin() {
        playJoinSeen = false;
        worldReadySeen = false;
        worldTicksSeen = 0;
        playableTicksSeen = 0;
    }

    public static void onPlayJoin() {
        resetForJoin();
        executedThisConnection = false;
        joinOrdinal++;
        playJoinSeen = true;
        if (timing() == Timing.JOINING) {
            executeOnce(Timing.JOINING);
        }
    }

    public static void onWorldReady() {
        worldReadySeen = true;
        if (timing() == Timing.WORLD) {
            executeOnce(Timing.WORLD);
        }
    }

    public static void onClientTick(Minecraft mc) {
        if (!enabled() || executedThisConnection) return;
        if (mc == null) return;

        boolean worldReady = isWorldReady(mc);
        if (!worldReady) {
            worldReadySeen = false;
            worldTicksSeen = 0;
            playableTicksSeen = 0;
            return;
        }

        if (!worldReadySeen) {
            worldReadySeen = true;
            worldTicksSeen = 0;
        }
        worldTicksSeen++;

        Timing timing = timing();
        switch (timing) {
            case JOINING -> {
                if (playJoinSeen) executeOnce(Timing.JOINING);
            }
            case WORLD -> executeOnce(Timing.WORLD);
            case FIRST_TICK -> {
                if (worldTicksSeen >= 1) executeOnce(Timing.FIRST_TICK);
            }
            case INVENTORY_READY -> {
                if (mc.player != null && mc.player.inventoryMenu != null && mc.player.getInventory() != null) {
                    executeOnce(Timing.INVENTORY_READY);
                }
            }
            case PLAYABLE -> {
                if (isPlayable(mc)) executeOnce(Timing.PLAYABLE);
            }
            case FULLY_READY -> {
                if (isPlayable(mc)) {
                    playableTicksSeen++;
                    if (playableTicksSeen >= 2) executeOnce(Timing.FULLY_READY);
                } else {
                    playableTicksSeen = 0;
                }
            }
        }
    }

    public static void onGameLeft() {
        resetForJoin();
        executedThisConnection = false;
    }

    private static void executeOnce(Timing timing) {
        if (!enabled() || executedThisConnection || !triggerJoin().matches(joinOrdinal)) return;
        executedThisConnection = true;

        String macroName = selectedMacroName();
        AutismMacro macro = AutismMacroManager.get().get(macroName);
        if (macro == null) {
            AutismNotifications.warning("Join macro missing: " + macroName);
            setSelectedMacro("");
            return;
        }
        boolean keepEnabled = keepEnabled();
        if (!keepEnabled) {
            setSelectedMacro("");
        } else if (triggerJoin() != TriggerJoin.ANY) {
            joinOrdinal = 0;
        }

        Minecraft mc = Minecraft.getInstance();
        Runnable run = () -> {
            try {
                macro.execute();
                AutismNotifications.show("Join macro: " + macro.name, 0xFF35D873);
            } catch (Throwable t) {
                AutismNotifications.error("Join macro failed.");
            }
        };

        if (mc != null) mc.execute(run);
        else run.run();
    }

    private static boolean isWorldReady(Minecraft mc) {
        return mc.getConnection() != null && mc.player != null && mc.level != null;
    }

    private static boolean isPlayable(Minecraft mc) {
        return isWorldReady(mc) && mc.screen == null;
    }

    public static String modeSummary() {
        boolean keepEnabled = keepEnabled();
        return timing().label() + " / " + triggerJoin().displayLabel(keepEnabled) + " / " + (keepEnabled ? "Stays Enabled" : "Clears After Run");
    }

    private static void resetSequence() {
        joinOrdinal = 0;
        executedThisConnection = false;
        resetForJoin();
    }

    private static TriggerJoin normalizeTriggerJoin(TriggerJoin triggerJoin, boolean keepEnabled) {
        TriggerJoin value = triggerJoin == null ? TriggerJoin.FIRST : triggerJoin;
        if (keepEnabled && value == TriggerJoin.FIRST) return TriggerJoin.ANY;
        if (!keepEnabled && value == TriggerJoin.ANY) return TriggerJoin.FIRST;
        return value;
    }
}
