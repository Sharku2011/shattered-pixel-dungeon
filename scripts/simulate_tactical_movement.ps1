$ErrorActionPreference = 'Stop'
$workspace = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$outDir = Join-Path $workspace '.java-user\tactical-sim'
New-Item -ItemType Directory -Force $outDir | Out-Null
$mainDir = Join-Path $workspace 'core\src\main\java\com\shatteredpixel\shatteredpixeldungeon\actors\mobs'
$testDir = Join-Path $workspace 'core\src\test\java\com\shatteredpixel\shatteredpixeldungeon\actors\mobs'
$mainFiles = @('SquadWorld.java', 'SquadDijkstra.java', 'SquadMovementPlanner.java') | ForEach-Object { Join-Path $mainDir $_ }
$testFiles = @('SquadTestMap.java', 'TacticalMovementPlannerSimulation.java') | ForEach-Object { Join-Path $testDir $_ }
& javac -d $outDir $mainFiles $testFiles
if ($LASTEXITCODE -ne 0) { throw "javac failed: $LASTEXITCODE" }
& java -cp $outDir com.shatteredpixel.shatteredpixeldungeon.actors.mobs.TacticalMovementPlannerSimulation
if ($LASTEXITCODE -ne 0) { throw "simulation failed: $LASTEXITCODE" }
