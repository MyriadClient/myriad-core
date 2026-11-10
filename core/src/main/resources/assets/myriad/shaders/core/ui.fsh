#version 330

in vec2 vLocal;
in vec2 vUv;
in vec2 vPixel;
in vec2 vScreenUv;
flat in vec2 vSize;
flat in vec4 vRadii;
flat in vec4 vColor1;
flat in vec4 vColor2;
flat in vec4 vParams;
flat in vec4 vClip;

uniform sampler2D Sampler0; // glyph page or image
uniform sampler2D Sampler1; // the frame's blurred scene

out vec4 fragColor;

const int FILL = 0;
const int OUTLINE = 1;
const int SHADOW = 2;
const int GLYPH = 3;
const int IMAGE = 4;
const int BACKDROP = 5;

// Signed distance to a box with half-size b and per-corner radii r (tl, tr, br, bl); y grows downwards.
float sdRoundBox(vec2 p, vec2 b, vec4 r) {
    float rad = p.x < 0.0 ? (p.y < 0.0 ? r.x : r.w) : (p.y < 0.0 ? r.y : r.z);
    rad = min(rad, min(b.x, b.y));
    vec2 q = abs(p) - b + rad;
    return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - rad;
}

vec4 gradient() {
    if (vColor1 == vColor2) return vColor1;
    vec2 dir = vec2(cos(vParams.z), sin(vParams.z));
    // Project onto the direction, normalised so the gradient spans the box regardless of angle.
    vec2 uv = vLocal / max(vSize, vec2(1.0)) - 0.5;
    float extent = abs(dir.x) * 0.5 + abs(dir.y) * 0.5;
    float t = clamp(dot(uv, dir) / (2.0 * extent) + 0.5, 0.0, 1.0);
    return mix(vColor1, vColor2, t);
}

void main() {
    if (vPixel.x < vClip.x || vPixel.y < vClip.y || vPixel.x > vClip.z || vPixel.y > vClip.w) discard;
    int type = int(vParams.x + 0.5);
    vec2 halfSize = vSize * 0.5;
    vec2 p = vLocal - halfSize;
    vec4 color;
    float coverage;

    if (type == GLYPH) {
        coverage = texture(Sampler0, vUv).a;
        color = vColor1;
    } else if (type == IMAGE) {
        vec4 t = texture(Sampler0, vUv) * vColor1;
        float d = sdRoundBox(p, halfSize, vRadii);
        coverage = t.a * clamp(0.5 - d, 0.0, 1.0);
        color = vec4(t.rgb, 1.0);
    } else if (type == SHADOW) {
        float soft = max(vParams.y, 1.0);
        float d = sdRoundBox(p, halfSize - soft, vRadii);
        float s = 1.0 - smoothstep(-soft * 0.5, soft, d);
        coverage = s * s;
        color = vColor1;
    } else if (type == OUTLINE) {
        float d = sdRoundBox(p, halfSize, vRadii);
        float thickness = vParams.y;
        coverage = clamp(0.5 - d, 0.0, 1.0) - clamp(0.5 - (d + thickness), 0.0, 1.0);
        color = gradient();
    } else if (type == BACKDROP) {
        float d = sdRoundBox(p, halfSize, vRadii);
        coverage = clamp(0.5 - d, 0.0, 1.0);
        // The blur is premultiplied; where it's of a picture's see-through part, what's under the window shows.
        vec4 b = texture(Sampler1, vScreenUv);
        color = vec4(b.rgb / max(b.a, 0.0001), b.a * vColor1.a);
    } else {
        float d = sdRoundBox(p, halfSize, vRadii);
        coverage = clamp(0.5 - d, 0.0, 1.0);
        color = gradient();
    }

    float a = color.a * coverage;
    if (a <= 0.0) discard;
    // Premultiplied output.
    fragColor = vec4(color.rgb * a, a);
}
