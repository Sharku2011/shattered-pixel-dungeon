$ErrorActionPreference = 'Stop'
$workspace = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$outDir = Join-Path $workspace '.java-user\tactical-sim'
New-Item -ItemType Directory -Force $outDir | Out-Null
$planner = Join-Path $workspace 'core\src\main\java\com\shatteredpixel\shatteredpixeldungeon\actors\mobs\SquadMovementPlanner.java'
$simulation = Join-Path $workspace 'core\src\test\java\com\shatteredpixel\shatteredpixeldungeon\actors\mobs\TacticalMovementPlannerSimulation.java'
& javac -d $outDir $planner $simulation
if ($LASTEXITCODE -ne 0) { throw "javac failed: $LASTEXITCODE" }
& java -cp $outDir com.shatteredpixel.shatteredpixeldungeon.actors.mobs.TacticalMovementPlannerSimulation
if ($LASTEXITCODE -ne 0) { throw "simulation failed: $LASTEXITCODE" }
