package com.alexmcclellan.betapt.render;

import com.alexmcclellan.betapt.BetaPT;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL42;
import org.lwjgl.opengl.GL43;

/**
 * Owns everything the path tracer draws with, and draws one frame in place of Beta's world.
 *
 * Per frame: work out the camera, run the compute shader into an offscreen image the
 * size of the window, then copy (blit) that image onto the screen. Beta then draws the
 * hotbar, chat and F3 on top as usual, because we only replace the world pass.
 */
public final class PathTracer {
    public static final PathTracer INSTANCE = new PathTracer();

    /** Toggled with F7. When false, Beta renders normally. */
    public boolean enabled = true;
    /** Trace with the GPU's ray-tracing hardware (Vulkan) when it can; OpenGL otherwise. */
    public boolean hardwareRt = true;
    /** Torches (and glowstone, jack o'lanterns, lava buckets) carried or dropped give off light: 0 off, 1 yours, 2 everyone's. */
    public int heldLights = HELD_EVERYONE;
    /** Your first-person hand and held item: drawn by Beta in the traced light's colour, or traced as objects in the world. */
    public int handMode = HAND_BETA;
    /** Particles and rain drawn by Beta in the brightness of the traced light where they are (a grid of light probes). */
    public boolean particleLight = true;
    /** M9 reflections: 0 off, 1 water and glass, 2 also metal blocks, diamond and obsidian. */
    public int reflections = REFLECT_WATER;
    public static final int REFLECT_OFF = 0, REFLECT_WATER = 1, REFLECT_ALL = 2;
    /** Bounces of light: 1 (light off one surface) or 2 (light spills round corners into rooms and caves; slower). */
    public int bounces = 1;
    /** LabPBR maps are used when the texture pack has them and reflections aren't off. */
    public boolean pbrActive() { return BlockPalette.hasPbr() && reflections != REFLECT_OFF; }
    private static final int GRID = 16 * 8 * 16;
    private final float[] gridBright = new float[GRID], gridOpen = new float[GRID];
    private final int[] gridOrigin = new int[3], pendingOrigin = new int[3];
    private boolean gridValid, pendingGrid;
    private float lavaGlow = 1.5f;
    private Object lastPack;
    public boolean particleLightActive() { return particleLight && active() && pathView() && gridValid; }

    /** Brightness Beta should draw a particle or raindrop at this world spot with (0..1), or -1 if unknown. */
    public float lightAt(double x, double y, double z) {
        double gx = (x - gridOrigin[0]) / 2 - 0.5, gy = (y - gridOrigin[1]) / 2 - 0.5, gz = (z - gridOrigin[2]) / 2 - 0.5;
        int x0 = (int) Math.floor(gx), y0 = (int) Math.floor(gy), z0 = (int) Math.floor(gz);
        double fx = gx - x0, fy = gy - y0, fz = gz - z0, sum = 0, w = 0;
        for (int c = 0; c < 8; c++) {
            int ix = x0 + (c & 1), iy = y0 + (c >> 1 & 1), iz = z0 + (c >> 2);
            if (ix < 0 || iy < 0 || iz < 0 || ix >= 16 || iy >= 8 || iz >= 16) continue;
            int k = ix + iy * 16 + iz * 128;
            double wk = ((c & 1) != 0 ? fx : 1 - fx) * ((c >> 1 & 1) != 0 ? fy : 1 - fy) * ((c >> 2) != 0 ? fz : 1 - fz) * gridOpen[k];
            sum += gridBright[k] * wk; w += wk;
        }
        return w < 1e-4 ? -1f : (float) (sum / w);
    }
    public static final int HAND_BETA = 0, HAND_TRACED = 1;

    /** For the shaders: 1 + the traced hand's index among this frame's mobs when walls mustn't hide it, else 0. */
    private int handEntity() { return handCollide || entityCount <= 0 ? 0 : com.alexmcclellan.betapt.render.EntityCapture.handEntity(); }

    /** True when this frame's hand and held item are traced (needs traced mobs, first person, not a map). */
    public boolean tracesHand(Minecraft mc) {
        if (handMode != HAND_TRACED || !active() || !pathView() || !tracesMobs() || mc.player == null || mc.options.perspective) return false;
        net.minecraft.item.ItemStack s = mc.player.inventory.getSelectedItem();
        return s == null || s.id != 358; // a map in hand stays Beta's
    }
    public static final int HELD_OFF = 0, HELD_MINE = 1, HELD_EVERYONE = 2;
    /** Colour Beta should draw the first-person hand and held item with (display units, 0..1), from the traced light there. */
    public final float[] handLight = {1f, 1f, 1f};
    public boolean handLightValid;
    private int probeBuffer;
    private final java.nio.FloatBuffer probeRead = org.lwjgl.BufferUtils.createFloatBuffer(4 * (1 + 16 * 8 * 16));
    public boolean broken; // set if the shader failed to load; we then fall back to vanilla

    /** The view key (Home) cycles: path traced, flat (M3 look), debug map (surface), debug map (cutaway at eye level). */
    public enum View { PATH, FLAT, MAP_SURFACE, MAP_CUTAWAY, VULKAN }
    /** M8c: true when this frame's six trace pictures came from the Vulkan hardware tracer. */
    public boolean vkActive;
    /** Path-traced views: the OpenGL tracer, or the same pipeline fed by the Vulkan hardware tracer. */
    public boolean pathView() { return view == View.PATH || view == View.VULKAN; }
    /** Mobs are traced (and Beta's models hidden) only by the OpenGL tracer so far. */
    public boolean tracesMobs() { return entityShadows && !legacyFrame; }
    public View view = View.PATH;

    /**
     * The "look" slider: 0 = as faithful to Beta as the path tracer gets, 1 = realistic light.
     * Each setting below is given at three points: Beta (0), the default we tuned in M4/M5
     * (0.25), and realistic (1). In between, values slide in a straight line. F10 steps it.
     */
    public float realism = 0.25f;
    /** Brightness slider: multiplies the auto exposure (0.5 = half as bright, 2 = twice). */
    public float brightness = 1f;
    public static final float DEFAULT_REALISM = 0.25f;

    private float look(float beta, float tuned, float real) {
        float r = Math.max(0f, Math.min(1f, realism));
        return r < 0.25f ? beta + (tuned - beta) * (r / 0.25f) : tuned + (real - tuned) * ((r - 0.25f) / 0.75f);
    }

    public final VoxelWorld voxels = new VoxelWorld();
    private final BlockPalette palette = new BlockPalette();
    private final BlockTable blocks = new BlockTable();
    private int program;      // debug map
    private int traceProgram; // primary rays only, flat lighting (M3)
    private int pathProgram, exposureProgram, tonemapProgram; // M4
    private int hdr;          // linear light, 16-bit float per channel
    private int depthTex;     // M6: exact ray distance per pixel, turned into the depth buffer by present.frag
    private int presentProgram, presentVao;
    private int skyFog;       // sky colour as Beta shows it (unaffected by exposure), alpha = how much of the pixel it covers
    private int exposureBuffer;

    // ---- M5 denoiser ----
    /** F9 toggles the denoiser, to compare against the raw 1-sample image. */
    public boolean denoise = true;
    /** Which denoiser when denoise is on: the original (SVGF-style) or Advanced (adds anti-lag and firefly cut, see temporal.comp). */
    public int denoiserMode = DENOISER_ADVANCED;
    public static final int DENOISER_STANDARD = 0, DENOISER_ADVANCED = 1;
    private int temporalProgram, atrousProgram;
    private int light, albedoTex, extraTex;              // written by the path tracer each frame
    private final int[] geom = new int[2];               // this frame's and last frame's geometry (swapped each frame)
    private final int[] accum = new int[2], moments = new int[2]; // running averages, same swapping
    private final int[] filt = new int[2];               // spatial filter ping-pong
    private int cur;                                     // which of each pair is "this frame"
    private boolean historyValid;
    private long lastPathNanos;
    private Camera prevCam;
    private float tanHalfFov, prevTanHalfFov;
    private static final int[] ATROUS_STEPS = {1, 2, 4, 8, 16};
    private int frameIndex;
    private int image, fbo;
    private int width, height;      // the path tracer's own resolution (render scale x window)
    private int outW, outH;         // the window
    /** M7: fraction of the window size the path tracer draws at (per side); 1 = native. */
    public float renderScale = 1f;
    /** M7: sharpening strength 0..1 (0 = off), AMD RCAS. */
    public float sharpen = 0.5f;
    /** M6: mobs cast path-traced shadows (and block bounce light). */
    public boolean entityShadows = true;
    public int entityCount;
    private int upscaleProgram, rcasProgram;
    /** M8: which upscaler turns the render-scale picture into the window picture. */
    public static final int UPSCALER_FSR = 0, UPSCALER_BASIC = 1, UPSCALER_OFF = 2; // OFF: always native size, no upscaling
    public int upscaler = UPSCALER_FSR;
    private final Fsr3 fsr = new Fsr3();
    private int fsrInputsProgram;
    private int fsrDepth, fsrMotion;                 // render-size inputs FSR needs (fsr_inputs.comp)
    private float jitX, jitY, prevJitX, prevJitY;    // sub-pixel camera nudge this frame and last (FSR only)
    private int jitterFrame;
    private Camera fsrPrevCam;
    private float fsrPrevTan;
    private long fsrLastNanos;
    /** True when the last frame was upscaled by FSR 3.1 (for F3 and the test report). */
    public boolean fsrActive;
    /** M8: shadows of Beta's clouds on the ground (menu: Cloud shadows). */
    public float cloudShadows = DEFAULT_CLOUD_SHADOWS; // 0 = off .. 1 = clouds block all direct sunlight
    public static final float DEFAULT_CLOUD_SHADOWS = 0.55f;

    /**
     * Menu presets: one click sets the look-related options together. Speed options (render scale,
     * upscaler, sharpening, trace distance) are left alone.
     *   Beta      - as close to Beta as the path tracer gets: Beta's light levels, no cloud shadows,
     *               mobs drawn by Beta with its round shadow blobs
     *   Faithful  - the default: Beta's feel with soft real shadows, cloud shadows, traced mobs
     *   Realistic - real light: dark nights and caves, strong eye adjustment, strong cloud shadows
     */
    public enum Preset {
        // effects: FX_* bits (Beta none; Faithful wet surfaces and god rays; Realistic all of them)
        // Off: Beta's own renderer (the path tracer off); the other settings stay as they are
        OFF("Off", DEFAULT_REALISM, 1f, DEFAULT_CLOUD_SHADOWS, true, HELD_EVERYONE, HAND_BETA, true, DENOISER_ADVANCED, REFLECT_WATER, 1, 0),
        BETA("Beta", 0f, 1f, 0f, false, HELD_OFF, HAND_BETA, false, DENOISER_STANDARD, REFLECT_OFF, 1, 0),
        FAITHFUL("Faithful", DEFAULT_REALISM, 1f, DEFAULT_CLOUD_SHADOWS, true, HELD_EVERYONE, HAND_BETA, true, DENOISER_ADVANCED, REFLECT_WATER, 1,
                FX_WET | FX_GODRAYS),
        REALISTIC("Realistic", 1f, 1f, 0.85f, true, HELD_EVERYONE, HAND_TRACED, true, DENOISER_ADVANCED, REFLECT_ALL, 2, 63);
        public final String label;
        final float realism, brightness, clouds;
        final boolean tracedMobs;
        final int held, hand;
        final boolean particles;
        final int denoiser, reflections, bounces, effects;
        Preset(String label, float realism, float brightness, float clouds, boolean tracedMobs, int held, int hand, boolean particles, int denoiser,
               int reflections, int bounces, int effects) {
            this.bounces = bounces;
            this.effects = effects;
            this.particles = particles; this.denoiser = denoiser; this.reflections = reflections;
            this.label = label; this.realism = realism; this.brightness = brightness; this.clouds = clouds; this.tracedMobs = tracedMobs;
            this.held = held; this.hand = hand;
        }
    }

    public void apply(Preset p) {
        if (p == Preset.OFF) { enabled = false; return; }
        enabled = true;
        realism = p.realism;
        brightness = p.brightness;
        cloudShadows = p.clouds;
        entityShadows = p.tracedMobs;
        heldLights = p.held;
        handMode = p.hand;
        particleLight = p.particles;
        denoiserMode = p.denoiser;
        reflections = p.reflections;
        bounces = p.bounces;
        setEffects(p.effects);
        denoise = true;
    }

    /** Turns the effects on and off from FX_* bits. */
    public void setEffects(int fx) {
        fxWet = (fx & FX_WET) != 0; fxHaze = (fx & FX_HAZE) != 0; fxSparkle = (fx & FX_SPARKLE) != 0;
        fxGodRays = (fx & FX_GODRAYS) != 0; fxWaving = (fx & FX_WAVING) != 0; fxWaves = (fx & FX_WAVES) != 0;
    }

    /** The preset the current settings match, or null ("Custom"). */
    public Preset preset() {
        if (!enabled) return Preset.OFF;
        for (Preset p : Preset.values())
            if (p != Preset.OFF && Math.abs(realism - p.realism) < 0.01f && Math.abs(brightness - p.brightness) < 0.01f
                    && Math.abs(cloudShadows - p.clouds) < 0.01f && entityShadows == p.tracedMobs && heldLights == p.held
                    && handMode == p.hand && particleLight == p.particles && denoiserMode == p.denoiser
                    && reflections == p.reflections && bounces == p.bounces && effects() == p.effects && denoise) return p;
        return null;
    }
    /**
     * How far rays look, in blocks; 0 = Auto (Beta's render distance: Far 256, Normal 128, Short 64,
     * Tiny 32). Fog ends there. Shorter is faster; past 248 the world copy on the GPU runs out, and
     * past Beta's own render distance chunks may not be loaded yet (they show as empty).
     */
    public int traceDistance = 0;
    /**
     * Leaves: Fancy (see-through, dappled light) or Fast (solid, like Beta's Fast graphics). Fast is much
     * quicker where leaves fill the view (Twilight Forest canopies): rays stop at the first leaf block
     * instead of testing every leaf face they pass.
     */
    public boolean fastLeaves = false;
    /** This frame's grass side overlay (1 + tile, 0 on Fast graphics) and whether the camera is under water (Vulkan gets them too). */
    private int grassOverlay;
    private boolean camInWater;

    /*
     * Optional effects (Path Tracer > Effects...), each its own switch; the presets set them too.
     * Wet surfaces and god rays are on by default (Faithful), the rest off.
     */
    public boolean fxWet = true;      // rain darkens and wets what's open to the sky; puddles on flat ground
    public boolean fxHaze = false;    // rain pulls the fog in
    public boolean fxSparkle = false; // snow and ice glint in the sun
    public boolean fxGodRays = true;  // sun shafts through leaves and gaps
    public boolean fxWaving = false;  // leaves, grass, flowers and crops sway
    public boolean fxWaves = false;
    /** Effects > Nights: Dark: the picture is dimmed at night (up to 40% at midnight) for Beta's darker nights. */
    public boolean darkNights = false;
    /**
     * The traced hand and held item: false (default) = they show even where a block is nearer than them (standing
     * against a wall, the hand reaches into it), as Beta draws them on top of everything; true = blocks hide them.
     */
    public boolean handCollide = false;   // water ripples (reflections shimmer)
    public static final int FX_WET = 1, FX_HAZE = 2, FX_SPARKLE = 4, FX_GODRAYS = 8, FX_WAVING = 16, FX_WAVES = 32;

    /** The effects that are on, as pt_base.glsl's FX_* bits. */
    public int effects() {
        return (fxWet ? FX_WET : 0) | (fxHaze ? FX_HAZE : 0) | (fxSparkle ? FX_SPARKLE : 0) | (fxGodRays ? FX_GODRAYS : 0)
                | (fxWaving ? FX_WAVING : 0) | (fxWaves ? FX_WAVES : 0);
    }
    /** This frame's effect values: seconds of game time, wind, rain wetness at the camera. */
    private float fxTime, fxWind, fxWetness;
    /** How much it was raining where the camera was last frame (0..1; 0 in snowy and dry biomes). */
    public float lastWetness() { return fxWetness; }
    /** Cloud shadow strength used last frame (0 = off), and the cloud sheet (height, drift, texels per block, v offset). */
    public float cloudStrength;
    public final float[] cloud = new float[4];

    /**
     * Where Beta's cloud layer is and how it maps clouds.png onto the world, worked out the way
     * WorldRenderer.renderClouds / renderFancyClouds draw it, so the shadows sit under the
     * clouds you see and drift with them.
     */
    private void cloudUniforms(Minecraft mc, float tickDelta, int p) {
        boolean fancy = mc.options.fancyGraphics;
        int ticks = ((com.alexmcclellan.betapt.mixin.WorldRendererAccessor) mc.worldRenderer).betapt$getTicks();
        cloud[0] = Dim.cloudHeight(mc.world) + 0.33f + (fancy ? 2f : 0f); // fancy clouds are 4 thick: use the middle
        cloud[1] = (ticks + tickDelta) * 0.03f;                                    // the drift along +x
        cloud[2] = fancy ? 1f / 12f : 256f / 2048f;                                // texels per block
        cloud[3] = fancy ? 0.33f : 0f;
        cloudStrength = Dim.nether(mc.world) ? 0f : Math.max(0f, Math.min(1f, cloudShadows));
        GL20.glUniform4f(loc(p, "uCloud"), cloud[0], cloud[1], cloud[2], cloud[3]);
        GL20.glUniform1f(loc(p, "uCloudShadow"), cloudStrength);
        bindTexture(7, GL11.GL_TEXTURE_2D, mc.textureManager.load("/clouds.png"));
    }

    /** M8b: last frame's camera, sun and ray range, for the Vulkan probe to trace the same view. */
    public Camera lastCam;
    public final float[] sunDir = new float[3];
    public float maxDist = 128f;
    public float tanHalfFov() { return tanHalfFov; }
    public int outWidth() { return outW; }
    public int outHeight() { return outH; }
    private static final float FSR_NEAR = 0.05f;
    private int upImage, outImage;  // window-sized: upscaled, then sharpened
    private int presentTex;         // whichever image ends up on screen this frame
    private GpuTimer timer;
    private StageTimer stages;
    /** GPU time per stage, e.g. "trace 6.12, temporal 0.21, ..." (path traced view only). */
    public String stageSummary() { return stages == null ? "" : stages.summary(); }
    public void resetStageWindow() { if (stages != null) stages.resetWindow(); }
    public String stageWindowSummary() { return stages == null ? "" : stages.windowSummary(); }

    // ---- autotest switches ----
    /** Exposure jumps to its target at once instead of adapting over ~1 s (lets the test move faster). */
    public boolean snapExposure;
    private int blockLumBuffer;
    /** Cost probe: skip rays (1 sun shadow, 2 torch light, 4 bounce, 8 sun ray from the bounce). */
    public int debugMask;
    /** Cost probe: draw the pre-M6 way, cancelling Beta's frame (see GameRendererMixin). */
    public boolean legacyFrame;
    private long lastFrameNanos;
    private boolean resumed; // the tracer was off last frame
    // Every 10 s of play (not during the autotest), one line in latest.log: how fast and why, so lag can be read back
    private long perfStart;
    private int perfFrames, perfSlow;
    private double perfWorst, perfSum;
    private void perfLog(Minecraft mc, double dt, long now) {
        if (com.alexmcclellan.betapt.test.AutoTest.running() || com.alexmcclellan.betapt.test.ModTest.running()) { perfStart = 0; return; }
        if (perfStart == 0) { perfStart = now; perfFrames = 0; perfSlow = 0; perfWorst = 0; perfSum = 0; return; }
        perfFrames++; perfSum += dt; perfWorst = Math.max(perfWorst, dt);
        if (dt > 33.3) perfSlow++;
        if (now - perfStart < 10_000_000_000L) return;
        BetaPT.LOGGER.info(String.format("Perf: %.1f fps (%.2f ms avg, %.1f worst, %d frames over 33 ms) | %s %dx%d scale %.0f%%, trace %.0f blocks, render dist %d"
                        + " | GPU %s | Vulkan %.2f ms GPU, %.2f ms CPU, chunks %d sent / %d built | lights %d, mobs %d, bounces %d, reflect %d",
                perfFrames / ((now - perfStart) / 1e9), perfSum / Math.max(1, perfFrames), perfWorst, perfSlow,
                vkActive ? "Vulkan" : "OpenGL", width, height, renderScale * 100, maxDist, mc.options.viewDistance,
                stageSummary(), VkBridge.liveGpuMs, VkBridge.liveCpuMs, VkBridge.chunksSent, VkBridge.chunksMeshed,
                voxels.lightCount, entityCount, bounces, reflections));
        perfStart = now; perfFrames = 0; perfSlow = 0; perfWorst = 0; perfSum = 0;
    }
    public double frameMs;

    private PathTracer() {}

    public boolean active() {
        return enabled && !broken;
    }

    private void init(Minecraft mc) {
        program = ShaderUtil.loadCompute("voxel_map.comp");
        traceProgram = ShaderUtil.loadCompute("primary.comp");
        pathProgram = ShaderUtil.loadCompute("pathtrace.comp");
        exposureProgram = ShaderUtil.loadCompute("exposure.comp");
        tonemapProgram = ShaderUtil.loadCompute("tonemap.comp");
        presentProgram = ShaderUtil.loadGraphics("present.vert", "present.frag");
        upscaleProgram = ShaderUtil.loadCompute("upscale.comp");
        rcasProgram = ShaderUtil.loadCompute("rcas.comp");
        presentVao = GL30.glGenVertexArrays();
        temporalProgram = ShaderUtil.loadCompute("temporal.comp");
        atrousProgram = ShaderUtil.loadCompute("atrous.comp");
        fsrInputsProgram = ShaderUtil.loadCompute("fsr_inputs.comp"); // 0 = FSR unavailable, basic upscaler used
        if (program == 0 || traceProgram == 0 || pathProgram == 0 || exposureProgram == 0 || tonemapProgram == 0
                || temporalProgram == 0 || atrousProgram == 0 || presentProgram == 0 || upscaleProgram == 0 || rcasProgram == 0) {
            broken = true;
            BetaPT.LOGGER.error("Path tracer disabled: shader did not load");
            return;
        }
        timer = new GpuTimer();
        stages = new StageTimer();
        exposureBuffer = org.lwjgl.opengl.GL15.glGenBuffers();
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, exposureBuffer);
        GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, org.lwjgl.BufferUtils.createFloatBuffer(4), GL15.GL_DYNAMIC_COPY);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);
        palette.build(mc);
        blocks.build();
    }

    private void resize(int w, int h) {
        if (image != 0) {
            GL11.glDeleteTextures(image);
            GL11.glDeleteTextures(hdr);
            GL11.glDeleteTextures(skyFog);
            GL11.glDeleteTextures(depthTex);
            GL11.glDeleteTextures(fsrDepth);
            GL11.glDeleteTextures(fsrMotion);
            for (int t : new int[]{light, albedoTex, extraTex, geom[0], geom[1], accum[0], accum[1], moments[0], moments[1], filt[0], filt[1]})
                GL11.glDeleteTextures(t);
            GL30.glDeleteFramebuffers(fbo);
        }
        width = w;
        height = h;
        image = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, image);
        GL42.glTexStorage2D(GL11.GL_TEXTURE_2D, 1, GL11.GL_RGBA8, w, h);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR); // the upscaler blends between its pixels
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        hdr = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, hdr);
        GL42.glTexStorage2D(GL11.GL_TEXTURE_2D, 1, GL30.GL_RGBA16F, w, h);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        skyFog = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, skyFog);
        GL42.glTexStorage2D(GL11.GL_TEXTURE_2D, 1, GL30.GL_RGBA16F, w, h);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        light = newImage(w, h); albedoTex = newImage(w, h); extraTex = newImage(w, h);
        depthTex = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, depthTex);
        GL42.glTexStorage2D(GL11.GL_TEXTURE_2D, 1, GL30.GL_R32F, w, h);
        fsrDepth = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, fsrDepth);
        GL42.glTexStorage2D(GL11.GL_TEXTURE_2D, 1, GL30.GL_R32F, w, h);
        fsrMotion = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, fsrMotion);
        GL42.glTexStorage2D(GL11.GL_TEXTURE_2D, 1, GL30.GL_RG16F, w, h);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        for (int i = 0; i < 2; i++) {
            geom[i] = newImage(w, h); accum[i] = newImage(w, h); moments[i] = newImage(w, h); filt[i] = newImage(w, h);
        }
        historyValid = false;

        fbo = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, fbo);
        GL30.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, image, 0);
        int status = GL30.glCheckFramebufferStatus(GL30.GL_READ_FRAMEBUFFER);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, 0);
        if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
            BetaPT.LOGGER.error("Output framebuffer incomplete: 0x{}", Integer.toHexString(status));
            broken = true;
        }
        BetaPT.LOGGER.info("Path tracer output resized to {}x{}", w, h);
    }

    private void resizeOutput(int w, int h) {
        if (upImage != 0) { GL11.glDeleteTextures(upImage); GL11.glDeleteTextures(outImage); }
        outW = w;
        outH = h;
        upImage = newRgba8(w, h);
        outImage = newRgba8(w, h);
    }

    private static int newRgba8(int w, int h) {
        int t = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, t);
        GL42.glTexStorage2D(GL11.GL_TEXTURE_2D, 1, GL11.GL_RGBA8, w, h);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        return t;
    }

    /**
     * M7: stretch the picture from the path tracer's resolution to the window (upscale.comp)
     * when render scale is below 100%, then sharpen it (rcas.comp) if sharpening is on.
     * Leaves presentTex pointing at whichever image is final.
     */
    private void upscaleAndSharpen() {
        presentTex = image;
        if (width != outW || height != outH) {
            GL20.glUseProgram(upscaleProgram);
            GL20.glUniform2i(loc(upscaleProgram, "uOutSize"), outW, outH);
            GL20.glUniform2f(loc(upscaleProgram, "uSrcSize"), width, height);
            bindTexture(0, GL11.GL_TEXTURE_2D, image);
            GL42.glBindImageTexture(0, upImage, 0, false, 0, GL15.GL_WRITE_ONLY, GL11.GL_RGBA8);
            GL43.glDispatchCompute((outW + 7) / 8, (outH + 7) / 8, 1);
            GL42.glMemoryBarrier(GL42.GL_TEXTURE_FETCH_BARRIER_BIT | GL42.GL_FRAMEBUFFER_BARRIER_BIT);
            presentTex = upImage;
        }
        if (sharpen > 0.001f) {
            GL20.glUseProgram(rcasProgram);
            GL20.glUniform2i(loc(rcasProgram, "uSize"), outW, outH);
            GL20.glUniform1f(loc(rcasProgram, "uSharpness"), (float) Math.pow(2.0, -2.0 * (1.0 - Math.min(1f, sharpen))));
            bindTexture(0, GL11.GL_TEXTURE_2D, presentTex);
            GL42.glBindImageTexture(0, outImage, 0, false, 0, GL15.GL_WRITE_ONLY, GL11.GL_RGBA8);
            GL43.glDispatchCompute((outW + 7) / 8, (outH + 7) / 8, 1);
            GL42.glMemoryBarrier(GL42.GL_TEXTURE_FETCH_BARRIER_BIT | GL42.GL_FRAMEBUFFER_BARRIER_BIT);
            presentTex = outImage;
        }
        bindTexture(0, GL11.GL_TEXTURE_2D, 0);
    }

    /** A full-screen RGBA16F image for compute shaders (read and written with imageLoad/imageStore). */
    private static int newImage(int w, int h) {
        int t = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, t);
        GL42.glTexStorage2D(GL11.GL_TEXTURE_2D, 1, GL30.GL_RGBA16F, w, h);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        return t;
    }

    private static void bindImage(int unit, int tex, int access) {
        GL42.glBindImageTexture(unit, tex, 0, false, 0, access, GL30.GL_RGBA16F);
    }

    private void cameraUniforms(int p, Camera cam) {
        GL20.glUniform2i(loc(p, "uResolution"), width, height);
        GL20.glUniform3f(loc(p, "uCamForward"), cam.fx, cam.fy, cam.fz);
        GL20.glUniform3f(loc(p, "uCamRight"), cam.rx, cam.ry, cam.rz);
        GL20.glUniform3f(loc(p, "uCamUp"), cam.ux, cam.uy, cam.uz);
        GL20.glUniform1f(loc(p, "uTanHalfFov"), tanHalfFov);
        GL20.glUniform2f(loc(p, "uJitter"), jitX, jitY);
    }

    /**
     * M8: FSR 3.1. First fsr_inputs.comp turns ray distances into the depth and motion
     * vectors FSR wants, then Fsr3 runs AMD's passes. Returns false if FSR can't run (the
     * caller then uses the basic upscaler).
     */
    private boolean fsrUpscale(Camera cam, long now) {
        boolean reset = fsrPrevCam == null || lightCut || now - fsrLastNanos > 250_000_000L
                || Math.abs(cam.x - fsrPrevCam.x) + Math.abs(cam.y - fsrPrevCam.y) + Math.abs(cam.z - fsrPrevCam.z) > 8.0;
        Camera pc = fsrPrevCam != null && !reset ? fsrPrevCam : cam;
        GL20.glUseProgram(fsrInputsProgram);
        cameraUniforms(fsrInputsProgram, cam);
        GL20.glUniform3f(loc(fsrInputsProgram, "uPrevForward"), pc.fx, pc.fy, pc.fz);
        GL20.glUniform3f(loc(fsrInputsProgram, "uPrevRight"), pc.rx, pc.ry, pc.rz);
        GL20.glUniform3f(loc(fsrInputsProgram, "uPrevUp"), pc.ux, pc.uy, pc.uz);
        GL20.glUniform1f(loc(fsrInputsProgram, "uPrevTanHalfFov"), pc == cam ? tanHalfFov : fsrPrevTan);
        GL20.glUniform3f(loc(fsrInputsProgram, "uEyeDelta"), (float) (cam.x - pc.x), (float) (cam.y - pc.y), (float) (cam.z - pc.z));
        GL20.glUniform1f(loc(fsrInputsProgram, "uNear"), FSR_NEAR);
        entityMotion(fsrInputsProgram);
        GL42.glBindImageTexture(3, geom[cur], 0, false, 0, GL15.GL_READ_ONLY, GL30.GL_RGBA16F);
        GL42.glBindImageTexture(0, depthTex, 0, false, 0, GL15.GL_READ_ONLY, GL30.GL_R32F);
        GL42.glBindImageTexture(1, fsrDepth, 0, false, 0, GL15.GL_WRITE_ONLY, GL30.GL_R32F);
        GL42.glBindImageTexture(2, fsrMotion, 0, false, 0, GL15.GL_WRITE_ONLY, GL30.GL_RG16F);
        GL43.glDispatchCompute((width + 7) / 8, (height + 7) / 8, 1);
        GL42.glMemoryBarrier(GL42.GL_TEXTURE_FETCH_BARRIER_BIT | GL42.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);
        for (int u = 0; u < 4; u++) GL42.glBindImageTexture(u, 0, 0, false, 0, GL15.GL_READ_ONLY, GL11.GL_RGBA8);
        boolean ran = fsr.dispatch(image, fsrDepth, fsrMotion, width, height, outW, outH, jitX, jitY, tanHalfFov, FSR_NEAR,
                (float) (frameMs / 1000.0), sharpen, reset);
        fsrPrevCam = cam;
        fsrPrevTan = tanHalfFov;
        fsrLastNanos = now;
        if (ran) presentTex = fsr.output();
        return ran;
    }

    /**
     * M5: turn the noisy 1-sample light into a clean one, then combine it with the
     * texture colours into the HDR image. See temporal.comp and atrous.comp.
     */
    private void denoiseAndCompose(Camera cam, long now, int groupsX, int groupsY) {
        int prev = cur ^ 1;
        final int R = GL15.GL_READ_ONLY, W = GL15.GL_WRITE_ONLY;
        int input = light;
        if (denoise) {
            boolean valid = historyValid && !lightCut && prevCam != null && now - lastPathNanos < 250_000_000L
                    && Math.abs(cam.x - prevCam.x) + Math.abs(cam.y - prevCam.y) + Math.abs(cam.z - prevCam.z) < 8.0;
            GL20.glUseProgram(temporalProgram);
            cameraUniforms(temporalProgram, cam);
            Camera pc = prevCam != null ? prevCam : cam;
            GL20.glUniform3f(loc(temporalProgram, "uPrevForward"), pc.fx, pc.fy, pc.fz);
            GL20.glUniform3f(loc(temporalProgram, "uPrevRight"), pc.rx, pc.ry, pc.rz);
            GL20.glUniform3f(loc(temporalProgram, "uPrevUp"), pc.ux, pc.uy, pc.uz);
            GL20.glUniform1f(loc(temporalProgram, "uPrevTanHalfFov"), prevCam != null ? prevTanHalfFov : tanHalfFov);
            GL20.glUniform2f(loc(temporalProgram, "uPrevJitter"), prevJitX, prevJitY);
            GL20.glUniform3f(loc(temporalProgram, "uEyeDelta"), (float) (cam.x - pc.x), (float) (cam.y - pc.y), (float) (cam.z - pc.z));
            GL20.glUniform1i(loc(temporalProgram, "uHistoryValid"), valid ? 1 : 0);
            GL20.glUniform1f(loc(temporalProgram, "uMaxHistory"), 24f);
            GL20.glUniform1i(loc(temporalProgram, "uAdvanced"), denoiserMode == DENOISER_ADVANCED ? 1 : 0);
            entityMotion(temporalProgram);
            bindImage(0, light, R);
            bindImage(1, geom[cur], R);
            bindImage(2, accum[prev], R);
            bindImage(3, moments[prev], R);
            bindImage(4, geom[prev], R);
            bindImage(5, accum[cur], W);
            bindImage(6, moments[cur], W);
            bindImage(7, filt[0], W);
            GL43.glDispatchCompute(groupsX, groupsY, 1);
            GL42.glMemoryBarrier(GL42.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);
            input = filt[0];
        }
        stages.stamp(); // end of temporal

        GL20.glUseProgram(atrousProgram);
        cameraUniforms(atrousProgram, cam);
        bindImage(1, geom[cur], R);
        bindImage(3, albedoTex, R);
        bindImage(4, extraTex, R);
        bindImage(5, hdr, W);
        int passes = denoise ? ATROUS_STEPS.length : 1;
        for (int i = 0; i < passes; i++) {
            boolean last = i == passes - 1;
            GL20.glUniform1i(loc(atrousProgram, "uStep"), denoise ? ATROUS_STEPS[i] : 0);
            GL20.glUniform1i(loc(atrousProgram, "uFinal"), last ? 1 : 0);
            int out = filt[(i + 1) & 1];
            bindImage(0, input, R);
            bindImage(2, out, W);
            GL43.glDispatchCompute(groupsX, groupsY, 1);
            GL42.glMemoryBarrier(GL42.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL42.GL_TEXTURE_FETCH_BARRIER_BIT);
            input = out;
        }
        stages.stamp(); // end of spatial

        historyValid = denoise; // with the denoiser off, nothing was accumulated: start fresh when it comes back
        prevCam = cam;
        prevTanHalfFov = tanHalfFov;
        lastPathNanos = now;
        cur ^= 1;
    }

    /** Called from GameRendererMixin in place of Beta's world rendering. */
    public void renderFrame(Minecraft mc, float tickDelta) {
        if (program == 0 && !broken) {
            try { init(mc); }
            catch (Throwable t) { // a start-up failure turns the tracer off instead of stopping the game
                broken = true;
                com.alexmcclellan.betapt.BetaPT.LOGGER.error("Path tracer could not start, using Beta's renderer", t);
            }
        }
        if (broken) return;
        // M9: a different texture pack: new block colours and LabPBR maps, and Vulkan reopens with them
        if (StationCompat.stale) { // StationAPI rebuilt its texture atlas (resource reload): new tile positions
            palette.build(mc);
            VkBridge.reopen();
        }
        int dw = mc.width, dh = mc.height;
        float scale = upscaler == UPSCALER_OFF ? 1f : Math.max(0.25f, Math.min(1f, renderScale));
        int iw = Math.max(16, Math.round(dw * scale)), ih = Math.max(16, Math.round(dh * scale));
        if (iw != width || ih != height) resize(iw, ih);
        if (dw != outW || dh != outH) resizeOutput(dw, dh);
        if (broken) return;

        int savedTex0 = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D); // Beta's texture on unit 0, put back at the end
        long now = System.nanoTime();
        if (lastFrameNanos != 0) {
            double dt = (now - lastFrameNanos) / 1.0e6;
            frameMs = frameMs * 0.95 + dt * 0.05;
            perfLog(mc, dt, now);
        }
        lastFrameNanos = now;

        // Beta has just set its camera up: use exactly that (the legacy order runs before Beta's camera exists)
        Camera cam = legacyFrame ? Camera.from(mc.player, tickDelta) : Camera.fromGl(mc.player, tickDelta);
        voxels.update(mc.world, cam.x, cam.z);
        lastCam = cam;
        lightCut = lightingJumped(mc, tickDelta) || resumed;
        resumed = false;

        timer.begin();
        // M8: FSR needs the camera nudged by a different fraction of a pixel every frame
        boolean useFsr = upscaler == UPSCALER_FSR && fsrInputsProgram != 0 && !fsr.failed() && pathView() && !legacyFrame;
        prevJitX = jitX; prevJitY = jitY;
        if (useFsr) { float[] j = Fsr3.jitter(jitterFrame++, width, outW); jitX = j[0]; jitY = j[1]; }
        else { jitX = 0f; jitY = 0f; }
        int groupsX = (width + 7) / 8, groupsY = (height + 7) / 8;
        if (pathView()) {
            stages.begin();
            // 1. trace into the HDR image
            setupScene(mc, cam, tickDelta, pathProgram);
            voxels.gatherLights((int) Math.floor(cam.x), (int) Math.floor(cam.y), (int) Math.floor(cam.z), heldLightList(mc, cam, tickDelta));
            readHandProbe();
            GL20.glUniform1f(loc(pathProgram, "uLavaGlow"), lavaGlow);
            GL20.glUniform1i(loc(pathProgram, "uReflections"), reflections);
            GL20.glUniform1i(loc(pathProgram, "uPbr"), pbrActive() ? 1 : 0);
            GL20.glUniform1i(loc(pathProgram, "uBounces"), bounces);
            GL20.glUniform1i(loc(pathProgram, "uLightCount"), voxels.lightCount);
            GL20.glUniform1i(loc(pathProgram, "uFrame"), frameIndex++);
            GL20.glUniform1i(loc(pathProgram, "uDebugMask"), debugMask);
            GL20.glUniform2f(loc(pathProgram, "uJitter"), jitX, jitY);
            // M6: last frame's mob boxes, positioned relative to this frame's camera block
            entityCount = !legacyFrame // mobs when they're traced, and blocks drawn by block entity renderers always
                    ? EntityCapture.upload((int) Math.floor(cam.x), (int) Math.floor(cam.y), (int) Math.floor(cam.z)) : 0;
            GL20.glUniform1i(loc(pathProgram, "uEntityCount"), entityCount);
            GL20.glUniform1i(loc(pathProgram, "uHandEnt"), handEntity());
            GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 7, EntityCapture.boxBuffer());
            GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 8, EntityCapture.entityBuffer());
            bindTexture(6, GL30.GL_TEXTURE_2D_ARRAY, EntityCapture.skinArray());
            // Beta end calibrated to vanilla's measured midnight (sand ~27/255); realistic end: no light floor at all
            GL20.glUniform1f(loc(pathProgram, "uAmbient"), look(0.005f, 0.008f, 0.0005f)); // light floor in caves / at night
            GL20.glUniform1f(loc(pathProgram, "uMoon"), look(0.013f, 0.02f, 0.006f));      // moonlight
            GL20.glUniform1f(loc(pathProgram, "uSkyFill"), look(1.8f, 1.0f, 1.0f));        // daylight in shadows: Beta barely has shadows
            cloudUniforms(mc, tickDelta, pathProgram);
            GL20.glUniform1f(loc(pathProgram, "uLowSunK"), look(0.2f, 0.45f, 1.0f));       // sunset ground: stays lit .. real angle
            GL20.glUniform1f(loc(pathProgram, "uSkySat"), look(0.4f, 0.6f, 1.0f));         // sky light: soft .. Beta's full blue
            GL20.glUniform1f(loc(pathProgram, "uRain"), Dim.rain(mc.world, tickDelta));
            effectUniforms(mc, tickDelta, pathProgram);
            float[] sunrise = Dim.sunrise(mc.world.getTimeOfDay(tickDelta));
            if (sunrise != null) GL20.glUniform4f(loc(pathProgram, "uSunrise"), sunrise[0], sunrise[1], sunrise[2], sunrise[3]);
            else GL20.glUniform4f(loc(pathProgram, "uSunrise"), 0, 0, 0, 0);
            GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 5, voxels.lightBuffer());
            bindImage(0, light, GL15.GL_WRITE_ONLY);
            bindImage(1, skyFog, GL15.GL_WRITE_ONLY);
            bindImage(2, albedoTex, GL15.GL_WRITE_ONLY);
            bindImage(3, extraTex, GL15.GL_WRITE_ONLY);
            bindImage(4, geom[cur], GL15.GL_WRITE_ONLY);
            GL42.glBindImageTexture(5, depthTex, 0, false, 0, GL15.GL_WRITE_ONLY, GL30.GL_R32F);
            // M8c: the same six pictures from the GPU's ray-tracing hardware (Vulkan), when that view is on
            vkActive = (view == View.VULKAN || (view == View.PATH && hardwareRt && !VkBridge.failed())) && vulkanTrace(mc);
            if (view == View.VULKAN && !vkActive) view = View.PATH; // couldn't run: back to this tracer (F3 shows why)
            if (!vkActive) GL43.glDispatchCompute(groupsX, groupsY, 1);
            dispatchHandProbe(mc, cam, tickDelta);
            GL42.glMemoryBarrier(GL42.GL_TEXTURE_FETCH_BARRIER_BIT | GL42.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL43.GL_SHADER_STORAGE_BARRIER_BIT);

            stages.stamp(); // end of trace
            // 1b. denoise the light and combine it with the textures into the HDR image
            denoiseAndCompose(cam, now, groupsX, groupsY);

            // 2. adapt exposure to the scene
            GL20.glUseProgram(exposureProgram);
            bindTexture(1, GL11.GL_TEXTURE_2D, hdr);
            GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 6, exposureBuffer);
            GL20.glUniform1f(loc(exposureProgram, "uDeltaTime"), snapExposure ? 100f : (float) Math.min(0.1, frameMs / 1000.0));
            // Nights: Dark scales the whole exposure down as the sun sets (not where there's no day: Nether, Twilight Forest)
            float night = 1f - Math.max(0f, Math.min(1f, sunDir[1] * 2f + 0.5f));
            float dim = darkNights && mc.world != null && !Dim.nether(mc.world) && fxDayCycle(mc) ? 1f - 0.4f * night : 1f;
            GL20.glUniform1f(loc(exposureProgram, "uKey"), 0.5f * dim);
            GL20.glUniform1f(loc(exposureProgram, "uAdapt"), look(0.2f, 0.45f, 0.8f));     // eye adjustment: fixed .. strong
            GL20.glUniform1f(loc(exposureProgram, "uMinExposure"), 0.1f * dim);
            GL20.glUniform1f(loc(exposureProgram, "uMaxExposure"), look(1.0f, 1.25f, 2.5f) * dim); // how far darkness gets brightened
            GL43.glDispatchCompute(1, 1, 1);
            GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);

            // 3. tone map into the 8-bit image that gets shown
            GL20.glUseProgram(tonemapProgram);
            GL20.glUniform2i(loc(tonemapProgram, "uResolution"), width, height);
            GL20.glUniform1f(loc(tonemapProgram, "uBrightness"), brightness);
            GL42.glBindImageTexture(0, image, 0, false, 0, GL15.GL_WRITE_ONLY, GL11.GL_RGBA8);
            GL42.glBindImageTexture(1, skyFog, 0, false, 0, GL15.GL_READ_ONLY, GL30.GL_RGBA16F);
            GL43.glDispatchCompute(groupsX, groupsY, 1);
            GL42.glMemoryBarrier(GL42.GL_FRAMEBUFFER_BARRIER_BIT);
            GL42.glBindImageTexture(1, 0, 0, false, 0, GL15.GL_READ_ONLY, GL30.GL_RGBA16F);
            stages.stamp(); // end of exposure + tone map
            fsrActive = useFsr && fsrUpscale(cam, now);
            if (!fsrActive) upscaleAndSharpen();
            stages.stamp(); // end of upscale + sharpen
        } else {
            if (view == View.FLAT) setupScene(mc, cam, tickDelta, traceProgram);
            else setupMap(cam);
            GL42.glBindImageTexture(0, image, 0, false, 0, GL15.GL_WRITE_ONLY, GL11.GL_RGBA8);
            GL43.glDispatchCompute(groupsX, groupsY, 1);
            GL42.glMemoryBarrier(GL42.GL_FRAMEBUFFER_BARRIER_BIT);
        }

        for (int u = 0; u < 8; u++) GL42.glBindImageTexture(u, 0, 0, false, 0, GL15.GL_READ_ONLY, GL11.GL_RGBA8);
        for (int b = 1; b <= 8; b++) GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, b, 0);
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + 7);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        for (int unit = 5; unit >= 1; unit--) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
            GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0);
        }
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + 6);
        GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, 0);
        GL13.glActiveTexture(GL13.GL_TEXTURE0); // Beta assumes texture unit 0 is active
        GL20.glUseProgram(0);

        if (pathView() && !legacyFrame) {
            present();
        } else {
            // Debug views: copy the image and clear depth, so Beta's entities draw on top of everything.
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, fbo);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, 0);
            GL30.glBlitFramebuffer(0, 0, width, height, 0, 0, outW, outH, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_LINEAR);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
            GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);
        }
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, savedTex0);
        restoreAfterVulkan();
        timer.end();
    }

    /**
     * Draw the finished image to the screen and fill the depth buffer from the ray distances
     * (present.frag). Every bit of OpenGL state we touch is saved and put back, because Beta
     * carries on drawing entities, particles and the hand straight after this.
     */
    private void present() {
        int prevTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glDisable(GL11.GL_ALPHA_TEST);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glDisable(GL11.GL_FOG);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDepthFunc(GL11.GL_ALWAYS);
        GL11.glDepthMask(true);
        GL11.glColorMask(true, true, true, true);
        GL11.glViewport(0, 0, outW, outH);

        GL20.glUseProgram(presentProgram);
        GL20.glUniform2i(loc(presentProgram, "uResolution"), width, height);
        GL20.glUniform2i(loc(presentProgram, "uOutSize"), outW, outH);
        float aspect = (float) width / height;
        GL20.glUniform2f(loc(presentProgram, "uTan"), tanHalfFov * aspect, tanHalfFov);
        GL20.glUniform4f(loc(presentProgram, "uProjZ"), Camera.projZ[0], Camera.projZ[1], Camera.projZ[2], Camera.projZ[3]);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, presentTex);
        GL42.glBindImageTexture(0, depthTex, 0, false, 0, GL15.GL_READ_ONLY, GL30.GL_R32F);
        GL30.glBindVertexArray(presentVao);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
        GL30.glBindVertexArray(0);
        GL42.glBindImageTexture(0, 0, 0, false, 0, GL15.GL_READ_ONLY, GL11.GL_RGBA8);
        GL20.glUseProgram(0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex);
        GL11.glPopAttrib();
    }

    private void setupMap(Camera cam) {
        GL20.glUseProgram(program);
        GL20.glUniform2i(loc(program, "uResolution"), width, height);
        GL20.glUniform3f(loc(program, "uCamPos"), (float) cam.x, (float) cam.y, (float) cam.z);
        GL20.glUniform2f(loc(program, "uCamDirXZ"), cam.fx, cam.fz);
        GL20.glUniform1f(loc(program, "uBlocksPerPixel"), 320f / height); // about 320 blocks top to bottom
        GL20.glUniform1i(loc(program, "uStartY"), view == View.MAP_CUTAWAY ? Math.min(127, (int) Math.floor(cam.y) + 1) : 127);
        bindTexture(1, GL12.GL_TEXTURE_3D, voxels.texture());
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 1, palette.buffer());
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 2, voxels.slotBuffer());
    }

    /**
     * True when the light changed all at once since last frame: the time jumped (sleeping in a bed, a time command,
     * the test's midnight shots), the rain switched on or off at once, or another world or dimension. The denoiser and
     * FSR then start over instead of blending the old light in, which otherwise lingers for a second or more (a
     * daylit floor at midnight, with dark ghosts where blocks were). Normal time and weather changes are gradual.
     */
    private boolean lightingJumped(Minecraft mc, float tickDelta) {
        long t = mc.world.ticks;
        float rain = Dim.rain(mc.world, tickDelta);
        int dim = Dim.id(mc.world);
        boolean jump = mc.world != cutWorld || dim != cutDim || Math.abs(t - cutTime) > 100 || Math.abs(rain - cutRain) > 0.2f;
        cutWorld = mc.world; cutDim = dim; cutTime = t; cutRain = rain;
        return jump;
    }
    private boolean lightCut;

    /** The world's sun moves (the overworld, the Aether), unlike dimensions with a fixed time of day. */
    private static boolean fxDayCycle(Minecraft mc) {
        try {
            float a = Dim.timeOfDay(0L, 0f), b = Dim.timeOfDay(6000L, 0f);
            return Math.abs(a - b) > 1e-3f;
        } catch (Throwable t) { return true; }
    }
    private Object cutWorld;
    private int cutDim;
    private long cutTime;
    private float cutRain;

    /** Uniforms and bindings shared by the flat (M3) and path traced (M4) shaders. */
    private void setupScene(Minecraft mc, Camera cam, float tickDelta, int p) {
        GL20.glUseProgram(p);
        GL20.glUniform2i(loc(p, "uResolution"), width, height);
        int bx = (int) Math.floor(cam.x), by = (int) Math.floor(cam.y), bz = (int) Math.floor(cam.z);
        GL20.glUniform3i(loc(p, "uCamBlock"), bx, by, bz);
        GL20.glUniform3f(loc(p, "uCamFrac"), (float) (cam.x - bx), (float) (cam.y - by), (float) (cam.z - bz));
        GL20.glUniform3f(loc(p, "uCamForward"), cam.fx, cam.fy, cam.fz);
        GL20.glUniform3f(loc(p, "uCamRight"), cam.rx, cam.ry, cam.rz);
        GL20.glUniform3f(loc(p, "uCamUp"), cam.ux, cam.uy, cam.uz);
        float fov = mc.player.isSubmergedIn(net.minecraft.block.material.Material.WATER) ? 60f : 70f;
        tanHalfFov = Float.isNaN(cam.tanHalfFov) ? (float) Math.tan(Math.toRadians(fov) / 2.0) : cam.tanHalfFov;
        GL20.glUniform1f(loc(p, "uTanHalfFov"), tanHalfFov);

        net.minecraft.util.math.Vec3d skyC = mc.world.getSkyColor(tickDelta);
        net.minecraft.util.math.Vec3d fogC = mc.world.getFogColor(tickDelta);
        GL20.glUniform3f(loc(p, "uSkyColor"), (float) skyC.x, (float) skyC.y, (float) skyC.z);
        GL20.glUniform3f(loc(p, "uFogColor"), (float) fogC.x, (float) fogC.y, (float) fogC.z);
        float angle = mc.world.getTimeOfDay(tickDelta) * (float) (Math.PI * 2.0);
        // Beta 1.7.3 spins the sun around the X axis, so it rises in -Z and sets in +Z
        // (the famous "sun rises in the north"). Later versions added a 90 degree turn.
        GL20.glUniform3f(loc(p, "uSunDir"), 0f, (float) Math.cos(angle), (float) Math.sin(angle));
        sunDir[0] = 0f; sunDir[1] = (float) Math.cos(angle); sunDir[2] = (float) Math.sin(angle);
        float daylight = Math.max(0f, Math.min(1f, (float) Math.cos(angle) * 2f + 0.5f));
        GL20.glUniform1f(loc(p, "uDaylight"), daylight);
        // Same distances as GameRenderer: fog from a quarter of the view distance to all of it.
        float betaDist = 256 >> Math.max(0, Math.min(3, mc.options.viewDistance));
        // the menu's trace distance, or Beta's render distance on Auto
        float viewDist = traceDistance > 0 ? Math.min(traceDistance, 248) : betaDist;
        GL20.glUniform1f(loc(p, "uMaxDist"), Math.min(viewDist, 248f));
        GL20.glUniform1i(loc(p, "uFastLeaves"), fastLeaves ? 1 : 0);
        // Fancy graphics: Beta lays tile 38 over grass sides in the biome's grass colour (Fast leaves the plain side)
        grassOverlay = mc.options.fancyGraphics ? (StationCompat.terrainTile(net.minecraft.block.Block.BY_ID[2], 38) & 0xFFFF) + 1 : 0;
        GL20.glUniform1i(loc(p, "uGrassOverlay"), grassOverlay);
        camInWater = mc.player.isSubmergedIn(net.minecraft.block.material.Material.WATER);
        maxDist = Math.min(viewDist, 248f);
        // Effects: rain haze pulls the fog in (at full rain it starts at 40% of the usual distance, ends at 65%)
        float haze = fxHaze && !Dim.nether(mc.world) ? Dim.rain(mc.world, tickDelta) : 0f;
        if (camInWater) {
            // under water, Beta's thick blue fog (exponential, density 0.1: nearly all fog by 28 blocks)
            float b = 0.2f + 0.8f * daylight;
            GL20.glUniform3f(loc(p, "uFogColor"), 0.02f * b, 0.02f * b, 0.2f * b);
            GL20.glUniform1f(loc(p, "uFogStart"), 0f);
            GL20.glUniform1f(loc(p, "uFogEnd"), 28f);
        } else {
            GL20.glUniform1f(loc(p, "uFogStart"), viewDist * 0.25f * (1f - 0.6f * haze));
            GL20.glUniform1f(loc(p, "uFogEnd"), viewDist * (1f - 0.35f * haze));
        }

        int terrain = StationCompat.terrainTexture(mc.textureManager.load("/terrain.png"));
        bindTexture(8, GL11.GL_TEXTURE_2D, palette.pbrSpecular());
        bindTexture(9, GL11.GL_TEXTURE_2D, palette.pbrNormal());
        bindTexture(2, GL11.GL_TEXTURE_2D, terrain);
        GL20.glUniform1i(loc(p, "uTileSize"), Math.max(1, GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH) / 16));
        bindTexture(1, GL12.GL_TEXTURE_3D, voxels.texture());
        bindTexture(3, GL11.GL_TEXTURE_2D, voxels.grassTexture());
        bindTexture(4, GL11.GL_TEXTURE_2D, voxels.foliageTexture());
        bindTexture(5, GL12.GL_TEXTURE_3D, voxels.brickTexture());
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 2, voxels.slotBuffer());
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 3, blocks.infoBuffer());
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 4, blocks.boxBuffer());
        if (blockLumBuffer == 0) { // each block ID's light level: modded light blocks glow by it (emission() in pt_base.glsl)
            blockLumBuffer = GL15.glGenBuffers();
            java.nio.IntBuffer lum = org.lwjgl.BufferUtils.createIntBuffer(StationCompat.MAX_ID);
            lum.put(VkBridge.blockLum()).flip();
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, blockLumBuffer);
            GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, lum, GL15.GL_STATIC_DRAW);
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);
        }
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 10, blockLumBuffer);
    }

    /**
     * The effects' values for this frame. Time runs with the game (paused game: still water). Wetness is the
     * rain where the camera is: none in snowy biomes (it snows there) or deserts (no rain).
     */
    private void effectUniforms(Minecraft mc, float tickDelta, int p) {
        float rain = Dim.rain(mc.world, tickDelta);
        fxTime = ((mc.world.ticks % 72000L) + tickDelta) / 20f;
        fxWind = 0.35f + 0.65f * rain;
        float wetHere = 0f;
        fxWetness = rain * wetHere;
        GL20.glUniform1i(loc(p, "uEffects"), Dim.nether(mc.world) ? effects() & ~(FX_WET | FX_GODRAYS | FX_HAZE) : effects());
        GL20.glUniform1f(loc(p, "uTime"), fxTime);
        GL20.glUniform1f(loc(p, "uWind"), fxWind);
        GL20.glUniform1f(loc(p, "uWet"), fxWetness);
    }

    private static void bindTexture(int unit, int target, int tex) {
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
        GL11.glBindTexture(target, tex);
    }

    // ---------------------------------------------------------------- M8c Vulkan hardware tracer

    private final java.nio.FloatBuffer uf = org.lwjgl.BufferUtils.createFloatBuffer(16);
    private final java.nio.IntBuffer ui = org.lwjgl.BufferUtils.createIntBuffer(16);
    private static final String[][] VK_FLOATS = { // name, how many floats: packed as vkpath.comp's Params.v[11]
            {"uCamFrac", "3"}, {"uTanHalfFov", "1"}, {"uCamForward", "3"}, {"uMaxDist", "1"}, {"uCamRight", "3"}, {"uFogStart", "1"},
            {"uCamUp", "3"}, {"uFogEnd", "1"}, {"uSkyColor", "3"}, {"uDaylight", "1"}, {"uFogColor", "3"}, {"uRain", "1"},
            {"uSunrise", "4"}, {"uSunDir", "3"}, {"uAmbient", "1"}, {"uMoon", "1"}, {"uLowSunK", "1"}, {"uSkySat", "1"}, {"uSkyFill", "1"},
            {"uJitter", "2"}, {"uCloudShadow", "1"}, {"uLavaGlow", "1"}, {"uCloud", "4"}};

    /**
     * Runs the Vulkan tracer with exactly the settings this frame's OpenGL tracer was just given
     * (read back from the program, so the two can't drift apart), then copies its six pictures
     * into the textures the denoiser reads. False if Vulkan can't run.
     */
    private int[] vkSaved; // this tracer's own textures while Vulkan's stand in for them

    private void restoreAfterVulkan() {
        if (vkSaved == null) return;
        light = vkSaved[0]; skyFog = vkSaved[1]; albedoTex = vkSaved[2]; extraTex = vkSaved[3];
        geom[0] = vkSaved[4]; geom[1] = vkSaved[5]; depthTex = vkSaved[6];
        vkSaved = null;
    }

    // ---------------------------------------------------------------- carried lights and the hand
    private static int lightIdOfItem(int itemId) {
        return switch (itemId) {
            case 50 -> 50;   // torch
            case 89 -> 89;   // glowstone block
            case 91 -> 91;   // jack o'lantern
            case 327 -> 10;  // lava bucket
            default -> { // a modded light block carried (StationAPI): it lights by its own level (emission() in pt_base.glsl)
                net.minecraft.block.Block b = itemId >= 256 ? EntityCapture.heldBlock(itemId) : null;
                yield b != null && StationCompat.luminance(b.id) > 0 ? b.id : 0;
            }
        };
    }

    /** A carried light's spot: right hand, a little in front of and below the eyes (yaw only). */
    private static void handSpot(double ex, double ey, double ez, float yawDeg, java.util.List<Float> out, Camera cam, int id) {
        double yaw = Math.toRadians(yawDeg);
        double fx = -Math.sin(yaw), fz = Math.cos(yaw), rx = -fz, rz = fx;
        out.add((float) (ex + fx * 0.4 + rx * 0.35 - Math.floor(cam.x)));
        out.add((float) (ey - 0.1 - Math.floor(cam.y)));
        out.add((float) (ez + fz * 0.4 + rz * 0.35 - Math.floor(cam.z)));
        out.add((float) id);
    }

    /** This frame's moving lights for the light list (see heldLights). Works the same in multiplayer:
     *  the server tells every client what the other players are holding, and dropped items are entities. */
    private float[] heldLightList(Minecraft mc, Camera cam, float tickDelta) {
        if (heldLights == HELD_OFF || mc.world == null || mc.player == null) return new float[0];
        java.util.ArrayList<Float> out = new java.util.ArrayList<>();
        for (Object o : mc.world.entities) {
            if (!(o instanceof net.minecraft.entity.Entity e)) continue;
            boolean mine = e == mc.player;
            if (!mine && heldLights != HELD_EVERYONE) continue;
            if (out.size() >= 4 * 32) break;
            double x = e.lastX + (e.x - e.lastX) * tickDelta, y = e.lastY + (e.y - e.lastY) * tickDelta, z = e.lastZ + (e.z - e.lastZ) * tickDelta;
            if ((x - cam.x) * (x - cam.x) + (z - cam.z) * (z - cam.z) > 64 * 64) continue;
            if (e instanceof net.minecraft.entity.mob.player.PlayerEntity p) {
                net.minecraft.item.ItemStack s = p.inventory.getSelectedItem();
                int id = s == null ? 0 : lightIdOfItem(s.id);
                if (id != 0) handSpot(x, y - p.eyeHeight + 1.62, z, p.yaw, out, cam, id); // your y is at eye height, other players' at their feet
            } else if (e instanceof net.minecraft.entity.ItemEntity it && it.item != null) {
                int id = lightIdOfItem(it.item.id);
                if (id != 0) {
                    out.add((float) (x - Math.floor(cam.x))); out.add((float) (y + 0.25 - Math.floor(cam.y)));
                    out.add((float) (z - Math.floor(cam.z))); out.add((float) id);
                }
            }
        }
        float[] f = new float[out.size()];
        for (int i = 0; i < f.length; i++) f[i] = out.get(i);
        lastHeldLights = f.length / 4;
        return f;
    }
    /** How many held or dropped light sources last frame's light list had (for the tests). */
    public int lastHeldLights;

    /** Last frame's probe result -> the colour Beta draws the hand with: as the tone mapper would show
     *  a mid-grey surface lit like that, relative to the same surface fully lit. */
    private void readHandProbe() {
        if (probeBuffer == 0) return;
        probeRead.clear();
        if (!pendingGrid) probeRead.limit(4); // only the hand probe was run
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, probeBuffer);
        GL15.glGetBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0, probeRead);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);
        if (probeRead.get(3) < 0.5f) return; // not written yet
        float exposure = exposureState()[0] * brightness;
        if (!(exposure > 0)) return;
        // lava as seen: shown at about 90% of its texture colours whatever the exposure (see pt_light.glsl)
        lavaGlow = Math.max(0.05f, Math.min(1.5f, 0.9f / exposure));
        float[] t = new float[3];
        toDisplay(probeRead.get(0), probeRead.get(1), probeRead.get(2), exposure, t);
        for (int i = 0; i < 3; i++) handLight[i] = handLightValid ? handLight[i] + (t[i] - handLight[i]) * 0.2f : t[i]; // smooth the noise
        handLightValid = true;
        if (pendingGrid) {
            for (int g = 0; g < GRID; g++) {
                int o = 4 * (g + 1);
                toDisplay(probeRead.get(o), probeRead.get(o + 1), probeRead.get(o + 2), exposure, t);
                float b = 0.2126f * t[0] + 0.7152f * t[1] + 0.0722f * t[2];
                boolean same = gridValid && java.util.Arrays.equals(gridOrigin, pendingOrigin);
                gridBright[g] = same ? gridBright[g] + (b - gridBright[g]) * 0.3f : b;
                gridOpen[g] = probeRead.get(o + 3);
            }
            System.arraycopy(pendingOrigin, 0, gridOrigin, 0, 3);
            gridValid = true;
        }
    }

    /** Traced light -> how bright Beta should draw a surface there: as the tone mapper would show a mid-grey
     *  surface lit like that, relative to the same surface fully lit (0.04..1 per channel). */
    private static void toDisplay(float r, float g, float b, float exposure, float[] out) {
        final float A = 0.4f, W = 6f; // reference albedo; tonemap.comp's white point
        float[] c = {Math.max(0f, r) * exposure * A, Math.max(0f, g) * exposure * A, Math.max(0f, b) * exposure * A};
        float L = 0.2126f * c[0] + 0.7152f * c[1] + 0.0722f * c[2];
        float k = L <= 0 ? 0 : (L * (1 + L / (W * W)) / (1 + L)) / L;
        for (int i = 0; i < 3; i++) out[i] = Math.max(0.04f, Math.min(1f, (float) Math.pow(Math.min(1f, c[i] * k) / A, 1 / 2.2)));
    }

    private void dispatchHandProbe(Minecraft mc, Camera cam, float tickDelta) {
        if (mc.player == null) return;
        if (probeBuffer == 0) {
            probeBuffer = GL15.glGenBuffers();
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, probeBuffer);
            GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, 16L * (1 + GRID), GL15.GL_DYNAMIC_READ);
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);
        }
        GL20.glUseProgram(pathProgram);
        // where the hand is drawn: right of and below the view centre, a little ahead; facing back at the camera and up
        GL20.glUniform3f(loc(pathProgram, "uProbeOffset"), cam.fx * 0.5f + cam.rx * 0.35f - cam.ux * 0.3f,
                cam.fy * 0.5f + cam.ry * 0.35f - cam.uy * 0.3f, cam.fz * 0.5f + cam.rz * 0.35f - cam.uz * 0.3f);
        float nx = -cam.fx + cam.ux * 0.6f, ny = -cam.fy + cam.uy * 0.6f, nz = -cam.fz + cam.uz * 0.6f;
        float nl = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        GL20.glUniform3f(loc(pathProgram, "uProbeNormal"), nx / nl, ny / nl, nz / nl);
        // the particle/rain grid: 32 x 16 x 32 blocks around the camera, lined up with the world every 2 blocks
        int bx = (int) Math.floor(cam.x), by = (int) Math.floor(cam.y), bz = (int) Math.floor(cam.z);
        pendingOrigin[0] = Math.floorDiv(bx, 2) * 2 - 16; pendingOrigin[1] = Math.floorDiv(by, 2) * 2 - 8; pendingOrigin[2] = Math.floorDiv(bz, 2) * 2 - 16;
        GL20.glUniform3f(loc(pathProgram, "uGridOrigin"), pendingOrigin[0] - bx, pendingOrigin[1] - by, pendingOrigin[2] - bz);
        pendingGrid = particleLight && (frameIndex & 1) == 0; // the grid every other frame: half the cost, smoothed anyway
        GL20.glUniform1i(loc(pathProgram, "uProbeOnly"), 1);
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 9, probeBuffer);
        GL43.glDispatchCompute(pendingGrid ? 1 + GRID : 1, 1, 1);
        GL20.glUniform1i(loc(pathProgram, "uProbeOnly"), 0);
    }

    private boolean vulkanTrace(Minecraft mc) {
        float[] f = new float[48];
        int k = 0;
        for (String[] u : VK_FLOATS) {
            int n = Integer.parseInt(u[1]);
            int l = u[0].equals("-") ? -1 : GL20.glGetUniformLocation(pathProgram, u[0]);
            if (l >= 0) {
                uf.clear();
                GL20.glGetUniform(pathProgram, l, uf);
                for (int i = 0; i < n; i++) f[k + i] = uf.get(i);
            }
            k += n;
        }
        int[] iv = new int[16];
        String[][] ints = {{"uResolution", "2"}, {"uFrame", "1"}, {"uLightCount", "1"}, {"uCamBlock", "3"}, {"uDebugMask", "1"}};
        k = 0;
        for (String[] u : ints) {
            int n = Integer.parseInt(u[1]);
            int l = GL20.glGetUniformLocation(pathProgram, u[0]);
            if (l >= 0) {
                ui.clear();
                GL20.glGetUniform(pathProgram, l, ui);
                for (int i = 0; i < n; i++) iv[k + i] = ui.get(i);
            }
            k += n;
        }
        iv[0] = width; iv[1] = height; // in case the program left them out
        iv[8] = cur;                   // which of Vulkan's two geometry pictures is "this frame"
        iv[9] = reflections;
        iv[10] = pbrActive() ? 1 : 0;
        iv[11] = bounces;
        // effects (vkpath.comp Params.fx, Params.ifx)
        f[44] = fxTime; f[45] = fxWind; f[46] = fxWetness;
        iv[12] = Dim.nether(mc.world) ? effects() & ~(FX_WET | FX_GODRAYS | FX_HAZE) : effects();
        iv[13] = handEntity(); // vkpath.comp Params.ifx.y
        iv[14] = grassOverlay; // Params.ifx.z
        iv[15] = camInWater ? 1 : 0; // Params.ifx.w
        // mobs: the boxes uploaded for this frame just above (none when traced mobs are off)
        int mobCount = entityCount > 0 ? EntityCapture.lastUploadedCount() : 0;
        int[] tex = VkBridge.liveTrace(mc, voxels, blocks.cpuInfo, blocks.cpuBoxes, width, height, f, iv, voxels.lightFloats(),
                EntityCapture.lastBoxes(), mobCount, EntityCapture.lastBoxEntity());
        if (tex == null) return false;
        // No copying: for the rest of this frame the denoiser, FSR and the screen read Vulkan's own pictures
        // (shared GPU memory). The usual textures come back at the end of the frame.
        vkSaved = new int[]{light, skyFog, albedoTex, extraTex, geom[0], geom[1], depthTex};
        light = tex[0]; skyFog = tex[1]; albedoTex = tex[2]; extraTex = tex[3];
        geom[0] = tex[4]; geom[1] = tex[6]; depthTex = tex[5];
        return true;
    }

    /** How far each traced mob moved since last frame, for the denoiser and FSR (geometry w = 2 + mob index). */
    private void entityMotion(int program) {
        boolean on = entityCount > 0 && EntityCapture.motionBuffer() != 0;
        GL20.glUniform1i(loc(program, "uEntMotionCount"), on ? EntityCapture.motionCount() : 0);
        if (on) GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 0, EntityCapture.motionBuffer());
    }

    private static int loc(int program, String name) {
        return GL20.glGetUniformLocation(program, name);
    }

    // ---- Frame pacing ------------------------------------------------------------
    // With nothing holding it back, the CPU can queue several frames for the GPU before
    // the first one is shown. The mouse movement for a frame is read when the CPU starts
    // it, so every queued frame adds delay between moving the mouse and seeing it.
    // Fix: drop a fence after each frame, and before reading input for the next one,
    // wait until the GPU has finished the previous frame. At most one frame in flight.
    private org.lwjgl.opengl.GLSync lastFrameFence;
    public double fenceWaitMs;

    /** Start of a frame, before Beta reads the mouse. */
    public void beginFrame() {
        // off (F6, the Off preset): the time away isn't a frame, and the history from before it is stale
        // (the tests switch it off for Beta's shot of a still scene, where that history is still right)
        if (!active()) { lastFrameNanos = 0; resumed = !com.alexmcclellan.betapt.test.ModTest.running() && !com.alexmcclellan.betapt.test.AutoTest.running(); }
        if (lastFrameFence == null) return;
        long t0 = System.nanoTime();
        org.lwjgl.opengl.GL32.glClientWaitSync(lastFrameFence, org.lwjgl.opengl.GL32.GL_SYNC_FLUSH_COMMANDS_BIT, 100_000_000L);
        org.lwjgl.opengl.GL32.glDeleteSync(lastFrameFence);
        lastFrameFence = null;
        fenceWaitMs = fenceWaitMs * 0.95 + (System.nanoTime() - t0) / 1.0e6 * 0.05;
    }

    /** End of a frame, after the HUD is drawn. */
    public void endFrame() {
        if (!active() || program == 0) return;
        lastFrameFence = org.lwjgl.opengl.GL32.glFenceSync(org.lwjgl.opengl.GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
    }

    /** Current auto exposure and average scene brightness (reads back from the GPU; debug use). */
    public float[] exposureState() {
        if (exposureBuffer == 0) return new float[]{0, 0};
        java.nio.FloatBuffer b = org.lwjgl.BufferUtils.createFloatBuffer(2);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, exposureBuffer);
        GL15.glGetBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0, b);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);
        return new float[]{b.get(0), b.get(1)};
    }

    public double gpuMs() {
        return timer == null ? 0 : timer.smoothedMs;
    }

}
