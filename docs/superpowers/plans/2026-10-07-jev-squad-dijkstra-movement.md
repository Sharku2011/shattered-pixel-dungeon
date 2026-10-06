# Jev 분대 다익스트라 이동 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** flank/escort_ranged 이동을 가중 다익스트라 비용장 기반의 로컬 목표 배정과 경로 예약으로 바꾸고, 근접 분대용 `formation` 전술을 추가하며, Jev에게는 모든 전술 선택지와 판단용 사실을 제공한다.

**Architecture:** 게임 의존성이 없는 순수 클래스 4개(`SquadWorld`, `SquadDijkstra`, `SquadCostField`, `FormationPlanner`)와 확장한 `SquadMovementPlanner`가 계산을 전부 맡는다. `JevMobAI`는 게임 상태를 `SquadWorld`로 감싸는 어댑터와 Plan 보관, Jev 요청을 맡는다. `Mob.Hunting`은 매 턴 `JevMobAI.tacticalStep`으로 다음 칸을 받아 `move(step)`한다.

**Tech Stack:** Java 8 호환 문법(기존 코드와 동일), libGDX, 순수 플래너용 standalone 시뮬레이션(`javac` + `java`), 게임 빌드는 Gradle(`spd_builder` 에이전트).

**Spec:** `docs/superpowers/specs/2026-10-07-jev-squad-dijkstra-movement-design.md`

## Global Constraints

- 거리와 인접은 체비셰프 거리(대각선 = 1)이며, "거리 1"은 둘러싼 8칸이다.
- 비용 상수: 기본 10, 영웅 거리 1 +40(자기 목표 제외), 영웅 거리 2 +15, 축 ≤1 +30, 축 2 +10, 예약 칸 +25, 예약 인접 +10, 분대원 현재 위치 인접 +15. 같은 항목 안에서는 중첩하지 않는다.
- 계산 범위는 관련 셀을 모두 감싸는 최소 사각형을 사방으로 `SEARCH_MARGIN = 4`만큼 넓힌 영역으로, 맵 경계에서 자른다.
- 방위 점수는 `(4 - diff) × 20`이며, `diff ≤ 1`은 후보에서 제외한다. 방위 버킷당 한 명만 배정한다. 동점은 멤버 id 순, 그다음 셀 인덱스 순으로 정한다.
- formation 상수: `GATHER_TIMEOUT = 4`(집결이 4턴을 넘으면 해제), `STALL_LIMIT = 3`(3턴 연속 진전이 없으면 해제).
- Jev의 tactic 수락 기준(confidence ≥ 0.45)과 Plan 수명 규칙(12턴, 영웅 6칸 이상 이동, 구성 변경)은 바꾸지 않는다.
- 순수 클래스는 `com.shatteredpixel.shatteredpixeldungeon.actors.mobs` 패키지의 package-private 클래스로 만들고, `java.util` 외에는 import하지 않는다.
- 시뮬레이션 실행 명령: `powershell -File scripts/simulate_tactical_movement.ps1`. 성공하면 `Tactical movement simulations passed`로 시작하는 줄이 출력된다.
- 로그 이벤트 이름은 spec 9절을 따른다: `move_goal`, `move_step`, `move_step_blocked`, `move_goal_reassigned reason=invalid|hero_moved|sector_lost|ally_moved`, `formation_phase phase=gather|advance|contact`, `formation_released reason=gather_timeout|stalled`, `tactic_degraded reason=no_open_sector|no_screen_position|too_few_melee|mixed_squad`.

## Review Focus

1. **맵 모서리의 영웅**: 영웅이 (1,1) 근처에 있어도 범위가 맵 안으로 잘려야 하고, 배열 범위 예외가 나지 않아야 한다. → Task 1 `boundsClampAtMapCorner`
2. **LARGE 멤버**: 배정된 목표와 경로의 모든 칸이 그 멤버의 `passable`(openSpace)을 만족해야 한다. → Task 3 `largeMemberGoalsRespectOpenSpace`
3. **분대가 1명으로 줄어든 경우**(사망·분열): `assign`이 예외 없이 배정 0건과 `no_open_sector`를 반환해야 한다. Plan에 남은, 이미 없는 멤버의 배정은 `nextStep`이 무시해야 한다. → Task 3 `singleSurvivorDegradesWithoutAssignments`
4. **멤버에게 영웅이 안 보이는 경우**: 그 멤버는 배정을 받지 않아야 한다. → Task 3 `heroHiddenFromMemberGivesNoGoal`
5. **움직이지 못하는 기준점**(rooted 등으로 제자리에 고정): formation이 무한 대기하지 않고 `STALL_LIMIT` 이후 해제돼야 한다. → Task 4 `immobileAnchorReleasesAfterStall`. Mob 쪽에서 rooted면 전술 이동을 건너뛰는지는 Task 5의 수동 확인 항목이다.

---

## File Structure

| 파일 | 상태 | 책임 |
|---|---|---|
| `core/src/main/java/.../actors/mobs/SquadWorld.java` | 생성 | 순수 플래너가 게임 상태를 묻는 인터페이스 |
| `core/src/main/java/.../actors/mobs/SquadDijkstra.java` | 생성 | 범위 제한 버킷 큐(Dial) 다익스트라, 경로 추적, 실행 횟수 카운터 |
| `core/src/main/java/.../actors/mobs/SquadCostField.java` | 생성 | spec 4절 비용장(`SquadDijkstra.Graph` 구현) |
| `core/src/main/java/.../actors/mobs/SquadMovementPlanner.java` | 수정 | flank/escort 배정, 재배정, 목표 이동, 다음 걸음, 사전 배정 사실. 구 후보 API는 Task 6에서 삭제 |
| `core/src/main/java/.../actors/mobs/FormationPlanner.java` | 생성 | formation 참여자, 연결 판정, 영웅 거리맵, 단계별 걸음 |
| `core/src/main/java/.../actors/mobs/JevMobAI.java` | 수정 | 게임 → `SquadWorld` 어댑터, Plan 저장, `tacticalStep`, Jev 요청/응답 |
| `core/src/main/java/.../actors/mobs/Mob.java` | 수정 | `Hunting`이 `tacticalStep`을 쓰도록 변경, `moveToFlankingPosition` 삭제 |
| `core/src/main/assets/messages/actors/actors.properties`, `actors_ko.properties` | 수정 | `tactic_formation` 메시지 |
| `core/src/test/java/.../actors/mobs/SquadTestMap.java` | 생성 | `SquadWorld`를 구현한 테스트 지도 |
| `core/src/test/java/.../actors/mobs/TacticalMovementPlannerSimulation.java` | 수정 | 시뮬레이션 진입점(main) |
| `scripts/simulate_tactical_movement.ps1` | 수정 | 새 파일 목록으로 컴파일 |

`...`는 `com/shatteredpixel/shatteredpixeldungeon`이다.

---

### Task 1: SquadWorld, SquadDijkstra, 테스트 지도

**Files:**
- Create: `core/src/main/java/.../actors/mobs/SquadWorld.java`, `SquadDijkstra.java`
- Create: `core/src/test/java/.../actors/mobs/SquadTestMap.java`
- Modify: `SquadMovementPlanner.java:18-32` (`Member`에 `speed` 추가), `scripts/simulate_tactical_movement.ps1`, `TacticalMovementPlannerSimulation.java`

**Interfaces:**
- Produces:
  ```java
  interface SquadWorld {
      int width(); int height();
      boolean passable(SquadMovementPlanner.Member mover, int cell); // 지형만 본다. 캐릭터는 보지 않는다
      boolean occupied(int cell);                                    // 영웅과 분대원을 포함한 모든 캐릭터
      boolean visible(SquadMovementPlanner.Member mover, int cell);
  }
  // SquadMovementPlanner.Member: 필드 `final float speed` 추가.
  // 생성자 Member(int id, int cell, boolean tactical, boolean ranged, String role, float speed)
  // 기존 5인자 생성자는 speed 1f로 위임하고 Task 6에서 삭제한다.
  final class SquadDijkstra {
      interface Graph { int width(); int height(); boolean passable(int cell); int enterCost(int cell); }
      static final int UNREACHABLE = Integer.MAX_VALUE;
      static final class Bounds {
          final int minX, minY, maxX, maxY;
          static Bounds around(int width, int height, int margin, int... cells);
          boolean contains(int cell, int width);
      }
      static int[] fromSource(Graph g, int source, Bounds b); // dist[c] = source에서 c까지 비용(들어간 칸들의 enterCost 합)
      static int[] toTarget(Graph g, int target, Bounds b);   // dist[c] = c에서 target까지 비용
      static ArrayList<Integer> tracePath(Graph g, int[] fromSourceDist, int source, int goal); // source 다음 칸부터 goal까지, 도달 불가면 빈 목록
      static int runCount(); static void resetRunCount();
  }
  // SquadTestMap implements SquadWorld:
  //   SquadTestMap(int w, int h)  — 테두리는 벽, 내부는 바닥이고 전부 보임
  //   cell(x,y), x(c), y(c), wall(x,y), occupy(cell), hide(cell), openSpace[]
  //   LARGE 이동자 id 집합 large: passable은 openSpace[cell]까지 요구
  // 시뮬레이션 헬퍼(TacticalMovementPlannerSimulation 안):
  //   uniform(map) → enterCost 10인 Graph, costed(map, IntUnaryOperator) → 지정한 비용의 Graph,
  //   bfsSteps(map, src) → 8방향 BFS 걸음 수(도달 불가 -1)
  ```

- [ ] **Step 1: 실패하는 테스트 작성** (`TacticalMovementPlannerSimulation`에 추가하고 `main`에서 호출)

```java
private static void dijkstraMatchesBfsWithUniformCost() {
    SquadTestMap map = new SquadTestMap(15, 15);
    map.wall(7, 3); map.wall(7, 4); map.wall(7, 5); map.wall(7, 6);
    SquadDijkstra.Graph g = uniform(map);           // passable = map.passable(null, c), enterCost = 10
    int src = map.cell(2, 5);
    SquadDijkstra.Bounds all = SquadDijkstra.Bounds.around(15, 15, 20, src);
    int[] d = SquadDijkstra.fromSource(g, src, all);
    int[] bfs = bfsSteps(map, src);                  // 테스트용 8방향 BFS
    for (int c = 0; c < d.length; c++)
        check(bfs[c] < 0 ? d[c] == SquadDijkstra.UNREACHABLE : d[c] == bfs[c] * 10, "uniform dijkstra == 10*bfs at " + c);
    int[] r = SquadDijkstra.toTarget(g, src, all);
    for (int c = 0; c < d.length; c++) check(r[c] == d[c], "symmetric uniform cost");
    ArrayList<Integer> p = SquadDijkstra.tracePath(g, d, src, map.cell(12, 5));
    check(p.size() == bfs[map.cell(12, 5)] && p.get(p.size() - 1) == map.cell(12, 5), "trace path length and end");
}
private static void weightedCostIsRespected() {
    SquadTestMap map = new SquadTestMap(9, 5);               // 내부 7x3: x 1..7, y 1..3
    int src = map.cell(1, 2), dst = map.cell(7, 2);
    SquadDijkstra.Bounds all = SquadDijkstra.Bounds.around(9, 5, 20, src);
    // (4,2)만 100: 위아래로 돌아가는 같은 길이(6걸음) 경로가 있으므로 피한다
    SquadDijkstra.Graph oneHot = costed(map, c -> c == map.cell(4, 2) ? 100 : 10);
    int[] d1 = SquadDijkstra.fromSource(oneHot, src, all);
    check(d1[dst] == 60, "detour around single expensive cell costs 6*10");
    check(!SquadDijkstra.tracePath(oneHot, d1, src, dst).contains(map.cell(4, 2)), "path avoids expensive cell");
    // x=4 열 전체가 100: 반드시 한 칸은 지나야 한다
    SquadDijkstra.Graph column = costed(map, c -> map.x(c) == 4 ? 100 : 10);
    check(SquadDijkstra.fromSource(column, src, all)[dst] == 5 * 10 + 100, "forced crossing pays once");
}
private static void boundsClampAtMapCorner() {
    SquadTestMap map = new SquadTestMap(10, 10);
    SquadDijkstra.Bounds b = SquadDijkstra.Bounds.around(10, 10, 4, map.cell(1, 1));
    check(b.minX == 0 && b.minY == 0 && b.maxX == 5 && b.maxY == 5, "bounds clamp to map");
    int[] d = SquadDijkstra.fromSource(uniform(map), map.cell(1, 1), b);
    check(d[map.cell(8, 8)] == SquadDijkstra.UNREACHABLE, "outside bounds stays unreachable");
}
```

- [ ] **Step 2: 스크립트 갱신 후 실행해서 실패 확인**

`simulate_tactical_movement.ps1`의 `javac` 줄을 다음 파일들을 컴파일하도록 바꾼다: main 쪽 `SquadWorld.java SquadDijkstra.java SquadMovementPlanner.java`와 test 쪽 `SquadTestMap.java TacticalMovementPlannerSimulation.java`. 이후 태스크에서 `SquadCostField.java`와 `FormationPlanner.java`를 이 목록에 추가한다.
Run: `powershell -File scripts/simulate_tactical_movement.ps1`
Expected: `javac failed` (`SquadDijkstra`가 아직 없음)

- [ ] **Step 3: `SquadWorld`, `Member.speed`, `SquadDijkstra`, `SquadTestMap` 구현**

`SquadDijkstra` 구현 규칙:
- Dial 알고리즘을 쓴다. 원형 버킷 개수는 `maxEnterCost + 1`이고, 최대 비용은 10+40+30+25+15 = 120이므로 121개면 된다. 큐에 넣을 때 `g.enterCost`가 이 값을 넘으면 `IllegalArgumentException`을 던진다.
- 8방향 이웃을 고정 순서(N, NE, E, SE, S, SW, W, NW)로 돌고, `Bounds` 밖이나 맵 밖은 건너뛴다.
- source/target 칸은 `passable`과 상관없이 시작점으로 쓴다.
- `toTarget`에서 u→v 간선의 비용은 `enterCost(v)`이다(`dist[u] = dist[v] + enterCost(v)`).
- `tracePath`는 goal에서 거꾸로 올라가며, 위 순서에서 처음 나오는 `dist[n] == dist[cur] - enterCost(cur)` 이웃을 고른다.
- 두 계산 함수 모두 호출할 때마다 `runCount`를 1 올린다.

- [ ] **Step 4: 실행해서 통과 확인**

Run: `powershell -File scripts/simulate_tactical_movement.ps1`
Expected: `Tactical movement simulations passed ...`. 기존 테스트도 그대로 통과해야 한다.

- [ ] **Step 5: Commit**

```bash
git add scripts/simulate_tactical_movement.ps1 core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/SquadWorld.java core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/SquadDijkstra.java core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/SquadMovementPlanner.java core/src/test/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/
git commit -m "feat(mobs): add bounded bucket-queue Dijkstra for squad movement"
```

---

### Task 2: SquadCostField

**Files:**
- Create: `core/src/main/java/.../actors/mobs/SquadCostField.java`
- Modify: `scripts/simulate_tactical_movement.ps1` (컴파일 목록에 추가), `TacticalMovementPlannerSimulation.java`

**Interfaces:**
- Consumes: `SquadWorld`, `SquadDijkstra.Graph`, `SquadMovementPlanner.Member`
- Produces:
  ```java
  final class SquadCostField implements SquadDijkstra.Graph {
      static final int BASE = 10, HERO_RING1 = 40, HERO_RING2 = 15, AXIS_NEAR = 30, AXIS_FAR = 10,
                       RESERVED = 25, RESERVED_ADJ = 10, SQUADMATE_ADJ = 15, SEARCH_MARGIN = 4;
      static final class Penalties {
          boolean heroProximity; int ownGoal = -1;        // ownGoal은 영웅 거리 1 페널티에서 제외
          int axisFrom = -1;                              // -1이면 축 페널티 없음
          List<List<Integer>> reservations = new ArrayList<>(); // 이동자 자신의 예약은 제외하고 넘긴다
          boolean squadmateAdjacency;
      }
      SquadCostField(SquadWorld world, SquadMovementPlanner.Member mover, List<SquadMovementPlanner.Member> squad,
                     int heroCell, Penalties penalties);
      int axisDistance(int cell);   // 축 칸 집합까지의 체비셰프 거리. 축이 없으면 Integer.MAX_VALUE
      SquadDijkstra.Bounds bounds(int... extraCells); // 영웅, 분대 전원, extra를 감싸고 SEARCH_MARGIN을 더한 범위
  }
  ```

축 칸 집합: `axisFrom`에서 영웅까지 체비셰프 거리 n만큼 `t = i/n`(i = 0..n-1)으로 보간하고 `Math.round`해서 얻은 칸들이다. 영웅 칸은 넣지 않는다. 축 페널티는 `axisDistance ≤ 1`이면 +30, `== 2`이면 +10이다.

`passable(c)`: `world.passable(mover, c)`이면서, 칸이 `occupied`이고 `visible(mover, c)`이며 분대원 칸이 아니고 `mover.cell`도 아닌 경우를 뺀다. 결과적으로 영웅 칸과 보이는 다른 캐릭터 칸은 막히고, 분대원 칸은 지나갈 수 있다.

- [ ] **Step 1: 실패하는 테스트 작성**

```java
private static void costFieldAppliesSpecPenalties() {
    SquadTestMap map = new SquadTestMap(17, 17);
    int hero = map.cell(8, 8);
    Member front = member(1, map.cell(3, 8)), mover = member(2, map.cell(3, 10)), mate = member(3, map.cell(12, 12));
    SquadCostField.Penalties p = new SquadCostField.Penalties();
    p.heroProximity = true; p.ownGoal = map.cell(9, 9); p.axisFrom = front.cell;
    p.reservations.add(Arrays.asList(map.cell(5, 14), map.cell(6, 14)));
    p.squadmateAdjacency = true;
    SquadCostField f = new SquadCostField(map, mover, squad(front, mover, mate), hero, p);
    check(f.enterCost(map.cell(9, 9)) == 10, "own goal exempt from ring1");
    check(f.enterCost(map.cell(9, 7)) == 50, "ring1 = 10+40");
    check(f.enterCost(map.cell(10, 10)) == 25, "ring2 = 10+15");
    check(f.enterCost(map.cell(5, 8)) == 40 && f.axisDistance(map.cell(5, 8)) == 0, "on axis = 10+30");
    check(f.enterCost(map.cell(5, 10)) == 20, "axis distance 2 = 10+10");
    check(f.enterCost(map.cell(5, 14)) == 35, "reserved = 10+25");
    check(f.enterCost(map.cell(4, 14)) == 20, "reserved-adjacent = 10+10");
    check(f.enterCost(map.cell(12, 13)) == 25, "squadmate-adjacent = 10+15");
    check(f.enterCost(map.cell(14, 2)) == 10, "plain floor = base");
    check(!f.passable(hero) && f.passable(mate.cell), "hero blocked, squadmate cell walkable");
}
```
`member(id, cell)`은 tactical 근접 멤버(speed 1)를 만드는 테스트 헬퍼다. 위 기대값은 해당 칸에 다른 페널티가 겹치지 않는 경우이므로, 겹치지 않는지 좌표를 확인한 뒤 고정한다.

- [ ] **Step 2: 실행해서 실패 확인** — Expected: `javac failed`
- [ ] **Step 3: `SquadCostField` 구현** — 인터페이스 블록과 Global Constraints의 값을 그대로 쓴다.
- [ ] **Step 4: 실행해서 통과 확인** — Expected: `Tactical movement simulations passed`
- [ ] **Step 5: Commit** — `git commit -m "feat(mobs): add squad movement cost field"`

---

### Task 3: flank/escort 배정과 실행 계산

**Files:**
- Modify: `core/src/main/java/.../actors/mobs/SquadMovementPlanner.java`
- Modify: `TacticalMovementPlannerSimulation.java`

**Interfaces:**
- Consumes: Task 1, 2
- Produces (모두 `SquadMovementPlanner`의 static 멤버):
  ```java
  static final class Assignment {
      final int memberId; final String maneuver;       // "flank" | "escort"
      int goal; int sector; int heroCell; int allyCell; // allyCell은 escort에서만 쓰고, 나머지는 -1
      ArrayList<Integer> path;                          // 다음 칸부터 goal까지
  }
  static final class SquadPlan {
      final LinkedHashMap<Integer, Assignment> byMember = new LinkedHashMap<>();
      int frontId = -1; int axisFrom = -1; String degradeReason; // null이면 degrade 없음
  }
  static int sectorDiff(int a, int b);                       // 원형 차이 0..4
  static int frontMember(List<Member> squad, int heroCell, SquadWorld world); // id, 없으면 -1
  static SquadPlan assign(String tactic, List<Member> squad, int heroCell, SquadWorld world); // tactic: "flank" | "escort_ranged"
  static boolean reassign(SquadPlan plan, Member member, List<Member> squad, int heroCell, SquadWorld world);
  static boolean retarget(SquadPlan plan, Member member, List<Member> squad, int heroCell, SquadWorld world);
  static int nextStep(SquadPlan plan, Member mover, List<Member> squad, int heroCell, SquadWorld world); // -1이면 없음
  static ArrayList<String> openSectors(List<Member> squad, int heroCell, SquadWorld world); // assign("flank")의 방위 이름
  static boolean screenPositionAvailable(List<Member> squad, int heroCell, SquadWorld world);
  ```

**규칙** (spec 5절과 6절. 구현에서 판단할 부분만 적는다)
- `frontMember`: role이 `"tank"`인 멤버를 먼저 고른다. 없으면 `!ranged && tactical`인 멤버 중 `toTarget(영웅)` 지형 비용이 최소인 멤버를 고르고, 동점이면 id가 작은 쪽이다.
- **목표 후보 조건**: `goalLegal(m, c)`, 즉 `world.passable(m,c) && !world.occupied(c) && world.visible(m,c) && world.visible(m, heroCell)`.
- **flank 측면조**: `tactical && !ranged`이고 front가 아닌 멤버. 축 기준은 `axisFrom = front.cell`이다.
- **escort**:
  - escort 멤버는 front(tank)다. 원거리 아군은 front에게 보이는 `ranged` 멤버 중 role이 `"dealer"`인 쪽을 먼저 고른다.
  - 후보 칸은 기존 `addEscort`의 기하 규칙을 private `escortCells(...)`로 옮겨 그대로 쓴다.
  - escort 칸 점수 = `fromSource` 비용 + `|heroDist-3| + |allyDist-2|`(기존 정렬 기준).
  - escort를 배정하면 `axisFrom`은 escort goal이 되고, 측면조에서 front와 그 원거리 아군을 뺀다.
  - 배정에 실패하면 `degradeReason = "no_screen_position"`으로 두고 flank 규칙으로 계속 진행한다(`axisFrom = front.cell`).
- **배정 루프**: spec 5.4를 따른다. 방위 기준은 `directionBucket(axisFrom, heroCell)`이다.
- **flank degrade**: flank 배정이 0건이고 `degradeReason`이 아직 비어 있으면 `"no_open_sector"`로 둔다. escort_ranged에서 escort는 성공하고 flank가 0건이면 degrade가 아니다.
- **`nextStep`**:
  - 배정이 없거나 멤버가 `squad`에 없으면 -1이다.
  - 비용장은 근접 페널티 + 축 + 다른 멤버의 남은 `path`를 예약으로 + 분대원 인접으로 구성한다.
  - `toTarget(goal)`을 계산하고, 이동자 이웃 중 `passable && !occupied`이면서 dist가 최소인 칸을 고른다. 단 `dist ≤ dist[mover.cell]`이어야 하고, 아니면 -1이다.
  - 고른 칸을 시작으로 `path`를 갱신한다.
- **`retarget`**: 같은 `sector` 버킷 안의 링 칸 중 `goalLegal`인 칸을 `fromSource` 비용 최소로 고르고, `goal`과 `heroCell`을 갱신한다. 그런 칸이 없으면 false.
- **`reassign`**: 다른 배정은 그대로 두고, 이미 쓰인 방위를 제외한 상태로 이 멤버에게만 배정 루프를 한 번 돌린다.

- [ ] **Step 1: 실패하는 테스트 작성** (spec 10절 2·3·4·5·7·8번과 Review Focus 2·3·4번)

```java
private static void flankersAvoidAxisAndHeroRing() {             // spec 10-2
    // 17x17 방, 영웅 (8,8), tank (4,8), 측면조 (3,8)·(2,8)이 축 위에 일렬로 있음
    // 각 측면조의 path에서 처음 2칸과 goal을 뺀 모든 칸이 axisDistance > 1 (축 이탈에 필요한 2칸만 허용)
    // path에서 goal을 뺀 모든 칸이 영웅 거리 > 1
}
private static void flankPathsSeparateInOpenRoom() {             // spec 10-3
    // 15x15, 영웅 (7,7), tank (2,7), 측면조 (2,6),(2,8)
    // 두 path의 공유 칸이 2개 이하
    // 1칸 폭 통로 지도에서는 assign이 예외 없이 끝나고 측면조 배정이 0건
}
private static void sectorsAreNonFrontalAndDistinct() {          // spec 10-4
    // 모든 배정에 대해 sectorDiff(directionBucket(goal), directionBucket(axisFrom)) >= 2
    // sector는 모두 서로 다름
}
private static void corridorYieldsNoFlankAndDegrades() {         // spec 10-4
    // 13x13 세로 1칸 통로: assign("flank").byMember가 비어 있고 degradeReason == "no_open_sector"
}
private static void escortScreensBetweenHeroAndAlly() {          // spec 10-5
    // 기존 escortGoalsScreenAVisibleRangedAlly와 같은 배치
    // escort goal이 영웅↔아군 사이에 투영되고(0 < dot < span), 영웅 거리 >= 2
    // 원거리 아군을 숨기면 degradeReason == "no_screen_position"이고 flank로 진행
}
private static void randomizedAssignmentsAreLegalAndDeterministic() { // spec 10-7
    // seed 0x5eed로 무작위 지형 500개
    // 각 goal은 goalLegal이고, path는 goal에서 끝나며 모든 칸이 passable
    // 같은 입력으로 두 번 돌린 결과(goal, path)가 같음
    // 지금의 randomizedTerrainAndPlacementReliability 기준을 유지:
    //   flank 배정 1건 이상인 지형 >= 400 (축과 정면 제외로 기준을 450에서 400으로 낮춤)
}
private static void dijkstraRunsPerAssignmentAreBounded() {      // spec 10-8
    // tank 역할을 넣어서 frontMember가 다익스트라를 돌리지 않게 한다
    // resetRunCount → 측면조 N=3명으로 assign("flank") → runCount() <= 3*4/2 + 1
    // assign("escort_ranged")도 같은 상한(+1이 escort 몫)
}
private static void heroMoveKeepsSector() {                      // spec 6.3
    // 배정 후 영웅을 1칸 옮기고 retarget → true, sector는 그대로, goal은 새 링 칸
    // 그 방위의 링 칸을 모두 벽으로 막으면 → false
}
private static void nextStepFollowsGoalAndAvoidsOccupied() {
    // nextStep이 dist를 줄이는 이웃을 반환함
    // 그 칸을 occupy하면 다른 칸을 반환하거나 -1
}
private static void largeMemberGoalsRespectOpenSpace() {         // Review Focus 2
    // 측면조 하나를 large로 등록하고 영웅 주변 일부 칸의 openSpace를 false로 둠
    // 그 멤버의 goal과 path 모든 칸이 openSpace
}
private static void singleSurvivorDegradesWithoutAssignments() { // Review Focus 3
    // squad 1명 → byMember 비어 있고 degradeReason == "no_open_sector"
    // nextStep(plan, 없는 멤버) == -1
}
private static void heroHiddenFromMemberGivesNoGoal() {          // Review Focus 4
    // map.hide(hero) → 해당 멤버의 배정 없음
}
```

- [ ] **Step 2: 실행해서 실패 확인** — Expected: `javac failed`
- [ ] **Step 3: 위 Interfaces와 규칙대로 구현.** 기존 `candidates`, `World` 등 구 API는 손대지 않는다. Task 6까지 `JevMobAI`가 이 API를 쓴다.
- [ ] **Step 4: 실행해서 통과 확인** — Expected: `Tactical movement simulations passed`
- [ ] **Step 5: Commit** — `git commit -m "feat(mobs): assign flank and escort goals with reserved Dijkstra paths"`

---

### Task 4: FormationPlanner와 degrade 전수 테스트

**Files:**
- Create: `core/src/main/java/.../actors/mobs/FormationPlanner.java`
- Modify: `scripts/simulate_tactical_movement.ps1` (컴파일 목록에 추가), `TacticalMovementPlannerSimulation.java`

**Interfaces:**
- Consumes: Task 1~3
- Produces:
  ```java
  final class FormationPlanner {
      static final int GATHER_TIMEOUT = 4, STALL_LIMIT = 3, RELEASED = -1;
      static final class State {
          String phase = "gather";      // "gather" | "advance" | "contact" | "released"
          int anchorId = -1, gatherTurns, stallTurns, bestProgress = SquadDijkstra.UNREACHABLE;
          String releaseReason;         // "gather_timeout" | "stalled"
      }
      static ArrayList<Member> participants(List<Member> squad);   // tactical && !ranged, id 순
      static String degradeReason(List<Member> squad);             // 참여자 < 2 → "too_few_melee", 원거리 포함 → "mixed_squad", 그 외 null
      static boolean connected(List<Member> participants, int movedId, int movedTo); // movedId == -1이면 현재 위치 그대로
      static int[] heroMap(List<Member> participants, int heroCell, SquadWorld world); // 지형 비용, 참여자 전원의 passable AND
      static int step(State state, Member mover, List<Member> squad, int heroCell, SquadWorld world);
      // 반환: 이동할 칸, mover.cell(대기), RELEASED(기본 AI)
  }
  ```

**`step` 규칙** (spec 7절. 순서대로 판정)
1. `mover`가 참여자가 아니거나 phase가 `contact`/`released`이면 `RELEASED`를 반환한다.
2. 참여자 중 누구든 영웅과 거리 1이면 phase를 `contact`로 바꾸고 `RELEASED`를 반환한다.
3. 기준점(anchor)을 정한다. 참여자 중 `speed` 최소, 동점이면 id 최소이며, 매 호출마다 다시 계산한다.
   카운터는 **mover가 기준점일 때만** 갱신한다.
   - `!connected`이면 phase를 gather로 둔다. advance에서 넘어오는 경우 `gatherTurns = 0`으로 초기화한다.
   - gather 중이면 `gatherTurns++`하고, `> GATHER_TIMEOUT`이면 `released/gather_timeout`.
   - advance 중이면 `progress = min(heroMap[참여자 위치])`를 구한다. `progress < bestProgress`이면 갱신하고 `stallTurns = 0`, 아니면 `stallTurns++`하고 `>= STALL_LIMIT`이면 `released/stalled`.
   - 이 단계에서 해제되면 `RELEASED`를 반환한다.
4. **gather**:
   - 기준점은 `mover.cell`(대기)을 반환한다.
   - 나머지는 `toTarget(anchor.cell)` 지형 맵에서 이웃 중 `!occupied`이면서 dist가 최소이고 현재보다 작은 칸으로 간다. 없으면 대기.
   - connected가 되면 phase를 advance로 바꾼다. 이 전환은 아무 멤버의 호출에서나 일어날 수 있다.
5. **advance**:
   - 후보는 이웃 8칸 중 `passable && !occupied && connected(participants, mover.id, c)`인 칸이다.
   - `heroMap[c] < heroMap[mover.cell]`인 칸 중 최소를 고른다. 동점이면 "영웅 거리 == 다른 참여자들의 최소 영웅 거리"인 칸을 먼저, 그다음 셀 인덱스 순.
   - 조건을 만족하는 칸이 없으면 대기.

- [ ] **Step 1: 실패하는 테스트 작성**

```java
private static void formationStaysConnectedUntilContact() {      // spec 10-6
    // 무작위 200회: 17x17, 벽 12%, 근접 참여자 3~4명, speed는 {0.5,1,2} 중 무작위
    // 처음에는 연결된 상태로 배치
    // 행동 일정 시뮬레이션: 각 멤버가 누적 시간 1/speed마다 행동하고, 시간이 같으면 id 순
    // 매 step 후 phase가 gather|advance이면 participants가 connected인지 확인
    // 반환값이 RELEASED로 바뀐 첫 시점의 phase는 contact 또는 released
    // 최소 150회는 contact에 도달
}
private static void fastMemberNeverLeadsByMoreThanOne() {        // spec 7 속도 맞추기
    // 빈 방, speed 2 멤버와 speed 0.5 멤버
    // advance 중 매 행동 후 두 멤버의 거리 <= 1
}
private static void gatherThenAdvance() {
    // 서로 떨어진 2명: 기준점(느린 쪽)은 대기(mover.cell)하고, 다른 쪽은 기준점 쪽으로 이동
    // 연결되면 phase == "advance"
}
private static void gatherTimeoutReleases() {
    // 벽으로 완전히 분리된 2명 → 기준점 차례 5번째에 RELEASED, releaseReason == "gather_timeout"
}
private static void immobileAnchorReleasesAfterStall() {         // Review Focus 5, spec 10-6
    // 기준점 앞 칸을 모두 occupy해서 진전이 불가능하게 만듦
    // 기준점 차례 3번 연속 진전 없음 → RELEASED, releaseReason == "stalled"
}
private static void degradeMatrixNeverThrows() {                 // spec 10-9
    // 전술 {advance, flank, escort_ranged, hold_range, formation}
    // × 구성 {근접만, 근접+원거리, 원거리 1명, instinctive 포함}
    // × 지형 {열린 방, 1칸 통로, 벽으로 막힌 방}
    //   flank/escort_ranged → assign 결과가 배정 1건 이상이거나 degradeReason != null
    //   formation → degradeReason(squad) 또는 step 반복(최대 30회)이 예외 없이 끝남
    //   advance/hold_range → 플래너를 호출하지 않음(실행 경로가 없음을 명시)
    //   "근접+원거리"에서는 formation degradeReason == "mixed_squad"
    //   "원거리 1명"에서는 "too_few_melee"
}
```

- [ ] **Step 2: 실행해서 실패 확인** — Expected: `javac failed`
- [ ] **Step 3: `FormationPlanner` 구현**
- [ ] **Step 4: 실행해서 통과 확인** — Expected: `Tactical movement simulations passed`
- [ ] **Step 5: Commit** — `git commit -m "feat(mobs): add cohesive formation planner"`

---

### Task 5: 게임 연결 (JevMobAI 어댑터, Plan, Mob.Hunting)

**Files:**
- Modify: `JevMobAI.java:59-68` (Plan), `JevMobAI.java:106-130` (`destinationFor`·`logMoveStep`·`logMoveBlocked`), `JevMobAI.java:521-524` (`plannerMember`)
- Modify: `Mob.java:808-835` (`moveToFlankingPosition` 삭제), `Mob.java:1489-1517` (Hunting 전술 블록)

**Interfaces:**
- Consumes: Task 3 `SquadPlan`, `assign`, `reassign`, `retarget`, `nextStep`, Task 4 `FormationPlanner`
- Produces:
  ```java
  // JevMobAI
  static int tacticalStep(Mob mob, String tactic); // -1이면 기본 AI, mob.pos면 대기, 그 외는 이동할 칸
  private static SquadWorld gameWorld(Level level, List<Mob> squad); // 어댑터
  // Plan에 추가: SquadMovementPlanner.SquadPlan squadPlan; FormationPlanner.State formation; boolean degradeLogged;
  // Plan의 moveGoals와 moveGoalTypes는 Task 6에서 삭제한다(구 응답 처리가 아직 씀)
  ```

**어댑터 규칙**
- `passable(m, c)`: 멤버별로 `Dungeon.findPassable(mob, level.passable, mob.fieldOfView, false, true)`를 **복사**해서 캐시하고, 그 값을 돌려준다. 원본은 공유 static 배열이다.
- `occupied(c)`: `Actor.findChar(c) != null`
- `visible(m, c)`: `mob.fieldOfView[c]`. 배열이 null이거나 범위를 벗어나면 false.
- `plannerMember(mob)`에 `mob.speed()`를 넘긴다.

**`tacticalStep` 규칙**
- `Dungeon.hero`가 null이거나 mob 쪽 영웅 시야가 없으면 -1.
- tactic이 `flank` 또는 `escort_ranged`일 때:
  - `plan.squadPlan`이 null이면 `assign`한다. 배정마다 `move_goal`을 남기고, `degradeReason`이 있으면 `tactic_degraded`를 한 번만 남긴다.
  - 이 멤버의 배정을 다음 순서로 점검한다.
    - goal이 `goalLegal`이 아니면 `reassign`하고 `invalid`를 기록한다.
    - 영웅 위치가 바뀌었으면 `retarget`한다. 실패하면 전체 `assign`을 다시 하고 `sector_lost`, 성공하면 `hero_moved`를 기록한다.
    - escort이고 `allyCell`이 바뀌었으면 전체 `assign`을 다시 하고 `ally_moved`를 기록한다.
  - 마지막으로 `nextStep`의 결과를 반환한다.
- tactic이 `formation`일 때:
  - `plan.formation`이 null이면 새로 만들고, `degradeReason`이 있으면 `tactic_degraded`를 한 번 남긴다.
  - `step`을 호출한다. phase가 바뀌면 `formation_phase`, 해제되면 `formation_released`를 남긴다.
  - `RELEASED`면 -1을 반환한다.
- 그 외 tactic이면 -1.

**`Mob.Hunting` 변경** (1489~1517행의 전술 블록 전체를 다음으로 교체)
```java
String tactic = JevMobAI.tacticFor(Mob.this);
if (enemyInFOV && enemy != null && !rooted && !(canAttack(enemy) && !isCharmedBy(enemy))) {
    int step = JevMobAI.tacticalStep(Mob.this, tactic);
    if (step == pos) { spend(1 / speed()); return true; }
    if (step >= 0 && cellIsPathable(step)) {
        int oldPos = pos;
        move(step);
        JevMobAI.logMoveStep(Mob.this, tactic, oldPos, pos);
        spend(1 / speed());
        return moveSprite(oldPos, pos);
    }
    if (step >= 0) JevMobAI.logMoveBlocked(Mob.this, tactic, pos);
}
```
- `logMoveStep`과 `logMoveBlocked`의 goal 인자는 빼고, 내부에서 Plan의 goal을 조회해 같은 로그 형식을 유지한다.
- `destinationFor`는 `Mob`만 호출하므로 삭제한다. spec 6.1은 "오버레이용으로 유지"라고 했지만 확인 결과 오버레이는 이 메서드를 쓰지 않는다.
- `tacticFor`가 tactic이 바뀐 Plan을 새로 만들면 `squadPlan`과 `formation`은 자연히 null로 시작한다.

- [ ] **Step 1: 시뮬레이션이 그대로 통과하는지 확인** — Run: `powershell -File scripts/simulate_tactical_movement.ps1`. Expected: passed.
- [ ] **Step 2: 위 변경을 구현한다**
- [ ] **Step 3: 빌드 확인** — `spd_builder` 에이전트로 desktop debug 빌드. Expected: BUILD SUCCESSFUL
- [ ] **Step 4: Commit** — `git commit -m "feat(mobs): drive squad tactics through local Dijkstra planner"`

---

### Task 6: Jev 요청 개편과 구 API 정리

**Files:**
- Modify: `JevMobAI.java:161-498` (`requestBatch`), `JevMobAI.java:526-586` (구 헬퍼 삭제)
- Modify: `SquadMovementPlanner.java` (구 API 삭제)
- Modify: `actors.properties`, `actors_ko.properties` (1933행 다음)
- Modify: `TacticalMovementPlannerSimulation.java` (구 테스트 삭제)

**Interfaces:**
- Consumes: Task 3 `openSectors`, `screenPositionAvailable`, Task 4 `FormationPlanner.connected`, `participants`, `heroMap`
- Produces: Jev 요청 JSON의 squad 상태 키 `openSectorsAroundHero`(문자열 목록), `screenPositionAvailable`(boolean), `squadConnected`(boolean). 멤버 키 `routeCostToHero`(int, 도달 불가면 -1). `speed`는 `characterState`에 이미 있다.

**변경 사항**
- `requestBatch`:
  - 멤버별 `routeCostToHero`는 `FormationPlanner.heroMap(Collections.singletonList(member), hero, world)[member.cell] / SquadCostField.BASE`로 구한다. 걸음 수 단위이며, 도달할 수 없으면 -1.
  - 다음을 삭제한다: 목표 질문 생성(293~324행), 목표 응답 처리(424~467행), fallback 루프(468~489행), `destinationChoiceOptions`, `destinationQuestionSquads`, `destinationQuestionMembers`, `movementOptionsByMember`, `flankDirections` 계산.
  - criteria는 다섯 전술 모두를 무조건 `put`한다. 문구는 spec 8.1 설명을 영어로 옮기고, advance와 hold_range는 기존 문구를 그대로 둔다.
  - instruction에서 다음 문장들을 제거한다.
    - "For flank or escort_ranged, coordinate with the separate destination questions."
    - "Never choose a maneuver omitted from criteria."
    - "flankMembersWithReachableGoals and flankApproachDirections summarize ..."로 시작하는 문장
  - instruction에 새 state 키를 설명하는 문장을 하나 추가한다.
  - 응답 허용 목록에 `formation`을 추가한다.
  - tactic이 확정되면 `plan.squadPlan = null`, `plan.formation = null`로 초기화한다.
  - 처리 순서는 역할 → tactic → 리더 외침 순으로 둔다. 지금 순서와 같다.
- Plan의 `moveGoals`와 `moveGoalTypes`를 삭제한다.
- `JevMobAI`에서 다음을 삭제한다: `movementWorld`, `movementCandidates`, `validMovementGoal`, `squadSpacing`, `moveChoiceDescription`, `requiredManeuver`.
  `legalVisibleGoal`은 Task 5의 어댑터가 쓰지 않으면 함께 삭제한다.
- `SquadMovementPlanner`에서 다음을 삭제한다: `World`, `Candidate`, `candidates`, `choiceKey`, `relevantForTactics`, `validateChoice`, `conflictsWithAssignedFlank`, `addFlank`, `flankScore`, `spacing`, `Member`의 5인자 생성자.
  `addEscort`의 기하 규칙은 Task 3의 `escortCells`로 이미 옮겼다.
- 메시지를 추가한다.
  - `actors.mobs.jevmobai.tactic_formation=Close ranks and push!`
  - `actors.mobs.jevmobai.tactic_formation=대열을 맞춰 밀고 들어가!`
- 시뮬레이션에서 구 API를 쓰는 테스트 6개를 삭제한다: `openRoomSupportsDistributedFlankGoals`, `narrowCorridorDoesNotOfferFalseEncirclement`, `escortGoalsScreenAVisibleRangedAlly`, `blockedAndUnseenGoalsAreNeverOffered`, `responseCoordinatesMustMatchOfferedChoicesAndRemainLegal`, `destinationQuestionsMustMatchAvailableTactics`. `randomizedTerrainAndPlacementReliability`와 구 `Fixture`도 삭제한다. 이들이 확인하던 내용은 Task 3의 테스트로 대체됐다.

- [ ] **Step 1: 구 테스트 삭제 후 시뮬레이션 통과 확인** — Expected: passed
- [ ] **Step 2: 위 변경을 구현한다**
- [ ] **Step 3: 남은 참조 확인** — Run: `rg -n "Candidate|candidates\(|validateChoice|choiceKey|moveGoals|move_squad_" core/src`. Expected: 결과 없음.
- [ ] **Step 4: 시뮬레이션과 빌드** — `powershell -File scripts/simulate_tactical_movement.ps1`이 passed이고, `spd_builder` desktop debug 빌드가 BUILD SUCCESSFUL.
- [ ] **Step 5: 수동 플레이 확인** (debug 빌드, Jev 키 설정, 분대 오버레이와 Jev 로그 창 사용)
  - 요청 JSON에 다섯 전술과 새 state 키가 있고 `move_squad_` 질문이 없다.
  - flank: `move_goal`이 서로 다른 방위로 나오고, `move_step` 경로가 tank 축을 벗어나 돈다.
  - formation: 근접 분대가 `formation_phase phase=advance` 이후 붙은 채로 이동하고, 접촉하면 `phase=contact`가 찍힌다.
  - 함정 등으로 rooted된 멤버는 전술 이동을 하지 않는다.
- [ ] **Step 6: Commit** — `git commit -m "feat(jev): offer all tactics with planner facts and drop per-member goal questions"`
