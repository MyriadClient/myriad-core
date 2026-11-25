// A background effect's frame (BackdropEffect): include this, then define vec4 effect(EffectInput e). Drawn with the UI's
// own quads (core/ui.vsh): the effect's colour goes over the background's own (Color1), inside the background's shape.

#include <minecraft:globals.glsl>

layout(location = 0) in vec2 vLocal;
layout(location = 1) in vec2 vUv;
layout(location = 2) in vec2 vPixel;
layout(location = 3) in vec2 vScreenUv;
layout(location = 4) flat in vec2 vSize;
layout(location = 5) flat in vec4 vRadii;
layout(location = 6) flat in vec4 vColor1;
layout(location = 7) flat in vec4 vColor2;
layout(location = 8) flat in vec4 vParams;
layout(location = 9) flat in vec4 vClip;

uniform sampler2D Sampler0; // the theme's colours (0-3) and the effect's options (4 on)
uniform sampler2D Sampler1;

layout(location = 0) out vec4 fragColor;

struct EffectInput {
    vec2 uv;          // 0 to 1 across the screen, from its top left
    vec2 pixel;       // the same in pixels
    vec2 resolution;  // the screen's size in pixels
    vec2 local;       // 0 to 1 across the background being drawn (a window, a tooltip, a toast), from its top left
    vec2 size;        // that background's size in pixels
    float time;       // seconds
    vec4 accent;
    vec4 secondary;
    vec4 text;
    vec4 background;
};

// Option i of the effect: a number 0 to 1 across its range in x, a switch as 1 or 0, a colour as RGBA, a choice as index / 255.
vec4 effectOption(int i) {
    return texelFetch(Sampler0, ivec2(4 + i, 0), 0);
}

float effectHash(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

float effectNoise(vec2 p) {
    vec2 i = floor(p), f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(effectHash(i), effectHash(i + vec2(1.0, 0.0)), f.x), mix(effectHash(i + vec2(0.0, 1.0)), effectHash(i + vec2(1.0, 1.0)), f.x), f.y);
}

float effectFbm(vec2 p) {
    float v = 0.0, a = 0.5;
    for (int i = 0; i < 5; i++) {
        v += a * effectNoise(p);
        p = p * 2.03 + vec2(17.1, 9.3);
        a *= 0.5;
    }
    return v;
}

float effectBayer2(vec2 a) {
    a = floor(a);
    return fract(dot(a, vec2(0.5, a.y * 0.75)));
}

float effectBayer4(vec2 a) {
    return effectBayer2(0.5 * a) * 0.25 + effectBayer2(a);
}

vec4 effect(EffectInput e);

float effectBox(vec2 p, vec2 b, vec4 r) {
    float rad = p.x < 0.0 ? (p.y < 0.0 ? r.x : r.w) : (p.y < 0.0 ? r.y : r.z);
    rad = min(rad, min(b.x, b.y));
    vec2 q = abs(p) - b + rad;
    return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - rad;
}

void main() {
    if (vPixel.x < vClip.x || vPixel.y < vClip.y || vPixel.x > vClip.z || vPixel.y > vClip.w) discard;
    vec2 hs = vSize * 0.5;
    float coverage = clamp(0.5 - effectBox(vLocal - hs, hs, vRadii), 0.0, 1.0);
    vec4 base = vColor1;
    vec4 fx = vec4(0.0);
    {
        EffectInput e;
        e.uv = vec2(vScreenUv.x, 1.0 - vScreenUv.y);
        e.resolution = ScreenSize;
        e.pixel = e.uv * ScreenSize;
        e.local = vLocal / max(vSize, vec2(1.0));
        e.size = vSize;
        e.time = vParams.z;
        e.accent = texelFetch(Sampler0, ivec2(0, 0), 0);
        e.secondary = texelFetch(Sampler0, ivec2(1, 0), 0);
        e.text = texelFetch(Sampler0, ivec2(2, 0), 0);
        e.background = texelFetch(Sampler0, ivec2(3, 0), 0);
        fx = clamp(effect(e), 0.0, 1.0);
    }
    float a = fx.a + base.a * (1.0 - fx.a);
    vec3 rgb = (fx.rgb * fx.a + base.rgb * base.a * (1.0 - fx.a)) / max(a, 0.0001);
    a *= vParams.y * coverage;
    if (a <= 0.0) discard;
    fragColor = vec4(rgb * a, a);
}
