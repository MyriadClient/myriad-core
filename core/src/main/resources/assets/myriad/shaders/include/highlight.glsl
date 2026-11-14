// Shared by the highlight passes (see HighlightRenderer). Highlights is a buffer of vec4s: texel 0 is
// (radius, GUI scale, count, boundary radius), texel 1 (seconds, unused, glows, glow scale), texel 2 (1 if the masks'
// depth can be read, 1 if clip space depth runs 0..1 rather than -1..1, 0, 0), texels 3 to 6 the columns of the matrix
// from the masks' clip space to view space, texels 7 to 10 those of the one from view space to the world's axes (from
// the camera; a preview's subject's own space), then HIGHLIGHT_TEXELS per highlight:
//   0 top colour, 1 bottom colour (RGBA, alpha already faded)
//   2 gradient rectangle in framebuffer pixels (x0, y0, x1, y1; y1 is the top)
//   3 (outline width, glow reach, glow strength, fill opacity), lengths in framebuffer pixels
//   4 (fill: 0 none, 1 solid, 2 dots, 16+ an addon's; dot spacing; dot radius; flags: bit 0 drops hidden parts, the
//     rest the group)
//   5 (where it stands: the bottom middle of its box on the world's axes from the camera, then how it's turned about y
//     in radians, a mob's body)
//   6 (its box's size in blocks, 0)
uniform samplerBuffer Highlights;

const int HIGHLIGHT_TEXELS = 7;

// Texel 1's other fields: (y) unused, (z) 1 when some highlight this frame glows past its outline, so the glow passes
// ran, (w) the glow field's scale: framebuffer pixels per texel (2, or 4 for a wide glow, whose falloff hides a coarser
// field).
bool highlightGlows() {
    return texelFetch(Highlights, 1).z > 0.5;
}

int highlightGlowScale() {
    return int(texelFetch(Highlights, 1).w + 0.5);
}

// The glow's distance field is the framebuffer's size over highlightGlowScale(); its texels hold (distance to the
// nearest silhouette pixel in framebuffer pixels * GLOW_SCALE, that silhouette's highlight id). GLOW_NONE: nothing within
// the radius.
const float GLOW_SCALE = 64.0;
const int GLOW_NONE = 65535;

// A mask texel's RGB: highlight id (low byte in red, high byte in green) and merge group (blue). Highlights in the
// same group connect; where groups meet, the later highlight (higher id) outlines itself across the boundary.
int highlightId(vec3 rgb) {
    ivec2 b = ivec2(rgb.rg * 255.0 + 0.5);
    return b.x | (b.y << 8);
}

int highlightGroup(vec3 rgb) {
    return int(rgb.b * 255.0 + 0.5);
}

// The spread texture is 16 bits a channel: (distance + 256 * group, id) for the nearest mask pixel in the row, then
// the same for the nearest pixel of another group (mask pixels only). Distance 255 means none.
const int NONE = 255;

float pack16(int v) {
    return float(v) / 65535.0;
}

int unpack16(float f) {
    return int(f * 65535.0 + 0.5);
}

const int HIGHLIGHT_HEADER = 11;

bool highlightHasDepth() {
    return texelFetch(Highlights, 2).x > 0.5;
}

// A point of a mask in view space (x right, y up, the camera looking down -z, in blocks), from its framebuffer pixel
// (gl_FragCoord.xy), the mask's depth there and the mask's size.
vec3 highlightViewPosition(vec2 pixel, float depth, vec2 size) {
    float z = texelFetch(Highlights, 2).y > 0.5 ? depth : depth * 2.0 - 1.0;
    mat4 toView = mat4(texelFetch(Highlights, 3), texelFetch(Highlights, 4), texelFetch(Highlights, 5), texelFetch(Highlights, 6));
    vec4 v = toView * vec4(pixel / size * 2.0 - 1.0, z, 1.0);
    return v.xyz / v.w;
}

// From view space on to the world's axes, still from the camera (w 1 for a point, 0 for a direction).
vec3 highlightToWorld(vec3 v, float w) {
    mat4 toWorld = mat4(texelFetch(Highlights, 7), texelFetch(Highlights, 8), texelFetch(Highlights, 9), texelFetch(Highlights, 10));
    return (toWorld * vec4(v, w)).xyz;
}


vec4 highlightData(int id, int texel) {
    return texelFetch(Highlights, HIGHLIGHT_HEADER + id * HIGHLIGHT_TEXELS + texel);
}

float highlightGuiScale() {
    return texelFetch(Highlights, 0).y;
}

float highlightTime() {
    return texelFetch(Highlights, 1).x;
}

int highlightRadius() {
    return int(texelFetch(Highlights, 0).x + 0.5);
}

int highlightBoundaryRadius() {
    return int(texelFetch(Highlights, 0).w + 0.5);
}
