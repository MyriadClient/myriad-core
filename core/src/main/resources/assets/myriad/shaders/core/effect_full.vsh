#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:globals.glsl>

// A background effect over the whole screen, as one background (no vertex buffer: a full-screen triangle): drawn once a
// frame and blurred, for what's frosted over an effect background to show it softened (UiRenderer's frost).
layout(std140) uniform FxInfo {
    vec4 FxParams; // x: the effect's time
};

layout(location = 0) out vec2 vLocal;
layout(location = 1) out vec2 vUv;
layout(location = 2) out vec2 vPixel;
layout(location = 3) out vec2 vScreenUv;
layout(location = 4) flat out vec2 vSize;
layout(location = 5) flat out vec4 vRadii;
layout(location = 6) flat out vec4 vColor1;
layout(location = 7) flat out vec4 vColor2;
layout(location = 8) flat out vec4 vParams;
layout(location = 9) flat out vec4 vClip;

void main() {
    vec2 uv = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
    gl_Position = vec4(uv * 2.0 - 1.0, 0.0, 1.0);
    vScreenUv = uv;
    vPixel = vec2(uv.x, 1.0 - uv.y) * ScreenSize;
    vLocal = vPixel;
    vUv = vec2(0.0);
    vSize = ScreenSize;
    vRadii = vec4(0.0);
    // No background colour: only the effect's own, premultiplied.
    vColor1 = vec4(0.0);
    vColor2 = vec4(0.0);
    vParams = vec4(0.0, 1.0, FxParams.x, 0.0);
    vClip = vec4(-1.0e9, -1.0e9, 1.0e9, 1.0e9);
}
