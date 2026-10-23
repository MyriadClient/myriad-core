#version 330

#moj_import <myriad:highlight.glsl>

// The glow's distance search along each row of its field, outwards so it stops at the first hit: (distance in field
// texels, or NONE; the id there). A field texel is a block of the full-size silhouettes, scale pixels square, read two
// by two pixels at a time: one filtered read between four pixels is above zero if any of them holds a silhouette
// (InSampler is sampled linearly); only a hit reads them one by one, for the id.
uniform sampler2D InSampler;

out vec4 fragColor;

// Whether the block of field texel q holds a silhouette pixel.
bool blockSet(ivec2 q, int scale, vec2 size) {
    for (int y = 0; y < scale; y += 2) {
        for (int x = 0; x < scale; x += 2) {
            // Between the four pixels from (q * scale + (x, y)).
            if (texture(InSampler, (vec2(q * scale + ivec2(x, y)) + 1.0) / size).a > 0.0) return true;
        }
    }
    return false;
}

// The id of a silhouette pixel in the block of field texel q, or -1 if none.
int blockId(ivec2 q, int scale, ivec2 size) {
    for (int y = 0; y < scale; y++) {
        for (int x = 0; x < scale; x++) {
            vec4 c = texelFetch(InSampler, min(q * scale + ivec2(x, y), size - 1), 0);
            if (c.a > 0.0) return highlightId(c.rgb);
        }
    }
    return -1;
}

void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    ivec2 size = textureSize(InSampler, 0);
    int scale = highlightGlowScale();
    int width = (size.x + scale - 1) / scale;
    int reach = highlightRadius() / scale + 1;
    for (int d = 0; d <= reach; d++) {
        for (int s = -1; s <= 1; s += 2) {
            int x = p.x + s * d;
            if (x < 0 || x >= width) continue;
            if (blockSet(ivec2(x, p.y), scale, vec2(size))) {
                int id = blockId(ivec2(x, p.y), scale, size);
                if (id >= 0) {
                    fragColor = vec4(pack16(d), pack16(id), 0.0, 0.0);
                    return;
                }
            }
            if (d == 0) break;
        }
    }
    fragColor = vec4(pack16(NONE), 0.0, 0.0, 0.0);
}
