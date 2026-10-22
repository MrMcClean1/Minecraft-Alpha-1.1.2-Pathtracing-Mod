// Shared by both path tracers (pathtrace.comp in OpenGL, vkpath.comp in Vulkan): small helpers
// and which blocks give off light. Edit here and both tracers change together.

// Normal packed into 2 numbers ("octahedral" encoding): fold the unit sphere onto a square.
vec2 octEncode(vec3 n) {
    n /= abs(n.x) + abs(n.y) + abs(n.z);
    vec2 e = n.xy;
    if (n.z < 0.0) e = (1.0 - abs(n.yx)) * vec2(n.x >= 0.0 ? 1.0 : -1.0, n.y >= 0.0 ? 1.0 : -1.0);
    return e;
}

// ---------------------------------------------------------------- random numbers
uint rngState;
uint pcg(uint v) { uint s = v * 747796405u + 2891336453u; uint w = ((s >> ((s >> 28u) + 4u)) ^ s) * 277803737u; return (w >> 22u) ^ w; }
float rnd() { rngState = pcg(rngState); return float(rngState) * (1.0 / 4294967296.0); }

vec3 srgbToLinear(vec3 c) { return pow(c, vec3(2.2)); }

// Light given off by a block, in the same units as sunlight (sun = 3).
// Each block ID's light level (0-15), for modded light blocks (IDs 97 and up), when the backend provides the table.
#ifdef BLOCKLUM_DECL
BLOCKLUM_DECL
float modLum(uint id) { return id < uint(blockLum.length()) ? float(blockLum[id] & 15u) : 0.0; }
// its texture's average colour (sRGB, packed above the light level), 0 if not known
vec3 modColour(uint id) { uint p = id < uint(blockLum.length()) ? blockLum[id] : 0u; return vec3((p >> 24) & 255u, (p >> 16) & 255u, (p >> 8) & 255u) / 255.0; }
#else
float modLum(uint id) { return 0.0; }
vec3 modColour(uint id) { return vec3(0.0); }
#endif

vec3 emission(uint id) {
    if (id >= 97u) {
        float l = modLum(id);
        if (l <= 0.0) return vec3(0.0);
        // a modded light shines in its texture's colour (a white block white, a blue lamp blue); a dark texture
        // (oil) doesn't say what colour its light is, so that keeps a torch's warm white
        vec3 c = pow(modColour(id), vec3(2.2));
        float m = max(c.r, max(c.g, c.b));
        vec3 tint = m > 0.05 ? c / m : vec3(1.0, 0.85, 0.65);
        return tint * (l / 15.0) * 5.0;
    }
    if (id == 50u) return vec3(1.0, 0.76, 0.50) * 6.0;   // torch
    if (id == 51u) return vec3(1.0, 0.62, 0.30) * 6.0;   // fire
    if (id == 10u || id == 11u) return vec3(1.0, 0.45, 0.12) * 5.0; // lava
    if (id == 89u) return vec3(1.0, 0.86, 0.62) * 5.0;   // glowstone
    if (id == 91u) return vec3(1.0, 0.70, 0.35) * 5.0;   // jack o'lantern
    if (id == 76u || id == 94u) return vec3(1.0, 0.15, 0.08) * 2.5; // lit redstone torch, repeater
    if (id == 74u) return vec3(1.0, 0.15, 0.08) * 1.5;   // glowing redstone ore
    if (id == 62u) return vec3(1.0, 0.6, 0.3) * 2.5;     // lit furnace
    if (id == 90u) return vec3(0.6, 0.3, 1.0) * 3.0;     // portal
    if (id == 39u) return vec3(1.0, 0.8, 0.6) * 0.5;     // brown mushroom
    return vec3(0.0);
}

// M9: how shiny a block is (built-in table; a LabPBR pack's _s maps can override it).
//   x: roughness, 0 = mirror .. 1 = matte (>= 0.9: no reflection at all)
//   y: reflectance looking straight on (F0): water 2%, glass 4%, diamond 17%; metals reflect their own colour
//   z: 1 = metal   w: the Reflections setting it needs (1 = Water & glass, 2 = All)
vec4 material(uint id) {
    if (id == 8u || id == 9u) return vec4(0.0, 0.02, 0.0, 1.0);   // water
    if (id == 79u)            return vec4(0.03, 0.02, 0.0, 1.0);  // ice
    if (id == 20u)            return vec4(0.0, 0.04, 0.0, 1.0);   // glass
    if (id == 42u)            return vec4(0.30, 1.0, 1.0, 2.0);   // iron block
    if (id == 41u)            return vec4(0.22, 1.0, 1.0, 2.0);   // gold block
    if (id == 57u)            return vec4(0.06, 0.17, 0.0, 2.0);  // diamond block
    if (id == 49u)            return vec4(0.12, 0.05, 0.0, 2.0);  // obsidian
    return vec4(1.0, 0.0, 0.0, 9.0);
}
float fresnel(float f0, float cosT) { return f0 + (1.0 - f0) * pow(1.0 - clamp(cosT, 0.0, 1.0), 5.0); }


// ---------------------------------------------------------------- leaves for the light that bounces around
// True while tracing the bounce light and the effects' rays: leaves count as solid blocks there (no test of
// each leaf pixel), the way Leaves: Fast draws them. Rays you see directly (the camera, the sun's shadow on what
// you look at, reflections) keep Fancy leaves, so the dappled light under trees stays. Leafy views ran at about
// 60% of the speed of Fast leaves, mostly in these rays.
bool gSolidLeaves = false;

// ---------------------------------------------------------------- optional effects (Path Tracer > Effects...)
// uEffects bits, set from PathTracer.effects(); uTime in seconds (game ticks / 20), uWind 0..1 (stronger in rain),
// uWet 0..1 (how much it's raining where the camera is; 0 in snowy and dry biomes).
// A texture pixel is solid above this alpha, as Beta's own alpha test (GL_GREATER 0.1): BuildCraft's dark pipe
// pixels sit between 0.1 and 0.5 and used to be holes.
const float ALPHA_CUT = 0.1;

const int FX_WET = 1, FX_HAZE = 2, FX_SPARKLE = 4, FX_GODRAYS = 8, FX_WAVING = 16, FX_WAVES = 32;
bool fxOn(int bit) { return (uEffects & bit) != 0; }

// Waving plants: the texture slides a little inside its square, so the cut-out shape sways while the block
// stays where it is (rays, shadows and the denoiser all see the same moving shape). Leaves drift as a whole;
// grass, flowers, saplings, crops and reeds bend from the bottom (height 0) to the top (height 1).
bool sways(uint id) { return id == 18u || id == 31u || id == 32u || id == 37u || id == 38u || id == 6u || id == 59u || id == 83u; }
vec2 swayUV(uint id, ivec3 worldCell, float height) {
    if (!fxOn(FX_WAVING) || !sways(id)) return vec2(0.0);
    float ph = float(worldCell.x) * 0.71 + float(worldCell.z) * 0.93 + float(worldCell.y) * 0.37;
    float s = sin(uTime * 1.6 + ph) * 0.65 + sin(uTime * 2.7 + ph * 1.9) * 0.35;
    float amp = (0.6 + uWind) * (id == 18u ? 0.03 : 0.07 * clamp(height, 0.0, 1.0));
    return vec2(s * amp, id == 18u ? cos(uTime * 1.2 + ph) * amp * 0.6 : 0.0);
}

// Smooth 2D noise in 0..1 (puddles).
float hash21(vec2 c) { return float(pcg(uint(int(c.x)) * 1597334677u ^ uint(int(c.y)) * 3812015801u)) * (1.0 / 4294967296.0); }
float valueNoise(vec2 x) {
    vec2 i = floor(x), f = fract(x);
    vec2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash21(i), hash21(i + vec2(1, 0)), u.x), mix(hash21(i + vec2(0, 1)), hash21(i + vec2(1, 1)), u.x), u.y);
}

// Water waves: three travelling ripples summed; returns the tilted surface normal at world x, z.
vec3 waveNormal(vec2 xz) {
    vec2 g = vec2(0.0);
    const vec2 D0 = vec2(0.8, 0.6), D1 = vec2(-0.5, 0.86), D2 = vec2(0.2, -0.98);
    g += D0 * 0.045 * cos(dot(D0, xz) * 1.9 + uTime * 1.5);
    g += D1 * 0.035 * cos(dot(D1, xz) * 3.1 + uTime * 2.2);
    g += D2 * 0.020 * cos(dot(D2, xz) * 5.3 + uTime * 3.1);
    g *= 0.7 + uWind;
    return normalize(vec3(-g.x, 1.0, -g.y));
}
