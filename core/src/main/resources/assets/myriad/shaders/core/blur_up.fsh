#version 330

// Dual-Kawase upsample.
uniform sampler2D InSampler;

layout(std140) uniform BlurInfo {
    vec2 HalfTexel;
    float Offset;
    float Opaque;
};

in vec2 texCoord;
out vec4 fragColor;

void main() {
    vec2 o = HalfTexel * Offset;
    vec4 sum = texture(InSampler, texCoord + vec2(-o.x * 2.0, 0.0));
    sum += texture(InSampler, texCoord + vec2(-o.x, o.y)) * 2.0;
    sum += texture(InSampler, texCoord + vec2(0.0, o.y * 2.0));
    sum += texture(InSampler, texCoord + vec2(o.x, o.y)) * 2.0;
    sum += texture(InSampler, texCoord + vec2(o.x * 2.0, 0.0));
    sum += texture(InSampler, texCoord + vec2(o.x, -o.y)) * 2.0;
    sum += texture(InSampler, texCoord + vec2(0.0, -o.y * 2.0));
    sum += texture(InSampler, texCoord + vec2(-o.x, -o.y)) * 2.0;
    // Alpha as it came down (the world's made opaque on the first pass down).
    fragColor = sum / 12.0;
}
