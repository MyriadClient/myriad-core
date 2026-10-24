// The highlight composite: outline, glow and fill from the spread pass. A fill shader (an addon's own fill, see
// HighlightStyle.Fill.custom) is a fragment shader that imports this and defines customFill:
//
//     #version 330
//     #moj_import <myriad:highlight_fill.glsl>
//
//     vec4 customFill(HighlightFill f) {
//         return vec4(f.color.rgb, f.color.a * f.opacity * pattern(f.local));
//     }
//
// It runs once more per frame for its own highlights' insides (MYRIAD_FILL_ID is its number); the main pass (number 0)
// draws everything else.

#moj_import <myriad:highlight.glsl>

#ifndef MYRIAD_FILL_ID
#define MYRIAD_FILL_ID 0
#endif

// What a fill shader gets for one pixel inside a highlight.
struct HighlightFill {
    vec2 pixel;    // framebuffer pixel (gl_FragCoord.xy)
    vec2 local;    // pixel relative to the highlight's screen rectangle, from its bottom-left corner
    vec2 size;     // that rectangle's size in pixels
    vec4 color;    // the highlight's colour here (gradient applied; alpha includes any fade)
    float opacity; // the style's fill opacity
    float spacing; // the look's Pattern Spacing (the style's dot spacing), in pixels: a pattern's scale
    float dotSize; // the look's Pattern Size (the style's dot size), in pixels
    float scale;   // pixels per GUI pixel
    float time;    // seconds, for animation (wraps every hour)
};

vec4 customFill(HighlightFill f);

// Second half of the distance search, then the highlight itself. The nearest candidate over this column's row results
// gives the exact distance (searched outwards, stopping once rows further away can't be closer). Outside every
// silhouette: the nearest highlight's outline and glow. Inside one: its fill, under the outline of a later highlight of
// another group that touches it.
uniform sampler2D SpreadSampler;
uniform sampler2D GlowSampler;

out vec4 fragColor;

const int FILL_NONE = 0;
const int FILL_DOTS = 2;
const int FILL_CUSTOM = 16;

// The highlight's colour at this height: top colour to bottom colour across its gradient rectangle.
vec4 colorAt(int id) {
    vec4 rect = highlightData(id, 2);
    float t = rect.w > rect.y ? clamp((rect.w - gl_FragCoord.y) / (rect.w - rect.y), 0.0, 1.0) : 0.0;
    return mix(highlightData(id, 0), highlightData(id, 1), t);
}

// Outline (and, outside silhouettes, glow) coverage of highlight id at squared distance d2 from its nearest pixel.
float edgeAlpha(int id, float d2, bool glow) {
    vec4 shape = highlightData(id, 3);
    // From this pixel's centre to the silhouette's edge, half a pixel short of the nearest mask pixel's centre.
    float edge = sqrt(d2) - 0.5;
    float alpha = clamp(shape.x + 0.5 - edge, 0.0, 1.0);
    if (glow && shape.y > 0.0) {
        float g = clamp((edge - shape.x) / shape.y, 0.0, 1.0);
        alpha = max(alpha, shape.z * (1.0 - g) * (1.0 - g));
    }
    return alpha;
}

// The glow field at this pixel: its distance blended between the four nearest field texels that have one, and the id
// of the nearest of them. False if none of them is near a silhouette.
bool glowAt(ivec2 p, out int id, out float dist) {
    int scale = highlightGlowScale();
    vec2 pos = (vec2(p) + 0.5) / float(scale) - 0.5;
    ivec2 base = ivec2(floor(pos));
    vec2 f = pos - vec2(base);
    // The part of the field in use: it's sized for the finest scale.
    ivec2 size = (textureSize(SpreadSampler, 0) + scale - 1) / scale;
    float sum = 0.0, weight = 0.0, nearest = 1e9;
    id = -1;
    for (int j = 0; j < 2; j++) {
        for (int i = 0; i < 2; i++) {
            ivec2 q = clamp(base + ivec2(i, j), ivec2(0), size - 1);
            vec4 t = texelFetch(GlowSampler, q, 0);
            int d = unpack16(t.r);
            if (d == GLOW_NONE) continue;
            float w = (i == 0 ? 1.0 - f.x : f.x) * (j == 0 ? 1.0 - f.y : f.y);
            float dd = float(d) / GLOW_SCALE;
            sum += dd * w;
            weight += w;
            if (dd < nearest) {
                nearest = dd;
                id = unpack16(t.g);
            }
        }
    }
    if (id < 0 || weight <= 0.0) return false;
    // Measured between texel middles, the field reads about half a texel long next to a silhouette (whose nearest
    // pixel can be anywhere in its texel's block): taken off, so the glow starts as strong as an exact one.
    dist = max(0.0, sum / weight - float(scale - 1) * 0.5);
    return true;
}

// Glow coverage of highlight id at dist framebuffer pixels from its silhouette (0 past its reach, or if it has none).
float glowAlpha(int id, float dist) {
    vec4 shape = highlightData(id, 3);
    if (shape.y <= 0.0) return 0.0;
    float g = clamp((dist - 0.5 - shape.x) / shape.y, 0.0, 1.0);
    return shape.z * (1.0 - g) * (1.0 - g);
}

void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    int height = textureSize(SpreadSampler, 0).y;
    vec4 here = texelFetch(SpreadSampler, p, 0);
    int hereNear = unpack16(here.r);

    if (hereNear % 256 == 0) {
        // Inside a silhouette.
        int id = unpack16(here.g);
        int group = hereNear / 256;
        vec4 fill = highlightData(id, 4);
        int mode = int(fill.x + 0.5);
        // An addon's fill is drawn by its own pass; each pass draws only its own fill's insides.
        if ((mode >= FILL_CUSTOM) != (MYRIAD_FILL_ID != 0) || (MYRIAD_FILL_ID != 0 && mode != MYRIAD_FILL_ID)) discard;
        float fillAlpha = 0.0;
        vec4 fillColor = colorAt(id);
        if (mode >= FILL_CUSTOM) {
            vec4 rect = highlightData(id, 2);
            HighlightFill f;
            f.pixel = gl_FragCoord.xy;
            f.local = gl_FragCoord.xy - rect.xy;
            f.size = rect.zw - rect.xy;
            f.color = fillColor;
            f.opacity = highlightData(id, 3).w;
            f.spacing = fill.y;
            f.dotSize = fill.z * 2.0;
            f.scale = highlightGuiScale();
            f.time = highlightTime();
            vec4 c = customFill(f);
            fillColor = vec4(c.rgb, 1.0);
            fillAlpha = clamp(c.a, 0.0, 1.0);
        } else if (mode != FILL_NONE) {
            fillAlpha = highlightData(id, 3).w;
            if (mode == FILL_DOTS) {
                // Anchored to the highlight's rectangle, so the grid moves with it rather than sliding across it.
                vec2 cell = mod(gl_FragCoord.xy - highlightData(id, 2).xy, fill.y) - fill.y * 0.5;
                fillAlpha *= clamp(fill.z + 0.5 - length(cell), 0.0, 1.0);
            }
            fillAlpha *= fillColor.a;
        }

        // The nearest pixel of another group: in rows of this group, its own search found it; a row whose pixel here
        // belongs to another group is a candidate itself. (Rows outside every silhouette are skipped: there the plain
        // outline already shows the edge.)
        float best = 1e9;
        int other = -1;
        int hereOther = unpack16(here.b);
        if (hereOther % 256 != NONE) {
            float dx = float(hereOther % 256);
            best = dx * dx;
            other = unpack16(here.a);
        }
        int reach = highlightBoundaryRadius();
        for (int d = 1; d <= reach; d++) {
            float dy2 = float(d * d);
            if (dy2 >= best) break;
            for (int s = -1; s <= 1; s += 2) {
                int y = p.y + s * d;
                if (y < 0 || y >= height) continue;
                vec4 row = texelFetch(SpreadSampler, ivec2(p.x, y), 0);
                int near = unpack16(row.r);
                if (near % 256 != 0) continue;
                if (near / 256 == group) {
                    int o = unpack16(row.b);
                    if (o % 256 == NONE) continue;
                    float dx = float(o % 256);
                    if (dx * dx + dy2 < best) {
                        best = dx * dx + dy2;
                        other = unpack16(row.a);
                    }
                } else if (dy2 < best) {
                    best = dy2;
                    other = unpack16(row.g);
                }
            }
        }

        float lineAlpha = 0.0;
        vec4 lineColor = vec4(0.0);
        // The later highlight draws the shared edge, over the earlier one.
        if (other > id) {
            lineColor = colorAt(other);
            lineAlpha = edgeAlpha(other, best, false) * lineColor.a;
        }
        float alpha = lineAlpha + fillAlpha * (1.0 - lineAlpha);
        if (alpha <= 0.0) discard;
        vec3 rgb = (lineColor.rgb * lineAlpha + fillColor.rgb * fillAlpha * (1.0 - lineAlpha)) / alpha;
        fragColor = vec4(rgb, alpha);
        return;
    }

    // Outlines and glow outside silhouettes belong to the main pass.
    if (MYRIAD_FILL_ID != 0) discard;
    bool legacy = highlightLegacy();
    float best = 1e9;
    int nearest = -1;
    if (hereNear % 256 != NONE) {
        float dx = float(hereNear % 256);
        best = dx * dx;
        nearest = unpack16(here.g);
    }
    // The outline's reach only: the glow comes from its distance field below.
    int radius = legacy ? highlightRadius() : highlightBoundaryRadius();
    for (int d = 1; d <= radius; d++) {
        float dy2 = float(d * d);
        if (dy2 >= best) break;
        for (int s = -1; s <= 1; s += 2) {
            int y = p.y + s * d;
            if (y < 0 || y >= height) continue;
            vec4 row = texelFetch(SpreadSampler, ivec2(p.x, y), 0);
            int near = unpack16(row.r);
            if (near % 256 == NONE) continue;
            float dx = float(near % 256);
            float d2 = dx * dx + dy2;
            if (d2 < best) {
                best = d2;
                nearest = unpack16(row.g);
            }
        }
    }
    vec4 color = vec4(0.0);
    float alpha = 0.0;
    if (nearest >= 0) {
        color = colorAt(nearest);
        alpha = edgeAlpha(nearest, best, legacy) * color.a;
    }
    int glowId;
    float glowDist;
    if (!legacy && highlightGlows() && glowAt(p, glowId, glowDist)) {
        vec4 glowColor = colorAt(glowId);
        float glow = glowAlpha(glowId, glowDist) * glowColor.a;
        if (glowId == nearest || alpha <= 0.0) {
            // The same highlight's outline and glow: the stronger of the two, as one edge.
            if (glow > alpha) {
                color = glowColor;
                alpha = glow;
            }
        } else if (glow > 0.0) {
            // Another highlight's glow: under this outline.
            float a = alpha + glow * (1.0 - alpha);
            color.rgb = (color.rgb * alpha + glowColor.rgb * glow * (1.0 - alpha)) / a;
            alpha = a;
        }
    }
    if (alpha <= 0.0) discard;
    fragColor = vec4(color.rgb, alpha);
}
