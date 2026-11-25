#version 330
#extension GL_ARB_separate_shader_objects : require
#include <myriad:effect.glsl>

// Now and then a band of the screen jumps: blocks shifted sideways in the accent and secondary colours, with faint
// scanlines over everything.
vec4 effect(EffectInput e) {
    float intensity = effectOption(0).x;
    bool scanlines = effectOption(1).x > 0.5;
    float t = e.time;
    // A new glitch a few times a second; most moments none.
    float slot = floor(t * 6.0);
    float on = step(1.0 - intensity * 0.35, effectHash(vec2(slot, 1.3)));
    float band = floor(e.uv.y * 24.0 + effectHash(vec2(slot, 7.1)) * 24.0);
    float hit = on * step(0.7, effectHash(vec2(band, slot)));
    float shift = (effectHash(vec2(band, slot + 0.5)) - 0.5) * 0.08 * intensity;
    float block = step(0.5, effectHash(vec2(floor((e.uv.x + shift) * 18.0), band + slot)));
    vec3 color = mix(e.accent.rgb, e.secondary.rgb, block);
    float a = hit * (0.25 + 0.35 * block) * (0.4 + intensity);
    if (scanlines) a = max(a, (mod(floor(e.pixel.y), 3.0) < 1.0 ? 0.05 : 0.0) * (0.5 + intensity));
    return vec4(color, a);
}
