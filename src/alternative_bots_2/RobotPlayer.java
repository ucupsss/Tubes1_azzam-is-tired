package alternative_bots_2;
import battlecode.common.*;
import java.util.*;

public class RobotPlayer {
    static int turnCount = 0;
    enum GamePhase { EARLY, MID, LATE }
    static GamePhase currentPhase = GamePhase.EARLY;
    static MapLocation closestRefillTower = null;

    public static void run(RobotController rc) throws GameActionException {
        Pathfinding.initBouncingDir(rc);
        while (true) {
            try {
                turnCount++;
                if (turnCount <= 300) currentPhase = GamePhase.EARLY;
                else if (turnCount <= 1000) currentPhase = GamePhase.MID;
                else currentPhase = GamePhase.LATE;
                
                // THROTTLING: Cek tower terdekat HANYA setiap 10 turn biar hemat bytecode
                if (!rc.getType().isTowerType() && turnCount % 10 == 1) {
                    closestRefillTower = null;
                    RobotInfo[] nearbyAllies = rc.senseNearbyRobots(-1, rc.getTeam());
                    for (RobotInfo ally : nearbyAllies) {
                        if (ally.getType().isTowerType() && (closestRefillTower == null || rc.getLocation().distanceSquaredTo(ally.getLocation()) < rc.getLocation().distanceSquaredTo(closestRefillTower))) {
                            closestRefillTower = ally.getLocation();
                        }
                    }
                }
                
                switch (rc.getType()) {
                    case SOLDIER: SoldierLogic.run(rc); break;
                    case SPLASHER: SplasherLogic.run(rc); break;
                    case MOPPER: MopperLogic.run(rc); break;
                    default: TowerLogic.run(rc); break;
                }
            } catch (Exception e) { 
                e.printStackTrace(); 
            } finally { 
                Clock.yield(); 
            }
        }
    }
}

class Pathfinding {
    static final Direction[] ALL_DIRS = { Direction.NORTH, Direction.NORTHEAST, Direction.EAST, Direction.SOUTHEAST, Direction.SOUTH, Direction.SOUTHWEST, Direction.WEST, Direction.NORTHWEST };
    static final Random rng = new Random(6147);
    static MapLocation bugTarget = null, exploreTarget = null;
    static boolean isTracing = false;
    static Direction tracingDir = null, bouncingDir = null;
    static int obstacleStartDist = 0, exploreVisit = 0, exploreRetargetRound = -9999;
    static HashSet<MapLocation> bugLine = null;

    public static void initBouncingDir(RobotController rc) { bouncingDir = ALL_DIRS[rng.nextInt(8)]; }
    
    public static void moveTowards(RobotController rc, MapLocation dest) throws GameActionException {
        if (!rc.isMovementReady() || dest == null || rc.getLocation().distanceSquaredTo(dest) <= 1) return;
        MapLocation myLoc = rc.getLocation();
        
        if (bugTarget == null || !bugTarget.equals(dest)) { 
            bugTarget = dest; 
            bugLine = createLine(dest, myLoc); 
            isTracing = false; 
        }
        
        if (!isTracing) {
            Direction dir = myLoc.directionTo(dest);
            if (rc.canMove(dir)) {
                rc.move(dir);
            } else { 
                isTracing = true; 
                obstacleStartDist = myLoc.distanceSquaredTo(dest); 
                doTrace(rc, dest); 
            }
        } else {
            if (bugLine.contains(myLoc) && myLoc.distanceSquaredTo(dest) < obstacleStartDist) {
                isTracing = false; 
                if (rc.canMove(myLoc.directionTo(dest))) rc.move(myLoc.directionTo(dest));
            } else {
                doTrace(rc, dest);
            }
        }
    }

    private static void doTrace(RobotController rc, MapLocation dest) throws GameActionException {
        if (tracingDir == null) tracingDir = rc.getLocation().directionTo(dest);
        if (rc.canMove(tracingDir)) { rc.move(tracingDir); tracingDir = tracingDir.rotateRight().rotateRight(); return; }
        for (int i = 0; i < 8; i++) {
            tracingDir = tracingDir.rotateLeft();
            if (rc.canMove(tracingDir)) { rc.move(tracingDir); tracingDir = tracingDir.rotateRight().rotateRight(); return; }
        }
    }

    public static void exploreByPhase(RobotController rc) throws GameActionException {
        if (exploreTarget == null || rc.getLocation().distanceSquaredTo(exploreTarget) <= 9 || rc.getRoundNum() - exploreRetargetRound >= 18) {
            exploreTarget = getSectorTarget(rc, ++exploreVisit); 
            exploreRetargetRound = rc.getRoundNum();
        }
        moveTowards(rc, exploreTarget);
    }

    public static MapLocation getSectorTarget(RobotController rc, int visit) {
        int mapWidth = rc.getMapWidth(), mapHeight = rc.getMapHeight(), robotID = rc.getID();
        if (robotID % 9 == 0) {
            MapLocation[] corners = {new MapLocation(1, 1), new MapLocation(mapWidth - 2, 1), new MapLocation(1, mapHeight - 2), new MapLocation(mapWidth - 2, mapHeight - 2)};
            return corners[Math.floorMod(robotID / 9 + visit, 4)];
        }
        // Grid sektor 4x4
        int sectorWidth = Math.max(1, mapWidth / 4), sectorHeight = Math.max(1, mapHeight / 4), sector = Math.floorMod(robotID * 5 + visit, 16);
        int centerX = (sector % 4) * sectorWidth + sectorWidth / 2, centerY = (sector / 4) * sectorHeight + sectorHeight / 2;
        int jitterX = Math.floorMod(robotID * 17 + visit * 31, Math.max(1, sectorWidth / 2)) - sectorWidth / 4;
        int jitterY = Math.floorMod(robotID * 29 + visit * 47, Math.max(1, sectorHeight / 2)) - sectorHeight / 4;
        return new MapLocation(clamp(centerX + jitterX, 1, mapWidth - 2), clamp(centerY + jitterY, 1, mapHeight - 2));
    }

    public static void paintNetworkTowards(RobotController rc, MapLocation toward, int minRes) throws GameActionException {
        if (!rc.isActionReady() || rc.getPaint() < minRes || toward == null) return;
        MapLocation me = rc.getLocation();
        if (rc.canSenseLocation(me) && rc.senseMapInfo(me).getPaint() == PaintType.EMPTY && rc.canAttack(me)) { 
            rc.attack(me, rc.senseMapInfo(me).getMark() == PaintType.ALLY_SECONDARY); 
            return; 
        }
        Direction dir = me.directionTo(toward); 
        if (dir == Direction.CENTER) return;
        MapLocation ah = me.add(dir);
        if (rc.canSenseLocation(ah) && !rc.senseMapInfo(ah).getPaint().isAlly() && rc.canAttack(ah)) {
            rc.attack(ah, rc.senseMapInfo(ah).getMark() == PaintType.ALLY_SECONDARY);
        }
    }

    static HashSet<MapLocation> createLine(MapLocation a, MapLocation b) {
        HashSet<MapLocation> locs = new HashSet<>();
        int currentX = a.x, currentY = a.y, deltaX = Math.abs(b.x - a.x), deltaY = Math.abs(b.y - a.y);
        int stepX = (int)Math.signum(b.x - a.x), stepY = (int)Math.signum(b.y - a.y);
        int distance = Math.max(deltaX, deltaY), errorTerm = distance / 2;
        for (int i = 0; i < distance; i++) { 
            locs.add(new MapLocation(currentX, currentY)); 
            if (deltaX > deltaY) { 
                currentX += stepX; errorTerm += deltaY; 
                if (errorTerm >= deltaX) { currentY += stepY; errorTerm -= deltaX; locs.add(new MapLocation(currentX, currentY)); } 
            } else { 
                currentY += stepY; errorTerm += deltaX; 
                if (errorTerm >= deltaY) { currentX += stepX; errorTerm -= deltaY; locs.add(new MapLocation(currentX, currentY)); } 
            } 
        }
        locs.add(new MapLocation(currentX, currentY)); return locs;
    }
    
    static int clamp(int value, int lo, int hi) { return Math.max(lo, Math.min(hi, value)); }
}

class MopperLogic {
    enum MopperState { HUNT, SCRUB, DISTRIBUTE, RECHARGE, EXPLORE }
    static MopperState currentState = MopperState.EXPLORE;
    static MapLocation mopperExp;
    static int exploreVisitCount, expRound = -9999;

    public static void run(RobotController rc) throws GameActionException {
        // Sense cuma 1x di awal
        RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
        
        if (enemies.length > 0) {
            currentState = MopperState.HUNT;
        } else if (rc.getPaint() < (RobotPlayer.currentPhase == RobotPlayer.GamePhase.EARLY ? 15 : 25)) {
            currentState = MopperState.RECHARGE;
        } else if (RobotPlayer.currentPhase != RobotPlayer.GamePhase.EARLY && rc.getPaint() > 140) {
            currentState = MopperState.DISTRIBUTE;
        } else {
            currentState = MopperState.EXPLORE;
        }
        
        // Scan map info (cuma radius kecil 9) buat cek cat musuh
        if (currentState == MopperState.EXPLORE) {
            for (MapInfo tile : rc.senseNearbyMapInfos(9)) {
                if (tile.getPaint().isEnemy()) { currentState = MopperState.SCRUB; break; }
            }
        }
        
        switch(currentState) {
            case HUNT: 
                RobotInfo bestEnemy = enemies[0]; 
                for(RobotInfo enemy : enemies) {
                    if (rc.getLocation().distanceSquaredTo(enemy.getLocation()) < rc.getLocation().distanceSquaredTo(bestEnemy.getLocation())) bestEnemy = enemy; 
                }
                Direction swingDir = rc.getLocation().directionTo(bestEnemy.getLocation()); 
                if(rc.isActionReady() && rc.canMopSwing(swingDir)) rc.mopSwing(swingDir); 
                else Pathfinding.moveTowards(rc, bestEnemy.getLocation()); 
                break;
                
            case RECHARGE: 
                if (RobotPlayer.closestRefillTower != null && rc.getLocation().distanceSquaredTo(RobotPlayer.closestRefillTower) > 2) {
                    Pathfinding.moveTowards(rc, RobotPlayer.closestRefillTower); 
                } else if (RobotPlayer.closestRefillTower == null) {
                    Pathfinding.exploreByPhase(rc); 
                }
                break;
                
            case SCRUB: 
                MapLocation closestEnemyPaint = null; 
                int minDist = 9999; 
                for(MapInfo tile : rc.senseNearbyMapInfos(-1)) {
                    if(tile.getPaint().isEnemy() && rc.getLocation().distanceSquaredTo(tile.getMapLocation()) < minDist) {
                        minDist = rc.getLocation().distanceSquaredTo(tile.getMapLocation()); 
                        closestEnemyPaint = tile.getMapLocation();
                    }
                }
                if(closestEnemyPaint != null) { 
                    if(rc.isActionReady()) { 
                        Direction bestSwingDir = null; int maxHit = 0; 
                        for(Direction dir : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) { 
                            if (!rc.canMopSwing(dir)) continue; 
                            int hits = 0; 
                            for(MapLocation loc : new MapLocation[]{rc.getLocation().add(dir), rc.getLocation().add(dir).add(dir), rc.getLocation().add(dir).add(dir.rotateLeft()), rc.getLocation().add(dir).add(dir.rotateRight())}) {
                                if (rc.canSenseLocation(loc)){ 
                                    RobotInfo bot = rc.senseRobotAtLocation(loc); 
                                    if (bot != null && bot.getTeam() != rc.getTeam()) hits += 2; 
                                    if (rc.senseMapInfo(loc).getPaint().isEnemy()) hits++; 
                                } 
                            }
                            if (hits > maxHit) { maxHit = hits; bestSwingDir = dir; } 
                        } 
                        if (bestSwingDir != null && maxHit > 0) { rc.mopSwing(bestSwingDir); return; } 
                        
                        for (MapInfo tile : rc.senseNearbyMapInfos(2)) {
                            if (tile.getPaint().isEnemy() && rc.canAttack(tile.getMapLocation())) {
                                rc.attack(tile.getMapLocation()); 
                                return;
                            }
                        } 
                    } 
                    Pathfinding.moveTowards(rc, closestEnemyPaint); 
                }
                break;
                
            case DISTRIBUTE: 
                RobotInfo[] allies = rc.senseNearbyRobots(-1, rc.getTeam());
                for(RobotInfo ally : allies) {
                    if (!ally.getType().isTowerType() && ally.getPaintAmount() < ally.getType().paintCapacity * 0.5) { 
                        if (rc.getLocation().distanceSquaredTo(ally.getLocation()) <= 2 && rc.canTransferPaint(ally.getLocation(), 20)) {
                            rc.transferPaint(ally.getLocation(), 20); 
                        } else {
                            Pathfinding.moveTowards(rc, ally.getLocation()); 
                        }
                        return; 
                    } 
                }
                Pathfinding.exploreByPhase(rc);
                break;
                
            case EXPLORE: 
                if(rc.isActionReady()) {
                    for(MapInfo tile : rc.senseNearbyMapInfos()) {
                        if(tile.getPaint().isEnemy() && rc.canAttack(tile.getMapLocation())) {
                            rc.attack(tile.getMapLocation()); 
                            break;
                        }
                    }
                }
                Pathfinding.exploreByPhase(rc);
                break;
        }
    }
}

class SoldierLogic {
    enum SoldierState { REFILL, FLEE, BUILD, EXPLORE }
    static MapLocation committedRuin, lastLocation;
    static UnitType committedType;
    static int committedRound = -1;
    static Direction currentNavDir = Direction.NORTH;

    public static void run(RobotController rc) throws GameActionException {
        // Sense cuma 1x di awal
        RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
        MapLocation[] ruins = rc.senseNearbyRuins(-1);
        
        if(committedRuin != null && rc.canSenseRobotAtLocation(committedRuin) && rc.senseRobotAtLocation(committedRuin).getType().isTowerType()) {
            committedRuin = null; committedType = null; committedRound = -1;
        }
        
        SoldierState state = SoldierState.EXPLORE; 
        if (rc.getPaint() <= 50) {
            state = SoldierState.REFILL; 
        } else { 
            boolean needsToFlee = false; 
            for(RobotInfo enemy : enemies) {
                if (enemy.getType() == UnitType.MOPPER) needsToFlee = true;
            }
            
            if (needsToFlee) state = SoldierState.FLEE;
            else if (getBuildTarget(rc, ruins) != null) state = SoldierState.BUILD;
            else state = SoldierState.EXPLORE;
        }
        
        switch(state) {
            case REFILL: 
                if (RobotPlayer.closestRefillTower != null && rc.getLocation().distanceSquaredTo(RobotPlayer.closestRefillTower) > 2) {
                    Pathfinding.moveTowards(rc, RobotPlayer.closestRefillTower); 
                } else if (RobotPlayer.closestRefillTower == null) {
                    Pathfinding.exploreByPhase(rc); 
                }
                break;
                
            case FLEE: 
                int avgX = 0, avgY = 0, mopperCount = 0; 
                for(RobotInfo enemy : enemies) {
                    if (enemy.getType() == UnitType.MOPPER) { 
                        avgX += enemy.getLocation().x; 
                        avgY += enemy.getLocation().y; 
                        mopperCount++; 
                    }
                } 
                if (mopperCount > 0) { 
                    Direction awayDir = rc.getLocation().directionTo(new MapLocation(avgX/mopperCount, avgY/mopperCount)).opposite(); 
                    Pathfinding.moveTowards(rc, rc.getLocation().add(awayDir).add(awayDir).add(awayDir)); 
                } 
                break;
                
            case BUILD: 
                handleBuild(rc, ruins); 
                break;
                
            case EXPLORE: 
                if (rc.isActionReady()) greedyPaint(rc); 
                if (rc.isMovementReady()) Pathfinding.exploreByPhase(rc); 
                break;
        }
    }
    
    static void handleBuild(RobotController rc, MapLocation[] ruins) throws GameActionException {
        MapLocation ruinLoc = getBuildTarget(rc, ruins); 
        if (ruinLoc == null) { 
            if (rc.isActionReady()) greedyPaint(rc); 
            if (rc.isMovementReady()) Pathfinding.exploreByPhase(rc); 
            return; 
        }
        if (committedRuin == null || !committedRuin.equals(ruinLoc)) { 
            committedRuin = ruinLoc; committedRound = rc.getRoundNum(); 
        }
        
        int myDist = rc.getLocation().distanceSquaredTo(ruinLoc); 
        boolean isPrimary = true; 
        for(RobotInfo ally : rc.senseNearbyRobots(ruinLoc, 8, rc.getTeam())) {
            if (ally.getType() == UnitType.SOLDIER && ally.getID() != rc.getID() && (ally.getLocation().distanceSquaredTo(ruinLoc) < myDist || (ally.getLocation().distanceSquaredTo(ruinLoc) == myDist && ally.getID() < rc.getID()))) {
                isPrimary = false;
            }
        }
        
        if (!isPrimary && RobotPlayer.currentPhase != RobotPlayer.GamePhase.LATE) { 
            int offsetX = Integer.signum(rc.getLocation().x - ruinLoc.x);
            int offsetY = Integer.signum(rc.getLocation().y - ruinLoc.y);
            if (offsetX == 0) { if (rc.getID() % 2 == 0) offsetX = 1; else offsetX = -1; }
            if (offsetY == 0) { if (rc.getID() % 3 == 0) offsetY = 1; else offsetY = -1; }
            if (rc.isMovementReady()) {
                greedyMove(rc, new MapLocation(Pathfinding.clamp(rc.getLocation().x + offsetX * 4, 1, rc.getMapWidth() - 2), Pathfinding.clamp(rc.getLocation().y + offsetY * 4, 1, rc.getMapHeight() - 2))); 
            }
            if (rc.isActionReady()) greedyPaint(rc); 
            return; 
        }
        
        if (committedType == null) { 
            int paintTowerCount = 0, moneyTowerCount = 0; 
            for(RobotInfo ally : rc.senseNearbyRobots(-1, rc.getTeam())) {
                if (ally.getType().toString().contains("PAINT")) paintTowerCount++; 
                else if (ally.getType().toString().contains("MONEY")) moneyTowerCount++; 
            }
            if (paintTowerCount <= moneyTowerCount) committedType = UnitType.LEVEL_ONE_PAINT_TOWER;
            else committedType = UnitType.LEVEL_ONE_MONEY_TOWER;
        }
        
        for(UnitType ty : new UnitType[]{committedType, UnitType.LEVEL_ONE_PAINT_TOWER, UnitType.LEVEL_ONE_MONEY_TOWER, UnitType.LEVEL_ONE_DEFENSE_TOWER}) {
            if (ty != null && rc.canCompleteTowerPattern(ty, ruinLoc)) {
                rc.completeTowerPattern(ty, ruinLoc); committedRuin = null; committedType = null; committedRound = -1; 
                return;
            }
        }
        
        boolean isMarked = false, isBlocked = false; 
        for(MapInfo tile : rc.senseNearbyMapInfos(ruinLoc, 8)) { 
            if (tile.getMark().isAlly()) isMarked = true; 
            if (tile.getMark().isAlly() && tile.getPaint().isEnemy()) isBlocked = true; 
        }
        
        if (!isMarked) { 
            if (rc.canMarkTowerPattern(committedType, ruinLoc)) rc.markTowerPattern(committedType, ruinLoc); 
            else if (rc.isMovementReady()) greedyMove(rc, ruinLoc); 
            return; 
        }
        
        if (rc.getLocation().distanceSquaredTo(ruinLoc) > 8) { 
            if (rc.isMovementReady()) greedyMove(rc, ruinLoc); 
            Pathfinding.paintNetworkTowards(rc, ruinLoc, 30); 
            return; 
        }
        
        MapLocation unpaintedLoc = null; int bestDist = 999; 
        for(MapInfo tile : rc.senseNearbyMapInfos(ruinLoc, 8)) {
            if (tile.getMark().isAlly() && (tile.getPaint() == PaintType.EMPTY || (tile.getPaint().isAlly() && tile.getPaint().isSecondary() != tile.getMark().isSecondary())) && rc.getLocation().distanceSquaredTo(tile.getMapLocation()) < bestDist) {
                bestDist = rc.getLocation().distanceSquaredTo(tile.getMapLocation()); 
                unpaintedLoc = tile.getMapLocation();
            }
        }
        
        if (unpaintedLoc != null) { 
            if (rc.isActionReady() && rc.canAttack(unpaintedLoc)) rc.attack(unpaintedLoc, rc.senseMapInfo(unpaintedLoc).getMark().isSecondary()); 
            else if (rc.isMovementReady()) greedyMove(rc, unpaintedLoc); 
            return; 
        }
        
        if (isBlocked) { 
            if (rc.canSenseLocation(rc.getLocation()) && !rc.senseMapInfo(rc.getLocation()).getPaint().isEnemy() && rc.isActionReady()) {
                greedyPaint(rc); 
            } else { 
                Direction safeMove = null; int bestScore = -999; 
                for(Direction direction : Pathfinding.ALL_DIRS) {
                    if (rc.canMove(direction) && rc.canSenseLocation(rc.getLocation().add(direction)) && -rc.getLocation().add(direction).distanceSquaredTo(ruinLoc) > bestScore) {
                        bestScore = -rc.getLocation().add(direction).distanceSquaredTo(ruinLoc); 
                        safeMove = direction;
                    }
                }
                if (safeMove != null) rc.move(safeMove); 
            } 
            return; 
        }
        
        if (rc.getLocation().distanceSquaredTo(ruinLoc) > 2 && rc.isMovementReady()) greedyMove(rc, ruinLoc);
    }
    
    static MapLocation getBuildTarget(RobotController rc, MapLocation[] ruins) throws GameActionException {
        if (committedRuin != null && (!rc.canSenseRobotAtLocation(committedRuin) || !rc.senseRobotAtLocation(committedRuin).getType().isTowerType())) return committedRuin;
        MapLocation bestRuin = null; int minDist = 9999; 
        for(MapLocation ruin : ruins) {
            if (!rc.canSenseRobotAtLocation(ruin) || !rc.senseRobotAtLocation(ruin).getType().isTowerType()) { 
                int dist = rc.getLocation().distanceSquaredTo(ruin); 
                if (dist < minDist) { minDist = dist; bestRuin = ruin; } 
            }
        }
        return bestRuin;
    }
    
    static void greedyMove(RobotController rc, MapLocation targ) throws GameActionException {
        Direction bestDir = null; int bestScore = -9999; 
        for(Direction dir : Pathfinding.ALL_DIRS) {
            if (rc.canMove(dir)) { 
                MapLocation nextTile = rc.getLocation().add(dir); 
                int score = -(nextTile.distanceSquaredTo(targ) * 5);
                if (dir == currentNavDir) score += 10;
                if (lastLocation != null && nextTile.equals(lastLocation)) score -= 25;
                if (rc.senseMapInfo(nextTile).getPaint().isEnemy()) score -= 4;
                
                if (score > bestScore) { bestScore = score; bestDir = dir; } 
            }
        }
        if (bestDir != null) { lastLocation = rc.getLocation(); rc.move(currentNavDir = bestDir); }
    }
    
    static void greedyPaint(RobotController rc) throws GameActionException {
        MapLocation bestLoc = null; int bestScore = 0; boolean useSecondary = false; 
        for(MapInfo tile : rc.senseNearbyMapInfos(9)) { 
            if (!rc.canAttack(tile.getMapLocation())) continue; 
            PaintType mark = tile.getMark(), paint = tile.getPaint(); 
            int score = 0; boolean wantsSecondary = false; 
            
            if (mark.isAlly() && paint != mark && !paint.isEnemy()) {
                score = 30; wantsSecondary = mark.isSecondary();
            } else if (paint.isEnemy()) {
                score = 15;
            } else if (paint == PaintType.EMPTY) {
                score = 8;
            }
            
            if (score > bestScore) { bestScore = score; bestLoc = tile.getMapLocation(); useSecondary = wantsSecondary; } 
        } 
        if (bestLoc != null) rc.attack(bestLoc, useSecondary);
    }
}

class SplasherLogic {
    static MapLocation exploreTarget;
    static int exploreVisitCount, expRound = -9999;

    public static void run(RobotController rc) throws GameActionException {
        RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent()); 
        boolean needsToFlee = false; 
        for(RobotInfo enemy : enemies) {
            if (enemy.getType() == UnitType.MOPPER) needsToFlee = true;
        }
        
        // Splasher tidak peduli cat habis, kecuali harus kabur dari mopper
        if (needsToFlee) { 
            int sumX = 0, sumY = 0, mopperCount = 0; 
            for(RobotInfo enemy : enemies) {
                if (enemy.getType() == UnitType.MOPPER) { 
                    sumX += enemy.getLocation().x; 
                    sumY += enemy.getLocation().y; 
                    mopperCount++; 
                }
            } 
            if (mopperCount > 0) { 
                Direction awayDir = rc.getLocation().directionTo(new MapLocation(sumX/mopperCount, sumY/mopperCount)).opposite(); 
                Pathfinding.moveTowards(rc, rc.getLocation().add(awayDir).add(awayDir).add(awayDir)); 
            } 
        } else {
            // Urusan serangan murni: ada target valid, langsung serang. Tidak peduli efisiensi cipratan.
            if (rc.isActionReady()) { 
                MapLocation attackTarget = null;
                for(MapInfo tile : rc.senseNearbyMapInfos(4)) {
                    if (rc.canAttack(tile.getMapLocation())) {
                        if (tile.getPaint().isEnemy() || rc.senseRobotAtLocation(tile.getMapLocation()) != null) {
                            attackTarget = tile.getMapLocation();
                            break;
                        }
                    }
                } 
                if (attackTarget != null) {
                    rc.attack(attackTarget); 
                }
            }
            
            // Pergerakan eksplorasi simpel
            if (exploreTarget == null || rc.getLocation().distanceSquaredTo(exploreTarget) <= 9 || rc.getRoundNum() - expRound >= 18) {
                exploreTarget = Pathfinding.getSectorTarget(rc, ++exploreVisitCount); 
                expRound = rc.getRoundNum();
            } 
            Pathfinding.moveTowards(rc, exploreTarget); 
        }
    }
}

class TowerLogic {
    static int soldierSpawnCount = 0, splasherSpawnCount = 0, mopperSpawnCount = 0, lastDisintegrateRound = -9999;

    public static void run(RobotController rc) throws GameActionException {
        // Cukup sense 1x
        RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
        RobotInfo[] allies = rc.senseNearbyRobots(-1, rc.getTeam());
        
        // Serangan dasar
        if (rc.isActionReady() && enemies.length > 0) { 
            RobotInfo weakestEnemy = null; int minHealth = 9999; 
            for(RobotInfo enemy : enemies) {
                if (rc.canAttack(enemy.getLocation()) && enemy.getHealth() < minHealth) { 
                    minHealth = enemy.getHealth(); 
                    weakestEnemy = enemy; 
                }
            } 
            if (weakestEnemy != null) rc.attack(weakestEnemy.getLocation()); 
        }
        
        // Transfer Cat ke Teman (Dibatasi threshold)
        int criticalThreshold;
        if (RobotPlayer.currentPhase == RobotPlayer.GamePhase.EARLY) criticalThreshold = 45;
        else if (RobotPlayer.currentPhase == RobotPlayer.GamePhase.MID) criticalThreshold = 25;
        else criticalThreshold = 20;
        
        for(RobotInfo ally : allies) {
            int transferAmount;
            if (ally.getPaintAmount() < 30) transferAmount = 30; else transferAmount = 20;
            
            if (!ally.getType().isTowerType() && ally.getPaintAmount() < criticalThreshold && rc.getPaint() > 90 && rc.canTransferPaint(ally.getLocation(), transferAmount)) { 
                rc.transferPaint(ally.getLocation(), transferAmount); 
                break; 
            }
        }
        
        // Disintegrate logic
        if (RobotPlayer.currentPhase == RobotPlayer.GamePhase.LATE && rc.getChips() > 20000 && rc.getRoundNum() - lastDisintegrateRound >= 10) {
            lastDisintegrateRound = rc.getRoundNum(); 
        }
        
        // Spawn Pasukan
        if (!rc.isActionReady() || rc.getPaint() <= 150) return;
        
        UnitType buildType = null; 
        int totalUnits = Math.max(1, soldierSpawnCount + splasherSpawnCount + mopperSpawnCount);
        
        if (RobotPlayer.currentPhase == RobotPlayer.GamePhase.EARLY) {
            if ((75 * totalUnits - soldierSpawnCount * 100) >= (25 * totalUnits - splasherSpawnCount * 100)) {
                buildType = UnitType.SOLDIER;
            } else {
                buildType = UnitType.SPLASHER;
            }
        } else { 
            int bestDeficitScore = -9999; 
            if (rc.getPaint() >= 200 && (47 * totalUnits - soldierSpawnCount * 100) > bestDeficitScore) { 
                bestDeficitScore = 47 * totalUnits - soldierSpawnCount * 100; 
                buildType = UnitType.SOLDIER; 
            } 
            if (rc.getPaint() >= 400 && (48 * totalUnits - splasherSpawnCount * 100) > bestDeficitScore) { 
                bestDeficitScore = 48 * totalUnits - splasherSpawnCount * 100; 
                buildType = UnitType.SPLASHER; 
            } 
            if (rc.getPaint() >= 200 && (5 * totalUnits - mopperSpawnCount * 100) > bestDeficitScore) { 
                bestDeficitScore = 5 * totalUnits - mopperSpawnCount * 100; 
                buildType = UnitType.MOPPER; 
            } 
        }
        
        if (buildType != null) {
            for(Direction spawnDir : Pathfinding.ALL_DIRS) {
                if (rc.canBuildRobot(buildType, rc.getLocation().add(spawnDir))) { 
                    rc.buildRobot(buildType, rc.getLocation().add(spawnDir)); 
                    if (buildType == UnitType.SOLDIER) soldierSpawnCount++; 
                    else if (buildType == UnitType.SPLASHER) splasherSpawnCount++; 
                    else mopperSpawnCount++; 
                    return; 
                }
            }
        }
    }
}