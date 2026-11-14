#version 330
#extension GL_ARB_separate_shader_objects : require

// The main highlight composite: everything but the insides of addons' own fills (see highlight_fill.glsl).
#include <myriad:highlight_fill.glsl>

vec4 customFill(HighlightFill f) {
    return vec4(0.0);
}
