# Alpha Path Tracer

The Beta Path Tracer, ported to Minecraft Alpha 1.1.2_01 (Fabric Loader through Ornithe, Feather mappings).

What works (tested in the cloud on the OpenGL path, Mesa llvmpipe): the traced world (terrain, water, sky, sun,
clouds), your hand and held item, mobs, the in-game Path Tracer settings (Options > Path Tracer...), and the keys
(F7 on/off, Home view, F9 denoiser, F10 look, F4 presets). Vulkan is built in as on Beta but not tested here
(it needs a real GPU; the Windows natives are bundled).

Added for Alpha: a GUI scale option (Options > Path Tracer..., top button: Auto, Small, Normal, Large), because
Alpha always makes menus as big as they fit; F2 screenshots into .minecraft/screenshots, which Alpha doesn't have;
and the Path Tracer... button sits beside Controls... in Options.

This stays a vanilla pack, like BetaPT: Alpha plus the path tracer, nothing else.

Alpha 1.1.2_01 differs from Beta 1.7.3: no biome colours (grass and leaves are green in terrain.png), no weather,
no Nether, no texture packs, and its terrain.png has a different tile 38 (the iron block's side, where Beta keeps the
grass side overlay), so the grass overlay is off. The port answers those (render/Dim.java) instead of reading them.

Build: Java 25 for Gradle (the Ornithe plugins need it), the mod itself targets Java 17.

    gradle build      ->  build/libs/alphapt-1.0.0.jar

Install: AlphaPT.zip (release) is a MultiMC / Prism instance made with the Ornithe installer
(`ornithe-installer install client a1.1.2_01 MultiMC Fabric`), with the mod in .minecraft/mods.
Needs Java 17 or newer in the launcher.

Not ported yet: the benchmark and modpack tests (test/ holds stubs), the StationAPI compatibility code (unused here).

Test scene: `gradle runClient -Pscene [-PsceneView=0..5]` builds a row of every block high in the air and holds the camera on it.

## Quick test

About 2 minutes on a real GPU (10 in the cloud on a software renderer). It makes a world "alphapt-test", builds a
row of every block in the air, checks the path tracer is running, the Alpha fixes (no Beta grass overlay, leaves
whatever their decay data, the GUI scale cap), the Options / Path Tracer / Effects menus at 5 window sizes and 4 GUI
scales (no button overlapping another or off the screen) and the F2 screenshot, then compares the traced picture
with Alpha's own from six sides, colour per grid cell (wrong textures show; shadows don't count). Report and pictures:
`.minecraft/alphapt-test/`. The game closes when it's done.

- Linux / cloud: `scripts/test.sh` (Xvfb, Gradle with Java 25).
- Windows (tests your Vulkan path too): make an empty file `.minecraft\alphapt-test\run` in the AlphaPT instance and
  start the game (the file is removed when the test starts, so the next launch is normal). Or add `-Dalphapt.test=true`
  to the JVM arguments in MultiMC (edit them in MultiMC itself: it rewrites instance.cfg while it runs).
  Read `.minecraft\alphapt-test\report.txt`.

The instance runs with `-Xss4m`: Alpha's lighting is recursive and a big change of light near spawn can overflow
the default 1 MB thread stack (it happened in the test's fresh worlds).
