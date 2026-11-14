#version 330
#extension GL_ARB_separate_shader_objects : require

#include <myriad:highlight.glsl>

// Drops the pixels of depth-tested highlights that the world hides. Depth is reversed (1 is near), and a silhouette
// drawn over its own entity has the same depth as it, so only a clear difference counts as hidden.
uniform sampler2D Mask;
uniform sampler2D MaskDepth;
uniform sampler2D SceneDepth;

layout(location = 0) out vec4 fragColor;

void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    vec4 mask = texelFetch(Mask, p, 0);
    if (mask.a == 0.0) {
        fragColor = vec4(0.0);
        return;
    }
    if ((int(highlightData(highlightId(mask.rgb), 4).w + 0.5) & 1) != 0) {
        float depth = texelFetch(MaskDepth, p, 0).r;
        if (depth * 1.002 + 1e-6 < texelFetch(SceneDepth, p, 0).r) {
            fragColor = vec4(0.0);
            return;
        }
    }
    fragColor = vec4(mask.rgb, 1.0);
}
