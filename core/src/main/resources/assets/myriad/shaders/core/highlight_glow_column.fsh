#version 330

#moj_import <myriad:highlight.glsl>

// The glow's distance field: the nearest of the row results over this column (searched outwards, stopping once rows
// further away can't be closer), as (distance in framebuffer pixels * GLOW_SCALE, id).
uniform sampler2D InSampler;

out vec4 fragColor;

void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    int scale = highlightGlowScale();
    int height = textureSize(InSampler, 0).y;
    int reach = highlightRadius() / scale + 1;
    float best = 1e9;
    int id = -1;
    vec4 here = texelFetch(InSampler, p, 0);
    int hereD = unpack16(here.r);
    if (hereD != NONE) {
        best = float(hereD * hereD);
        id = unpack16(here.g);
    }
    for (int d = 1; d <= reach; d++) {
        float dy2 = float(d * d);
        if (dy2 >= best) break;
        for (int s = -1; s <= 1; s += 2) {
            int y = p.y + s * d;
            if (y < 0 || y >= height) continue;
            vec4 row = texelFetch(InSampler, ivec2(p.x, y), 0);
            int dx = unpack16(row.r);
            if (dx == NONE) continue;
            float d2 = float(dx * dx) + dy2;
            if (d2 < best) {
                best = d2;
                id = unpack16(row.g);
            }
        }
    }
    if (id < 0) {
        fragColor = vec4(pack16(GLOW_NONE), 0.0, 0.0, 0.0);
        return;
    }
    // Field texels to framebuffer pixels.
    fragColor = vec4(pack16(min(GLOW_NONE - 1, int(sqrt(best) * float(scale) * GLOW_SCALE + 0.5))), pack16(id), 0.0, 0.0);
}
