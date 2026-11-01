// Shared by both path tracers: sky, sun, moon, clouds, torch light, the bounce, and main().
// The file that includes this provides how rays find things:
//   Hit traceRay(o, d, maxT, primary)  nearest hit (primary = camera ray: also note the water surface)
//   Hit traceShadow(o, d, maxT)        anything in the way? (may stop at the first hit)
//   void storeGeom(px, geom)           where the geometry picture goes

// ---------------------------------------------------------------- sky and sun
// Sunlight fades and reddens as it passes through more air near the horizon.
float sunUp() { return smoothstep(-0.03, 0.18, uSunDir.y); }
float moonUp() { return smoothstep(-0.03, 0.30, -uSunDir.y); }

vec3 sunLight() {
    vec3 tint = mix(vec3(1.0, 0.45, 0.20), vec3(1.0, 0.92, 0.80), smoothstep(0.0, 0.25, uSunDir.y));
    return tint * 3.0 * sunUp() * (1.0 - 0.8 * uRain);
}
vec3 moonLight() { return vec3(0.55, 0.65, 1.0) * uMoon * moonUp() * (1.0 - 0.8 * uRain); }

vec3 skyRadiance(vec3 d) {
    float up = clamp(d.y, 0.0, 1.0);
    vec3 col = mix(uFogColor, uSkyColor, pow(up, 0.5));
    // Beta's sunrise/sunset glow: strongest on the horizon toward the sun
    float towardSun = max(dot(normalize(vec3(d.x, 0.0, d.z) + 1e-6), normalize(vec3(uSunDir.x, 0.0, uSunDir.z) + 1e-6)), 0.0);
    float glow = uSunrise.a * pow(towardSun, 3.0) * (1.0 - smoothstep(0.0, 0.6, abs(d.y))) * 0.8;
    col = mix(col, uSunrise.rgb, glow);
    return srgbToLinear(col);
}

// What a camera ray sees when it misses everything: sky, Beta-sized sun, moon, stars.
vec3 skyView(vec3 d) {
    vec3 col = skyRadiance(d);
    // Beta draws the sun as a square 30 units wide at 100 units away
    vec3 s = uSunDir;
    vec3 a = normalize(cross(abs(s.y) < 0.99 ? vec3(0, 1, 0) : vec3(1, 0, 0), s));
    vec3 b = cross(s, a);
    float ds = dot(d, s);
    if (ds > 0.0) {
        vec2 q = vec2(dot(d, a), dot(d, b)) / ds;
        // sized and coloured to match Beta's sun texture on screen (visible core ~0.08 of the distance)
        float core = 1.0 - smoothstep(0.075, 0.085, max(abs(q.x), abs(q.y)));
        float halo = 1.0 - smoothstep(0.0, 0.25, max(abs(q.x), abs(q.y)));
        float vis = smoothstep(-0.15, 0.0, s.y) * (1.0 - uRain); // drawn whenever it's above the horizon, like Beta
        col += (core * vec3(1.0, 0.92, 0.6) * 1.2 + halo * halo * vec3(1.0, 0.8, 0.5) * 0.35) * vis;
    } else if (ds < 0.0) {
        vec2 q = vec2(dot(d, a), dot(d, b)) / -ds;
        float moon = 1.0 - smoothstep(0.045, 0.055, max(abs(q.x), abs(q.y)));
        col += moon * vec3(0.85, 0.9, 1.0) * 1.2 * smoothstep(-0.15, 0.0, -s.y) * (1.0 - uRain);
    }
    // stars, fading in as the daylight goes
    if (d.y > 0.0) {
        vec3 g = floor(d * 300.0);
        uint hsh = pcg(uint(int(g.x) * 73856093) ^ uint(int(g.y) * 19349663) ^ uint(int(g.z) * 83492791));
        if ((hsh & 1023u) < 2u) col += vec3(0.6) * (1.0 - uDaylight) * (1.0 - uRain);
    }
    return col;
}

// Direction inside a cone around 'axis' (soft shadows from a sun of non-zero size).
vec3 coneSample(vec3 axis, float cosMax) {
    float u = rnd(), v = rnd();
    float cosT = mix(1.0, cosMax, u);
    float sinT = sqrt(max(0.0, 1.0 - cosT * cosT));
    float phi = 2.0 * PI * v;
    vec3 a = normalize(cross(abs(axis.y) < 0.99 ? vec3(0, 1, 0) : vec3(1, 0, 0), axis));
    vec3 b = cross(axis, a);
    return normalize(a * cos(phi) * sinT + b * sin(phi) * sinT + axis * cosT);
}
vec3 cosineSample(vec3 n) {
    float u = rnd(), v = rnd();
    float r = sqrt(u), phi = 2.0 * PI * v;
    vec3 a = normalize(cross(abs(n.y) < 0.99 ? vec3(0, 1, 0) : vec3(1, 0, 0), n));
    vec3 b = cross(n, a);
    return normalize(a * r * cos(phi) + b * r * sin(phi) + n * sqrt(max(0.0, 1.0 - u)));
}

// How much cloud is between point p (relative to uCamBlock) and the sky along direction l: 0 = clear, 1 = cloud.
float cloudCover(vec3 p, vec3 l) {
    if (uCloudShadow <= 0.0 || l.y < 0.02) return 0.0;
    vec3 w = p + vec3(uCamBlock);
    if (w.y >= uCloud.x) return 0.0;               // above the clouds
    vec3 c = w + l * ((uCloud.x - w.y) / l.y);     // where the ray crosses the cloud sheet
    vec2 texel = (vec2(c.x + uCloud.y, c.z)) * uCloud.z + vec2(0.0, uCloud.w);
    ivec2 px = ivec2(floor(mod(texel, 256.0)));
    return texelFetch(uClouds, px, 0).a > 0.5 ? 1.0 : 0.0;
}

// Direct light from the sun or moon at point p (relative to uCamBlock) with normal n.
vec3 sunAndMoon(vec3 p, vec3 n, float maxT) {
    vec3 result = vec3(0.0);
    bool day = uSunDir.y > -0.1;
    vec3 dir = day ? uSunDir : -uSunDir;
    vec3 light = day ? sunLight() : moonLight();
    float cosN = dot(n, dir);
    if (cosN > 0.0 && max(light.r, light.b) > 0.0) {
        vec3 l = coneSample(dir, 0.99985); // about 1 degree across: soft but defined shadows
        Hit s = traceShadow(p + n * 1e-3, l, maxT);
        // "In between" sunset look: with the sun low, a real sun barely lights flat ground
        // (cos 78 deg = 0.2), while Beta keeps it at full daylight. Flattening the angle
        // falloff while the sun is low lifts that 0.2 to ~0.46; at midday it's untouched.
        float k = day ? mix(uLowSunK, 1.0, smoothstep(0.1, 0.5, uSunDir.y)) : 1.0;
        if (!s.hit) result = light * s.transmit * pow(cosN, k) * (1.0 - uCloudShadow * cloudCover(p, l));
    }
    return result;
}

// Light from one torch / lava / glowstone block, picked from the light list.
// The list is sorted nearest-first, and candidates are drawn with a strong bias toward
// the start of it (index = N * u^3), so the torch next to you is tried far more often
// than lava 60 blocks away. Among 8 candidates, one is kept in proportion to how much
// light it would deliver here (resampled importance sampling), then a shadow ray checks it.
float pickProb(int k, int n) { // probability that index k comes out of floor(n * u^3)
    float a = pow(float(k + 1) / float(n), 1.0 / 3.0);
    float b = pow(float(k) / float(n), 1.0 / 3.0);
    return max(a - b, 1e-6);
}

vec3 blockLights(vec3 p, vec3 n) {
    if (uLightCount <= 0) return vec3(0.0);
    const int M = 8;
    float wSum = 0.0;
    vec3 chosenPos = vec3(0.0), chosenCol = vec3(0.0);
    float chosenPHat = 0.0;
    for (int i = 0; i < M; i++) {
        float u = rnd();
        int k = min(int(float(uLightCount) * u * u * u), uLightCount - 1);
        vec4 L = lights[k];
        vec3 lp = L.xyz + vec3(0.3 + 0.4 * rnd(), 0.2 + 0.5 * rnd(), 0.3 + 0.4 * rnd());
        vec3 toL = lp - p;
        float d2 = max(dot(toL, toL), 0.25);
        vec3 col = emission(uint(L.w));
        float cosN = max(dot(n, toL * inversesqrt(d2)), 0.0);
        float pHat = dot(col, vec3(0.3, 0.6, 0.1)) * cosN / (d2 + 1.0);
        float w = pHat / pickProb(k, uLightCount);
        wSum += w;
        if (w > 0.0 && rnd() * wSum < w) { chosenPos = lp; chosenCol = col; chosenPHat = pHat; }
    }
    if (chosenPHat <= 0.0) return vec3(0.0);
    vec3 toL = chosenPos - p;
    float dist = length(toL);
    vec3 l = toL / dist;
    vec3 contrib = chosenCol * max(dot(n, l), 0.0) / (max(dist * dist, 0.25) + 1.0);
    Hit s = traceShadow(p + n * 1e-3, l, max(dist - 0.6, 0.0)); // stop a little short of the light block itself
    if (s.hit) return vec3(0.0);
    // RIS estimate: f(x) / p_hat(x) * (sum of weights / M)
    return contrib * s.transmit * (wSum / float(M)) / chosenPHat;
}

// M9: steady light for reflections. A mirror shows its reflection sharp and undenoised, so the light at
// the reflected spot must not flicker: the sun without the soft-shadow jitter, and the two torches (of the 16
// nearest) that give the most there, each with a shadow ray, instead of a random pick.
vec3 sunSteady(vec3 p, vec3 n) {
    bool day = uSunDir.y > -0.1;
    vec3 dir = day ? uSunDir : -uSunDir, light = day ? sunLight() : moonLight();
    float cosN = dot(n, dir);
    if (cosN <= 0.0 || max(light.r, light.b) <= 0.0) return vec3(0.0);
    Hit s = traceShadow(p + n * 1e-3, dir, 128.0);
    if (s.hit) return vec3(0.0);
    float k = day ? mix(uLowSunK, 1.0, smoothstep(0.1, 0.5, uSunDir.y)) : 1.0;
    return light * s.transmit * pow(cosN, k) * (1.0 - uCloudShadow * cloudCover(p, dir));
}
vec3 lightsSteady(vec3 p, vec3 n) {
    int best[2] = int[](-1, -1);
    float bw[2] = float[](0.0, 0.0);
    for (int k = 0; k < min(uLightCount, 16); k++) {
        vec4 L = lights[k];
        vec3 toL = L.xyz + vec3(0.5, 0.45, 0.5) - p;
        float d2 = max(dot(toL, toL), 0.25);
        float w = dot(emission(uint(L.w)), vec3(0.3, 0.6, 0.1)) * max(dot(n, toL * inversesqrt(d2)), 0.0) / (d2 + 1.0);
        if (w > bw[0]) { bw[1] = bw[0]; best[1] = best[0]; bw[0] = w; best[0] = k; }
        else if (w > bw[1]) { bw[1] = w; best[1] = k; }
    }
    vec3 sum = vec3(0.0);
    for (int j = 0; j < 2; j++) {
        // a torch too far away to show in a reflection isn't worth a shadow ray (most of the cost at noon)
        if (best[j] < 0 || bw[j] < 0.004) continue;
        vec4 L = lights[best[j]];
        vec3 toL = L.xyz + vec3(0.5, 0.45, 0.5) - p;
        float dist = length(toL);
        vec3 l = toL / dist;
        Hit s = traceShadow(p + n * 1e-3, l, max(dist - 0.6, 0.0));
        if (!s.hit) sum += emission(uint(L.w)) * max(dot(n, l), 0.0) / (max(dist * dist, 0.25) + 1.0) * s.transmit;
    }
    return sum;
}

// The second bounce (Bounces: 2): from where the first bounce landed, one more ray: sky light, or the sun
// and glow on whatever it hits. Lights up rooms and caves the way light spills round corners.
vec3 secondBounce(vec3 p, vec3 n) {
    vec3 dir = cosineSample(n);
    Hit b = traceRay(p + n * 1e-3, dir, 48.0, false);
    if (!b.hit) {
        vec3 sky = skyRadiance(dir);
        sky = mix(vec3(dot(sky, vec3(0.2126, 0.7152, 0.0722))), sky, uSkySat);
        return sky * (0.25 + 0.75 * uDaylight) * mix(1.0, uSkyFill, uDaylight) * b.transmit;
    }
    vec3 bp = p + n * 1e-3 + dir * b.t, bn = b.n;
    if (dot(bn, dir) > 0.0) bn = -bn;
    return (emission(b.id) * b.albedo * 1.5 * b.glow + b.albedo * sunSteady(bp, bn)) * b.transmit;
}

// One bounce: sky light, or light reflected off whatever the bounce ray hits.
vec3 bounceLight(vec3 p, vec3 n) {
    gSolidLeaves = true; // the bounce, its sun ray and the second bounce: leaves as solid blocks
    vec3 indirect = vec3(0.0);
    vec3 bdir = cosineSample(n);
    Hit b;
    if ((uDebugMask & 4) == 0) b = traceRay(p + n * 1e-3, bdir, 96.0, false);
    if ((uDebugMask & 4) != 0) {
    } else if (!b.hit) {
        // Beta's sky blue is very saturated; as light on the ground it reads too blue
        // (grey-blue sand at sunset), so the light it casts is partly desaturated.
        vec3 sky = skyRadiance(bdir);
        sky = mix(vec3(dot(sky, vec3(0.2126, 0.7152, 0.0722))), sky, uSkySat);
        indirect = sky * (0.25 + 0.75 * uDaylight) * mix(1.0, uSkyFill, uDaylight) * b.transmit
                 * (1.0 - 0.4 * uCloudShadow * cloudCover(p, bdir)); // clouds dim the sky light a little too
    } else {
        vec3 bp = p + n * 1e-3 + bdir * b.t;
        vec3 bn = b.n;
        if (dot(bn, bdir) > 0.0) bn = -bn;
        vec3 atB = (uDebugMask & 8) == 0 ? sunAndMoon(bp, bn, 128.0) : vec3(0.0);
        if (uBounces >= 2) atB += secondBounce(bp, bn);
        indirect = (emission(b.id) * b.albedo * 1.5 * b.glow + b.albedo * atB) * b.transmit;
    }
    gSolidLeaves = false;
    return indirect;
}

#ifdef PT_PROBE
// Light probes (OpenGL only), one 64-thread group each, every sample a different direction:
//   group 0: the light reaching the player's hand (Beta draws the hand in its colour, PathTracer.handLight)
//   groups 1..: a 16 x 8 x 16 grid of points 2 blocks apart around the camera, for particles and rain
//   (PathTracer.lightAt). w = 1 if the point is in the open, 0 inside a solid block (ignored).
shared vec3 probeSum[64];
void probeMain() {
    uint g = gl_WorkGroupID.x, i = gl_LocalInvocationIndex;
    rngState = pcg(i * 9781u + g * 7919u + uint(uFrame) * 6271u) | 1u;
    vec3 p, n;
    float open = 1.0;
    if (g == 0u) {
        p = uCamFrac + uProbeOffset; n = uProbeNormal;
    } else {
        uint c = g - 1u;
        p = uGridOrigin + (vec3(c % 16u, (c / 16u) % 8u, c / 128u) + 0.5) * 2.0;
        // any direction: a speck of smoke or a raindrop is lit from all around
        float z = rnd() * 2.0 - 1.0, a = rnd() * 2.0 * PI, r = sqrt(max(0.0, 1.0 - z * z));
        n = vec3(r * cos(a), z, r * sin(a));
        uint v = voxel(ivec3(floor(p)));
        if ((v & 4095u) != 0u && (blockInfo[((v & 4095u) * 16u + (v >> 12u)) * 9u] & 15u) == CUBE) open = 0.0;
    }
    probeSum[i] = open > 0.0 ? sunAndMoon(p, n, 256.0) + blockLights(p, n) + bounceLight(p, n) + vec3(uAmbient) : vec3(0.0);
    barrier();
    if (i == 0u) {
        vec3 sum = vec3(0.0);
        for (int k = 0; k < 64; k++) sum += probeSum[k];
        probeOut[g] = vec4(sum / 64.0, open);
    }
}
#endif

// M9: what a reflection ray sees: the sky (with the sun), or the surface it hits lit by the sun, nearby
// torches and a rough share of sky light (no bounce of its own, to keep it cheap and steady).
vec3 reflectedLight(vec3 p, vec3 r) {
    Hit b = traceRay(p, r, 192.0, false);
    if (!b.hit) return skyView(r) * b.transmit;
    vec3 bp = p + r * b.t, bn = b.n;
    if (dot(bn, r) > 0.0) bn = -bn;
    vec3 sky = skyRadiance(vec3(0, 1, 0)) * (0.25 + 0.75 * uDaylight) * mix(1.0, uSkyFill, uDaylight) * 0.6;
    vec3 L = sunSteady(bp, bn) + lightsSteady(bp, bn) + sky + vec3(uAmbient);
    vec3 glow = emission(b.id) * b.albedo * 1.5 * b.glow;
    if (b.id == 10u || b.id == 11u) return b.albedo * uLavaGlow * b.transmit; // lava: only its own glow
    return (b.albedo * L + glow) * b.transmit;
}

// M9 LabPBR: one texel of a PBR map, same tile and position as the colour texture
vec4 pbrTexel(sampler2D s, uint tile, vec2 uv) {
    int ts = max(textureSize(s, 0).x / int(GRID_COLS), 1);
    ivec2 t = ivec2(int(tile & (GRID_COLS - 1u)), int(tile >> GRID_SHIFT)) * ts;
    return texelFetch(s, t + clamp(ivec2(floor(uv * float(ts))), ivec2(0), ivec2(ts - 1)), 0);
}
// The texture's right (T) and down (B) directions on a block face, as faceUV lays textures out.
bool faceFrame(vec3 n, out vec3 T, out vec3 B) {
    vec3 a = abs(n);
    T = vec3(1, 0, 0); B = vec3(0, -1, 0);
    if (max(a.x, max(a.y, a.z)) < 0.99) return false;    // plants and tilted parts: no bump
    if (a.y > 0.99) { T = vec3(1, 0, 0); B = vec3(0, 0, 1); }
    else if (n.z < 0.0 && a.z > 0.99) T = vec3(-1, 0, 0);
    else if (a.z > 0.99) T = vec3(1, 0, 0);
    else if (n.x < 0.0) T = vec3(0, 0, 1);
    else T = vec3(0, 0, -1);
    return true;
}

// A direction near the mirror direction r, spread by roughness (cheap glossy lobe), kept above the surface.
vec3 glossy(vec3 r, vec3 n, float rough) {
    if (rough <= 0.0) return r;
    float z = rnd() * 2.0 - 1.0, a = rnd() * 2.0 * PI, s = sqrt(max(0.0, 1.0 - z * z));
    vec3 g = normalize(r + rough * vec3(s * cos(a), z, s * sin(a)));
    return dot(g, n) > 0.0 ? g : r;
}

// Effects: what rain can wet (not water, lava, glass, ice or snow, which have their own look)
bool wettable(uint id) {
    return id != 0u && id != 8u && id != 9u && id != 10u && id != 11u && id != 20u && id != 78u && id != 79u && id != 80u;
}

// Effects: god rays. Sunlight scattered toward the camera by the air along the ray, from the parts of the
// ray the sun reaches (vis: the share of 1 to 4 jittered points along it that see the sun): shafts through leaves and gaps.
vec3 godRays(vec3 d, float tEnd, float vis) {
    float c = dot(d, uSunDir);
    const float g = 0.6;
    float phase = (1.0 - g * g) / (4.0 * PI * pow(1.0 + g * g - 2.0 * g * c, 1.5)); // forward scattering: brightest toward the sun
    return sunLight() * vis * phase * 0.006 * tEnd;
}

void main() {
#ifdef PT_PROBE
    if (uProbeOnly != 0) { probeMain(); return; }
#endif
    ivec2 px = ivec2(gl_GlobalInvocationID.xy);
    if (px.x >= uResolution.x || px.y >= uResolution.y) return;
    rngState = pcg(uint(px.x) * 1973u + uint(px.y) * 9277u + uint(uFrame) * 26699u) | 1u;

    vec2 ndc = (vec2(px) + 0.5 + uJitter) / vec2(uResolution) * 2.0 - 1.0;
    float aspect = float(uResolution.x) / float(uResolution.y);
    vec3 d = normalize(uCamForward + ndc.x * uTanHalfFov * aspect * uCamRight + ndc.y * uTanHalfFov * uCamUp);

    Hit h = traceRay(uCamFrac, d, uMaxDist, true);

    // Effects: their extra shadow rays all go through one loop. The tracer is inlined wherever it's called, so
    // every new call site makes the shader much bigger (slower to build, more registers): one site for all.
    //   wet surfaces: is the spot open to the sky?  snow sparkle: does a glinting texel see the sun?
    //   god rays: which of 4 points along the camera ray see the sun?
    // No arrays of rays: they cost registers for every pixel (11 of them made every frame 30-60% slower on an RX
    // 7900 XT). Each ray is worked out inside the loop from a few values, and the answers are bits in fxHits.
    int fxN = 0, iWet = -1, iSpark = -1, iGod = -1, godN = 0;
    float sparkle = 0.0, godEnd = 0.0, jit = 0.0;
    vec3 hp = uCamFrac + d * h.t, hn = dot(h.n, d) > 0.0 ? -h.n : h.n;
    if (h.hit && fxOn(FX_WET) && uWet > 0.01 && h.ent == 0u && wettable(h.id)) { iWet = fxN; fxN++; }
    if (h.hit && fxOn(FX_SPARKLE) && (h.id == 78u || h.id == 79u || h.id == 80u) && hn.y > 0.5 && uSunDir.y > 0.02) {
        // a few texels of snow and ice, each its own slightly tilted mirror
        ivec3 sc = ivec3(floor((hp + vec3(uCamBlock)) * 16.0));
        uint hs = pcg(uint(sc.x) * 73856093u ^ uint(sc.y) * 19349663u ^ uint(sc.z) * 83492791u);
        if ((hs & 255u) < 16u) {
            vec3 jn = normalize(hn + vec3(float((hs >> 8) & 255u) / 127.5 - 1.0, 0.0, float((hs >> 16) & 255u) / 127.5 - 1.0) * 0.45);
            sparkle = pow(max(dot(reflect(d, jn), uSunDir), 0.0), 150.0);
            if (sparkle > 0.02) { iSpark = fxN; fxN++; }
        }
    }
    if (fxOn(FX_GODRAYS) && uSunDir.y > 0.02) {
        // two points along every ray. (It used to be 4 toward the sun, 2 across, 1 away: fewer points are noisier, the
        // denoiser's outlier clamp darkens noisy pixels more, so where the count changed a straight line crossed the
        // screen, the plane square to the sun: the "shadow line" through the leaves.)
        godN = 2;
        godEnd = min(h.hit ? h.t : uMaxDist, 40.0);
        jit = rnd();
        iGod = fxN; fxN += godN;
    }
    // the first see-through surface the camera ray went through (water, ice, an Aether cloud): is it under open sky
    // (three rays: up, and up at 45 degrees toward the camera and away from it), does it see the sun, and does it see
    // the brightest light block near it? (A shadow ray called anywhere else in main() turned these surfaces black:
    // one call site for every extra ray.)
    int iWSky = -1, iWSun = -1, iWTorch = -1;
    vec3 wsn = vec3(0.0, 1.0, 0.0), wsp = vec3(0.0), wTorch = vec3(0.0), wTorchL = vec3(0.0, 1.0, 0.0);
    vec2 hz = vec2(0.0, 1.0);
    float wTorchT = 0.0;
    if (h.water) {
        wsn = dot(h.waterN, d) > 0.0 ? -h.waterN : h.waterN;
        wsp = uCamFrac + d * h.waterT + wsn * 1e-3;
        hz = normalize(vec2(d.x, d.z) + vec2(1e-5));
        iWSky = fxN; fxN += 3;
        if (uSunDir.y > 0.0 && dot(wsn, uSunDir) > 0.0) { iWSun = fxN; fxN++; }
        int bk = -1; float bw = 0.0;
        for (int k = 0; k < min(uLightCount, 16); k++) {
            vec3 toL = lights[k].xyz + vec3(0.5, 0.45, 0.5) - wsp;
            float d2 = max(dot(toL, toL), 0.25);
            float w = dot(emission(uint(lights[k].w)), vec3(0.3, 0.6, 0.1)) * max(dot(wsn, toL * inversesqrt(d2)), 0.0) / (d2 + 1.0);
            if (w > bw) { bw = w; bk = k; }
        }
        if (bk >= 0 && bw > 0.004) {
            vec3 toL = lights[bk].xyz + vec3(0.5, 0.45, 0.5) - wsp;
            float dist = length(toL);
            wTorchL = toL / dist;
            wTorchT = max(dist - 0.6, 0.0);
            wTorch = emission(uint(lights[bk].w)) * max(dot(wsn, wTorchL), 0.0) / (max(dist * dist, 0.25) + 1.0);
            iWTorch = fxN; fxN++;
        }
    }
    // wet sky check, sparkle and god rays see leaves as solid blocks; the see-through surface's rays (from iWSky on)
    // see Fancy leaves, so water under a tree isn't taken for water in a cave
    uint fxHits = 0u;
    for (int i = 0; i < fxN; i++) {
        vec3 ro = wsp, rd = uSunDir;
        float rt = 128.0;
        if (i == iWet) { ro = hp + hn * 1e-3; rd = vec3(0.0, 1.0, 0.0); }
        else if (i == iSpark) { ro = hp + hn * 1e-3; }
        else if (iGod >= 0 && i >= iGod && i < iGod + godN) { ro = uCamFrac + d * (godEnd * (float(i - iGod) + jit) / float(godN)); rt = 64.0; }
        else if (i == iWSky) rd = vec3(0.0, 1.0, 0.0);
        else if (i == iWSky + 1) { rd = normalize(vec3(-hz.x, 1.0, -hz.y)); rt = 64.0; }
        else if (i == iWSky + 2) { rd = normalize(vec3(hz.x, 1.0, hz.y)); rt = 64.0; }
        else if (i == iWTorch) { rd = wTorchL; rt = wTorchT; }
        gSolidLeaves = iWSky < 0 || i < iWSky;
        if (traceShadow(ro, rd, rt).hit) fxHits |= 1u << uint(i);
    }
    gSolidLeaves = false;
    vec3 light = vec3(0.0), albedo = vec3(0.0), extra = vec3(0.0);
    vec4 geom = vec4(0.0, 0.0, 0.0, 0.0);
    vec4 skyFog = vec4(0.0);
    if (!h.hit) {
        extra = skyView(d) * h.transmit; // kept here too so auto exposure still sees the sky
        skyFog = vec4(extra, 1.0);
    } else {
        vec3 p = uCamFrac + d * h.t;
        vec3 gn = h.n;
        if (dot(gn, d) > 0.0) gn = -gn; // plants: face the camera
        vec3 n = gn;                    // the normal light sees (bumped by a LabPBR normal map); gn goes to the denoiser
        vec4 m = material(h.id);
        vec3 pbrGlow = vec3(0.0);
        if (uPbr != 0 && h.tile < 256u) {
            vec3 T, B;
            vec4 nm = pbrTexel(uAtlasN, h.tile, h.uv);
            if (faceFrame(gn, T, B)) {
                // LabPBR normals: red = right, green = down the texture (DirectX style), z rebuilt
                vec2 xy = nm.rg * 2.0 - 1.0;
                vec3 bn = normalize(T * xy.x + B * xy.y + gn * sqrt(max(0.0, 1.0 - dot(xy, xy))));
                if (dot(bn, -d) > 0.0) n = bn;
            }
            vec4 sp = pbrTexel(uAtlasS, h.tile, h.uv);
            float g = sp.g * 255.0;
            // red: perceptual smoothness; green: F0 (0..229), 230+ = metal; alpha: glow (255 = none)
            if (sp.r > 0.0 || g > 0.5) m = vec4(pow(1.0 - sp.r, 2.0), g >= 229.5 ? 1.0 : sp.g, g >= 229.5 ? 1.0 : 0.0, 1.0);
            if (sp.a < 0.999) pbrGlow = h.albedo * sp.a * 3.0;
        }
        // Effects: wet surfaces. While it rains here, whatever is open to the sky darkens and turns glossy, and
        // flat ground gathers puddles (smooth noise over the world) that reflect like still water.
        float wet = 0.0, puddle = 0.0;
        if (iWet >= 0) {
            if ((fxHits & (1u << uint(iWet))) == 0u) {
                wet = uWet * (gn.y > 0.7 ? 1.0 : 0.45);
                if (gn.y > 0.9) {
                    vec2 wxz = p.xz + vec2(uCamBlock.xz);
                    float nz = valueNoise(wxz * 0.15) * 0.7 + valueNoise(wxz * 0.6) * 0.3;
                    puddle = smoothstep(0.58, 0.70, nz) * smoothstep(0.35, 0.9, uWet);
                }
            }
        }
        vec3 emit = emission(h.id) * h.albedo * 1.5 * h.glow; // glow follows the texture: a torch's flame shines, its stick doesn't
        // lava as seen: its own texture colours at about 90% after exposure (uLavaGlow = 0.9 / last frame's
        // exposure). Any brighter and the red channel overflows, the tone mapper blends it toward white, and
        // it turns pink, worst at night when the exposure opens up. The light it casts is unchanged.
        if (h.id == 10u || h.id == 11u) emit = h.albedo * uLavaGlow;
        vec3 direct = ((uDebugMask & 1) == 0 ? sunAndMoon(p, n, 256.0) : vec3(0.0))
                    + ((uDebugMask & 2) == 0 ? blockLights(p, n) : vec3(0.0));

        vec3 indirect = bounceLight(p, n);

        // Beta-faithful floor: a little light everywhere so caves and nights stay playable
        vec3 ambient = vec3(uAmbient);
        light = direct + indirect + ambient;
        // Rare, extremely bright samples ("fireflies", e.g. a bounce ray that happens to hit
        // lava) would take the denoiser many frames to average away. Cap them.
        float lum = dot(light, vec3(0.2126, 0.7152, 0.0722));
        if (lum > 12.0) light *= 12.0 / lum;
        // lava shows only its own glow: sunlight on top of it pushed it past white at midday
        if (h.id == 10u || h.id == 11u) light = vec3(0.0);
        albedo = h.albedo * h.transmit * (1.0 - 0.45 * wet); // wet things are darker
        extra = (emit + pbrGlow) * h.transmit;

        // Effects: snow sparkle: a glinting texel that sees the sun, drawn sharp on top
        if (iSpark >= 0 && (fxHits & (1u << uint(iSpark))) == 0u) extra += sunLight() * sparkle * 8.0 * h.transmit;

        // M9 reflections (Reflections setting: 1 = water and glass, 2 = also metals, diamond, obsidian;
        // a LabPBR pack's surfaces count as 1)
        // Effects: the wet sheen and puddles reflect like water (Reflect: Water or All), through the same
        // reflection as shiny blocks (one call site: see the effect rays above); a shiny block keeps its own
        float wetR = max(wet * 0.6, puddle), refScale = 1.0;
        if (!(m.x < 0.9 && float(uReflections) >= m.w) && wetR > 0.0 && uReflections >= 1) {
            m = vec4(mix(0.3, 0.0, puddle), 0.02, 0.0, 1.0);
            refScale = wetR;
            if (puddle > 0.5) n = gn; // puddles are flat
        }
        if (m.x < 0.9 && float(uReflections) >= m.w) {
            vec3 r = glossy(reflect(d, n), n, m.x);
            vec3 R = reflectedLight(p + gn * 1e-3, r);
            if (m.z > 0.5) {
                // metal: mostly mirror, tinted by its own colour (albedo), so it stays in the denoised light
                light = mix(light, R, 0.8);
            } else {
                float F = fresnel(m.y, dot(-d, n)) * refScale;
                if (m.x > 0.15) {
                    // a blurry reflection is grainy: send it through the denoiser with the rest of the light
                    light = light * (1.0 - F) + F * R / max(h.albedo, vec3(0.05));
                    float l2 = dot(light, vec3(0.2126, 0.7152, 0.0722));
                    if (l2 > 12.0) light *= 12.0 / l2;
                } else {
                    light *= 1.0 - F;
                    extra += F * R * h.transmit;
                }
            }
        }
        geom = vec4(octEncode(gn), h.t, 1.0 + float(h.ent)); // w: 0 sky, 1 world, 2+ a mob (which one: its motion)

        float fog = clamp((h.t - uFogStart) / (uFogEnd - uFogStart), 0.0, 1.0);
        skyFog = vec4(skyRadiance(d), fog);
    }

    // water surface: its own colour lit by sky and sun, laid over what's beneath
    if (h.water) {
        float a = h.waterAlpha * 0.85;
        // lit by the face it was seen through, with Beta's face shading (top 1, sides 0.8 / 0.6, bottom 0.5), so a
        // block of ice or a modded see-through block (an Aether cloud) shows its edges; a water top is lit as before
        vec3 sn = wsn;
        float faceShade = sn.y > 0.5 ? 1.0 : sn.y < -0.5 ? 0.5 : abs(sn.z) > 0.5 ? 0.8 : 0.6;
        // sky light only where the sky is open above it (water in a cave used to glow with it), the sun only where
        // it reaches, and the nearest bright light block (its ray from the batch above)
        float open = iWSky < 0 ? 3.0 : 3.0 - float(bitCount((fxHits >> uint(iWSky)) & 7u));
        float skyVis = open < 0.5 ? 0.03 : mix(0.15, 1.0, open * open / 9.0);
        vec3 sunW = iWSun >= 0 && (fxHits & (1u << uint(iWSun))) == 0u ? sunLight() * dot(sn, uSunDir) : vec3(0.0);
        vec3 torch = iWTorch >= 0 && (fxHits & (1u << uint(iWTorch))) == 0u ? wTorch : vec3(0.0);
        vec3 surf = h.waterColor * (skyRadiance(vec3(0, 1, 0)) * (0.25 + 0.75 * uDaylight) * faceShade * skyVis
                                    + sunW + torch) * 0.26;
        // (Beta's water texture is nearly pure blue; any brighter and the blue channel
        //  overflows, the tone mapper blends it toward white, and it turns lilac.)
        albedo *= 1.0 - a;
        extra = mix(extra, surf, a);
        if (!h.hit) skyFog.rgb = mix(skyFog.rgb, surf, a);
        // M9: the water (or ice) surface reflects, a lot at a glancing angle, little straight down
        if (uReflections >= 1 && d.y < -0.01) {
            vec3 wn = vec3(0.0, 1.0, 0.0);
            vec3 wp = uCamFrac + d * h.waterT + wn * 1e-3;
            vec3 rd = reflect(d, wn);
            if (fxOn(FX_WAVES)) { // Effects: water waves tilt the surface; a ray bent below it keeps the flat one
                vec3 ww = waveNormal(wp.xz + vec2(uCamBlock.xz));
                vec3 r2 = reflect(d, ww);
                if (r2.y > 0.02) { wn = ww; rd = r2; }
            }
            // the light on the water sets how well it mirrors: bright (open sky, sun, a torch beside it) it is a clear
            // mirror, in a dark cave it is a dull black pool; lava is opaque and glows, so it never mirrors
            float wLit = clamp(max(skyVis * (0.25 + 0.75 * uDaylight), dot(sunW, vec3(0.3, 0.6, 0.1)) + 2.0 * dot(torch, vec3(0.3, 0.6, 0.1))), 0.0, 1.0);
            float F = fresnel(0.02, dot(-d, wn)) * mix(0.3, 1.0, sqrt(wLit));
            vec3 R = reflectedLight(wp, rd);
            albedo *= 1.0 - F;
            extra = extra * (1.0 - F) + F * R;
            if (!h.hit) skyFog.rgb = skyFog.rgb * (1.0 - F) + F * R;
        }
    }
    // M9: the clear part of glass reflects a little too (4% straight on, much more at a glancing angle)
    if (h.glass && uReflections >= 1 && (!h.hit || h.glassT < h.t)) {
        vec3 gnm = dot(h.glassN, d) > 0.0 ? -h.glassN : h.glassN;
        float F = fresnel(0.04, dot(-d, gnm));
        vec3 R = reflectedLight(uCamFrac + d * h.glassT + gnm * 1e-3, reflect(d, gnm));
        albedo *= 1.0 - F;
        extra = extra * (1.0 - F) + F * R;
        if (!h.hit) skyFog.rgb = skyFog.rgb * (1.0 - F) + F * R;
    }

    // Effects: god rays, up to 40 blocks out (in the light for surfaces, so the denoiser smooths them; on the sky
    // directly, scaled to the sky's display brightness)
    if (iGod >= 0) {
        float vis = 0.0;
        for (int i = 0; i < godN; i++) if ((fxHits & (1u << uint(iGod + i))) == 0u) vis += 1.0 - uCloudShadow * cloudCover(uCamFrac + d * (godEnd * (float(i) + jit) / float(godN)), uSunDir);
        vec3 rays = godRays(d, godEnd, vis / float(godN));
        // Haze in front of the surface. Dividing by a dark surface colour (0.05) blew the noisy shafts up 20x; the
        // denoiser clamped them and the long rays near the horizon came out as a dark band. 0.35 caps that at 3x.
        if (h.hit) light += rays / max(albedo, vec3(0.35));
        else skyFog.rgb += rays * 0.35;
    }

    imageStore(outLight, px, vec4(max(light, vec3(0.0)), 1.0));
    imageStore(outAlbedo, px, vec4(albedo, 1.0));
    imageStore(outExtra, px, vec4(max(extra, vec3(0.0)), 1.0));
    storeGeom(px, geom);
    imageStore(outDepth, px, vec4(h.water ? h.waterT : (h.hit ? h.t : -1.0)));
    imageStore(outSkyFog, px, max(skyFog, vec4(0.0)));
}
