package autismclient.modules;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class PackModuleCategory {
    private static final List<PackModuleCategory> CATEGORIES = new ArrayList<>();

    public static final PackModuleCategory MOVEMENT = register("Movement");
    public static final PackModuleCategory PLAYER = register("Player");
    public static final PackModuleCategory MISC = register("Misc");
    public static final PackModuleCategory RENDER = register("Render");

    private final String label;

    public PackModuleCategory(String label) {
        this.label = label;
    }

    public static PackModuleCategory register(String label) {
        PackModuleCategory category = new PackModuleCategory(label);
        CATEGORIES.add(category);
        return category;
    }

    public static List<PackModuleCategory> values() {
        return Collections.unmodifiableList(CATEGORIES);
    }

    public String label() {
        return label;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PackModuleCategory that = (PackModuleCategory) o;
        return Objects.equals(label, that.label);
    }

    @Override
    public int hashCode() {
        return Objects.hash(label);
    }

    @Override
    public String toString() {
        return label;
    }

    public String name() {
        return label.toUpperCase().replace(" ", "_");
    }
}
