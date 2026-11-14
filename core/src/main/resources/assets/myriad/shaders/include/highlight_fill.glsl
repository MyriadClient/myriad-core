// The highlight composite: outline, glow and fill from the spread pass. An addon's own fill (HighlightStyle.Fill.custom)
// is a fragment shader that imports this and defines customFill, which gets a pixel inside a highlight and returns its
// colour (alpha 0 leaves it clear):
//
//     #version 330
//     #include <myriad:highlight_fill.glsl>
//
//     vec4 customFill(HighlightFill f) {
//         float stripe = step(0.5, fract((f.local.x + f.local.y) / f.spacing));
//         return vec4(f.color.rgb, f.color.a * f.opacity * stripe);
//     }
//
// What a fill has to work with: the HighlightFill fields below, and these functions (since 1.5.0, distances in screen
// pixels, positions in view space as HighlightFill.position):
//
//     vec4  highlightOption(int i)                            the fill's own option i (HighlightStyle.Fill.options)
//     float highlightEdgeDistance(HighlightFill f, float reach)      to the silhouette's edge, up to reach
//     float highlightFaceEdgeDistance(HighlightFill f, float reach)  to the edge of the face f is on, up to reach
//     float highlightCavity(HighlightFill f, float reach)    how the surface bends, measured reach either way
//     bool  highlightSurface(ivec2 p, out vec3 position)      the surface at any pixel, false where there's none
//     vec4  highlightScene(ivec2 p)                           the screen at any pixel (with Fill.readsScene)
//     bool  highlightInView(ivec2 p)                          whether that's the highlighted thing (with readsScene)
//
// Only addons' fill passes read the surface, the options and the scene, so they cost nothing elsewhere; a helper costs
// only when it's called (the distances up to about 60 reads deep inside a shape, the others a few). Everything else in
// these files is the composite's own and may change.
//
// It runs once more per frame for its own highlights' insides (MYRIAD_FILL_ID is its number); the main pass (number 0)
// draws everything else.

#include <myriad:highlight.glsl>

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
    // Since Myriad 1.5.0. The shape's surface here, as drawn into the highlight's mask (the front of what's
    // highlighted, through walls or not):
    bool hasDepth;  // whether the three below are known (false before 1.5.0's core, or where nothing was drawn)
    float depth;    // distance from the camera, in blocks (a preview's camera is flat: compare its depths, don't measure)
    vec3 position;  // the point in view space: x right, y up, the camera looking down -z, in blocks
    vec3 normal;    // the way the surface faces, in view space; towards the camera, z > 0
    // The same point and facing in the highlighted thing's own space: blocks from the bottom middle of its box, turned
    // with it (a mob's body). A pattern laid out with these stays put on it as it and the camera move:
    vec3 objectPosition;
    vec3 objectNormal;
    vec3 objectView;     // from the camera to this point in that space, a unit vector: to look into the surface
    vec3 objectSize;     // the size of the thing's box, in blocks: objectPosition.y / objectSize.y is 0 at its feet, 1 at its top
    // How the surface bends here, a pixel either way: above 0 on a ridge (a cube's edge, seen from outside), below 0 in
    // a valley (where an arm meets the body), 0 on a flat face. Scaled so a right-angled edge is about 1; for wider
    // edges call highlightCavity.
    float cavity;
    // How many blocks one screen pixel spans on the surface here: for sizing lines and fading out detail too fine to
    // show. (fwidth isn't reliable in a fill: it runs inside a branch.)
    float pixelSize;
    // With HighlightStyle.Fill.readsScene: what's on screen here as drawn before the highlights, and whether that's the
    // highlighted thing itself (in view) rather than something in front of it. Otherwise 0 and false.
    vec4 scene;
    bool inView;
};

#if MYRIAD_FILL_ID != 0
// The fill's own options (HighlightStyle.Fill.options), in their order: a number in x, a switch as 1 or 0, a colour as
// RGBA from 0 to 1, a choice as its index. Since Myriad 1.5.0.
layout(std140) uniform FillOptions {
    vec4 myriadFillOptions[16];
};

vec4 highlightOption(int i) {
    return myriadFillOptions[clamp(i, 0, 15)];
}

// The highlights' mask depth: 0 where nothing was drawn. Read it with highlightSurface to compare neighbours.
uniform sampler2D MaskDepth;
// With HighlightStyle.Fill.readsScene: the screen under the fill as drawn before it, and its depth (in a preview, none).
uniform sampler2D SceneColor;
uniform sampler2D SceneDepth;

// What's on screen at pixel p as drawn before the highlights, for comparing neighbours (a fill that reads the scene).
vec4 highlightScene(ivec2 p) {
    ivec2 size = textureSize(SceneColor, 0);
    if (size.x <= 1) return vec4(0.0);
    return texelFetch(SceneColor, clamp(p, ivec2(0), size - 1), 0);
}

// Whether what's on screen at pixel p is the highlighted thing itself, not something in front of it.
bool highlightInView(ivec2 p) {
    ivec2 size = textureSize(SceneColor, 0), depthSize = textureSize(SceneDepth, 0), maskSize = textureSize(MaskDepth, 0);
    if (size.x <= 1) return false;
    if (depthSize.x <= 1 || maskSize.x <= 1) return true;
    // Depth is reversed (1 near): in view where the mask is as near as the scene, allowing for its precision.
    return texelFetch(MaskDepth, p, 0).r * 1.002 + 1e-6 >= texelFetch(SceneDepth, p, 0).r;
}

// The mask's surface at pixel p in view space, or false where nothing was drawn.
bool highlightSurface(ivec2 p, out vec3 position) {
    ivec2 size = textureSize(MaskDepth, 0);
    if (size.x <= 1 || !highlightHasDepth() || p.x < 0 || p.y < 0 || p.x >= size.x || p.y >= size.y) return false;
    float d = texelFetch(MaskDepth, p, 0).r;
    if (d <= 0.0) return false;
    position = highlightViewPosition(vec2(p) + 0.5, d, vec2(size));
    return true;
}

// Internal: highlight id's own space from view space (w 1 for a point, 0 for a direction).
vec3 highlightToObject(int id, vec3 v, float w) {
    vec4 stands = highlightData(id, 5);
    vec3 r = highlightToWorld(v, w) - stands.xyz * w;
    float c = cos(stands.w), s = sin(stands.w);
    return vec3(r.x * c + r.z * s, r.y, r.z * c - r.x * s);
}

// Internal: fills in f's depth fields from the mask around pixel p: the normal from the neighbours on the nearer side each way, so
// it doesn't bend round the shape's edges.
void highlightDepth(inout HighlightFill f, ivec2 p, int id) {
    f.hasDepth = false;
    f.depth = 0.0;
    f.position = vec3(0.0);
    f.normal = vec3(0.0, 0.0, 1.0);
    f.objectPosition = vec3(0.0);
    f.objectNormal = vec3(0.0, 1.0, 0.0);
    f.objectView = vec3(0.0, 0.0, -1.0);
    f.objectSize = highlightData(id, 6).xyz;
    f.cavity = 0.0;
    f.pixelSize = 0.0;
    f.scene = highlightScene(p);
    f.inView = highlightInView(p);
    vec3 c;
    if (!highlightSurface(p, c)) return;
    f.hasDepth = true;
    f.position = c;
    f.depth = length(c);
    f.objectPosition = highlightToObject(id, c, 1.0);
    f.objectSize = highlightData(id, 6).xyz;
    f.objectView = normalize(highlightToObject(id, normalize(c), 0.0));
    vec3 l, r, d, u;
    bool hl = highlightSurface(p - ivec2(1, 0), l), hr = highlightSurface(p + ivec2(1, 0), r);
    bool hd = highlightSurface(p - ivec2(0, 1), d), hu = highlightSurface(p + ivec2(0, 1), u);
    vec3 dx = hr && (!hl || abs(r.z - c.z) < abs(c.z - l.z)) ? r - c : hl ? c - l : vec3(1.0, 0.0, 0.0);
    vec3 dy = hu && (!hd || abs(u.z - c.z) < abs(c.z - d.z)) ? u - c : hd ? c - d : vec3(0.0, 1.0, 0.0);
    f.pixelSize = 0.5 * (length(dx) + length(dy));
    vec3 n = cross(dx, dy);
    if (dot(n, n) < 1e-12) return;
    n = normalize(n);
    f.normal = n.z < 0.0 ? -n : n;
    f.objectNormal = normalize(highlightToObject(id, f.normal, 0.0));
    // From the same five points: how far the middle stands out of the line through its neighbours, along the normal.
    if (hl && hr) f.cavity -= dot(l + r - 2.0 * c, f.normal) / max(length(r - l), 1e-4);
    if (hd && hu) f.cavity -= dot(d + u - 2.0 * c, f.normal) / max(length(u - d), 1e-4);
}

// Internal: the cavity measured reach pixels either way at pixel p (highlightCavity).
float highlightCavityAt(ivec2 p, int reach) {
    vec3 c, l, r, d, u;
    if (!highlightSurface(p, c)) return 0.0;
    bool hl = highlightSurface(p - ivec2(reach, 0), l), hr = highlightSurface(p + ivec2(reach, 0), r);
    bool hd = highlightSurface(p - ivec2(0, reach), d), hu = highlightSurface(p + ivec2(0, reach), u);
    if (!(hl && hr) && !(hd && hu)) return 0.0;
    vec3 n = cross(hl && hr ? r - l : vec3(1.0, 0.0, 0.0), hd && hu ? u - d : vec3(0.0, 1.0, 0.0));
    if (dot(n, n) < 1e-12) return 0.0;
    n = normalize(n);
    if (n.z < 0.0) n = -n;
    float bend = 0.0;
    if (hl && hr) bend -= dot(l + r - 2.0 * c, n) / max(length(r - l), 1e-4);
    if (hd && hu) bend -= dot(d + u - 2.0 * c, n) / max(length(u - d), 1e-4);
    return bend;
}

// The cavity (see HighlightFill.cavity) measured reach pixels either way at f's pixel: wider ridges and valleys, as
// Blender's cavity's ridge and valley widths. Five reads of the mask's depth each call.
float highlightCavity(HighlightFill f, float reach) {
    return highlightCavityAt(ivec2(f.pixel), max(1, int(reach + 0.5)));
}
#endif

vec4 customFill(HighlightFill f);

// Second half of the distance search, then the highlight itself. The nearest candidate over this column's row results
// gives the exact distance (searched outwards, stopping once rows further away can't be closer). Outside every
// silhouette: the nearest highlight's outline and glow. Inside one: its fill, under the outline of a later highlight of
// another group that touches it.
uniform sampler2D SpreadSampler;
uniform sampler2D GlowSampler;

#if MYRIAD_FILL_ID != 0
// Internal: whether pixel p is inside a silhouette.
bool highlightInside(ivec2 p) {
    // Off the screen counts as inside, so an edge isn't found along the screen's own.
    ivec2 size = textureSize(SpreadSampler, 0);
    if (p.x < 0 || p.y < 0 || p.x >= size.x || p.y >= size.y) return true;
    return unpack16(texelFetch(SpreadSampler, p, 0).r) % 256 == 0;
}

// Pixels from f's pixel to the nearest edge of the silhouette it's inside, up to reach: an edge mask for fills that
// gather at the edges or fade towards the middle. Eight directions feel outwards in growing steps, then close in on the
// edge they find; they turn a little from pixel to pixel, which grains the result rather than banding it. Up to about
// 60 reads where it's deep inside, a few near the edge.
float highlightEdgeDistance(HighlightFill f, float reach) {
    vec2 pixel = f.pixel;
    const float STEPS[5] = float[5](0.06, 0.16, 0.32, 0.6, 1.0);
    uint h = uint(pixel.x) * 0x9e3779b1u ^ uint(pixel.y) * 0x85ebca77u;
    h = (h ^ (h >> 15)) * 0x2c1b3c6du;
    float turn = float((h ^ (h >> 12)) & 0xffffu) / 65535.0 * 0.785;
    float best = reach;
    for (int i = 0; i < 8; i++) {
        float a = turn + float(i) * 0.785;
        vec2 dir = vec2(cos(a), sin(a));
        float from = 0.0;
        for (int s = 0; s < 5; s++) {
            float at = STEPS[s] * reach;
            if (at >= best) break;
            if (!highlightInside(ivec2(pixel + dir * at))) {
                float to = at;
                for (int b = 0; b < 3; b++) {
                    float mid = (from + to) * 0.5;
                    if (highlightInside(ivec2(pixel + dir * mid))) from = mid;
                    else to = mid;
                }
                best = min(best, to);
                break;
            }
            from = at;
        }
    }
    return best;
}

// Internal: whether the mask's surface at pixel q lies on the face through c facing n (all in view space): within a little of its
// plane, allowing for the depth's precision further off.
bool highlightOnFace(ivec2 q, vec3 c, vec3 n) {
    vec3 p;
    if (!highlightSurface(q, p)) return false;
    vec3 d = p - c;
    return abs(dot(d, n)) <= 0.004 + 0.03 * length(d);
}

// Pixels from this one to the nearest edge of the face it's on, up to reach: a crease, a step to another part, or the
// silhouette, whichever is nearest. For a gradient towards each face's middle (frost creeping in from every edge), as
// highlightEdgeDistance is for the whole silhouette. The same search, testing whether each point is still on this
// face's plane; without depth it's highlightEdgeDistance. Up to about 60 reads deep inside a face.
float highlightFaceEdgeDistance(HighlightFill f, float reach) {
    if (!f.hasDepth) return highlightEdgeDistance(f, reach);
    const float STEPS[5] = float[5](0.06, 0.16, 0.32, 0.6, 1.0);
    uint h = uint(f.pixel.x) * 0x9e3779b1u ^ uint(f.pixel.y) * 0x85ebca77u;
    h = (h ^ (h >> 15)) * 0x2c1b3c6du;
    float turn = float((h ^ (h >> 12)) & 0xffffu) / 65535.0 * 0.785;
    float best = reach;
    for (int i = 0; i < 8; i++) {
        float a = turn + float(i) * 0.785;
        vec2 dir = vec2(cos(a), sin(a));
        float from = 0.0;
        for (int s = 0; s < 5; s++) {
            float at = STEPS[s] * reach;
            if (at >= best) break;
            if (!highlightOnFace(ivec2(f.pixel + dir * at), f.position, f.normal)) {
                float to = at;
                for (int b = 0; b < 3; b++) {
                    float mid = (from + to) * 0.5;
                    if (highlightOnFace(ivec2(f.pixel + dir * mid), f.position, f.normal)) from = mid;
                    else to = mid;
                }
                best = min(best, to);
                break;
            }
            from = at;
        }
    }
    return best;
}
#endif

layout(location = 0) out vec4 fragColor;

const int FILL_NONE = 0;
const int FILL_DOTS = 2;
const int FILL_CUSTOM = 16;

// The highlight's colour at this height: top colour to bottom colour across its gradient rectangle.
vec4 colorAt(int id) {
    vec4 rect = highlightData(id, 2);
    float t = rect.w > rect.y ? clamp((rect.w - gl_FragCoord.y) / (rect.w - rect.y), 0.0, 1.0) : 0.0;
    return mix(highlightData(id, 0), highlightData(id, 1), t);
}

// Outline coverage of highlight id at squared distance d2 from its nearest pixel (the glow beyond it has its own field).
float edgeAlpha(int id, float d2) {
    vec4 shape = highlightData(id, 3);
    // From this pixel's centre to the silhouette's edge, half a pixel short of the nearest mask pixel's centre.
    float edge = sqrt(d2) - 0.5;
    return clamp(shape.x + 0.5 - edge, 0.0, 1.0);
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
#if MYRIAD_FILL_ID != 0
            highlightDepth(f, p, id);
#else
            f.hasDepth = false;
            f.depth = 0.0;
            f.position = vec3(0.0);
            f.normal = vec3(0.0, 0.0, 1.0);
            f.objectPosition = vec3(0.0);
            f.objectNormal = vec3(0.0, 1.0, 0.0);
            f.objectView = vec3(0.0, 0.0, -1.0);
            f.objectSize = vec3(0.0);
            f.cavity = 0.0;
            f.pixelSize = 0.0;
            f.scene = vec4(0.0);
            f.inView = false;
#endif
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
            lineAlpha = edgeAlpha(other, best) * lineColor.a;
        }
        float alpha = lineAlpha + fillAlpha * (1.0 - lineAlpha);
        if (alpha <= 0.0) discard;
        vec3 rgb = (lineColor.rgb * lineAlpha + fillColor.rgb * fillAlpha * (1.0 - lineAlpha)) / alpha;
        fragColor = vec4(rgb, alpha);
        return;
    }

    // Outlines and glow outside silhouettes belong to the main pass.
    if (MYRIAD_FILL_ID != 0) discard;
    float best = 1e9;
    int nearest = -1;
    if (hereNear % 256 != NONE) {
        float dx = float(hereNear % 256);
        best = dx * dx;
        nearest = unpack16(here.g);
    }
    // The outline's reach only: the glow comes from its distance field below.
    int radius = highlightBoundaryRadius();
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
        alpha = edgeAlpha(nearest, best) * color.a;
    }
    int glowId;
    float glowDist;
    if (highlightGlows() && glowAt(p, glowId, glowDist)) {
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
