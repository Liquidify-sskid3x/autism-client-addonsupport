package autismclient.modules;

import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.function.Predicate;

public final class PackModuleOption {
    public enum Type {
        BOOLEAN,
        INTEGER,
        DOUBLE,
        ENUM,
        STRING,
        STRING_LIST,
        PACKET_LIST,
        ITEM_LIST,
        BLOCK_LIST,
        ENTITY_TYPE_LIST,
        SOUND_EVENT_LIST,
        STORAGE_LIST,
        COLOR,
        KEYBIND,
        ACTION
    }

    public enum DisplayMode {
        DEFAULT,
        READONLY_SUMMARY,
        FILE_PICKER,
        PLAYER_NAME_LIST,
        MACRO_PICKER,
        CONDITIONAL_MACRO_PICKER
    }

    private final String id;
    private final String label;
    private final String group;
    private final String description;
    private final Type type;
    private final double min;
    private final double max;
    private final double sliderMin;
    private final double sliderMax;
    private final double step;
    private final String defaultValue;
    private final List<String> choices;
    private final Predicate<PackModule> visible;
    private final Function<String, String> formatter;
    private final Runnable action;
    private final DisplayMode displayMode;
    private final String linkedActionId;
    private final String unit;

    private PackModuleOption(Builder builder) {
        this.id = builder.id;
        this.label = builder.label;
        this.group = builder.group;
        this.description = builder.description;
        this.type = builder.type;
        this.min = builder.min;
        this.max = builder.max;
        this.sliderMin = builder.sliderMin;
        this.sliderMax = builder.sliderMax;
        this.step = builder.step;
        this.defaultValue = builder.defaultValue;
        this.choices = List.copyOf(builder.choices);
        this.visible = builder.visible;
        this.formatter = builder.formatter;
        this.action = builder.action;
        this.displayMode = builder.displayMode;
        this.linkedActionId = builder.linkedActionId;
        this.unit = builder.unit;
    }

    public static Builder builder(Type type, String id, String label, String defaultValue) {
        return new Builder(type, id, label, defaultValue);
    }

    public static PackModuleOption bool(String id, String label, boolean defaultValue) {
        return builder(Type.BOOLEAN, id, label, Boolean.toString(defaultValue)).build();
    }

    public static PackModuleOption integer(String id, String label, int defaultValue, int min, int max, int step) {
        return builder(Type.INTEGER, id, label, Integer.toString(defaultValue)).range(min, max).sliderRange(min, max).step(Math.max(1, step)).build();
    }

    public static PackModuleOption decimal(String id, String label, double defaultValue, double min, double max, double step) {
        return builder(Type.DOUBLE, id, label, Double.toString(defaultValue)).range(min, max).sliderRange(min, max).step(step <= 0 ? 0.1 : step).build();
    }

    public static PackModuleOption enumChoice(String id, String label, String defaultValue, String... choices) {
        return builder(Type.ENUM, id, label, defaultValue).choices(choices).build();
    }

    public static PackModuleOption text(String id, String label, String defaultValue) {
        return builder(Type.STRING, id, label, defaultValue == null ? "" : defaultValue).build();
    }

    public static PackModuleOption stringList(String id, String label, String defaultValue) {
        return builder(Type.STRING_LIST, id, label, defaultValue == null ? "" : defaultValue).build();
    }

    public static PackModuleOption packetList(String id, String label, String defaultValue) {
        return builder(Type.PACKET_LIST, id, label, defaultValue == null ? "" : defaultValue).build();
    }

    public static PackModuleOption registryList(Type type, String id, String label, String defaultValue) {
        return builder(type, id, label, defaultValue == null ? "" : defaultValue).build();
    }

    public static PackModuleOption color(String id, String label, int defaultArgb) {
        return builder(Type.COLOR, id, label, String.format(Locale.ROOT, "%08X", defaultArgb)).build();
    }

    public static PackModuleOption keybind(String id, String label, int defaultKey) {
        return builder(Type.KEYBIND, id, label, Integer.toString(defaultKey)).build();
    }

    public static PackModuleOption action(String id, String label, Runnable action) {
        return builder(Type.ACTION, id, label, "").action(action).build();
    }

    public String id() {
        return id;
    }

    public String label() {
        return label;
    }

    public String group() {
        return group;
    }

    public String description() {
        return description;
    }

    public Type type() {
        return type;
    }

    public double min() {
        return min;
    }

    public double max() {
        return max;
    }

    public double sliderMin() {
        return sliderMin;
    }

    public double sliderMax() {
        return sliderMax;
    }

    public double step() {
        return step;
    }

    public String defaultValue() {
        return defaultValue;
    }

    public List<String> choices() {
        return choices;
    }

    public boolean isVisible(PackModule module) {
        return visible == null || visible.test(module);
    }

    public String format(String value) {
        return formatter == null ? value : formatter.apply(value);
    }

    public Runnable action() {
        return action;
    }

    public DisplayMode displayMode() {
        return displayMode;
    }

    public String linkedActionId() {
        return linkedActionId;
    }

    public PackModuleOption group(String group) {
        return copy().group(group).build();
    }

    public PackModuleOption description(String description) {
        return copy().description(description).build();
    }

    public PackModuleOption visible(Predicate<PackModule> visible) {
        return copy().visible(visible).build();
    }

    public PackModuleOption formatter(Function<String, String> formatter) {
        return copy().formatter(formatter).build();
    }

    public String unit() {
        return unit;
    }

    public PackModuleOption unit(String unit) {
        return copy().unit(unit).build();
    }

    public PackModuleOption displayMode(DisplayMode displayMode) {
        return copy().displayMode(displayMode).build();
    }

    public PackModuleOption filePicker(String actionOptionId) {
        return copy().displayMode(DisplayMode.FILE_PICKER).linkedActionId(actionOptionId).build();
    }

    public PackModuleOption playerNameList() {
        return copy().displayMode(DisplayMode.PLAYER_NAME_LIST).build();
    }

    public PackModuleOption macroPicker() {
        return copy().displayMode(DisplayMode.MACRO_PICKER).build();
    }

    public PackModuleOption conditionalMacroPicker() {
        return copy().displayMode(DisplayMode.CONDITIONAL_MACRO_PICKER).build();
    }

    public PackModuleOption readonlySummary() {
        return copy().displayMode(DisplayMode.READONLY_SUMMARY).build();
    }

    public PackModuleOption build() {
        return this;
    }

    public boolean isModified(String value) {
        return value != null && !value.equals(defaultValue);
    }

    private Builder copy() {
        Builder builder = builder(type, id, label, defaultValue)
            .group(group)
            .description(description)
            .range(min, max)
            .sliderRange(sliderMin, sliderMax)
            .step(step)
            .visible(visible)
            .formatter(formatter)
            .action(action)
            .displayMode(displayMode)
            .linkedActionId(linkedActionId)
            .unit(unit);
        if (!choices.isEmpty()) builder.choices(choices.toArray(String[]::new));
        return builder;
    }

    public static final class Builder {
        private final Type type;
        private final String id;
        private final String label;
        private final String defaultValue;
        private String group = "General";
        private String description = "";
        private double min = 0;
        private double max = 1;
        private double sliderMin = 0;
        private double sliderMax = 1;
        private double step = 1;
        private List<String> choices = List.of();
        private Predicate<PackModule> visible;
        private Function<String, String> formatter;
        private Runnable action;
        private String unit = "";
        private DisplayMode displayMode = DisplayMode.DEFAULT;
        private String linkedActionId = "";

        private Builder(Type type, String id, String label, String defaultValue) {
            this.type = type;
            this.id = id;
            this.label = label;
            this.defaultValue = defaultValue == null ? "" : defaultValue;
        }

        public Builder group(String group) {
            this.group = group == null || group.isBlank() ? "General" : group;
            return this;
        }

        public Builder description(String description) {
            this.description = description == null ? "" : description;
            return this;
        }

        public Builder range(double min, double max) {
            this.min = min;
            this.max = Math.max(min, max);
            return this;
        }

        public Builder sliderRange(double min, double max) {
            this.sliderMin = min;
            this.sliderMax = Math.max(min, max);
            return this;
        }

        public Builder step(double step) {
            this.step = step <= 0 ? 1 : step;
            return this;
        }

        public Builder choices(String... choices) {
            this.choices = choices == null ? List.of() : List.of(choices);
            return this;
        }

        public Builder visible(Predicate<PackModule> visible) {
            this.visible = visible;
            return this;
        }

        public Builder formatter(Function<String, String> formatter) {
            this.formatter = formatter;
            return this;
        }

        public Builder unit(String unit) {
            this.unit = unit == null ? "" : unit;
            return this;
        }

        public Builder action(Runnable action) {
            this.action = action;
            return this;
        }

        public Builder displayMode(DisplayMode displayMode) {
            this.displayMode = displayMode == null ? DisplayMode.DEFAULT : displayMode;
            return this;
        }

        public Builder linkedActionId(String linkedActionId) {
            this.linkedActionId = linkedActionId == null ? "" : linkedActionId;
            return this;
        }

        public PackModuleOption build() {
            return new PackModuleOption(this);
        }
    }
}
