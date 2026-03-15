package main_bot;

import battlecode.common.*;
import java.util.Random;

public class RobotPlayer {

    static int turnCount = 0;
    static int spawnCounter = 0;

    static final Random rng = new Random(6147);

    static Direction myDir;
    static MapLocation lastLoc;

    public static void run(RobotController rc) throws GameActionException {
        myDir = Direction.allDirections()[rng.nextInt(8)];

        while (true) {
            turnCount++;

            try {
                switch (rc.getType()) {
                    case SOLDIER:
                        runSoldier(rc);
                        break;

                    case SPLASHER:
                        runSplasher(rc);
                        break;

                    default:
                        runTower(rc);
                        break;
                }
            } catch (Exception e) {
                e.printStackTrace();
            }

            Clock.yield();
        }
    }

    static void runTower(RobotController rc) throws GameActionException {
        if (!rc.isActionReady()) return;

        if (rc.getRoundNum() > 300 && rc.canUpgradeTower(rc.getLocation())) {
            rc.upgradeTower(rc.getLocation());
        }

        RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
        if (enemies.length > 0 && rc.canAttack(enemies[0].getLocation())) {
            rc.attack(enemies[0].getLocation());
        }

        UnitType build;

        if (rc.getRoundNum() < 400) {
            build = (spawnCounter % 4 == 3) ? UnitType.SPLASHER : UnitType.SOLDIER;
        } else if (rc.getRoundNum() <= 800) {
            build = (spawnCounter % 3 == 2) ? UnitType.SOLDIER : UnitType.SPLASHER;
        } else if (rc.getNumberTowers() < 25) {
            build = (spawnCounter % 6 == 1) ? UnitType.SOLDIER : UnitType.SPLASHER;
        } else {
            build = UnitType.SPLASHER;
        }

        for (Direction d : Direction.allDirections()) {
            MapLocation spawn = rc.getLocation().add(d);

            if (rc.canBuildRobot(build, spawn)) {
                rc.buildRobot(build, spawn);
                spawnCounter++;
                return;
            }
        }
    }

    static void runSoldier(RobotController rc) throws GameActionException {
        if (rc.getPaint() < 100 && rc.isActionReady()) {
            RobotInfo[] allies = rc.senseNearbyRobots(2, rc.getTeam());

            for (RobotInfo ally : allies) {
                if (ally.getType().isTowerType() &&
                    rc.canTransferPaint(ally.getLocation(), -100)) {
                    rc.transferPaint(ally.getLocation(), -100);
                    break;
                }
            }
        }

        MapLocation ruin = null;

        for (MapInfo tile : rc.senseNearbyMapInfos()) {
            if (!tile.hasRuin()) continue;

            RobotInfo r = rc.senseRobotAtLocation(tile.getMapLocation());

            if (r == null || !r.getType().isTowerType()) {
                ruin = tile.getMapLocation();
                break;
            }
        }

        if (ruin != null) {
            if (rc.getLocation().distanceSquaredTo(ruin) > 4) {
                if (rc.isMovementReady()) {
                    greedyMove(rc, ruin, true);
                }
                return;
            }

            UnitType tower = (rc.getNumberTowers() % 2 == 0)
                    ? UnitType.LEVEL_ONE_MONEY_TOWER
                    : UnitType.LEVEL_ONE_PAINT_TOWER;

            if (rc.canMarkTowerPattern(tower, ruin)) {
                rc.markTowerPattern(tower, ruin);
            }

            for (MapInfo tile : rc.senseNearbyMapInfos(ruin, 8)) {
                if (tile.getMark() != PaintType.EMPTY &&
                    tile.getMark() != tile.getPaint()) {

                    if (!tile.getPaint().isEnemy() &&
                        rc.canAttack(tile.getMapLocation())) {

                        rc.attack(
                            tile.getMapLocation(),
                            tile.getMark() == PaintType.ALLY_SECONDARY
                        );
                        return;
                    }
                }
            }

            if (rc.canCompleteTowerPattern(tower, ruin)) {
                rc.completeTowerPattern(tower, ruin);
                return;
            }

            if (rc.isMovementReady()) {
                greedyMove(rc, ruin, true);
            }

            return;
        }

        if (rc.isActionReady() && rc.canAttack(rc.getLocation())) {
            MapInfo me = rc.senseMapInfo(rc.getLocation());

            if (!me.getPaint().isAlly() && !me.getPaint().isEnemy()) {
                rc.attack(rc.getLocation());
            }
        }

        if (rc.isMovementReady()) {
            greedyExplore(rc, true);
        }
    }

    static void runSplasher(RobotController rc) throws GameActionException {
        MapLocation bestTarget = null;
        int bestScore = -999999;

        for (MapInfo tile : rc.senseNearbyMapInfos()) {
            if (!tile.getPaint().isEnemy()) continue;

            MapLocation loc = tile.getMapLocation();
            int dist = rc.getLocation().distanceSquaredTo(loc);

            int enemyCluster = 0;
            for (MapInfo near : rc.senseNearbyMapInfos(loc, 2)) {
                if (near.getPaint().isEnemy()) {
                    enemyCluster++;
                }
            }

            int score = enemyCluster * 15 - dist * 3 + rng.nextInt(5);

            if (score > bestScore) {
                bestScore = score;
                bestTarget = loc;
            }
        }

        if (bestTarget != null) {
            if (rc.isActionReady() && rc.canAttack(bestTarget)) {
                rc.attack(bestTarget);
            }

            if (rc.isMovementReady()) {
                greedyMoveSplasher(rc, bestTarget);
            }

            return;
        }

        if (rc.isActionReady() && rc.canAttack(rc.getLocation())) {
            if (!rc.senseMapInfo(rc.getLocation()).getPaint().isAlly()) {
                rc.attack(rc.getLocation());
            }
        }

        if (rc.isMovementReady()) {
            greedyMoveSplasher(rc, null);
        }
    }

    static void greedyMoveSplasher(RobotController rc, MapLocation target)
            throws GameActionException {

        MapLocation myLoc = rc.getLocation();
        Direction bestDir = null;
        int bestScore = -999999;

        for (Direction dir : Direction.allDirections()) {
            if (!rc.canMove(dir)) continue;

            MapLocation next = myLoc.add(dir);
            int score = 0;

            RobotInfo[] alliesNear = rc.senseNearbyRobots(next, 2, rc.getTeam());
            score -= alliesNear.length * 100;

            if (target != null) {
                score -= next.distanceSquaredTo(target) * 10;
            } else {
                PaintType p = rc.senseMapInfo(next).getPaint();

                if (p == PaintType.EMPTY) score += 20;
                if (p.isEnemy()) score += 40;
                if (dir == myDir) score += 5;
            }

            if (lastLoc != null && next.equals(lastLoc)) score -= 30;

            score += rng.nextInt(5);

            if (score > bestScore) {
                bestScore = score;
                bestDir = dir;
            }
        }

        if (bestDir != null) {
            lastLoc = myLoc;
            myDir = bestDir;
            rc.move(bestDir);
        }
    }

    static void greedyExplore(RobotController rc, boolean avoidEnemy)
            throws GameActionException {

        MapLocation myLoc = rc.getLocation();
        Direction bestDir = null;
        int bestScore = -999999;

        for (Direction dir : Direction.allDirections()) {
            if (!rc.canMove(dir)) continue;

            MapLocation next = myLoc.add(dir);
            int score = 0;

            if (dir == myDir) score += 15;
            if (lastLoc != null && next.equals(lastLoc)) score -= 30;

            PaintType paint = rc.senseMapInfo(next).getPaint();

            if (paint == PaintType.EMPTY) score += 10;
            if (avoidEnemy && paint.isEnemy()) score -= 1000;
            else if (paint.isEnemy()) score -= 5;

            RobotInfo r = rc.senseRobotAtLocation(next);
            if (r != null && r.getTeam() == rc.getTeam()) score -= 35;

            score += rng.nextInt(3);

            if (score > bestScore) {
                bestScore = score;
                bestDir = dir;
            }
        }

        if (bestDir != null) {
            lastLoc = myLoc;
            myDir = bestDir;
            rc.move(bestDir);
        }
    }

    static void greedyMove(RobotController rc, MapLocation target, boolean avoidEnemy)
            throws GameActionException {

        MapLocation myLoc = rc.getLocation();
        int currentDist = myLoc.distanceSquaredTo(target);

        Direction bestDir = null;
        int bestScore = -999999;

        for (Direction dir : Direction.allDirections()) {
            if (!rc.canMove(dir)) continue;

            MapLocation next = myLoc.add(dir);
            int score = 0;

            int newDist = next.distanceSquaredTo(target);

            score -= newDist * 5;

            if (newDist >= currentDist) score -= 20;
            if (dir == myDir) score += 10;
            if (lastLoc != null && next.equals(lastLoc)) score -= 25;

            PaintType paint = rc.senseMapInfo(next).getPaint();

            if (avoidEnemy && paint.isEnemy()) score -= 1000;
            else if (paint.isEnemy()) score -= 4;

            RobotInfo r = rc.senseRobotAtLocation(next);
            if (r != null && r.getTeam() == rc.getTeam()) score -= 35;

            score += rng.nextInt(4);

            if (score > bestScore) {
                bestScore = score;
                bestDir = dir;
            }
        }

        if (bestDir != null) {
            lastLoc = myLoc;
            myDir = bestDir;
            rc.move(bestDir);
        }
    }
}