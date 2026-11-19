package com.sweepscan.server

/** GLSL ES 3.0 shaders of the web panel's 3D view (WebGL2), sent with the scene. */
object PanelShaders {
    val SHADERS: Map<String, String> get() = mapOf("BOX_VS" to BOX_VS, "BOX_FS" to BOX_FS, "FLOOR_VS" to FLOOR_VS, "FLOOR_FS" to FLOOR_FS)

    private const val BOX_VS = """#version 300 es
layout(location=0) in vec3 aPos;
layout(location=1) in vec3 aNormal;
layout(location=2) in vec3 iCenter;
layout(location=3) in vec3 iSize;
layout(location=4) in vec4 iColor;
layout(location=5) in vec3 iMisc;
uniform mat4 uViewProj;
uniform float uTime;
out vec3 vNormal;
out vec4 vColor;
out vec3 vWorld;
out vec3 vLocal;
out vec3 vSize;
flat out float vKind;
out float vParam;
void main() {
float kind = iMisc.y;
float yaw = iMisc.x;
vec3 center = iCenter;
if (abs(kind - 3.0) < 0.5) { yaw += uTime * 1.6; center.y += 0.09 * sin(uTime * 2.6); }
float c = cos(yaw), s = sin(yaw);
vec3 p = aPos * iSize;
p = vec3(c * p.x + s * p.z, p.y, -s * p.x + c * p.z);
vec3 n = vec3(c * aNormal.x + s * aNormal.z, aNormal.y, -s * aNormal.x + c * aNormal.z);
vec3 w = center + p;
vWorld = w;
vNormal = n;
vColor = iColor;
vLocal = aPos;
vSize = iSize;
vKind = kind;
vParam = iMisc.z;
gl_Position = uViewProj * vec4(w, 1.0);
}
"""

    private const val BOX_FS = """#version 300 es
precision highp float;
in vec3 vNormal;
in vec4 vColor;
in vec3 vWorld;
in vec3 vLocal;
in vec3 vSize;
flat in float vKind;
in float vParam;
uniform vec3 uEye;
uniform float uTime;
uniform vec2 uFog;
out vec4 outColor;
const vec3 BG = vec3(0.055, 0.063, 0.075);
const vec3 AMBER = vec3(1.0, 0.70, 0.0);
void main() {
vec3 n = normalize(vNormal);
vec3 base = vColor.rgb;
int kind = int(vKind + 0.5);
if (kind == 4) {
    // Light beam: fades upwards and at the sides.
    float up = clamp(vLocal.y + 0.5, 0.0, 1.0);
    float a = (1.0 - up) * (1.0 - up) * vColor.a * (0.75 + 0.25 * sin(uTime * 3.0));
    outColor = vec4(AMBER * a, a);
    return;
}
if (kind == 2) {
    // Route: stripes flowing towards the end.
    float along = vParam + (vLocal.x + 0.5) * vSize.x;
    float stripe = smoothstep(0.35, 0.5, fract(along * 0.9 - uTime * 1.3));
    float edge = smoothstep(0.5, 0.36, abs(vLocal.z));
    vec3 col = mix(base * 0.55, base * 1.25, stripe) * mix(0.6, 1.0, edge);
    float d = length(vWorld - uEye);
    outColor = vec4(mix(col, BG, smoothstep(uFog.x, uFog.y, d)), 1.0);
    return;
}
vec3 L = normalize(vec3(-0.45, 0.85, 0.3));
float diff = max(dot(n, L), 0.0);
float hemi = 0.6 + 0.4 * n.y;
vec3 col = base * (0.42 * hemi + 0.72 * diff);
if (kind == 5) col = base * 0.95;
// Darker towards the bottom of each box (cheap contact shading).
col *= mix(0.78, 1.0, clamp(vLocal.y + 0.5, 0.0, 1.0));
// Crisp edges: darken a thin band (in metres) along the face's borders.
vec3 dist = (0.5 - abs(vLocal)) * vSize;
vec3 an = abs(n);
float e = an.x > 0.5 ? min(dist.y, dist.z) : (an.y > 0.5 ? min(dist.x, dist.z) : min(dist.x, dist.y));
if (kind != 5) col *= mix(0.62, 1.0, smoothstep(0.004, 0.012, e));
if (kind == 1) {
    float pulse = 0.5 + 0.5 * sin(uTime * 4.0);
    col = mix(col, AMBER, 0.22 * vParam) + AMBER * (0.06 + 0.16 * pulse) * vParam;
} else if (kind == 3) {
    col = AMBER * (0.85 + 0.35 * diff) + vec3(0.15);
}
float d = length(vWorld - uEye);
col = mix(col, BG, smoothstep(uFog.x, uFog.y, d) * (kind == 1 || kind == 3 ? 0.3 : 1.0));
outColor = vec4(col, 1.0);
}
"""

    private const val FLOOR_VS = """#version 300 es
layout(location=0) in vec3 aPos;
uniform mat4 uViewProj;
out vec3 vWorld;
void main() {
vWorld = aPos;
gl_Position = uViewProj * vec4(aPos, 1.0);
}
"""

    private const val FLOOR_FS = """#version 300 es
precision highp float;
in vec3 vWorld;
uniform vec3 uEye;
uniform vec2 uFog;
out vec4 outColor;
const vec3 BG = vec3(0.055, 0.063, 0.075);
void main() {
vec3 col = vec3(0.110, 0.129, 0.153);
// A faint 1 m grid, anti-aliased with screen-space derivatives.
vec2 g = abs(fract(vWorld.xz) - 0.5) / fwidth(vWorld.xz);
float line = 1.0 - min(min(g.x, g.y), 1.0);
col = mix(col, vec3(0.17, 0.19, 0.22), line * 0.6);
float d = length(vWorld - uEye);
outColor = vec4(mix(col, BG, smoothstep(uFog.x, uFog.y, d)), 1.0);
}
"""
}
