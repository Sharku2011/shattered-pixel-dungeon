---
name: shattered-pixel-dungeon-build
description: Build the Shattered Pixel Dungeon desktop debug or release artifact using this repository's exact Gradle tasks and jar names.
---

# Shattered Pixel Dungeon desktop builds

Use this skill only for builds in this repository.

Run commands from the repository root with the Windows wrapper:

- Debug build: `.\gradlew.bat :desktop:debug`
  - Output: `desktop/build/libs/desktop-4.0.0-INDEV.jar`
- Release build: `.\gradlew.bat :desktop:release`
  - Output: `desktop/build/libs/desktop-4.0.0.jar`

Match the task to the requested build type. If the user just asks for a build while iterating, use the debug task. Report success only after Gradle completes successfully and confirm the expected jar exists.

Compile the game but leave launching it to the user. Do not run the jar or open the GUI unless the user explicitly asks you to.
