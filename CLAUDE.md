# CLAUDE.md

Sibling of the Tekkit SMP path tracer. The full handoff (who Alex is, how to build, test, deploy, push, style rules: no em dashes) is in `C:\MinecraftBetaRaytracing\tekkitsmp-pt\CLAUDE.md`. Read it first.

This repo: build with `.\gradlew build -x test` (Java 17). Deploy the jar as `mods\alphapt.jar` in the matching MultiMC instances (see the Tekkit CLAUDE.md). Changes to shared shaders (e.g. `pt_light.glsl`) usually go into all three repos.
