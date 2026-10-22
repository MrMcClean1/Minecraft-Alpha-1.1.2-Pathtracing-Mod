package com.alexmcclellan.betapt.render;

import com.alexmcclellan.betapt.BetaPT;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GL42;
import org.lwjgl.opengl.GL43;
import org.lwjgl.opengl.GL44;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;

/**
 * M8: AMD FidelityFX Super Resolution 3.1 (the upscaler part), running inside our own OpenGL
 * frame.
 *
 * FSR builds a sharp window-sized picture out of smaller frames by remembering past frames:
 * every frame the camera is nudged by a fraction of a pixel (the "jitter"), so over 8 to 20
 * frames each window pixel gets looked at from several slightly different spots, like a
 * scanner taking several passes. Motion vectors tell it where each pixel was last frame, so
 * the memory follows the picture when you move; depth and luma checks throw memory away
 * where something new comes into view.
 *
 * The GPU side is AMD's own shader code (FidelityFX SDK v1.1.4, MIT licence), converted from
 * Vulkan GLSL to OpenGL GLSL by scripts/fsr3gl/convert.py into shaders/fsr3/. This class is
 * the host side of ffx_fsr3upscaler.cpp: it makes the textures, fills the constant buffers
 * and runs the 8 passes in AMD's order, with the same ping-pong of history textures.
 */
public final class Fsr3 {
    // ---- resources (names follow ffx_fsr3upscaler_resources.h) ----
    private static final int INPUT_COLOR = 0, INPUT_DEPTH = 1, INPUT_MOTION_VECTORS = 2, INPUT_EXPOSURE = 3,
            REACTIVE_MASK = 4, TC_MASK = 5, DILATED_MOTION_VECTORS = 6, DILATED_DEPTH = 7, RECONSTRUCTED_PREV_NEAREST_DEPTH = 8,
            ACCUMULATION = 9, INTERNAL_UPSCALED = 10, RCAS_INPUT = 11, LUMA_HISTORY = 12, CURRENT_LUMA = 13, PREVIOUS_LUMA = 14,
            FARTHEST_DEPTH = 15, LUMA_INSTABILITY = 16, FARTHEST_DEPTH_MIP1 = 17, SHADING_CHANGE = 18, NEW_LOCKS = 19,
            DILATED_REACTIVE_MASKS = 20, FRAME_INFO = 21, SPD_MIPS = 22, SPD_GLOBAL_ATOMIC = 23, LANCZOS_LUT = 24,
            UPSCALED_OUTPUT = 25, SPD_MIP0 = 30; // SPD_MIP0 + n = mip level n of SPD_MIPS, as an image

    private static final int CB_MAIN = 0, CB_SPD = 1, CB_RCAS = 2;

    /** One pass: its program and what goes on which texture unit, image unit and uniform-buffer slot. */
    private static final class Pass {
        final String file;
        final int[][] srv;   // {unit, resource}
        final int[][] uav;   // {unit, resource, GL format}
        final int[][] cb;    // {binding, which}
        int program;
        Pass(String file, int[][] srv, int[][] uav, int[][] cb) { this.file = file; this.srv = srv; this.uav = uav; this.cb = cb; }
    }

    private static final int R8 = GL30.GL_R8, R16F = GL30.GL_R16F, RG16F = GL30.GL_RG16F, RGBA16F = GL30.GL_RGBA16F,
            RGBA8 = GL11.GL_RGBA8, R32F = GL30.GL_R32F, R32UI = GL30.GL_R32UI, RGBA32F = GL30.GL_RGBA32F;

    // Binding numbers are the ones in AMD's *_pass.glsl files (see fsr3/*.comp)
    private final Pass prepareInputs = new Pass("fsr3/fsr3_prepare_inputs.comp",
            new int[][]{{0, INPUT_MOTION_VECTORS}, {1, INPUT_DEPTH}, {2, INPUT_COLOR}},
            new int[][]{{3, DILATED_MOTION_VECTORS, RG16F}, {4, DILATED_DEPTH, R32F}, {5, RECONSTRUCTED_PREV_NEAREST_DEPTH, R32UI},
                    {6, FARTHEST_DEPTH, R16F}, {7, CURRENT_LUMA, R16F}},
            new int[][]{{8, CB_MAIN}});
    private final Pass lumaPyramid = new Pass("fsr3/fsr3_luma_pyramid.comp",
            new int[][]{{0, CURRENT_LUMA}, {1, FARTHEST_DEPTH}},
            new int[][]{{2, SPD_GLOBAL_ATOMIC, R32UI}, {3, FRAME_INFO, RGBA32F}, {4, SPD_MIP0, RG16F}, {5, SPD_MIP0 + 1, RG16F},
                    {6, SPD_MIP0 + 2, RG16F}, {7, SPD_MIP0 + 3, RG16F}, {8, SPD_MIP0 + 4, RG16F}, {9, SPD_MIP0 + 5, RG16F},
                    {10, FARTHEST_DEPTH_MIP1, R16F}},
            new int[][]{{11, CB_MAIN}, {12, CB_SPD}});
    private final Pass shadingChangePyramid = new Pass("fsr3/fsr3_shading_change_pyramid.comp",
            new int[][]{{0, CURRENT_LUMA}, {1, PREVIOUS_LUMA}, {2, DILATED_MOTION_VECTORS}, {3, INPUT_EXPOSURE}},
            new int[][]{{4, SPD_GLOBAL_ATOMIC, R32UI}, {5, SPD_MIP0, RG16F}, {6, SPD_MIP0 + 1, RG16F}, {7, SPD_MIP0 + 2, RG16F},
                    {8, SPD_MIP0 + 3, RG16F}, {9, SPD_MIP0 + 4, RG16F}, {10, SPD_MIP0 + 5, RG16F}},
            new int[][]{{11, CB_MAIN}, {12, CB_SPD}});
    private final Pass shadingChange = new Pass("fsr3/fsr3_shading_change.comp",
            new int[][]{{0, SPD_MIPS}},
            new int[][]{{1, SHADING_CHANGE, R8}},
            new int[][]{{2, CB_MAIN}});
    private final Pass prepareReactivity = new Pass("fsr3/fsr3_prepare_reactivity.comp",
            new int[][]{{0, RECONSTRUCTED_PREV_NEAREST_DEPTH}, {1, DILATED_MOTION_VECTORS}, {2, DILATED_DEPTH}, {3, REACTIVE_MASK},
                    {4, TC_MASK}, {5, ACCUMULATION}, {6, SHADING_CHANGE}, {7, CURRENT_LUMA}, {8, INPUT_EXPOSURE}},
            new int[][]{{9, DILATED_REACTIVE_MASKS, RGBA8}, {10, NEW_LOCKS, R8}, {11, ACCUMULATION, R8}},
            new int[][]{{12, CB_MAIN}});
    private final Pass lumaInstability = new Pass("fsr3/fsr3_luma_instability.comp",
            new int[][]{{0, INPUT_EXPOSURE}, {1, DILATED_REACTIVE_MASKS}, {2, DILATED_MOTION_VECTORS}, {3, FRAME_INFO},
                    {4, LUMA_HISTORY}, {5, FARTHEST_DEPTH_MIP1}, {6, CURRENT_LUMA}},
            new int[][]{{7, LUMA_HISTORY, RGBA16F}, {8, LUMA_INSTABILITY, R16F}},
            new int[][]{{9, CB_MAIN}});
    private final Pass accumulate = accumulatePass("fsr3/fsr3_accumulate.comp");
    private final Pass accumulateSharpen = accumulatePass("fsr3/fsr3_accumulate_sharpen.comp");
    private final Pass rcas = new Pass("fsr3/fsr3_rcas.comp",
            new int[][]{{0, INPUT_EXPOSURE}, {1, RCAS_INPUT}},
            new int[][]{{2, UPSCALED_OUTPUT, RGBA16F}},
            new int[][]{{3, CB_MAIN}, {4, CB_RCAS}});

    private static Pass accumulatePass(String file) {
        return new Pass(file,
                new int[][]{{0, INPUT_EXPOSURE}, {1, DILATED_REACTIVE_MASKS}, {2, DILATED_MOTION_VECTORS}, {3, INTERNAL_UPSCALED},
                        {4, LANCZOS_LUT}, {5, FARTHEST_DEPTH_MIP1}, {6, CURRENT_LUMA}, {7, LUMA_INSTABILITY}, {8, INPUT_COLOR}},
                new int[][]{{9, INTERNAL_UPSCALED, RGBA16F}, {10, UPSCALED_OUTPUT, RGBA16F}, {11, NEW_LOCKS, R8}},
                new int[][]{{12, CB_MAIN}});
    }

    private Pass[] all() { return new Pass[]{prepareInputs, lumaPyramid, shadingChangePyramid, shadingChange, prepareReactivity, lumaInstability, accumulate, accumulateSharpen, rcas}; }

    // ---- textures ----
    private int renderW, renderH, upW, upH;
    private int acc1, acc2, luma1, luma2, intermediate, shadingChangeTex, newLocks, up1, up2, spdMips, spdLevels, farthestMip1,
            lumaHist1, lumaHist2, spdAtomic, dilatedReactive, lanczosLut, defaultReactivity, defaultExposure, frameInfo,
            dilatedDepth, dilatedMotion, reconPrevDepth, output;
    private int sampler, samplerInt; // linear clamp; nearest for the integer (uint) texture
    private final int[] ubo = new int[3];
    private boolean ok, failed, firstExecution = true;
    private int resourceFrameIndex;

    // ---- per-frame state (ffx_fsr3upscaler.cpp keeps these in its constants) ----
    private float jitterX, jitterY, prevJitterX, prevJitterY, jitterPhaseCount, frameIndex;
    private int prevRenderW, prevRenderH, prevUpW, prevUpH;

    /** The finished window-sized picture (RGBA16F), valid after dispatch(). */
    public int output() { return output; }
    public boolean failed() { return failed; }

    /** Load the passes once. False (and logged) if any shader fails; the caller then uses the basic upscaler. */
    private boolean init() {
        if (ok || failed) return ok;
        for (Pass p : all()) {
            p.program = ShaderUtil.loadCompute(p.file);
            if (p.program == 0) { failed = true; BetaPT.LOGGER.error("FSR 3.1 disabled: {} did not compile", p.file); return false; }
        }
        sampler = GL33.glGenSamplers();
        GL33.glSamplerParameteri(sampler, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR_MIPMAP_NEAREST);
        GL33.glSamplerParameteri(sampler, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL33.glSamplerParameteri(sampler, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL33.glSamplerParameteri(sampler, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        samplerInt = GL33.glGenSamplers();
        GL33.glSamplerParameteri(samplerInt, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL33.glSamplerParameteri(samplerInt, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL33.glSamplerParameteri(samplerInt, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL33.glSamplerParameteri(samplerInt, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        for (int i = 0; i < 3; i++) ubo[i] = GL15.glGenBuffers();
        ok = true;
        return true;
    }

    private static int tex(int format, int w, int h, int levels) {
        int t = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, t);
        GL42.glTexStorage2D(GL11.GL_TEXTURE_2D, levels, format, Math.max(1, w), Math.max(1, h));
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, levels - 1);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        return t;
    }

    private void release() {
        int[] all = {acc1, acc2, luma1, luma2, intermediate, shadingChangeTex, newLocks, up1, up2, spdMips, farthestMip1, lumaHist1, lumaHist2,
                spdAtomic, dilatedReactive, lanczosLut, defaultReactivity, defaultExposure, frameInfo, dilatedDepth, dilatedMotion, reconPrevDepth, output};
        for (int t : all) if (t != 0) GL11.glDeleteTextures(t);
        acc1 = 0;
    }

    /** (Re)create every texture for this render size and window size; also restarts the history. */
    private void create(int rw, int rh, int uw, int uh) {
        release();
        renderW = rw; renderH = rh; upW = uw; upH = uh;
        int hw = Math.max(1, rw / 2), hh = Math.max(1, rh / 2);
        acc1 = tex(R8, rw, rh, 1); acc2 = tex(R8, rw, rh, 1);
        luma1 = tex(R16F, rw, rh, 1); luma2 = tex(R16F, rw, rh, 1);
        intermediate = tex(R16F, rw, rh, 1);
        shadingChangeTex = tex(R8, hw, hh, 1);
        newLocks = tex(R8, uw, uh, 1);
        up1 = tex(RGBA16F, uw, uh, 1); up2 = tex(RGBA16F, uw, uh, 1);
        spdLevels = 1 + (int) Math.floor(Math.log(Math.max(hw, hh)) / Math.log(2));
        spdLevels = Math.max(spdLevels, 6);
        spdMips = tex(RG16F, hw, hh, spdLevels);
        farthestMip1 = tex(R16F, hw, hh, 1);
        lumaHist1 = tex(RGBA16F, rw, rh, 1); lumaHist2 = tex(RGBA16F, rw, rh, 1);
        spdAtomic = tex(R32UI, 1, 1, 1);
        dilatedReactive = tex(RGBA8, rw, rh, 1);
        frameInfo = tex(RGBA32F, 1, 1, 1);
        dilatedDepth = tex(R32F, rw, rh, 1);
        dilatedMotion = tex(RG16F, rw, rh, 1);
        reconPrevDepth = tex(R32UI, rw, rh, 1);
        output = tex(RGBA16F, uw, uh, 1);

        // Lanczos-2 table, 128 entries (only read when the LUT variant is compiled, but every accumulate declares it)
        lanczosLut = tex(GL31.GL_R16_SNORM, 128, 1, 1);
        ShortBuffer lut = BufferUtils.createShortBuffer(128);
        for (int i = 0; i < 128; i++) {
            double x = 2.0 * i / 127.0;
            double y = Math.abs(x) < 1e-6 ? 1.0 : (Math.sin(Math.PI * x) / (Math.PI * x)) * (Math.sin(0.5 * Math.PI * x) / (0.5 * Math.PI * x));
            lut.put((short) Math.round(y * 32767.0));
        }
        lut.flip();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, lanczosLut);
        GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 128, 1, GL11.GL_RED, GL11.GL_SHORT, lut);
        defaultReactivity = tex(R8, 1, 1, 1);   // no reactive mask: 0
        defaultExposure = tex(GL30.GL_RG32F, 1, 1, 1); // 0 = "already exposed" (Exposure() treats 0 as 1)
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        clearFloat(defaultReactivity, 0, 0, 0, 0, 0);
        clearFloat(defaultExposure, 0, 0, 0, 0, 0);
        firstExecution = true;
        resourceFrameIndex = 0;
        jitterPhaseCount = 0;
        BetaPT.LOGGER.info("FSR 3.1 resources: render {}x{} -> window {}x{}", rw, rh, uw, uh);
    }

    private static final FloatBuffer CLEAR = BufferUtils.createFloatBuffer(4);
    private static final IntBuffer CLEAR_I = BufferUtils.createIntBuffer(4);

    private static void clearFloat(int t, int level, float r, float g, float b, float a) {
        CLEAR.clear(); CLEAR.put(r).put(g).put(b).put(a).flip();
        GL44.glClearTexImage(t, level, GL11.GL_RGBA, GL11.GL_FLOAT, CLEAR);
    }

    private static void clearUint(int t, int value) {
        CLEAR_I.clear(); CLEAR_I.put(value).put(value).put(value).put(value).flip();
        GL44.glClearTexImage(t, 0, GL30.GL_RGBA_INTEGER, GL11.GL_UNSIGNED_INT, CLEAR_I);
    }

    /** Number of frames in the jitter cycle: 8 at native size, more when upscaling (AMD's formula). */
    public static int phaseCount(int renderW, int upW) {
        return Math.max(1, (int) (8.0 * Math.pow((double) upW / renderW, 2.0)));
    }

    private static float halton(int index, int base) {
        float f = 1f, r = 0f;
        for (int i = index; i > 0; i /= base) { f /= base; r += f * (i % base); }
        return r;
    }

    /** Sub-pixel camera nudge for this frame, in pixels of the render size (-0.5..0.5). */
    public static float[] jitter(int frame, int renderW, int upW) {
        int n = phaseCount(renderW, upW);
        return new float[]{halton((frame % n) + 1, 2) - 0.5f, halton((frame % n) + 1, 3) - 0.5f};
    }

    /**
     * Run FSR 3.1 for one frame.
     *
     * @param color     render-size picture (tone mapped), sampled
     * @param depth     render-size depth, R32F, 0 near .. 1 at infinity (see fsr_inputs.comp)
     * @param motion    render-size motion vectors, RG16F, in UV units: previous UV minus current UV
     * @param jx        this frame's jitter, pixels
     * @param tanHalfFovY  vertical tan(fov/2) of the camera
     * @param near      near plane used to make the depth
     * @param sharpness 0 = off, 1 = strongest (RCAS)
     * @param reset     throw away the history (teleport, resize, after a pause)
     */
    public boolean dispatch(int color, int depth, int motion, int rw, int rh, int uw, int uh, float jx, float jy,
                            float tanHalfFovY, float near, float frameSeconds, float sharpness, boolean reset) {
        if (!init()) return false;
        if (rw != renderW || rh != renderH || uw != upW || uh != upH || acc1 == 0) create(rw, rh, uw, uh);
        boolean odd = (resourceFrameIndex & 1) != 0;
        boolean resetAccumulation = reset || firstExecution;
        if (firstExecution) {
            clearFloat(acc1, 0, 0, 0, 0, 0); clearFloat(acc2, 0, 0, 0, 0, 0);
            clearFloat(luma1, 0, 0, 0, 0, 0); clearFloat(luma2, 0, 0, 0, 0, 0);
        }
        firstExecution = false;

        // ---- constants, as fsr3upscalerDispatch fills them ----
        prevJitterX = jitterX; prevJitterY = jitterY;
        jitterX = jx; jitterY = jy;
        int pRW = prevRenderW == 0 ? rw : prevRenderW, pRH = prevRenderH == 0 ? rh : prevRenderH;
        int pUW = prevUpW == 0 ? uw : prevUpW, pUH = prevUpH == 0 ? uh : prevUpH;
        float aspect = (float) rw / rh;
        float tanHalfFovX = tanHalfFovY * aspect;
        // depth made as 1 - near / viewZ ("infinite far plane, not inverted" in AMD's table)
        final float eps = 1.1920929e-7f;
        float d0 = 1f + eps, d1 = -near - eps;
        float cot = 1f / tanHalfFovY;
        float a = cot / aspect, b = cot;
        int phases = phaseCount(rw, uw);
        if (resetAccumulation || jitterPhaseCount == 0) jitterPhaseCount = phases;
        else if (phases > jitterPhaseCount) jitterPhaseCount++;
        else if (phases < jitterPhaseCount) jitterPhaseCount--;
        frameIndex = resetAccumulation ? 0 : frameIndex + 1;

        ByteBuffer cb = BufferUtils.createByteBuffer(160);
        cb.putInt(rw).putInt(rh).putInt(pRW).putInt(pRH);
        cb.putInt(uw).putInt(uh).putInt(pUW).putInt(pUH);
        cb.putInt(rw).putInt(rh).putInt(uw).putInt(uh);                     // max sizes = current (textures are remade on resize)
        cb.putFloat(d0).putFloat(d1).putFloat(1f / a).putFloat(1f / b);     // device depth -> view depth
        cb.putFloat(jx).putFloat(jy).putFloat(prevJitterX).putFloat(prevJitterY);
        cb.putFloat(1f).putFloat(1f);                                       // motion vectors already in UV units
        cb.putFloat((float) rw / uw).putFloat((float) rh / uh);             // downscale factor
        cb.putFloat(0f).putFloat(0f);                                       // jitter cancellation (our vectors have no jitter)
        cb.putFloat(tanHalfFovX).putFloat(jitterPhaseCount);
        cb.putFloat(Math.max(0f, Math.min(1f, frameSeconds))).putFloat(1f).putFloat(1f).putFloat(frameIndex); // dt, pre-exposure, metres per block, frame
        cb.putFloat(1f).putFloat(1f).putFloat(1f).putFloat(1f / 3f).putFloat(-1f / 3f);
        while (cb.position() < 160) cb.putFloat(0f);
        cb.flip();
        upload(ubo[CB_MAIN], cb);

        // SPD (single pass downsampler) setup: 64x64 tiles over the render size
        int groupsX = (rw - 1) / 64 + 1, groupsY = (rh - 1) / 64 + 1;
        int mips = (int) Math.min(Math.floor(Math.log(Math.max(rw, rh)) / Math.log(2)), 12);
        ByteBuffer spd = BufferUtils.createByteBuffer(32);
        spd.putInt(mips).putInt(groupsX * groupsY).putInt(0).putInt(0).putInt(rw).putInt(rh).putInt(0).putInt(0).flip();
        upload(ubo[CB_SPD], spd);

        // RCAS: slider 0..1 -> AMD's "stops" 2..0
        float stops = -2f * Math.max(0f, Math.min(1f, sharpness)) + 2f;
        float lin = (float) Math.pow(2.0, -stops);
        ByteBuffer rc = BufferUtils.createByteBuffer(16);
        short h = halfBits(lin);
        rc.putInt(Float.floatToRawIntBits(lin)).putInt((h & 0xffff) | ((h & 0xffff) << 16)).putInt(0).putInt(0).flip();
        upload(ubo[CB_RCAS], rc);

        // ---- clears ----
        int accSrv = odd ? acc2 : acc1;
        if (resetAccumulation) {
            clearFloat(accSrv, 0, 0, 0, 0, 0);
            for (int l = 0; l < spdLevels; l++) clearFloat(spdMips, l, 0, 0, 0, 0);
            clearFloat(frameInfo, 0, -1, 1, 0, 0);
        }
        clearUint(reconPrevDepth, Float.floatToRawIntBits(1f)); // farthest depth, as AMD clears it (float bits in a uint)
        clearUint(spdAtomic, 0);

        // ---- the passes ----
        this.colorIn = color; this.depthIn = depth; this.motionIn = motion; this.oddFrame = odd;
        int srcX = (rw + 7) / 8, srcY = (rh + 7) / 8, dstX = (uw + 7) / 8, dstY = (uh + 7) / 8;
        run(prepareInputs, srcX, srcY);
        run(lumaPyramid, groupsX, groupsY);
        clearUint(spdAtomic, 0); // each SPD pass counts finished tiles from zero
        run(shadingChangePyramid, groupsX, groupsY);
        run(shadingChange, ((int) (rw * 0.5f) + 7) / 8, ((int) (rh * 0.5f) + 7) / 8);
        run(prepareReactivity, srcX, srcY);
        run(lumaInstability, srcX, srcY);
        boolean sharpen = sharpness > 0.001f;
        run(sharpen ? accumulateSharpen : accumulate, dstX, dstY);
        if (sharpen) run(rcas, (uw + 15) / 16, (uh + 15) / 16);

        for (int u = 0; u < 13; u++) {
            GL33.glBindSampler(u, 0);
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + u);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
            GL42.glBindImageTexture(u, 0, 0, false, 0, GL15.GL_READ_ONLY, GL11.GL_RGBA8);
        }
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        for (int i = 0; i < 13; i++) GL30.glBindBufferBase(GL31.GL_UNIFORM_BUFFER, i, 0);

        resourceFrameIndex = (resourceFrameIndex + 1) % 16;
        prevRenderW = rw; prevRenderH = rh; prevUpW = uw; prevUpH = uh;
        return true;
    }

    private int colorIn, depthIn, motionIn;
    private boolean oddFrame;

    /** The texture behind a resource name this frame (history textures swap every frame). */
    private int resource(int id, boolean asUav) {
        boolean odd = oddFrame;
        switch (id) {
            case INPUT_COLOR: return colorIn;
            case INPUT_DEPTH: return depthIn;
            case INPUT_MOTION_VECTORS: return motionIn;
            case INPUT_EXPOSURE: return defaultExposure;
            case REACTIVE_MASK: case TC_MASK: return defaultReactivity;
            case DILATED_MOTION_VECTORS: return dilatedMotion;
            case DILATED_DEPTH: return dilatedDepth;
            case RECONSTRUCTED_PREV_NEAREST_DEPTH: return reconPrevDepth;
            case ACCUMULATION: return asUav ? (odd ? acc1 : acc2) : (odd ? acc2 : acc1);
            case INTERNAL_UPSCALED: return asUav ? (odd ? up1 : up2) : (odd ? up2 : up1);
            case RCAS_INPUT: return odd ? up1 : up2; // what accumulate just wrote
            case LUMA_HISTORY: return asUav ? (odd ? lumaHist1 : lumaHist2) : (odd ? lumaHist2 : lumaHist1);
            case CURRENT_LUMA: return odd ? luma2 : luma1;
            case PREVIOUS_LUMA: return odd ? luma1 : luma2;
            case FARTHEST_DEPTH: case LUMA_INSTABILITY: return intermediate;
            case FARTHEST_DEPTH_MIP1: return farthestMip1;
            case SHADING_CHANGE: return shadingChangeTex;
            case NEW_LOCKS: return newLocks;
            case DILATED_REACTIVE_MASKS: return dilatedReactive;
            case FRAME_INFO: return frameInfo;
            case SPD_MIPS: return spdMips;
            case SPD_GLOBAL_ATOMIC: return spdAtomic;
            case LANCZOS_LUT: return lanczosLut;
            case UPSCALED_OUTPUT: return output;
            default:
                if (id >= SPD_MIP0 && id < SPD_MIP0 + 6) return spdMips;
                throw new IllegalArgumentException("resource " + id);
        }
    }

    private void run(Pass p, int gx, int gy) {
        GL20.glUseProgram(p.program);
        for (int[] s : p.srv) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + s[0]);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, resource(s[1], false));
            GL33.glBindSampler(s[0], s[1] == RECONSTRUCTED_PREV_NEAREST_DEPTH ? samplerInt : sampler);
        }
        for (int[] u : p.uav) {
            int level = u[1] >= SPD_MIP0 ? u[1] - SPD_MIP0 : 0;
            GL42.glBindImageTexture(u[0], resource(u[1], true), level, false, 0, GL15.GL_READ_WRITE, u[2]);
        }
        for (int[] c : p.cb) GL30.glBindBufferBase(GL31.GL_UNIFORM_BUFFER, c[0], ubo[c[1]]);
        GL43.glDispatchCompute(Math.max(1, gx), Math.max(1, gy), 1);
        GL42.glMemoryBarrier(GL42.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL42.GL_TEXTURE_FETCH_BARRIER_BIT
                | GL42.GL_TEXTURE_UPDATE_BARRIER_BIT | GL42.GL_FRAMEBUFFER_BARRIER_BIT);
    }

    private static void upload(int buffer, ByteBuffer data) {
        GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER, buffer);
        GL15.glBufferData(GL31.GL_UNIFORM_BUFFER, data, GL15.GL_DYNAMIC_DRAW);
        GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER, 0);
    }

    /** IEEE half-precision bits of a float (for RCAS's packed constant). */
    private static short halfBits(float f) {
        int bits = Float.floatToIntBits(f);
        int sign = (bits >>> 16) & 0x8000;
        int exp = ((bits >>> 23) & 0xff) - 127 + 15;
        int mant = bits & 0x7fffff;
        if (exp <= 0) return (short) sign;
        if (exp >= 31) return (short) (sign | 0x7c00);
        return (short) (sign | (exp << 10) | (mant >>> 13));
    }
}
