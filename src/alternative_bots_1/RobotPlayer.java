package alternative_bots_1;

import battlecode.common.*;

import java.util.HashSet;
import java.util.Random;

public class RobotPlayer {
    static final Random rng = new Random(6147);

    static final Direction[] directions = {
            Direction.NORTH,
            Direction.NORTHEAST,
            Direction.EAST,
            Direction.SOUTHEAST,
            Direction.SOUTH,
            Direction.SOUTHWEST,
            Direction.WEST,
            Direction.NORTHWEST,
    };

    // ======================================================
    // GLOBAL CONSTANTS
    // ======================================================
    static final UnitType DEFAULT_BUILD_TOWER = UnitType.LEVEL_ONE_MONEY_TOWER;

    // Message types (4 bits enough)
    static final int MSG_DISCOVER = 1;
    static final int MSG_ASSIGN   = 2;
    static final int MSG_DONE     = 3;
    static final int MSG_BUILDING = 4;

    // Ruin statuses for tower memory
    static final int RUIN_DISCOVERED = 1;
    static final int RUIN_BUILDING   = 2;
    static final int RUIN_DONE       = 3;

    // Tower-local ruin memory
    static final int MAX_RUINS = 64;
    static MapLocation[] ruinLocs = new MapLocation[MAX_RUINS];
    static int[] ruinStatus = new int[MAX_RUINS];
    static int[] ruinWorkers = new int[MAX_RUINS];
    static int[] ruinLastUpdate = new int[MAX_RUINS];
    static int ruinCount = 0;

    // Soldier-local state
    static MapLocation assignedRuin = null;
    static MapLocation tentativeRuin = null;
    static MapLocation homeTower = null;
    static int lastReportedRound = -9999;
    static int lastBuildingReportRound = -9999;

    // ======================================================
    // BUG2 PATHFINDING STATE (per-robot copy)
    // ======================================================
    static MapLocation target = null;
    static MapLocation prevDest = null;
    static HashSet<MapLocation> line = null;
    static boolean isTracing = false;
    static int obstacleStarDist = 0;
    static Direction tracingDirection = Direction.NORTH;

    @SuppressWarnings("unused")
    public static void run(RobotController rc) throws GameActionException {
        while (true) {
            try {
                switch (rc.getType()) {
                    case SOLDIER:
                        runSoldier(rc);
                        break;
                    default:
                        runTower(rc);
                        break;
                }
            } catch (GameActionException e) {
                System.out.println("GameActionException");
                e.printStackTrace();
            } catch (Exception e) {
                System.out.println("Exception");
                e.printStackTrace();
            } finally {
                Clock.yield();
            }
        }
    }

    // ======================================================
    // TOWER LOGIC
    // ======================================================
    static void runTower(RobotController rc) throws GameActionException {
        int round = rc.getRoundNum();

        // 1. Read incoming messages and update ruin DB
        Message[] messages = rc.readMessages(-1);
        for (Message m : messages) {
            int msg = m.getBytes();
            int type = decodeType(msg);
            MapLocation loc = decodeLoc(msg);

            if (!isValidEncodedLoc(loc)) continue;

            if (type == MSG_DISCOVER) {
                addOrUpdateRuin(loc, RUIN_DISCOVERED, round);
                maybeBroadcast(rc, msg);
            } else if (type == MSG_BUILDING) {
                addOrUpdateRuin(loc, RUIN_BUILDING, round);
            } else if (type == MSG_DONE) {
                int idx = findRuinIndex(loc);
                addOrUpdateRuin(loc, RUIN_DONE, round);
                if (idx != -1) ruinWorkers[idx] = 0;
                maybeBroadcast(rc, msg);
            }
        }

        // 2. Cleanup stale assignments
        cleanupStaleRuinData(rc);

        // 3. Upgrade if possible and reasonable
        tryUpgradeSelfTower(rc);

        // 4. Build SOLDIER only
        tryBuildSoldier(rc);

        // 5. Assign best ruin to reachable soldiers
        assignRuinToNearbySoldiers(rc);

        // 6. Attack enemy if possible
        tryTowerAttack(rc);

        rc.setIndicatorString("ruins=" + ruinCount);
    }

    static void tryUpgradeSelfTower(RobotController rc) throws GameActionException {
        if (rc.canUpgradeTower(rc.getLocation())) {
            // Simple policy:
            // upgrade whenever affordable/possible, since strategy is tower snowball.
            rc.upgradeTower(rc.getLocation());
        }
    }

    static void tryBuildSoldier(RobotController rc) throws GameActionException {
        Direction[] order = shuffledDirections();
        for (Direction dir : order) {
            MapLocation spawn = rc.getLocation().add(dir);
            if (rc.canBuildRobot(UnitType.SOLDIER, spawn)) {
                rc.buildRobot(UnitType.SOLDIER, spawn);
                return;
            }
        }

        // also try two-step positions because spawn radius is sqrt(4)
        for (Direction dir : order) {
            MapLocation spawn = rc.getLocation().add(dir).add(dir);
            if (rc.canBuildRobot(UnitType.SOLDIER, spawn)) {
                rc.buildRobot(UnitType.SOLDIER, spawn);
                return;
            }
        }
    }

    static void assignRuinToNearbySoldiers(RobotController rc) throws GameActionException {
        int bestIdx = chooseBestRuinIndex(rc);
        if (bestIdx == -1) return;

        int msg = encodeMessage(MSG_ASSIGN, ruinLocs[bestIdx].x, ruinLocs[bestIdx].y);
        RobotInfo[] allies = rc.senseNearbyRobots(-1, rc.getTeam());

        // Assign to at most 2 soldiers if still under worker cap
        for (RobotInfo ally : allies) {
            if (ruinWorkers[bestIdx] >= 2) break;
            if (ally.getType() != UnitType.SOLDIER) continue;

            MapLocation loc = ally.getLocation();
            if (rc.canSendMessage(loc, msg)) {
                rc.sendMessage(loc, msg);
                ruinWorkers[bestIdx]++;
                ruinLastUpdate[bestIdx] = rc.getRoundNum();
            }
        }
    }

    static int chooseBestRuinIndex(RobotController rc) {
        MapLocation towerLoc = rc.getLocation();
        int round = rc.getRoundNum();

        int bestIdx = -1;
        int bestScore = Integer.MIN_VALUE;

        for (int i = 0; i < ruinCount; i++) {
            if (ruinLocs[i] == null) continue;
            if (ruinStatus[i] == RUIN_DONE) continue;
            if (ruinWorkers[i] >= 2) continue;

            int score = 0;

            if (ruinStatus[i] == RUIN_BUILDING) score += 1000;
            else if (ruinStatus[i] == RUIN_DISCOVERED) score += 200;

            int freshness = round - ruinLastUpdate[i];
            if (freshness <= 10) score += 100;
            else if (freshness <= 20) score += 50;

            score -= 100 * ruinWorkers[i];
            score -= towerLoc.distanceSquaredTo(ruinLocs[i]);

            if (score > bestScore) {
                bestScore = score;
                bestIdx = i;
            }
        }
        return bestIdx;
    }

    static void cleanupStaleRuinData(RobotController rc) {
        int round = rc.getRoundNum();
        for (int i = 0; i < ruinCount; i++) {
            if (ruinLocs[i] == null) continue;

            // Release worker claims if too old
            if (round - ruinLastUpdate[i] > 30) {
                ruinWorkers[i] = 0;
                if (ruinStatus[i] == RUIN_BUILDING) {
                    ruinStatus[i] = RUIN_DISCOVERED;
                }
            }
        }
    }

    static void tryTowerAttack(RobotController rc) throws GameActionException {
        RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
        for (RobotInfo enemy : enemies) {
            if (rc.canAttack(enemy.getLocation())) {
                rc.attack(enemy.getLocation());
                return;
            }
        }

        // Optional AoE if tower can do it this turn.
        try {
            if (rc.canAttack(null)) {
                rc.attack(null);
            }
        } catch (Exception ignored) {
        }
    }

    static void maybeBroadcast(RobotController rc, int msg) throws GameActionException {
        if (rc.canBroadcastMessage()) {
            rc.broadcastMessage(msg);
        }
    }

    // ======================================================
    // SOLDIER LOGIC
    // ======================================================
    static void runSoldier(RobotController rc) throws GameActionException {
        // 1. Read messages from tower
        readSoldierMessages(rc);

        // 2. Update nearest home tower if visible
        homeTower = findNearestReachableTowerForMessaging(rc);

        // 3. Sense nearby ruins
        MapLocation visibleRuin = findVisibleUntoweredRuin(rc);

        if (visibleRuin != null) {
            if (assignedRuin == null) {
                tentativeRuin = visibleRuin;
            }
            // Report discovery sometimes
            if (rc.getRoundNum() - lastReportedRound >= 5) {
                tryReportToTower(rc, MSG_DISCOVER, visibleRuin);
            }
        }

        // 4. Choose target
        MapLocation myTarget = null;
        if (assignedRuin != null) myTarget = assignedRuin;
        else if (tentativeRuin != null) myTarget = tentativeRuin;

        // 5. No target -> explore
        if (myTarget == null) {
            randomExplore(rc);
            rc.setIndicatorString("exploring");
            return;
        }

        // 6. If target no longer valid, clear it
        if (!isPotentiallyValidRuinTarget(rc, myTarget)) {
            if (assignedRuin != null && assignedRuin.equals(myTarget)) assignedRuin = null;
            if (tentativeRuin != null && tentativeRuin.equals(myTarget)) tentativeRuin = null;
            randomExplore(rc);
            return;
        }

        // 7. Go toward ruin
        rc.setIndicatorString("target=" + myTarget.x + "," + myTarget.y);
        if (rc.getLocation().distanceSquaredTo(myTarget) > 8) {
            pathFinding(rc, myTarget);
            return;
        }

        // 8. Near ruin: report building
        if (rc.getRoundNum() - lastBuildingReportRound >= 5) {
            tryReportToTower(rc, MSG_BUILDING, myTarget);
            lastBuildingReportRound = rc.getRoundNum();
        }

        // 9. Work on pattern / complete tower
        if (workOnRuin(rc, myTarget)) {
            return;
        }

        // 10. Reposition around ruin if needed
        pathFinding(rc, myTarget);
    }

    static void readSoldierMessages(RobotController rc) throws GameActionException {
        Message[] messages = rc.readMessages(-1);
        for (Message m : messages) {
            int msg = m.getBytes();
            int type = decodeType(msg);
            if (type != MSG_ASSIGN) continue;

            MapLocation loc = decodeLoc(msg);
            if (!isValidEncodedLoc(loc)) continue;

            assignedRuin = loc;
            tentativeRuin = null;

            // Reset pathfinding state when target changes
            resetPathfindingForNewTarget();
        }
    }

    static boolean workOnRuin(RobotController rc, MapLocation ruinLoc) throws GameActionException {
        if (!isPotentiallyValidRuinTarget(rc, ruinLoc)) {
            return false;
        }

        // Mark pattern if possible
        if (rc.canMarkTowerPattern(DEFAULT_BUILD_TOWER, ruinLoc)) {
            rc.markTowerPattern(DEFAULT_BUILD_TOWER, ruinLoc);
        }

        // Paint only pattern tiles around ruin
        MapInfo[] patternTiles = rc.senseNearbyMapInfos(ruinLoc, 8);
        for (MapInfo tile : patternTiles) {
            PaintType mark = tile.getMark();
            PaintType paint = tile.getPaint();

            if (mark != PaintType.EMPTY && paint != mark) {
                boolean useSecondary = (mark == PaintType.ALLY_SECONDARY);
                MapLocation tileLoc = tile.getMapLocation();

                if (rc.canAttack(tileLoc)) {
                    rc.attack(tileLoc, useSecondary);
                    return true;
                }
            }
        }

        // Complete tower if possible
        if (rc.canCompleteTowerPattern(DEFAULT_BUILD_TOWER, ruinLoc)) {
            rc.completeTowerPattern(DEFAULT_BUILD_TOWER, ruinLoc);
            tryReportToTower(rc, MSG_DONE, ruinLoc);
            assignedRuin = null;
            tentativeRuin = null;
            resetPathfindingForNewTarget();
            try {
                rc.setTimelineMarker("Tower built", 0, 255, 0);
            } catch (Exception ignored) {}
            return true;
        }

        return false;
    }

    static void randomExplore(RobotController rc) throws GameActionException {
        Direction[] order = shuffledDirections();
        for (Direction d : order) {
            if (rc.canMove(d)) {
                rc.move(d);
                return;
            }
        }
    }

    static MapLocation findVisibleUntoweredRuin(RobotController rc) throws GameActionException {
        MapInfo[] infos = rc.senseNearbyMapInfos();
        MapLocation myLoc = rc.getLocation();

        MapLocation best = null;
        int bestDist = Integer.MAX_VALUE;

        for (MapInfo info : infos) {
            if (!info.hasRuin()) continue;

            MapLocation loc = info.getMapLocation();

            // If there's already a robot/tower on the ruin, ignore it
            if (rc.canSenseRobotAtLocation(loc)) {
                RobotInfo bot = rc.senseRobotAtLocation(loc);
                if (bot != null && isTower(bot.getType())) continue;
            }

            int d = myLoc.distanceSquaredTo(loc);
            if (d < bestDist) {
                bestDist = d;
                best = loc;
            }
        }
        return best;
    }

    static boolean tryReportToTower(RobotController rc, int type, MapLocation loc) throws GameActionException {
        if (loc == null) return false;

        int msg = encodeMessage(type, loc.x, loc.y);
        RobotInfo[] allies = rc.senseNearbyRobots(-1, rc.getTeam());

        MapLocation bestTower = null;
        int bestDist = Integer.MAX_VALUE;

        for (RobotInfo ally : allies) {
            if (!isTower(ally.getType())) continue;
            MapLocation towerLoc = ally.getLocation();

            if (rc.canSendMessage(towerLoc, msg)) {
                int d = rc.getLocation().distanceSquaredTo(towerLoc);
                if (d < bestDist) {
                    bestDist = d;
                    bestTower = towerLoc;
                }
            }
        }

        if (bestTower != null) {
            rc.sendMessage(bestTower, msg);
            lastReportedRound = rc.getRoundNum();
            return true;
        }

        return false;
    }

    static MapLocation findNearestReachableTowerForMessaging(RobotController rc) throws GameActionException {
        RobotInfo[] allies = rc.senseNearbyRobots(-1, rc.getTeam());
        MapLocation myLoc = rc.getLocation();
        MapLocation best = null;
        int bestDist = Integer.MAX_VALUE;

        for (RobotInfo ally : allies) {
            if (!isTower(ally.getType())) continue;
            MapLocation loc = ally.getLocation();

            int d = myLoc.distanceSquaredTo(loc);
            if (d < bestDist) {
                bestDist = d;
                best = loc;
            }
        }
        return best;
    }

    // ======================================================
    // RUIN DATABASE HELPERS (TOWER SIDE)
    // ======================================================
    static int findRuinIndex(MapLocation loc) {
        if (loc == null) return -1;
        for (int i = 0; i < ruinCount; i++) {
            if (ruinLocs[i] != null && ruinLocs[i].equals(loc)) {
                return i;
            }
        }
        return -1;
    }

    static void addOrUpdateRuin(MapLocation loc, int status, int round) {
        if (loc == null) return;

        int idx = findRuinIndex(loc);
        if (idx == -1) {
            if (ruinCount >= MAX_RUINS) return;
            ruinLocs[ruinCount] = loc;
            ruinStatus[ruinCount] = status;
            ruinWorkers[ruinCount] = 0;
            ruinLastUpdate[ruinCount] = round;
            ruinCount++;
            return;
        }

        ruinLastUpdate[idx] = round;

        // Priority: DONE > BUILDING > DISCOVERED
        if (status == RUIN_DONE) {
            ruinStatus[idx] = RUIN_DONE;
            ruinWorkers[idx] = 0;
        } else if (status == RUIN_BUILDING) {
            if (ruinStatus[idx] != RUIN_DONE) {
                ruinStatus[idx] = RUIN_BUILDING;
            }
        } else if (status == RUIN_DISCOVERED) {
            if (ruinStatus[idx] != RUIN_DONE && ruinStatus[idx] != RUIN_BUILDING) {
                ruinStatus[idx] = RUIN_DISCOVERED;
            }
        }
    }

    // ======================================================
    // MESSAGE ENCODING
    // ======================================================
    static int encodeMessage(int type, int x, int y) {
        return (type << 12) | (x << 6) | y;
    }

    static int decodeType(int msg) {
        return (msg >> 12) & 0xF;
    }

    static int decodeX(int msg) {
        return (msg >> 6) & 0x3F;
    }

    static int decodeY(int msg) {
        return msg & 0x3F;
    }

    static MapLocation decodeLoc(int msg) {
        return new MapLocation(decodeX(msg), decodeY(msg));
    }

    static boolean isValidEncodedLoc(MapLocation loc) {
        return loc != null && loc.x >= 0 && loc.x < 64 && loc.y >= 0 && loc.y < 64;
    }

    // ======================================================
    // TOWER / TARGET VALIDATION
    // ======================================================
    static boolean isTower(UnitType t) {
        return t == UnitType.LEVEL_ONE_MONEY_TOWER
                || t == UnitType.LEVEL_TWO_MONEY_TOWER
                || t == UnitType.LEVEL_THREE_MONEY_TOWER
                || t == UnitType.LEVEL_ONE_PAINT_TOWER
                || t == UnitType.LEVEL_TWO_PAINT_TOWER
                || t == UnitType.LEVEL_THREE_PAINT_TOWER
                || t == UnitType.LEVEL_ONE_DEFENSE_TOWER
                || t == UnitType.LEVEL_TWO_DEFENSE_TOWER
                || t == UnitType.LEVEL_THREE_DEFENSE_TOWER;
    }

    static boolean isPotentiallyValidRuinTarget(RobotController rc, MapLocation ruin) throws GameActionException {
        if (ruin == null) return false;

        // If we cannot sense it yet, assume it's still valid.
        if (!rc.canSenseLocation(ruin)) return true;

        MapInfo info = rc.senseMapInfo(ruin);
        if (!info.hasRuin()) return false;

        if (rc.canSenseRobotAtLocation(ruin)) {
            RobotInfo bot = rc.senseRobotAtLocation(ruin);
            if (bot != null && isTower(bot.getType())) return false;
        }

        return true;
    }

    // ======================================================
    // BUG2 PATHFINDING ALGORITHM (BY USER)
    // ======================================================
    public static void pathFinding(RobotController rc, MapLocation dest) throws GameActionException {
        if (dest == null) return;
        if (rc.getLocation().equals(dest)) return;

        // Reset state if target changes
        if (target == null || !target.equals(dest)) {
            target = dest;
            prevDest = target;
            line = createLine(target, rc.getLocation());
            isTracing = false;
        }

        if (!isTracing) {
            Direction dir = rc.getLocation().directionTo(target);
            if (dir != Direction.CENTER && rc.canMove(dir)) {
                rc.move(dir);
            } else {
                isTracing = true;
                obstacleStarDist = rc.getLocation().distanceSquaredTo(target);
                tracingDirection = dir;
            }
        } else {
            if (line != null
                    && line.contains(rc.getLocation())
                    && rc.getLocation().distanceSquaredTo(target) < obstacleStarDist) {
                isTracing = false;
            } else {
                if (tracingDirection != Direction.CENTER && rc.canMove(tracingDirection)) {
                    rc.move(tracingDirection);
                    tracingDirection = tracingDirection.rotateRight().rotateRight();
                } else {
                    for (int i = 0; i < 8; i++) {
                        tracingDirection = tracingDirection.rotateLeft();
                        if (tracingDirection != Direction.CENTER && rc.canMove(tracingDirection)) {
                            rc.move(tracingDirection);
                            tracingDirection = tracingDirection.rotateRight().rotateRight();
                            break;
                        }
                    }
                }
            }
        }
    }

    public static HashSet<MapLocation> createLine(MapLocation a, MapLocation b) {
        HashSet<MapLocation> locs = new HashSet<>();
        int x = a.x, y = a.y;
        int dx = b.x - a.x;
        int dy = b.y - a.y;
        int sx = (int) Math.signum(dx);
        int sy = (int) Math.signum(dy);
        dx = Math.abs(dx);
        dy = Math.abs(dy);
        int d = Math.max(dx, dy);
        int r = d / 2;

        if (dx > dy) {
            for (int i = 0; i < d; i++) {
                locs.add(new MapLocation(x, y));
                x += sx;
                r += dy;
                if (r >= dx) {
                    locs.add(new MapLocation(x, y));
                    y += sy;
                    r -= dx;
                }
            }
        } else {
            for (int i = 0; i < d; i++) {
                locs.add(new MapLocation(x, y));
                y += sy;
                r += dx;
                if (r >= dy) {
                    locs.add(new MapLocation(x, y));
                    x += sx;
                    r -= dy;
                }
            }
        }

        locs.add(new MapLocation(x, y));
        return locs;
    }

    static void resetPathfindingForNewTarget() {
        target = null;
        prevDest = null;
        line = null;
        isTracing = false;
        obstacleStarDist = 0;
        tracingDirection = Direction.NORTH;
    }

    // ======================================================
    // RANDOM / UTILS
    // ======================================================
    static Direction[] shuffledDirections() {
        Direction[] arr = directions.clone();
        for (int i = 0; i < arr.length; i++) {
            int j = rng.nextInt(arr.length);
            Direction tmp = arr[i];
            arr[i] = arr[j];
            arr[j] = tmp;
        }
        return arr;
    }
}
