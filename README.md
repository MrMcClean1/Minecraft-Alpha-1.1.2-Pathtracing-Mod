# Alpha Path Tracer

The Beta Path Tracer, ported to Minecraft Alpha 1.1.2_01 (Fabric Loader through Ornithe, Feather mappings).

What works (tested in the cloud on the OpenGL path, Mesa llvmpipe): the traced world (terrain, water, sky, sun,
clouds), your hand and held item, mobs, the in-game Path Tracer settings (Options > Path Tracer...), and the keys
(F7 on/off, Home view, F9 denoiser, F10 look, F4 presets). Vulkan is built in as on Beta but not tested here
(it needs a real GPU; the Windows natives are bundled).

Alpha 1.1.2_01 differs from Beta 1.7.3: no biome colours (grass and leaves are green in terrain.png), no weather,
no Nether, no texture packs. The port answers those (render/Dim.java) instead of reading them.

Build: Java 25 for Gradle (the Ornithe plugins need it), the mod itself targets Java 17.

    gradle build      ->  build/libs/alphapt-1.0.0.jar

Install: AlphaPT.zip (release) is a MultiMC / Prism instance made with the Ornithe installer
(`ornithe-installer install client a1.1.2_01 MultiMC Fabric`), with the mod in .minecraft/mods.
Needs Java 17 or newer in the launcher.

Not ported yet: the benchmark and modpack tests (test/ holds stubs), the StationAPI compatibility code (unused here).
