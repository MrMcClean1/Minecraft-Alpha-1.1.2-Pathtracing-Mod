#version 430
// Puts the finished path-traced image on screen AND writes the depth buffer from the
// distance each camera ray travelled, converted with Beta's own projection matrix.
// With real depth, everything Beta still draws itself afterwards (mobs, particles, the
// block outline, rain, clouds) is hidden behind or shown in front of blocks correctly.
layout(binding = 0) uniform sampler2D uColor;
layout(r32f, binding = 0) uniform readonly image2D uDepth;   // ray distance, < 0 for sky
uniform ivec2 uResolution;   // the path tracer's resolution (render scale)
uniform ivec2 uOutSize;      // the window
uniform vec2  uTan;    // tan(half fov) * aspect, tan(half fov)
uniform vec4  uProjZ;  // projection matrix entries P22, P23, P32, P33
out vec4 fragColor;

void main() {
    ivec2 opx = ivec2(gl_FragCoord.xy);
    fragColor = texelFetch(uColor, opx, 0);       // final, window-sized picture
    // depth comes from the path tracer's own (possibly smaller) grid of rays
    ivec2 px = min(ivec2((vec2(opx) + 0.5) * vec2(uResolution) / vec2(uOutSize)), uResolution - 1);
    float t = imageLoad(uDepth, px).r;
    if (t < 0.0) { gl_FragDepth = 1.0; return; } // sky: as far as it gets
    vec2 ndc = (vec2(px) + 0.5) / vec2(uResolution) * 2.0 - 1.0;
    float zEye = -t / length(vec3(ndc * uTan, 1.0));  // distance along the ray -> distance along the view axis
    float clipZ = uProjZ.x * zEye + uProjZ.y;
    float clipW = uProjZ.z * zEye + uProjZ.w;
    gl_FragDepth = clamp(clipZ / clipW * 0.5 + 0.5, 0.0, 1.0);
}
