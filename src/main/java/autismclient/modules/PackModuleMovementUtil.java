package autismclient.modules;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;

public final class PackModuleMovementUtil {
    private static final Minecraft MC = Minecraft.getInstance();
    private static volatile SprintState sprintState = SprintState.inactive(-1);
    private static volatile MovementState movementState = MovementState.inactive(-1);

    private PackModuleMovementUtil() {
    }

    public static boolean shouldCancelNoFallBounce(Entity entity) {
        return movementState().noFallAntiBounce() && MC != null && entity == MC.player;
    }

    public static float speedTimerMultiplier() {
        MovementState state = movementState();
        PackBuiltinModules.SpeedModule speed = state.speed();
        if (speed == null || !speed.shouldApplySpeedTimer()) return 1.0f;
        return state.speedTimerMultiplier();
    }

    public static float flightFlyingSpeed() {
        PackBuiltinModules.FlightModule flight = movementState().flight();
        if (flight != null) return flight.getFlyingSpeed();
        return -1.0f;
    }

    public static boolean flightNoSneak() {
        PackBuiltinModules.FlightModule flight = movementState().flight();
        return flight != null && flight.noSneak();
    }

    public static boolean sprintKeepsRunning() {
        return sprintIsOmnidirectional();
    }

    public static boolean sprintIsOmnidirectional() {
        return sprintState().omnidirectional();
    }

    public static boolean sprintIgnoresCollision() {
        return sprintState().ignoreCollision();
    }

    public static boolean sprintIgnoresHunger() {
        return sprintState().ignoreHunger();
    }

    public static boolean sprintIgnoresBlindness() {
        return sprintState().ignoreBlindness();
    }

    public static void autoSprintLocalPlayerTick() {
        PackModule module = PackModuleRegistry.get("sprint");
        if (module instanceof PackBuiltinModules.SprintModule sprint && sprint.isEnabled()) {
            sprint.runWurstAutoSprintTick();
        }
    }

    private static SprintState sprintState() {
        int revision = PackModuleRegistry.revision();
        SprintState snapshot = sprintState;
        if (snapshot.revision() == revision) return snapshot;
        PackModule module = PackModuleRegistry.get("sprint");
        if (!(module instanceof PackBuiltinModules.SprintModule sprint) || !sprint.isEnabled()) {
            snapshot = SprintState.inactive(revision);
        } else {
            snapshot = new SprintState(
                revision,
                sprint.omnidirectional(),
                sprint.ignoreCollision(),
                sprint.ignoreHunger(),
                sprint.ignoreBlindness()
            );
        }
        sprintState = snapshot;
        return snapshot;
    }

    private static MovementState movementState() {
        int revision = PackModuleRegistry.revision();
        MovementState snapshot = movementState;
        if (snapshot.revision() == revision) return snapshot;

        PackModule flightModule = PackModuleRegistry.get("flight");
        PackBuiltinModules.FlightModule flight =
            flightModule instanceof PackBuiltinModules.FlightModule typed && typed.isEnabled() ? typed : null;

        PackModule speedModule = PackModuleRegistry.get("speed");
        PackBuiltinModules.SpeedModule speed =
            speedModule instanceof PackBuiltinModules.SpeedModule typed && typed.isEnabled() ? typed : null;

        float speedTimer = 1.0f;
        if (speed != null) {
            try {
                speedTimer = Math.max(0.01f, Math.min(10.0f, Float.parseFloat(speed.value("timer"))));
            } catch (Exception ignored) {
                speedTimer = 1.0f;
            }
        }

        PackModule noFall = PackModuleRegistry.get("no-fall");
        boolean noFallAntiBounce = noFall != null && noFall.isEnabled() && Boolean.parseBoolean(noFall.value("anti-bounce"));

        snapshot = new MovementState(revision, flight, speed, speedTimer, noFallAntiBounce);
        movementState = snapshot;
        return snapshot;
    }

    public static void preMovementTick() {
        PackModuleRegistry.preMovementTick();
    }

    public static Vec3 onPlayerMove(Entity entity, MoverType type, Vec3 movement) {
        if (entity != MC.player) return movement;
        Vec3 adjusted = PackModuleRegistry.onPlayerMove(type, movement);
        if (type == MoverType.SELF && adjusted != movement) {
            MC.player.setDeltaMovement(adjusted);
        }
        return adjusted;
    }

    private record SprintState(
        int revision,
        boolean omnidirectional,
        boolean ignoreCollision,
        boolean ignoreHunger,
        boolean ignoreBlindness
    ) {
        static SprintState inactive(int revision) {
            return new SprintState(revision, false, false, false, false);
        }
    }

    private record MovementState(
        int revision,
        PackBuiltinModules.FlightModule flight,
        PackBuiltinModules.SpeedModule speed,
        float speedTimerMultiplier,
        boolean noFallAntiBounce
    ) {
        static MovementState inactive(int revision) {
            return new MovementState(revision, null, null, 1.0f, false);
        }
    }
}
