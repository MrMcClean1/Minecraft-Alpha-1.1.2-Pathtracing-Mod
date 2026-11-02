#version 430
// Far terrain (FarTerrain.java): where the path tracer's rays found only sky, march the far height map from the end
// of the tracer's range out to ~1000 blocks. Heights are blended between cells, so cliffs come out as slopes, not
// steps; land is lit by the sun with shadows from the land around it; water reflects the sky; all fades into fog.
layout(r32f, binding = 0) uniform readonly image2D uDepth;   // the tracer's ray distance, < 0 for sky
uniform sampler2D uMap;      // 2048 x 2048, wraps: rgb top block colour, a: height (low 7 bits) + water (bit 7); 0 unknown
uniform ivec2 uResolution;   // the tracer's resolution
uniform ivec2 uOutSize;      // the window
uniform vec2  uTan;          // tan(half fov) * aspect, tan(half fov)
uniform vec3  uCam;          // camera: x and z in the map's coordinates, y as in the world
uniform vec3  uFwd, uRight, uUp;
uniform float uNear, uFar, uWorldTop;
uniform vec3  uSun;          // towards the sun (or the moon at night)
uniform vec3  uFog;          // the fog / horizon colour
uniform float uDaylight;     // 0 night .. 1 day
out vec4 fragColor;

const int MAPN = 2048;

vec4 cellAt(ivec2 c) { return texelFetch(uMap, c & (MAPN - 1), 0); }
int code(vec4 t) { return int(t.a * 255.0 + 0.5); }

// height of the land at a point: the four nearest cells blended (unknown cells take their known neighbours' mean)
float heightAt(vec2 p) {
    vec2 q = p - 0.5;
    ivec2 i = ivec2(floor(q));
    vec2 f = q - vec2(i);
    int a = code(cellAt(i)), b = code(cellAt(i + ivec2(1, 0))), c = code(cellAt(i + ivec2(0, 1))), d = code(cellAt(i + ivec2(1, 1)));
    float n = float(a > 0) + float(b > 0) + float(c > 0) + float(d > 0);
    if (n == 0.0) return -1.0;
    float ha = float(a & 127), hb = float(b & 127), hc = float(c & 127), hd = float(d & 127);
    float m = (ha * float(a > 0) + hb * float(b > 0) + hc * float(c > 0) + hd * float(d > 0)) / n;
    if (a == 0) ha = m; if (b == 0) hb = m; if (c == 0) hc = m; if (d == 0) hd = m;
    return mix(mix(ha, hb, f.x), mix(hc, hd, f.x), f.y);
}

bool below(vec3 p) { float h = heightAt(p.xz); return h > 0.0 && p.y <= h; }

void main() {
    ivec2 opx = ivec2(gl_FragCoord.xy);
    ivec2 px = min(ivec2((vec2(opx) + 0.5) * vec2(uResolution) / vec2(uOutSize)), uResolution - 1);
    if (imageLoad(uDepth, px).r >= 0.0) discard;   // the tracer drew something here
    vec2 ndc = (vec2(opx) + 0.5) / vec2(uOutSize) * 2.0 - 1.0;
    vec3 dir = normalize(uFwd + ndc.x * uTan.x * uRight + ndc.y * uTan.y * uUp);
    if (uCam.y >= uWorldTop && dir.y >= 0.0) discard;
    if (dir.y > 0.3) discard;                       // well above the horizon: nothing out there is that high
    float t = uNear, prevT = t;
    bool hit = false;
    for (int i = 0; i < 480 && t < uFar; i++) {
        vec3 p = uCam + dir * t;
        if (p.y > uWorldTop && dir.y >= 0.0) break;
        if (below(p)) { hit = true; break; }
        prevT = t;
        t += max(0.75, t * 0.004);
    }
    if (!hit) discard;
    float lo = prevT, hi = t;                       // close in on the ground
    for (int i = 0; i < 8; i++) {
        float mid = 0.5 * (lo + hi);
        if (below(uCam + dir * mid)) hi = mid; else lo = mid;
    }
    t = hi;
    vec3 p = uCam + dir * t;
    vec4 cell = cellAt(ivec2(floor(p.xz)));
    int cd = code(cell);
    bool water = cd >= 128;
    vec3 albedo = pow(cell.rgb, vec3(2.2));
    float e = 0.75;
    float hl = heightAt(p.xz - vec2(e, 0.0)), hr = heightAt(p.xz + vec2(e, 0.0)), hb = heightAt(p.xz - vec2(0.0, e)), hf = heightAt(p.xz + vec2(0.0, e));
    float h0 = heightAt(p.xz);
    if (hl < 0.0) hl = h0; if (hr < 0.0) hr = h0; if (hb < 0.0) hb = h0; if (hf < 0.0) hf = h0;
    vec3 n = water ? vec3(0.0, 1.0, 0.0) : normalize(vec3(hl - hr, 2.0 * e, hb - hf));
    vec3 L = normalize(uSun);
    // sun shadow: a few growing steps towards the sun over the land
    float lit = 1.0;
    if (L.y > 0.02) {
        vec3 q = p + n * 0.6;
        float s = 1.5;
        for (int i = 0; i < 20; i++) {
            q += L * s;
            if (q.y > uWorldTop) break;
            if (below(q)) { lit = 0.0; break; }
            s *= 1.25;
        }
    }
    float sunLight = max(dot(n, L), 0.0) * uDaylight * lit;
    float sky = 0.25 + 0.15 * uDaylight * (0.6 + 0.4 * n.y);
    vec3 col = albedo * (sky + 0.85 * sunLight);
    if (water) {
        float fres = 0.04 + 0.96 * pow(1.0 - max(-dir.y, 0.0), 5.0);
        vec3 skyCol = pow(uFog, vec3(2.2));
        col = mix(col, skyCol, fres * 0.8);
        vec3 hv = normalize(L - dir);
        col += vec3(1.0, 0.95, 0.85) * pow(max(hv.y, 0.0), 200.0) * uDaylight * lit;
    }
    col = pow(col, vec3(1.0 / 2.2));
    // into the fog: thin at the tracer's edge, all fog at the far end
    float fog = smoothstep(uNear, uFar, t);
    fragColor = vec4(mix(col, uFog, fog * 0.6), 1.0 - fog * fog);
}
