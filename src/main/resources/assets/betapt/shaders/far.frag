#version 430
// Far terrain (FarTerrain.java): where the path tracer's rays found only sky, march the saved-chunk height map from
// the end of the tracer's range out to ~1000 blocks; land found is lit by the sun and faded into the fog.
layout(r32f, binding = 0) uniform readonly image2D uDepth;   // the tracer's ray distance, < 0 for sky
uniform sampler2D uMap;      // rgb: top block colour, a: its height / 255 (0: no saved chunk there)
uniform ivec2 uResolution;   // the tracer's resolution
uniform ivec2 uOutSize;      // the window
uniform vec2  uTan;          // tan(half fov) * aspect, tan(half fov)
uniform vec3  uCam;          // camera, x and z from the map's corner, y as in the world
uniform vec3  uFwd, uRight, uUp;
uniform float uNear, uFar, uCell, uWorldTop;
uniform vec3  uSun;          // towards the sun (or the moon at night)
uniform vec3  uFog;          // the fog / horizon colour
uniform float uDaylight;     // 0 night .. 1 day
out vec4 fragColor;

float heightAt(vec2 xz) {
    vec2 uv = xz / (uCell * vec2(textureSize(uMap, 0)));
    if (any(lessThan(uv, vec2(0.0))) || any(greaterThan(uv, vec2(1.0)))) return -1.0;
    float a = texture(uMap, uv).a;
    return a <= 0.0 ? -1.0 : a * 255.0;
}

void main() {
    ivec2 opx = ivec2(gl_FragCoord.xy);
    ivec2 px = min(ivec2((vec2(opx) + 0.5) * vec2(uResolution) / vec2(uOutSize)), uResolution - 1);
    if (imageLoad(uDepth, px).r >= 0.0) discard;   // the tracer drew something here
    vec2 ndc = (vec2(opx) + 0.5) / vec2(uOutSize) * 2.0 - 1.0;
    vec3 dir = normalize(uFwd + ndc.x * uTan.x * uRight + ndc.y * uTan.y * uUp);
    if (dir.y > 0.25) discard;                      // well above the horizon: nothing out there is that high
    float t = uNear;
    float prevT = t;
    bool hit = false;
    for (int i = 0; i < 400 && t < uFar; i++) {
        vec3 p = uCam + dir * t;
        if (p.y > uWorldTop && dir.y >= 0.0) break;
        float h = heightAt(p.xz);
        if (h > 0.0 && p.y <= h) { hit = true; break; }
        prevT = t;
        t += max(uCell, t * 0.006);
    }
    if (!hit) discard;
    // close in on the edge between the last step above the ground and the first below
    float lo = prevT, hi = t;
    for (int i = 0; i < 6; i++) {
        float mid = 0.5 * (lo + hi);
        vec3 p = uCam + dir * mid;
        float h = heightAt(p.xz);
        if (h > 0.0 && p.y <= h) hi = mid; else lo = mid;
    }
    t = hi;
    vec3 p = uCam + dir * t;
    vec2 uv = p.xz / (uCell * vec2(textureSize(uMap, 0)));
    vec3 albedo = texture(uMap, uv).rgb;
    albedo = pow(albedo, vec3(2.2));
    // slope from the neighbouring cells
    float hx = heightAt(p.xz + vec2(uCell, 0.0)), hz = heightAt(p.xz + vec2(0.0, uCell)), h0 = heightAt(p.xz);
    if (hx < 0.0) hx = h0;
    if (hz < 0.0) hz = h0;
    vec3 n = normalize(vec3(h0 - hx, uCell, h0 - hz));
    float sunLight = max(dot(n, normalize(uSun)), 0.0) * uDaylight;
    vec3 lit = albedo * (0.25 + 0.15 * uDaylight + 0.85 * sunLight);
    vec3 col = pow(lit, vec3(1.0 / 2.2));
    // into the fog: thin at the tracer's edge, all fog at the far end
    float fog = smoothstep(uNear, uFar, t);
    fragColor = vec4(mix(col, uFog, fog * 0.6), 1.0 - fog * fog);
}
