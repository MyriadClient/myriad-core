#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>

// Retained world meshes (WorldMesh): vertices are relative to the mesh's origin, and ModelOffset is that origin
// relative to the camera, so positions stay small and precise anywhere in the world.

layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;

layout(location = 0) out vec4 vertexColor;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position + ModelOffset, 1.0);

    vertexColor = Color;
}
