# Jev 분대 전술 이동: 다익스트라 비용장 기반 재설계

- 날짜: 2026-10-07
- 브랜치: `codex/jev-ai-dungeon-director`
- 관련 파일: `SquadMovementPlanner.java`, `JevMobAI.java`, `Mob.java`, `TacticalMovementPlannerSimulation.java`, `actors*.properties`

## 1. 배경과 목표

현재 flank/escort 이동은 다음과 같이 동작한다.

- `SquadMovementPlanner`가 멤버마다 후보 셀을 최대 8개 만들고, 후보마다 `Dungeon.findPath`로 도달 가능성을 검사한다(멤버당 최대 80회).
- Jev(LLM)가 멤버별 목표 질문(`move_squad_*_member_*`)에 답해 목표 셀을 고른다.
- 이동은 `Mob.getCloser(destination)`가 담당한다. `PathFinder`는 균일 비용 BFS 거리맵이다(A*가 아님).

해결할 문제:

- **A. 경로 겹침**: 목표는 달라도 같은 통로로 줄지어 이동해 포위가 아닌 일렬 돌진이 된다.
- **C. 우회가 우회답지 않음**: 측면으로 가는 도중 영웅 근접 구간과 정면 축을 그대로 통과한다.
- **D. 성능·구조**: 후보별 `findPath` 반복과, 멤버별로 따로 답하는 LLM 목표 질문이 비효율적이다.

추가 목표:

- 근접 분대가 붙은 대열로 함께 전진하는 새 전술 `formation`을 도입한다.
- 전술 선택은 Jev에게 맡긴다. 코드는 선택지를 걸러내지 않고, 판단 재료가 되는 사실을 state로 제공한다. 실행할 수 없는 선택은 실행 단계에서 안전하게 degrade한다.

역할 분담 원칙: **Jev는 분대 전술(무엇을 할지)을, 로컬 플래너는 기하학적 배치와 경로(어떻게 할지)를 맡는다.**

## 2. 비목표

- 엔진 `PathFinder`(`SPD-classes`) 변경, 일반 몹 이동 변경
- 원거리 몹의 사격 위치 선정
- `hold_range` 동작 변경
- Plan 수명 규칙(12턴, 영웅 6칸 이상 이동, 구성 변경) 변경

## 3. 구조

`SquadMovementPlanner`를 게임 의존성 없는 순수 플래너로 확장한다. 게임 상태는 기존처럼 `World` 인터페이스로 주입하며, 여기에 통과 가능 여부 질의를 더한다.

| 단위 | 책임 |
|---|---|
| `SquadMovementPlanner` (순수) | 비용장 생성, 버킷 큐 다익스트라, 목표 배정, 다음 걸음 계산, formation 걸음 선택, 사전 배정(dry-run) |
| `JevMobAI` | Jev 요청/응답, state 구성, Plan에 배정 결과 보관, 게임 → `World` 어댑터, 로그 |
| `Mob.Hunting` | 매 턴 플래너에 다음 걸음을 묻고 `move(step)` 실행. 공격 우선 |

`Mob.moveToFlankingPosition`은 삭제한다.

## 4. 비용장

**그래프**: 8방향 이동이며, 대각선도 직선과 같은 비용이다. 통과 규칙은 `Dungeon.findPath`와 같다. `passable`인 칸만 쓰고 `avoid`는 제외하며, LARGE 몹은 `openSpace`만 지나고, 해당 몹에게 보이는 캐릭터가 있는 칸은 막힌 것으로 본다.

**칸 진입 비용** = 기본 10 + 페널티 합. 모든 페널티는 소프트 비용이다.

| 항목 | 대상 칸 | 페널티 | 적용 |
|---|---|---|---|
| 영웅 근접 | 영웅과 거리 1 (자기 목표 셀 제외) | +40 | flank, escort |
| 영웅 근접 | 영웅과 거리 2 | +15 | flank, escort |
| 주공 축선 | 축 선분에서 수직거리 ≤1 | +30 | flank |
| 주공 축선 | 축 선분에서 수직거리 2 | +10 | flank |
| 경로 예약 | 다른 멤버 예약 경로 위의 칸 | +25 | flank, escort |
| 경로 예약 | 예약 칸에 인접한 칸 | +10 | flank, escort |
| 현재 인접 | 다른 분대원 현재 위치의 주변 8칸 | +15 | flank, escort (매 턴) |

- **축 선분**: flank에서는 정면 멤버 위치↔영웅이고, escort_ranged에서는 tank의 엄호 목표↔영웅이다.
- formation은 페널티 없이 지형 비용만 쓴다.
- 모든 가중치는 `SquadMovementPlanner`의 상수로 둔다.
- 거리와 인접은 모두 체비셰프 거리(대각선 = 1)다. "거리 1"은 둘러싼 8칸을 뜻한다.
- 계산 범위는 영웅과 분대원 위치를 모두 감싸는 최소 사각형을 사방으로 4칸씩 넓힌 영역으로 제한한다. 이 여백은 사각형 밖으로 휘어 나가는 우회 경로를 허용하기 위한 것이며, 상수 `SEARCH_MARGIN = 4`로 둔다.
- 비용이 작은 정수이므로 버킷 큐 다익스트라를 쓴다.

## 5. 목표 배정 (flank, escort_ranged)

### 5.1 역할 분류

**정면 멤버**: tank를 쓴다. tank가 없으면(역할 미정 포함) 근접 tactical 멤버 중 `routeCostToHero`가 가장 낮은 멤버를 쓰고, 동점이면 id가 작은 쪽을 쓴다.

| tactic | 정면 | escort 목표 | flank 목표(측면조) | 기존 AI |
|---|---|---|---|---|
| flank | 정면 멤버 (목표 없음) | - | 정면을 뺀 tactical 근접 멤버 | 원거리, instinctive |
| escort_ranged | - | 정면 멤버 | 정면과 원거리 아군을 뺀 tactical 근접 멤버 | 원거리, instinctive |

escort_ranged에서는 instinctive tank도 escort를 수행한다(현재 동작 유지).

### 5.2 후보 셀

- **flank**: 영웅과 거리 1이고, 해당 멤버에게 보이며 `legal`인 칸.
- **escort**: 기존 escort 후보 규칙을 유지한다. 영웅↔원거리 아군 선분의 30/45/60% 지점 주변 칸으로, 영웅과 거리 2 이상이고 아군까지의 거리가 영웅↔아군 거리보다 짧아야 한다. 영웅이 보이는 원거리 아군 중 dealer를 우선한다.

### 5.3 방위 점수

- `diff` = 정면 멤버의 방위 버킷과 후보 셀 방위 버킷의 원형 차이(0~4).
- `diff ≤ 1`은 정면이므로 후보에서 제외한다.
- 방위 점수 = `(4 - diff) × 20`.

### 5.4 배정 순서 (탐욕적, 결정적)

1. escort_ranged이면 tank의 escort 목표를 먼저 정한다. tank 위치에서 다익스트라를 한 번 돌리고, `경로 비용 + 기존 escort 위치 점수`가 가장 낮은 칸을 고른 뒤 경로를 예약한다.
2. 배정되지 않은 측면조마다 현재 예약을 반영한 비용장으로 다익스트라를 한 번 돌린다.
3. 모든 (멤버, 셀) 쌍 중 `경로 비용 + 방위 점수`가 가장 낮은 쌍을 배정한다. 방위 버킷당 한 명만 배정하고, 동점이면 멤버 id 순으로 정한다.
4. 배정된 경로를 예약하고 2번으로 돌아간다. 후보가 남지 않으면 종료한다.

배정 1회당 다익스트라 실행 횟수는 `N(N+1)/2 + 1` 이하다(N = 측면조 수).

배정받지 못한 멤버는 기존 AI로 압박한다.

## 6. 실행 (flank, escort_ranged)

### 6.1 저장

Plan에 멤버별 `Assignment`를 둔다. 저장 항목은 목표 셀, maneuver, 방위 버킷, 예약 경로, 배정 당시의 영웅 위치와 원거리 아군 위치다. `moveGoals`와 `moveGoalTypes`는 이 구조로 대체한다. `destinationFor(Mob, String)`의 시그니처는 디버그 오버레이와 로그를 위해 유지한다.

### 6.2 매 턴 (해당 멤버의 Hunting 턴, 영웅이 보일 때만)

1. `canAttack(영웅)`이면 공격한다.
2. 목표가 점유됐거나 보이지 않으면 이 멤버만 다시 배정한다(5.4의 2~4단계를 이 멤버에게만 적용).
3. 목표에서 역방향 다익스트라를 돌린다(비용장 = 4절 + 다른 멤버의 남은 예약 + 현재 인접). 인접 칸 중 남은 비용이 최소인 칸을 다음 걸음으로 하고, 남은 경로로 자기 예약을 갱신한다.
4. `move(step)`으로 이동한다. 걸음 소비와 스프라이트 처리는 기존과 같다. 다음 칸이 막혀 있으면, 비용이 유한하고 현재 칸보다 나쁘지 않은 다른 인접 칸으로 간다. 그것도 없으면 대기하고 `move_step_blocked`를 기록한다.

영웅이 보이지 않으면 목표 이동을 하지 않고 기존 AI를 따른다.

### 6.3 영웅과 아군 이동 대응

- 영웅 위치가 배정 당시와 다르면 방위는 유지하고, 새 위치 기준으로 같은 방위의 링 칸 중 가장 싼 칸으로 목표를 옮긴다. 그 방위에 쓸 수 있는 칸이 없으면 분대 전체를 다시 배정한다.
- escort tank는 원거리 아군 위치가 바뀌면 엄호 목표를 다시 계산한다.

## 7. formation 전술

**참여자**: 근접 tactical 멤버만 참여한다. 원거리와 instinctive 멤버는 기존 AI로 움직이고, 연결 판정에서도 빠진다.

**연결 규칙**: 참여자로 만든 인접 그래프(거리 1이면 간선)가 하나로 연결돼 있어야 한다. 각 멤버가 다른 참여자 중 적어도 한 명과 인접하면 된다.

**공용 영웅 거리맵**: 분대마다 영웅 위치를 소스로 하는 지형 비용 다익스트라 맵을 하나 만들고, 영웅 위치나 턴이 바뀔 때 다시 만든다.

**멤버 행동 (각자 턴에)**
1. `canAttack(영웅)`이면 공격한다.
2. 참여자 중 누구든 영웅에게 인접하면 Plan을 **접촉 단계**로 바꾼다. 이후 전원이 advance처럼 움직인다.
3. 접촉 전이고 분대가 연결돼 있으면, 제자리를 포함한 9개 칸 중 이동 후에도 참여자 그래프가 연결된 채 남는 칸만 후보로 둔다. 그중 영웅 거리맵 값이 최소인 칸으로 이동한다. 동점이면 영웅과의 거리가 가장 앞선 참여자와 같은 칸(나란히 서기)을 우선하고, 그다음 id 순으로 정한다. 가장 좋은 후보가 제자리면 대기한다. 이 규칙만으로 빠른 멤버는 1칸 이상 앞서지 못하고 가장 느린 멤버의 속도에 맞춰진다.
4. 접촉 전이고 분대가 끊겨 있으면 **집결** 단계다. 가장 느린 참여자(`speed()` 최소, 동점이면 id 최소)가 기준점이 되어 제자리에서 기다리고, 나머지는 기준점까지의 경로로 이동한다.

**해제**
- 집결 단계가 4턴을 넘거나, 전진 단계에서 참여자 전체의 영웅 거리맵 최솟값이 3턴 연속 줄지 않으면 advance로 전환한다.
- 전환할 때 `formation_released reason=gather_timeout|stalled`를 기록한다.

## 8. Jev 연동

### 8.1 선택지

`advance`, `flank`, `escort_ranged`, `hold_range`, `formation`을 항상 모두 제공한다. 지시문에서 "criteria에 없는 전술 금지" 문구와 "목표 질문과 맞춰 고르라" 문구를 삭제한다. criteria에는 각 전술이 언제 유리한지와 어떤 조건이 필요한지를 설명한다.

- `flank`: 정면 멤버가 압박하는 동안 근접 멤버들이 정면을 피해 서로 다른 측면·후방 방위로 돌아가 영웅에게 붙는다. 열린 방위가 있어야 효과가 있다.
- `escort_ranged`: tank가 영웅과 원거리 아군 사이에서 엄호하고, 나머지 근접 멤버는 측면으로 돌아간다. 엄호 위치가 있어야 효과가 있다.
- `formation`: 근접 병종만으로 된 분대가 서로 붙은 대열을 유지하며 가장 느린 멤버의 속도로 함께 전진해 동시에 접촉한다. 원거리·instinctive 멤버는 대열에 참여하지 않는다.
- `advance`, `hold_range`: 기존 설명을 유지한다.

### 8.2 state (판정이 아닌 사실)

| 항목 | 단위 | 내용 |
|---|---|---|
| `openSectorsAroundHero` | 분대 | flank 사전 배정으로 확인한 방위 이름 목록 |
| `screenPositionAvailable` | 분대 | escort 사전 배정 성공 여부 |
| `squadConnected` | 분대 | 근접 tactical 멤버 그래프의 연결 여부 |
| `routeCostToHero` | 멤버 | 공용 영웅 거리맵 값. 도달 불가면 -1 |
| `speed` | 멤버 | `speed()` |
| `isRangedAttacker`, `intelligence`, role | 멤버 | 기존 항목 |

삭제하는 항목: `flankMembersWithReachableGoals`, `flankApproachDirections`, `escortGoalAvailable`.

### 8.3 삭제하는 코드

- `move_squad_*_member_*` 질문 생성과 응답 처리, `move_goal_fallback` 루프
- `destinationChoiceOptions`, `destinationQuestionSquads`, `destinationQuestionMembers`
- `choiceKey`, `validateChoice`, `relevantForTactics`, `conflictsWithAssignedFlank`, `moveChoiceDescription`
- 선택지 필터용 분기(`flankAvailable`, `hasEscortCandidates`, `hasRangedAlly`에 따른 criteria 추가). 관련 값은 state 사실로만 남긴다.
- `Mob.moveToFlankingPosition`

### 8.4 응답 처리 순서

역할 배정 응답 → tactic 확정(허용 목록에 `formation` 추가, confidence ≥ 0.45 기준 유지) → 리더 외침 → 실제 배정(flank/escort) 또는 formation 상태 초기화.

리더 외침 메시지 `tactic_formation`을 `actors.properties`와 `actors_ko.properties`에 추가한다.

### 8.5 실행 단계 degrade

| 선택 | 상황 | 처리 | 로그 |
|---|---|---|---|
| flank | 열린 방위가 없음 | 측면조 배정 없이 기존 AI(사실상 advance) | `tactic_degraded reason=no_open_sector` |
| escort_ranged | 원거리 아군 또는 엄호 위치가 없음 | 정면 멤버가 압박하고 나머지는 flank 배정 | `tactic_degraded reason=no_screen_position` |
| formation | 참여자가 2명 미만 | advance | `tactic_degraded reason=too_few_melee` |
| formation | 원거리 멤버가 섞임 | 근접 멤버만 대열, 나머지는 기존 AI | `tactic_degraded reason=mixed_squad` |

Jev API 키가 없을 때의 동작(tactic은 항상 advance)은 바뀌지 않는다.

## 9. 로그

- 유지: `move_goal`, `move_step`, `move_step_blocked`, `decision`, `rejected`, `role_*`
- 추가: `move_goal_reassigned reason=invalid|hero_moved|sector_lost|ally_moved`, `formation_phase phase=gather|advance|contact`, `formation_released`, `tactic_degraded`

## 10. 테스트

`TacticalMovementPlannerSimulation`을 새 플래너 API에 맞게 다시 쓴다. 게임 없이 실행하는 standalone main이며, `scripts/simulate_tactical_movement.ps1`로 돌린다.

1. **다익스트라 정확성**: 페널티가 없으면 BFS 거리와 일치한다.
2. **우회 (C)**: tank 뒤에 줄지어 선 분대에서, 측면조 경로가 축 띠(수직거리 ≤1)와 영웅 거리 1 칸(목표 제외)을 지나지 않는다.
3. **경로 분리 (A)**: 우회로가 있는 15×15 방에서 두 측면조 경로의 공유 칸이 2칸 이하다. 1칸 폭 통로에서도 배정에 실패하지 않는다.
4. **방위**: 배정된 목표의 `diff ≥ 2`이고 멤버마다 버킷이 다르다. 1칸 폭 통로에서는 측면조 배정이 나오지 않는다.
5. **escort**: 엄호 위치가 영웅↔원거리 아군 사이에 투영되고, 영웅과 거리 2 이상이다.
6. **formation**: 무작위 지형과 속도 조합 200회. 접촉 전 매 턴 참여자 그래프가 연결돼 있고, 접촉하면 advance로 전환되며, 정체가 3턴 이어지면 해제된다.
7. **무작위 지형 500개**: 목표가 legal·reachable하고, 같은 입력이면 같은 결과가 나온다.
8. **비용 상한**: 배정 1회당 다익스트라 실행이 `N(N+1)/2 + 1` 이하다.
9. **degrade 전수**: 전술 5종 × 분대 구성(근접만, 혼합, 원거리 1명, instinctive 포함) × 지형(열린 방, 1칸 통로, 막힌 방)에서 예외가 없고, 정상 실행이거나 degrade 로그가 남는다.

마지막으로 `spd_builder`로 데스크톱 debug 빌드를 확인하고, 분대 오버레이와 Jev 로그 창으로 실제 플레이에서 동작을 확인한다.
