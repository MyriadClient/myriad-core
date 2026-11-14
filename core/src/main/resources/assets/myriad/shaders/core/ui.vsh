#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:globals.glsl>

// One quad of the batched UI renderer. Everything is in framebuffer pixels, y down.
layout(location = 0) in vec3 Position;
layout(location = 1) in vec2 Local;    // pixel position inside the shape's box (0..size)
layout(location = 2) in vec2 Size;     // shape box size in pixels
layout(location = 3) in vec4 Radii;    // corner radii: top-left, top-right, bottom-right, bottom-left
layout(location = 4) in vec4 Color1;
layout(location = 5) in vec4 Color2;
layout(location = 6) in vec4 Params;   // x = shape type, y = thickness/softness, z = gradient angle (radians), w = unused
layout(location = 7) in vec2 TexCoord;
layout(location = 8) in vec4 Clip;     // visible area: x0, y0, x1, y1

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
    gl_Position = vec4(Position.x / ScreenSize.x * 2.0 - 1.0, 1.0 - Position.y / ScreenSize.y * 2.0, 0.0, 1.0);
    vLocal = Local;
    vUv = TexCoord;
    vPixel = Position.xy;
    vScreenUv = vec2(Position.x / ScreenSize.x, 1.0 - Position.y / ScreenSize.y);
    vSize = Size;
    vRadii = Radii;
    vColor1 = Color1;
    vColor2 = Color2;
    vParams = Params;
    vClip = Clip;
}
