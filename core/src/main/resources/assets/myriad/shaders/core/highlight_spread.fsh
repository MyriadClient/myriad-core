#version 330

#moj_import <myriad:highlight.glsl>

// First half of the distance search, searched outwards along the row so it stops at the first hit:
//  - the nearest mask pixel within the outline's reach;
//  - for a mask pixel, the nearest pixel of another group within the outline width (where highlights that look
//    different meet).
uniform sampler2D InSampler;

out vec4 fragColor;

void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    vec4 here = texelFetch(InSampler, p, 0);
    bool inside = here.a > 0.0;
    int group = inside ? highlightGroup(here.rgb) : 0;
    int nearD = NONE, nearId = 0, nearG = 0;
    int otherD = NONE, otherId = 0, otherG = 0;
    if (inside) {
        nearD = 0;
        nearId = highlightId(here.rgb);
        nearG = group;
    }
    // Outside, only the outline's reach: the glow beyond it comes from its own half-size distance field.
    int reach = inside || !highlightLegacy() ? highlightBoundaryRadius() : highlightRadius();
    int width = textureSize(InSampler, 0).x;
    for (int d = 1; d <= reach; d++) {
        if (inside ? otherD != NONE : nearD != NONE) break;
        for (int s = -1; s <= 1; s += 2) {
            int x = p.x + s * d;
            if (x < 0 || x >= width) continue;
            vec4 c = texelFetch(InSampler, ivec2(x, p.y), 0);
            if (c.a == 0.0) continue;
            int g = highlightGroup(c.rgb);
            if (nearD == NONE) {
                nearD = d;
                nearId = highlightId(c.rgb);
                nearG = g;
            }
            if (inside && otherD == NONE && g != group) {
                otherD = d;
                otherId = highlightId(c.rgb);
                otherG = g;
            }
        }
    }
    fragColor = vec4(pack16(nearD + 256 * nearG), pack16(nearId), pack16(otherD + 256 * otherG), pack16(otherId));
}
