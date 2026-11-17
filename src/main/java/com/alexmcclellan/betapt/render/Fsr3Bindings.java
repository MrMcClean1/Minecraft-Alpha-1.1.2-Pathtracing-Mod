package com.alexmcclellan.betapt.render;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AMD's FSR 3.1 shaders number their images (UAVs) up to 11, and the luma pyramid uses 9 of them. NVIDIA's OpenGL has
 * 8 image units, 0 to 7 (GL_MAX_IMAGE_UNITS, GL_MAX_COMPUTE_IMAGE_UNIFORMS), so those passes don't compile there. Each
 * pass's source is rewritten as it loads: its images are numbered 0, 1, 2... in the order Fsr3 lists them, and the
 * downsampler's tile counter (a 1x1 r32ui image) becomes a storage buffer at binding 0. Texture and uniform buffer
 * numbers are separate and stay as AMD wrote them. No game classes here, so tools can run it on its own.
 */
public final class Fsr3Bindings {
    private Fsr3Bindings() {}
    public static final int MAX_IMAGES = 8;

    private static final Pattern IMAGE = Pattern.compile("layout\\(binding = (\\d+), (?!std140)([a-z0-9]+)\\)");

    /**
     * @param old       each image's binding in AMD's source, in the order the pass binds them
     * @param atomicOld the tile counter's binding among them, or -1
     * @param unit      out: the image unit for each entry, -1 for the counter (storage buffer 0)
     */
    public static String rewrite(String src, int[] old, int atomicOld, int[] unit) {
        if (atomicOld >= 0) {
            Matcher a = Pattern.compile("(?s)layout\\(binding = " + atomicOld + ", r32ui\\)coherent uniform uimage2D rw_spd_global_atomic;.*?"
                    + "void SPD_ResetAtomicCounter\\(\\)\\s*\\{.*?\\}").matcher(src);
            if (!a.find()) throw new IllegalStateException("the SPD tile counter isn't where it was expected");
            src = src.substring(0, a.start())
                    + "layout(std430, binding = 0) coherent buffer SpdGlobalAtomic { uint spdGlobalAtomic; };\n"
                    + "void SPD_IncreaseAtomicCounter(inout uint spdCounter) { spdCounter = atomicAdd(spdGlobalAtomic, 1u); }\n"
                    + "void SPD_ResetAtomicCounter() { spdGlobalAtomic = 0u; }"
                    + src.substring(a.end());
        }
        Map<Integer, Integer> map = new HashMap<>();
        int next = 0;
        for (int i = 0; i < old.length; i++) {
            if (old[i] == atomicOld) { unit[i] = -1; continue; }
            unit[i] = next;
            map.put(old[i], next++);
        }
        if (next > MAX_IMAGES) throw new IllegalStateException(next + " images, more than " + MAX_IMAGES);
        Matcher m = IMAGE.matcher(src);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            Integer n = map.get(Integer.parseInt(m.group(1)));
            if (n == null) throw new IllegalStateException("image binding " + m.group(1) + " isn't one the pass binds");
            m.appendReplacement(out, "layout(binding = " + n + ", " + m.group(2) + ")");
        }
        m.appendTail(out);
        return out.toString();
    }
}
