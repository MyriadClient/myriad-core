#version 330
#extension GL_ARB_separate_shader_objects : require

// Dual-Kawase downsample.
uniform sampler2D InSampler;

layout(std140) uniform BlurInfo {
    vec2 HalfTexel;
    float Offset;
    // 1 for the rendered world, whose alpha means nothing here; 0 for a picture (the title screen's, the menus'), whose
    // see-through parts stay see-through, so what's drawn under a frosted window shows there.
    float Opaque;
};

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

void main() {
    vec2 o = HalfTexel * Offset;
    vec4 sum = texture(InSampler, texCoord) * 4.0;
    sum += texture(InSampler, texCoord - o);
    sum += texture(InSampler, texCoord + o);
    sum += texture(InSampler, texCoord + vec2(o.x, -o.y));
    sum += texture(InSampler, texCoord - vec2(o.x, -o.y));
    vec4 avg = sum / 8.0;
    fragColor = Opaque > 0.5 ? vec4(avg.rgb, 1.0) : avg;
}
