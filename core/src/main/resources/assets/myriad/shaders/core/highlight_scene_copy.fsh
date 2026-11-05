#version 330

// What's under a highlight, copied for an addon's fill that reads the scene (HighlightStyle.Fill.readsScene).
#moj_import <myriad:highlight.glsl>

uniform sampler2D InSampler;

out vec4 fragColor;

void main() {
    fragColor = texelFetch(InSampler, ivec2(gl_FragCoord.xy), 0);
    // Keeps the highlight data the pass is given in use.
    if (highlightGuiScale() < 0.0) fragColor = vec4(0.0);
}
