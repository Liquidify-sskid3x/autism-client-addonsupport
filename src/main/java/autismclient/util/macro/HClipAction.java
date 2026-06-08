package autismclient.util.macro;

import autismclient.util.AutismClientMessaging;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Comparator;
import java.util.PriorityQueue;
import java.util.Set;

public class HClipAction implements MacroAction {
    private static final int ACCURACY_FIRST_PACKET_LIMIT = 12;
    private static final int MAX_ENDPOINTS = 96;
    private static final int MAX_ROUTE_POINTS = 760;
    private static final int MAX_SEARCH_VISITS = 14000;
    private static final int MAX_NODE_EDGE_CHECKS = 128;
    private static final double FALL_DAMAGE_RESET_NUDGE = 0.0625;
    private static final double FALL_SAFE_DIRECT_LIMIT = 3.0;
    private static final boolean DEBUG_ROUTES = Boolean.getBoolean("autism.hclip.debug");
    private static RouteCacheKey lastRouteKey;
    private static HClipPlan lastRoutePlan;
    private static long lastRouteTick = Long.MIN_VALUE;

    public enum Mode {
        MANUAL,
        FORWARD,
        BACK
    }

    public Mode    mode = Mode.MANUAL;
    public double  blocks = 0.0;
    public boolean useSegmented = true;
    public int     segmentBlocks = 10;
    public int     maxPackets = 20;
    public boolean updateLocalPosition = true;
    public boolean tryVehicleFirst = true;
    public boolean forceGrounded = false;
    public int     searchRadius = 32;
    public int     verticalRange = 8;
    public int     maxRoutePackets = 80;
    private boolean enabled = true;

    public record Result(boolean success, int packetsRequired, String message) {}

    public static final class Options {
        public Mode mode = Mode.MANUAL;
        public double blocks = 0.0;
        public boolean useSegmented = true;
        public int segmentBlocks = 10;
        public int maxPackets = 20;
        public boolean updateLocalPosition = true;
        public boolean tryVehicleFirst = true;
        public boolean forceGrounded = false;
        public int searchRadius = 32;
        public int verticalRange = 8;
        public int maxRoutePackets = 80;
        public boolean showMessage = false;

        public static Options defaults(double blocks) {
            Options options = new Options();
            options.blocks = blocks;
            return options;
        }

        public Options singlePacket() {
            useSegmented = false;
            return this;
        }
    }

    @Override
    public void execute(Minecraft mc) {
        Options options = new Options();
        options.mode = mode;
        options.blocks = blocks;
        options.useSegmented = useSegmented;
        options.segmentBlocks = segmentBlocks;
        options.maxPackets = maxPackets;
        options.updateLocalPosition = updateLocalPosition;
        options.tryVehicleFirst = tryVehicleFirst;
        options.forceGrounded = forceGrounded;
        options.searchRadius = searchRadius;
        options.verticalRange = verticalRange;
        options.maxRoutePackets = maxRoutePackets;
        perform(mc, options);
    }

    public static Result perform(Minecraft mc, Options options) {
        if (options == null) options = new Options();
        if (mc == null || mc.player == null || mc.getConnection() == null) {
            if (options.showMessage) AutismClientMessaging.sendPrefixed("\u00a7cHClip: no world / connection.");
            return new Result(false, 0, "No world / connection");
        }

        LocalPlayer player = mc.player;
        double blocks = options.blocks;
        if (options.mode != Mode.MANUAL) {
            AutoHorizontalTarget target = resolveAutoHorizontalTarget(player, options);
            if (!target.success()) {
                if (options.showMessage) AutismClientMessaging.sendPrefixed("\u00a7cHClip: " + target.message());
                return new Result(false, 0, target.message());
            }
            blocks = target.blocks();
        }
        int segment = Math.max(1, options.segmentBlocks);
        int maxPaddingPackets = Math.max(1, options.maxPackets);
        int paddingPackets = options.useSegmented ? Math.max(0, (int) Math.ceil(Math.abs(blocks) / (double) segment) - 1) : 0;
        if (paddingPackets + 1 > maxPaddingPackets) paddingPackets = 0;

        double yawRad = Math.toRadians(player.getYRot());
        double deltaX = -Math.sin(yawRad) * blocks;
        double deltaZ = Math.cos(yawRad) * blocks;
        HClipPlan plan = planRoute(player, deltaX, deltaZ, blocks, options);
        if (!plan.success()) {
            if (options.showMessage) AutismClientMessaging.sendPrefixed("\u00a7cHClip: " + plan.message());
            return new Result(false, 0, plan.message());
        }

        Entity vehicle = options.tryVehicleFirst ? player.getVehicle() : null;
        int routePackets = plan.waypoints().size();
        if (vehicle != null) {
            if (plan.requiresVertical(player.getY())) {
                String message = "planned route requires vertical clipping; vehicle hclip refused";
                if (options.showMessage) AutismClientMessaging.sendPrefixed("\u00a7cHClip: " + message);
                return new Result(false, plan.waypoints().size(), message);
            }
            try {
                for (int i = 0; i < paddingPackets; i++) {
                    mc.getConnection().send(ServerboundMoveVehiclePacket.fromEntity(vehicle));
                }
                for (Vec3 waypoint : plan.waypoints()) {
                    vehicle.setPos(waypoint.x, waypoint.y, waypoint.z);
                    mc.getConnection().send(ServerboundMoveVehiclePacket.fromEntity(vehicle));
                }
            } catch (Throwable t) {
                String message = "Vehicle hclip failed: " + t.getMessage();
                if (options.showMessage) AutismClientMessaging.sendPrefixed("\u00a7c" + message);
                return new Result(false, paddingPackets + plan.waypoints().size(), message);
            }
        } else {
            boolean grounded = options.forceGrounded;
            for (int i = 0; i < paddingPackets; i++) {
                mc.getConnection().send(new ServerboundMovePlayerPacket.StatusOnly(grounded, false));
            }
            routePackets = 0;
            Vec3 current = player.position();
            clearLocalFallState(player);
            for (Vec3 waypoint : plan.waypoints()) {
                routePackets += sendWaypointFallSafe(mc, current, waypoint, grounded);
                current = waypoint;
            }
            Vec3 finalPos = plan.waypoints().getLast();
            if (options.updateLocalPosition) {
                player.setPos(finalPos.x, finalPos.y, finalPos.z);
                clearLocalFallState(player);
            }
        }

        int packetCount = paddingPackets + routePackets;
        String prefix = options.mode == Mode.MANUAL ? "hclip " + blocks : "hclip " + options.mode.name().toLowerCase(java.util.Locale.ROOT) + " -> " + String.format(java.util.Locale.ROOT, "%.2f", blocks);
        String message = prefix + " (" + packetCount + " packet" + (packetCount == 1 ? "" : "s") + ")";
        if (DEBUG_ROUTES && plan.success()) {
            message += ", " + plan.message();
        }
        if (options.showMessage) AutismClientMessaging.sendPrefixed("\u00a7a" + message);
        return new Result(true, packetCount, message);
    }

    private static int sendWaypointFallSafe(Minecraft mc, Vec3 from, Vec3 to, boolean grounded) {
        double dy = to.y - from.y;
        if (dy < -FALL_SAFE_DIRECT_LIMIT) {
            double resetY = to.y + FALL_DAMAGE_RESET_NUDGE;
            mc.getConnection().send(new ServerboundMovePlayerPacket.Pos(to.x, to.y, to.z, false, false));
            mc.getConnection().send(new ServerboundMovePlayerPacket.Pos(to.x, resetY, to.z, false, false));
            mc.getConnection().send(new ServerboundMovePlayerPacket.Pos(to.x, to.y, to.z, true, false));
            return 3;
        }
        mc.getConnection().send(new ServerboundMovePlayerPacket.Pos(to, grounded, false));
        return 1;
    }

    private static void clearLocalFallState(LocalPlayer player) {
        player.resetFallDistance();
        Vec3 velocity = player.getDeltaMovement();
        if (velocity.y < 0.0) {
            player.setDeltaMovement(velocity.x, 0.0, velocity.z);
        }
    }

    private static HClipPlan planRoute(LocalPlayer player, double deltaX, double deltaZ, double blocks, Options options) {
        Vec3 start = player.position();
        Vec3 requestedTarget = start.add(deltaX, 0.0, deltaZ);
        int radius = Math.max(1, options.searchRadius);
        int verticalRange = Math.max(0, options.verticalRange);
        int routeCap = Math.max(1, Math.min(200, options.maxRoutePackets));
        ClipFrame frame = ClipFrame.create(deltaX, deltaZ, blocks, requestedTarget);
        RouteContext ctx = new RouteContext(player, start, requestedTarget, frame, radius, verticalRange, routeCap);
        RouteCacheKey cacheKey = RouteCacheKey.create(player, start, requestedTarget, radius, verticalRange, routeCap);
        long tick = player.level() == null ? Long.MIN_VALUE : player.level().getGameTime();
        if (lastRouteKey != null && lastRouteKey.equals(cacheKey) && lastRoutePlan != null && tick - lastRouteTick <= 2L) {
            return lastRoutePlan;
        }

        if (isPositionLoaded(player, requestedTarget) && isPositionClear(player, requestedTarget) && hasClearHorizontalPath(player, start, requestedTarget)) {
            return rememberRoute(cacheKey, tick, HClipPlan.ok(start.y, List.of(requestedTarget), "direct"));
        }

        List<RoutePoint> endpoints = chooseEndpointCandidates(ctx);
        if (endpoints.isEmpty()) return rememberRoute(cacheKey, tick, HClipPlan.fail("no collision-free target near requested end"));

        HClipPlan directNear = planDirectEndpoint(ctx, endpoints);
        if (directNear.success()) return rememberRoute(cacheKey, tick, directNear);

        HClipPlan straightPlane = planStraightVerticalPlane(ctx, endpoints);
        if (straightPlane.success()) return rememberRoute(cacheKey, tick, straightPlane);

        HClipPlan graph = planPacketGraph(ctx, endpoints);
        if (graph.success()) return rememberRoute(cacheKey, tick, graph);

        return rememberRoute(cacheKey, tick, HClipPlan.fail("no loaded collision-free packet route"));
    }

    private static HClipPlan rememberRoute(RouteCacheKey key, long tick, HClipPlan plan) {
        if (plan.success()) {
            lastRouteKey = key;
            lastRoutePlan = plan;
            lastRouteTick = tick;
        }
        return plan;
    }

    private static AutoHorizontalTarget resolveAutoHorizontalTarget(LocalPlayer player, Options options) {
        int direction = options.mode == Mode.BACK ? -1 : 1;
        int radius = Math.max(1, Math.min(128, options.searchRadius));
        double yawRad = Math.toRadians(player.getYRot());
        double dirX = -Math.sin(yawRad) * direction;
        double dirZ = Math.cos(yawRad) * direction;
        Vec3 start = player.position();
        boolean seenBlocked = false;
        int firstBlocked = 0;
        int lastBlocked = 0;

        for (int distance = 1; distance <= radius; distance++) {
            Vec3 candidate = new Vec3(start.x + dirX * distance, start.y, start.z + dirZ * distance);
            boolean clear = isPositionLoaded(player, candidate) && isPositionClear(player, candidate);
            if (!clear) {
                if (!seenBlocked) firstBlocked = distance;
                lastBlocked = distance;
                seenBlocked = true;
                continue;
            }
            if (seenBlocked) {
                return new AutoHorizontalTarget(true, direction * distance, "past obstruction");
            }
        }

        if (seenBlocked) {
            int requested = Math.min(radius, Math.max(firstBlocked + 1, lastBlocked + 1));
            return new AutoHorizontalTarget(true, direction * requested, "route around obstruction");
        }
        return new AutoHorizontalTarget(false, 0.0, options.mode == Mode.BACK ? "no blocking run behind you" : "no blocking run in front of you");
    }

    private static HClipPlan planDirectEndpoint(RouteContext ctx, List<RoutePoint> endpoints) {
        RoutePoint best = null;
        RouteScore bestScore = null;
        for (RoutePoint endpoint : endpoints) {
            if (endpoint.exact()) continue;
            if (endpoint.minPackets() > 1) continue;
            if (Math.abs(endpoint.pos().y - ctx.start().y) > 1.0E-5) continue;
            if (!hasClearHorizontalPath(ctx.player(), ctx.start(), endpoint.pos())) continue;
            RouteScore score = routeScore(1, endpoint, 0.0);
            if (bestScore == null || score.compareTo(bestScore) < 0) {
                best = endpoint;
                bestScore = score;
            }
        }
        return best == null ? HClipPlan.fail("no direct nearby target") : HClipPlan.ok(ctx.start().y, List.of(best.pos()), "direct-near");
    }

    private static HClipPlan planStraightVerticalPlane(RouteContext ctx, List<RoutePoint> endpoints) {
        RoutePoint bestEndpoint = null;
        List<Vec3> bestRoute = List.of();
        RouteScore bestScore = null;
        for (int offset : verticalOffsets(ctx.verticalRange())) {
            if (offset == 0) continue;
            Vec3 raisedStart = ctx.start().add(0.0, offset, 0.0);
            if (!isPositionLoaded(ctx.player(), raisedStart) || !isPositionClear(ctx.player(), raisedStart)) continue;
            for (RoutePoint endpoint : endpoints) {
                Vec3 raisedEnd = new Vec3(endpoint.pos().x, raisedStart.y, endpoint.pos().z);
                if (!isPositionLoaded(ctx.player(), raisedEnd) || !isPositionClear(ctx.player(), raisedEnd)) continue;
                if (!hasClearHorizontalPath(ctx.player(), raisedStart, raisedEnd)) continue;

                List<Vec3> waypoints = new ArrayList<>(3);
                waypoints.add(raisedStart);
                if (!samePosition(raisedStart, raisedEnd)) waypoints.add(raisedEnd);
                if (!samePosition(raisedEnd, endpoint.pos())) waypoints.add(endpoint.pos());
                if (waypoints.isEmpty() || waypoints.size() > ctx.routeCap()) continue;

                RouteScore score = routeScore(waypoints.size(), endpoint, routeQuality(ctx, waypoints));
                if (bestScore == null || score.compareTo(bestScore) < 0) {
                    bestEndpoint = endpoint;
                    bestRoute = waypoints;
                    bestScore = score;
                }
            }
        }
        return bestEndpoint == null ? HClipPlan.fail("no straight vertical plane") : HClipPlan.ok(ctx.start().y, cleanupRoute(ctx.start(), bestRoute), "vertical-plane");
    }

    private static List<Integer> verticalOffsets(int verticalRange) {
        List<Integer> offsets = new ArrayList<>(verticalRange * 2 + 1);
        offsets.add(0);
        for (int i = 1; i <= verticalRange; i++) {
            offsets.add(i);
            offsets.add(-i);
        }
        return offsets;
    }

    private static List<RoutePoint> chooseEndpointCandidates(RouteContext ctx) {
        List<CandidatePoint> scored = new ArrayList<>();
        if (isPositionLoaded(ctx.player(), ctx.requestedTarget()) && isPositionClear(ctx.player(), ctx.requestedTarget())) {
            scored.add(new CandidatePoint(new RoutePoint(new GridKey(ctx.frame().targetForward, 0, 0), ctx.requestedTarget(), true, 0, 1, 0.0, 0.0, 0.0, 0.0), true, 0.0));
        }

        int endpointRadius = Math.min(ctx.radius(), 4);
        addEndpointRing(ctx, scored, endpointRadius);
        addForwardEscapeEndpoints(ctx, scored);
        if (scored.isEmpty() && ctx.radius() > endpointRadius) {
            addEndpointRing(ctx, scored, Math.min(ctx.radius(), 8));
            addForwardEscapeEndpoints(ctx, scored);
        }

        scored.sort((a, b) -> {
            int exact = Boolean.compare(!a.point().exact(), !b.point().exact());
            if (exact != 0) return exact;
            int important = Boolean.compare(!a.important(), !b.important());
            if (important != 0) return important;
            return Double.compare(a.score(), b.score());
        });

        List<RoutePoint> result = new ArrayList<>(Math.min(MAX_ENDPOINTS, scored.size()));
        Set<GridKey> seen = new HashSet<>();
        boolean exactSeen = false;
        for (CandidatePoint candidate : scored) {
            RoutePoint point = candidate.point();
            if (point.exact()) {
                if (exactSeen) continue;
                exactSeen = true;
                seen.add(point.key());
            } else if (!seen.add(point.key())) {
                continue;
            }
            result.add(point);
            if (result.size() >= MAX_ENDPOINTS) break;
        }
        return result;
    }

    private static void addEndpointRing(RouteContext ctx, List<CandidatePoint> scored, int endpointRadius) {
        for (int y : verticalOffsets(ctx.verticalRange())) {
            for (int r = 0; r <= endpointRadius; r++) {
                for (int forward = ctx.frame().targetForward - r; forward <= ctx.frame().targetForward + r; forward++) {
                    for (int side = -r; side <= r; side++) {
                        if (Math.max(Math.abs(forward - ctx.frame().targetForward), Math.abs(side)) != r) continue;
                        GridKey key = new GridKey(forward, side, y);
                        addEndpointCandidate(ctx, scored, key, false);
                    }
                }
            }
        }
    }

    private static void addForwardEscapeEndpoints(RouteContext ctx, List<CandidatePoint> scored) {
        List<Integer> blockers = scanDirectBlockers(ctx);
        int target = ctx.frame().targetForward;
        int maxForward = target + Math.min(ctx.radius(), Math.max(8, target + 4));
        List<Integer> forwardCandidates = new ArrayList<>();
        if (blockers.isEmpty()) {
            if (target <= 5) {
                for (int forward = target + 1; forward <= maxForward; forward++) {
                    addUnique(forwardCandidates, forward);
                }
            }
        } else {
            for (int blocker : blockers) {
                for (int forward = Math.max(target, blocker + 2); forward <= Math.min(maxForward, blocker + 10); forward++) {
                    addUnique(forwardCandidates, forward);
                }
            }
        }

        List<Integer> sideOffsets = prioritizedOffsets(Math.min(ctx.radius(), 3));
        for (int forward : forwardCandidates) {
            for (int y : verticalOffsets(ctx.verticalRange())) {
                for (int side : sideOffsets) {
                    addEndpointCandidate(ctx, scored, new GridKey(forward, side, y), true, 2, 2);
                }
            }
        }
    }

    private static void addEndpointCandidate(RouteContext ctx, List<CandidatePoint> scored, GridKey key, boolean important) {
        addEndpointCandidate(ctx, scored, key, important, 1, 1);
    }

    private static void addEndpointCandidate(RouteContext ctx, List<CandidatePoint> scored, GridKey key, boolean important, int endpointTier, int minPackets) {
        Vec3 pos = positionFor(ctx.start(), ctx.frame(), key);
        if (!isPositionLoaded(ctx.player(), pos) || !isPositionClear(ctx.player(), pos)) return;
        double forwardError = Math.abs(key.forward - ctx.frame().targetForward);
        double sideError = Math.abs(key.side);
        double yError = Math.abs(key.y);
        double targetDistance = pos.distanceTo(ctx.requestedTarget());
        double score = targetDistance + sideError * 16.0 + forwardError * 3.0 + yError * 28.0;
        scored.add(new CandidatePoint(new RoutePoint(key, pos, false, endpointTier, minPackets, targetDistance, sideError, yError, forwardError), important, score));
    }

    private static HClipPlan planPacketGraph(RouteContext ctx, List<RoutePoint> endpoints) {
        List<RoutePoint> points = buildRoutePoints(ctx, endpoints);
        if (points.isEmpty()) return HClipPlan.fail("no route points");

        Map<Integer, PathNode> best = new HashMap<>();
        PriorityQueue<PathNode> open = new PriorityQueue<>(PathNode::compareTo);
        PathNode startNode = new PathNode(0, null, 0, 0.0, false);
        best.put(0, startNode);
        open.add(startNode);

        Map<EdgeKey, Boolean> edgeCache = new HashMap<>();
        PathNode bestEnd = null;
        RouteScore bestScore = null;
        int visited = 0;
        while (!open.isEmpty() && visited++ < MAX_SEARCH_VISITS) {
            PathNode node = open.poll();
            PathNode currentBest = best.get(node.index());
            if (currentBest != node) continue;
            if (bestScore != null
                && node.packets() > ACCURACY_FIRST_PACKET_LIMIT
                && node.packets() > bestScore.packets()) {
                break;
            }

            RoutePoint point = points.get(node.index());
            if (node.index() != 0 && point.endpoint()) {
                if (node.packets() < point.minPackets()) continue;
                RouteScore score = routeScore(node.packets(), point, node.quality());
                if (bestScore == null || score.compareTo(bestScore) < 0) {
                    bestScore = score;
                    bestEnd = node;
                }
                continue;
            }
            if (node.packets() >= ctx.routeCap()) continue;

            for (int nextIndex : candidateNextIndices(points, node.index())) {
                if (nextIndex == node.index()) continue;
                EdgeKey edgeKey = new EdgeKey(node.index(), nextIndex);
                RoutePoint next = points.get(nextIndex);
                boolean reachable = edgeCache.computeIfAbsent(edgeKey, key -> canPacketEdge(ctx.player(), point, next));
                if (!reachable) continue;

                int nextPackets = node.packets() + 1;
                double nextQuality = node.quality() + edgeQuality(ctx, point, next);
                PathNode existing = best.get(nextIndex);
                if (existing != null && (existing.packets() < nextPackets || existing.packets() == nextPackets && existing.quality() <= nextQuality)) {
                    continue;
                }
                PathNode nextNode = new PathNode(nextIndex, node, nextPackets, nextQuality, next.endpoint());
                best.put(nextIndex, nextNode);
                open.add(nextNode);
            }
        }

        if (bestEnd == null) return HClipPlan.fail("no packet graph route");
        List<Vec3> route = reconstruct(points, bestEnd);
        if (route.isEmpty()) return HClipPlan.fail("empty packet graph route");
        if (route.size() > ctx.routeCap()) return HClipPlan.fail("route needs " + route.size() + " packets, cap is " + ctx.routeCap());
        return HClipPlan.ok(ctx.start().y, cleanupRoute(ctx.start(), route), "packet-graph");
    }

    private static List<Integer> candidateNextIndices(List<RoutePoint> points, int fromIndex) {
        RoutePoint from = points.get(fromIndex);
        List<ScoredIndex> scored = new ArrayList<>(Math.min(points.size(), MAX_NODE_EDGE_CHECKS + 32));
        for (int i = 1; i < points.size(); i++) {
            if (i == fromIndex) continue;
            RoutePoint to = points.get(i);
            boolean sameColumn = to.key().forward == from.key().forward && to.key().side == from.key().side;
            boolean sameHeight = to.key().y == from.key().y;
            if (!to.endpoint() && !sameColumn && !sameHeight) continue;
            double score = nextPointScore(from, to);
            scored.add(new ScoredIndex(i, score));
        }
        scored.sort(Comparator.comparingDouble(ScoredIndex::score));
        List<Integer> result = new ArrayList<>(Math.min(MAX_NODE_EDGE_CHECKS, scored.size()));
        for (ScoredIndex index : scored) {
            result.add(index.index());
            if (result.size() >= MAX_NODE_EDGE_CHECKS) break;
        }
        return result;
    }

    private static double nextPointScore(RoutePoint from, RoutePoint to) {
        double score = to.endpoint() ? -5000.0 : 0.0;
        if (to.key().forward == from.key().forward && to.key().side == from.key().side) score -= 1000.0;
        if (to.key().y == from.key().y) score -= 500.0;
        score += to.sideError() * 18.0;
        score += to.yError() * 28.0;
        score += to.targetDistance() * 1.4;
        score += Math.max(0.0, to.targetDistance() - from.targetDistance()) * 4.0;
        score += to.pos().distanceTo(from.pos()) * 0.02;
        return score;
    }

    private static List<RoutePoint> buildRoutePoints(RouteContext ctx, List<RoutePoint> endpoints) {
        Map<GridKey, CandidatePoint> unique = new LinkedHashMap<>();
        List<RoutePoint> points = new ArrayList<>();
        points.add(new RoutePoint(new GridKey(0, 0, 0), ctx.start(), false, 1, 1, ctx.requestedTarget().distanceTo(ctx.start()), 0.0, 0.0, Math.abs(ctx.frame().targetForward)));

        for (RoutePoint endpoint : endpoints) {
            points.add(endpoint.withEndpoint(true));
            addVerticalColumn(ctx, unique, endpoint.key(), true);
        }
        addVerticalColumn(ctx, unique, new GridKey(0, 0, 0), true);
        addObstacleAnchors(ctx, unique);
        addEscapeColumns(ctx, unique);

        int target = ctx.frame().targetForward;
        int minForward = Math.min(0, target) - Math.min(ctx.radius(), 8);
        int maxForward = Math.max(0, target) + Math.min(ctx.radius(), 8);
        int sideLimit = Math.min(ctx.radius(), Math.max(4, Math.min(14, Math.abs(target) / 2 + 4)));
        List<Integer> forwardSamples = prioritizedForwardSamples(minForward, maxForward, target);
        List<Integer> sideSamples = prioritizedOffsets(sideLimit);
        List<Integer> ySamples = verticalOffsets(ctx.verticalRange());

        for (int y : ySamples) {
            for (int forward : forwardSamples) {
                for (int side : sideSamples) {
                    GridKey key = new GridKey(forward, side, y);
                    addCandidate(ctx, unique, key, false);
                }
            }
        }

        List<CandidatePoint> candidates = new ArrayList<>(unique.values());
        candidates.sort((a, b) -> Boolean.compare(!a.important(), !b.important()) != 0
            ? Boolean.compare(!a.important(), !b.important())
            : Double.compare(a.score(), b.score()));

        Set<GridKey> existing = new HashSet<>();
        existing.add(new GridKey(0, 0, 0));
        for (RoutePoint endpoint : endpoints) existing.add(endpoint.key());

        int cap = MAX_ROUTE_POINTS;
        for (CandidatePoint candidate : candidates) {
            if (points.size() >= cap) break;
            if (!existing.add(candidate.point().key())) continue;
            points.add(candidate.point());
        }
        return points;
    }

    private static void addObstacleAnchors(RouteContext ctx, Map<GridKey, CandidatePoint> unique) {
        List<Integer> blockers = scanDirectBlockers(ctx);
        if (blockers.isEmpty()) return;
        int sideLimit = Math.min(ctx.radius(), 10);
        List<Integer> sideOffsets = prioritizedOffsets(sideLimit);
        List<Integer> yOffsets = verticalOffsets(ctx.verticalRange());
        for (int blockerForward : blockers) {
            for (int forwardDelta : new int[] { -3, -2, -1, 0, 1, 2, 3 }) {
                int forward = blockerForward + forwardDelta;
                for (int side : sideOffsets) {
                    if (side == 0 && forwardDelta == 0) continue;
                    for (int y : yOffsets) {
                        boolean important = Math.abs(side) <= 3 || Math.abs(y) <= 2;
                        addCandidate(ctx, unique, new GridKey(forward, side, y), important);
                    }
                }
            }
        }
    }

    private static void addEscapeColumns(RouteContext ctx, Map<GridKey, CandidatePoint> unique) {
        List<Integer> blockers = scanDirectBlockers(ctx);
        if (blockers.isEmpty() && ctx.frame().targetForward > 5) return;
        int target = ctx.frame().targetForward;
        int maxForward = target + Math.min(ctx.radius(), Math.max(8, target + 4));
        List<Integer> forwards = new ArrayList<>();
        if (blockers.isEmpty()) {
            for (int forward = target; forward <= maxForward; forward++) addUnique(forwards, forward);
        } else {
            for (int blocker : blockers) {
                for (int forward = Math.max(target, blocker + 1); forward <= Math.min(maxForward, blocker + 10); forward++) {
                    addUnique(forwards, forward);
                }
            }
        }
        for (int forward : forwards) {
            for (int side : prioritizedOffsets(Math.min(ctx.radius(), 4))) {
                for (int y : verticalOffsets(ctx.verticalRange())) {
                    addCandidate(ctx, unique, new GridKey(forward, side, y), true);
                }
            }
        }
    }

    private static List<Integer> scanDirectBlockers(RouteContext ctx) {
        List<Integer> blockers = new ArrayList<>();
        int target = ctx.frame().targetForward;
        int direction = target < 0 ? -1 : 1;
        int last = Integer.MIN_VALUE;
        for (int forward = direction; direction > 0 ? forward <= target : forward >= target; forward += direction) {
            GridKey key = new GridKey(forward, 0, 0);
            Vec3 pos = positionFor(ctx.start(), ctx.frame(), key);
            if (isPositionLoaded(ctx.player(), pos) && isPositionClear(ctx.player(), pos)) continue;
            if (last == Integer.MIN_VALUE || Math.abs(forward - last) > 2) {
                blockers.add(forward);
                if (blockers.size() >= 16) break;
            }
            last = forward;
        }
        return blockers;
    }

    private static void addVerticalColumn(RouteContext ctx, Map<GridKey, CandidatePoint> unique, GridKey base, boolean important) {
        for (int y : verticalOffsets(ctx.verticalRange())) {
            addCandidate(ctx, unique, new GridKey(base.forward, base.side, y), important);
        }
    }

    private static void addCandidate(RouteContext ctx, Map<GridKey, CandidatePoint> unique, GridKey key, boolean important) {
        Vec3 pos = positionFor(ctx.start(), ctx.frame(), key);
        if (!isPositionLoaded(ctx.player(), pos) || !isPositionClear(ctx.player(), pos)) return;
        double targetDistance = pos.distanceTo(ctx.requestedTarget());
        double sideError = Math.abs(key.side);
        double yError = Math.abs(key.y);
        double forwardError = Math.abs(key.forward - ctx.frame().targetForward);
        double score = sideError * 10.0 + yError * 18.0 + forwardError * 1.8 + targetDistance * 0.2;
        CandidatePoint next = new CandidatePoint(new RoutePoint(key, pos, false, 1, 1, targetDistance, sideError, yError, forwardError), important, score);
        CandidatePoint existing = unique.get(key);
        if (existing == null || next.score() < existing.score() || important && !existing.important()) {
            unique.put(key, next);
        }
    }

    private static List<Integer> prioritizedForwardSamples(int minForward, int maxForward, int target) {
        List<Integer> samples = new ArrayList<>();
        addUnique(samples, 0);
        addUnique(samples, target);
        addUnique(samples, target / 2);
        int direction = target < 0 ? -1 : 1;
        int step = 2 * direction;
        for (int forward = step; direction > 0 ? forward <= maxForward : forward >= minForward; forward += step) {
            addUnique(samples, forward);
        }
        for (int forward = minForward; forward <= maxForward; forward += 4) {
            addUnique(samples, forward);
        }
        return samples;
    }

    private static List<Integer> prioritizedOffsets(int limit) {
        List<Integer> values = new ArrayList<>(limit * 2 + 1);
        values.add(0);
        for (int i = 1; i <= limit; i++) {
            values.add(i);
            values.add(-i);
        }
        return values;
    }

    private static void addUnique(List<Integer> values, int value) {
        if (!values.contains(value)) values.add(value);
    }

    private static boolean canPacketEdge(LocalPlayer player, RoutePoint from, RoutePoint to) {
        if (samePosition(from.pos(), to.pos())) return false;
        boolean sameXZ = Math.abs(from.pos().x - to.pos().x) < 1.0E-5 && Math.abs(from.pos().z - to.pos().z) < 1.0E-5;
        if (sameXZ) return true;
        boolean sameY = Math.abs(from.pos().y - to.pos().y) < 1.0E-5;
        return sameY && hasClearHorizontalPath(player, from.pos(), to.pos());
    }

    private static double edgeQuality(RouteContext ctx, RoutePoint from, RoutePoint to) {
        double side = Math.abs(to.key().side) * 0.04;
        double y = Math.abs(to.key().y) * 0.16;
        double awayFromTarget = Math.max(0.0, to.targetDistance() - from.targetDistance()) * 0.01;
        return side + y + awayFromTarget + from.pos().distanceTo(to.pos()) * 0.001;
    }

    private static double routeQuality(RouteContext ctx, List<Vec3> route) {
        double quality = 0.0;
        Vec3 previous = ctx.start();
        for (Vec3 waypoint : route) {
            quality += waypoint.distanceTo(previous) * 0.001;
            quality += Math.abs(waypoint.y - ctx.start().y) * 0.18;
            previous = waypoint;
        }
        return quality;
    }

    private static RouteScore routeScore(int packets, RoutePoint endpoint, double quality) {
        return new RouteScore(packets, endpoint.endpointTier(), endpoint.targetDistance(), endpoint.sideError(), endpoint.yError(), endpoint.forwardError(), quality);
    }

    private static List<Vec3> reconstruct(List<RoutePoint> points, PathNode node) {
        List<Vec3> route = new ArrayList<>();
        for (PathNode cursor = node; cursor != null && cursor.previous() != null; cursor = cursor.previous()) {
            route.add(0, points.get(cursor.index()).pos());
        }
        return route;
    }

    private static List<Vec3> cleanupRoute(Vec3 start, List<Vec3> route) {
        List<Vec3> cleaned = new ArrayList<>(route.size());
        Vec3 previous = start;
        for (Vec3 waypoint : route) {
            if (samePosition(previous, waypoint)) continue;
            cleaned.add(waypoint);
            previous = waypoint;
        }
        return cleaned;
    }

    private static boolean samePosition(Vec3 a, Vec3 b) {
        return Math.abs(a.x - b.x) < 1.0E-5
            && Math.abs(a.y - b.y) < 1.0E-5
            && Math.abs(a.z - b.z) < 1.0E-5;
    }

    private static Vec3 positionFor(Vec3 start, ClipFrame frame, GridKey key) {
        return new Vec3(
            start.x + frame.forwardX * key.forward + frame.sideX * key.side,
            start.y + key.y,
            start.z + frame.forwardZ * key.forward + frame.sideZ * key.side
        );
    }

    private static boolean isPositionLoaded(LocalPlayer player, Vec3 pos) {
        return player != null && player.level() != null && player.level().isLoaded(BlockPos.containing(pos));
    }

    private static boolean hasClearHorizontalPath(LocalPlayer player, Vec3 from, Vec3 to) {
        if (Math.abs(from.x - to.x) < 1.0E-5 && Math.abs(from.z - to.z) < 1.0E-5) return true;
        if (Math.abs(from.y - to.y) > 1.0E-5) return false;
        double distance = from.distanceTo(to);
        int steps = Math.max(1, (int) Math.ceil(distance / 0.25));
        for (int i = 1; i <= steps; i++) {
            double t = i / (double) steps;
            Vec3 pos = from.lerp(to, t);
            if (!isPositionLoaded(player, pos) || !isPositionClear(player, pos)) return false;
        }
        return true;
    }

    private static boolean isPositionClear(LocalPlayer player, Vec3 pos) {
        Vec3 delta = pos.subtract(player.position());
        return player.level().noCollision(player, player.getBoundingBox().move(delta));
    }

    private record ClipFrame(double forwardX, double forwardZ, double sideX, double sideZ, int targetForward, Vec3 target) {
        static ClipFrame create(double deltaX, double deltaZ, double blocks, Vec3 target) {
            double distance = Math.hypot(deltaX, deltaZ);
            double forwardX = distance < 1.0E-5 ? 0.0 : deltaX / distance;
            double forwardZ = distance < 1.0E-5 ? 1.0 : deltaZ / distance;
            double sideX = forwardZ;
            double sideZ = -forwardX;
            return new ClipFrame(forwardX, forwardZ, sideX, sideZ, (int) Math.round(Math.abs(blocks)), target);
        }
    }
    private record RouteContext(LocalPlayer player, Vec3 start, Vec3 requestedTarget, ClipFrame frame, int radius, int verticalRange, int routeCap) {}
    private record GridKey(int forward, int side, int y) {}
    private record RoutePoint(GridKey key, Vec3 pos, boolean endpoint, boolean exact, int endpointTier, int minPackets, double targetDistance, double sideError, double yError, double forwardError) {
        RoutePoint(GridKey key, Vec3 pos, boolean exact, int endpointTier, int minPackets, double targetDistance, double sideError, double yError, double forwardError) {
            this(key, pos, false, exact, endpointTier, minPackets, targetDistance, sideError, yError, forwardError);
        }

        RoutePoint withEndpoint(boolean endpoint) {
            return new RoutePoint(key, pos, endpoint, exact, endpointTier, minPackets, targetDistance, sideError, yError, forwardError);
        }
    }
    private record CandidatePoint(RoutePoint point, boolean important, double score) {}
    private record EdgeKey(int from, int to) {}
    private record ScoredIndex(int index, double score) {}
    private record AutoHorizontalTarget(boolean success, double blocks, String message) {}
    private record RouteCacheKey(int levelId, long startX, long startY, long startZ, long targetX, long targetY, long targetZ, int radius, int verticalRange, int routeCap) {
        static RouteCacheKey create(LocalPlayer player, Vec3 start, Vec3 target, int radius, int verticalRange, int routeCap) {
            int levelId = player.level() == null ? 0 : System.identityHashCode(player.level());
            return new RouteCacheKey(
                levelId,
                quantize(start.x), quantize(start.y), quantize(start.z),
                quantize(target.x), quantize(target.y), quantize(target.z),
                radius, verticalRange, routeCap
            );
        }

        private static long quantize(double value) {
            return Math.round(value * 64.0);
        }
    }
    private record RouteScore(int packets, int endpointTier, double targetDistance, double sideError, double yError, double forwardError, double quality) implements Comparable<RouteScore> {
        @Override
        public int compareTo(RouteScore other) {
            int result = Integer.compare(endpointTier, other.endpointTier);
            if (result != 0) return result;
            boolean accuracyFirst = packets <= ACCURACY_FIRST_PACKET_LIMIT && other.packets <= ACCURACY_FIRST_PACKET_LIMIT;
            if (accuracyFirst) {
                result = Double.compare(targetDistance, other.targetDistance);
                if (result != 0) return result;
                result = Double.compare(yError, other.yError);
                if (result != 0) return result;
                result = Double.compare(sideError, other.sideError);
                if (result != 0) return result;
                result = Double.compare(forwardError, other.forwardError);
                if (result != 0) return result;
                result = Integer.compare(packets, other.packets);
                if (result != 0) return result;
            } else {
                result = Integer.compare(packets, other.packets);
                if (result != 0) return result;
                result = Double.compare(targetDistance, other.targetDistance);
                if (result != 0) return result;
                result = Double.compare(yError, other.yError);
                if (result != 0) return result;
                result = Double.compare(sideError, other.sideError);
                if (result != 0) return result;
                result = Double.compare(forwardError, other.forwardError);
                if (result != 0) return result;
            }
            return Double.compare(quality, other.quality);
        }
    }
    private record HClipPlan(boolean success, double originY, List<Vec3> waypoints, String message) {
        static HClipPlan ok(double originY, List<Vec3> waypoints) { return ok(originY, waypoints, "ok"); }
        static HClipPlan ok(double originY, List<Vec3> waypoints, String message) { return new HClipPlan(true, originY, List.copyOf(waypoints), message); }
        static HClipPlan fail(String message) { return new HClipPlan(false, 0.0, List.of(), message); }
        boolean requiresVertical(double fallbackOriginY) {
            if (waypoints.isEmpty()) return false;
            double y = originY == 0.0 ? fallbackOriginY : originY;
            for (Vec3 waypoint : waypoints) {
                if (Math.abs(waypoint.y - y) > 1.0E-5) return true;
            }
            return false;
        }
    }

    private record PathNode(int index, PathNode previous, int packets, double quality, boolean endpoint) implements Comparable<PathNode> {
        @Override
        public int compareTo(PathNode other) {
            int result = Integer.compare(packets, other.packets);
            if (result != 0) return result;
            if (endpoint != other.endpoint) return endpoint ? -1 : 1;
            return Double.compare(quality, other.quality);
        }
    }

    @Override
    public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        tag.putString("type", getType().name());
        tag.putString("mode", mode.name());
        tag.putDouble("blocks", blocks);
        tag.putBoolean("useSegmented", useSegmented);
        tag.putInt("segmentBlocks", Math.max(1, segmentBlocks));
        tag.putInt("maxPackets", Math.max(1, maxPackets));
        tag.putBoolean("updateLocalPosition", updateLocalPosition);
        tag.putBoolean("tryVehicleFirst", tryVehicleFirst);
        tag.putBoolean("forceGrounded", forceGrounded);
        tag.putInt("searchRadius", Math.max(1, searchRadius));
        tag.putInt("verticalRange", Math.max(0, verticalRange));
        tag.putInt("maxRoutePackets", Math.max(1, Math.min(200, maxRoutePackets)));
        tag.putBoolean("enabled", enabled);
        return tag;
    }

    @Override
    public void fromTag(CompoundTag tag) {
        mode = parseMode(tag.getStringOr("mode", Mode.MANUAL.name()));
        blocks = tag.getDoubleOr("blocks", 0.0);
        useSegmented = tag.getBooleanOr("useSegmented", true);
        segmentBlocks = Math.max(1, tag.getIntOr("segmentBlocks", 10));
        maxPackets = Math.max(1, tag.getIntOr("maxPackets", 20));
        updateLocalPosition = tag.getBooleanOr("updateLocalPosition", true);
        tryVehicleFirst = tag.getBooleanOr("tryVehicleFirst", true);
        forceGrounded = tag.getBooleanOr("forceGrounded", false);
        searchRadius = Math.max(1, tag.getIntOr("searchRadius", 32));
        verticalRange = Math.max(0, tag.getIntOr("verticalRange", 8));
        maxRoutePackets = Math.max(1, Math.min(200, tag.contains("maxRoutePackets") ? tag.getIntOr("maxRoutePackets", 80) : tag.getIntOr("maxPackets", 80)));
        if (tag.contains("enabled")) enabled = tag.getBooleanOr("enabled", true);
    }

    @Override
    public MacroActionType getType() {
        return MacroActionType.HCLIP;
    }

    @Override
    public String getDisplayName() {
        if (mode == Mode.FORWARD) return "HClip Forward";
        if (mode == Mode.BACK) return "HClip Back";
        return "HClip " + String.format(java.util.Locale.ROOT, "%.2f", blocks);
    }

    @Override
    public String getIcon() { return "HC"; }

    @Override public boolean isEnabled() { return enabled; }
    @Override public void setEnabled(boolean e) { this.enabled = e; }

    private static Mode parseMode(String value) {
        try {
            return Mode.valueOf(value == null ? Mode.MANUAL.name() : value.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return Mode.MANUAL;
        }
    }
}
