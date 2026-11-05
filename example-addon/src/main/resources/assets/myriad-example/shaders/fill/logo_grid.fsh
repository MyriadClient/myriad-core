#version 330

// A custom highlight fill: the Myriad mark (two interlocked rounded squares) repeated in a staggered grid that moves
// with the highlight. Registered in ExampleAddon with HighlightStyle.Fill.custom; LogoEsp uses it.
//
// Importing highlight_fill.glsl brings in the whole highlight composite (outline, glow, the shared edges between
// highlights); this file only has to say what colour a pixel inside a highlight is.
#moj_import <myriad:highlight_fill.glsl>

// The mark on a 512 grid, as MyriadLogo draws it: stroke centre lines of two rounded squares, the second offset by 102.
const vec2 CENTER_A = vec2(205.0);
const vec2 CENTER_B = vec2(307.0);
const float HALF = 95.0;
const float RADIUS = 34.0;
const float STROKE = 15.0; // half the stroke width
const float HALO = 31.0;   // half the gap cut around the square on top where they cross

float roundedSquare(vec2 p, vec2 center) {
    vec2 q = abs(p - center) - vec2(HALF - RADIUS);
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - RADIUS;
}

// Coverage of the mark at p (512 grid, y down), antialiased over aa grid units.
float mark(vec2 p, float aa) {
    float a = abs(roundedSquare(p, CENTER_A));
    float b = abs(roundedSquare(p, CENTER_B));
    float strokeA = 1.0 - smoothstep(STROKE - aa, STROKE + aa, a);
    float strokeB = 1.0 - smoothstep(STROKE - aa, STROKE + aa, b);
    float haloA = 1.0 - smoothstep(HALO - aa, HALO + aa, a);
    float haloB = 1.0 - smoothstep(HALO - aa, HALO + aa, b);
    // The squares interlock: B passes over A, except where A's right side crosses B's top edge.
    bool aOnTop = p.x > 245.0 && p.x < 355.0 && p.y > 157.0 && p.y < 267.0;
    return aOnTop ? max(strokeA, strokeB * (1.0 - haloA)) : max(strokeB, strokeA * (1.0 - haloB));
}

vec4 customFill(HighlightFill f) {
    // One mark per cell; the style's dot spacing sets the cell size, so the Fill Spacing setting scales the pattern.
    float cell = max(f.spacing * 6.0, 8.0 * f.scale);
    vec2 grid = f.local / cell;
    // Every other row shifted half a cell, and the whole grid drifting upwards at the fill's Drift option (ExampleAddon).
    grid.y += f.time * highlightOption(0).x;
    grid.x += mod(floor(grid.y), 2.0) * 0.5;
    vec2 inCell = fract(grid);
    // The mark spans the middle of its cell (the 512 grid's 80..432), y flipped: the framebuffer's y grows upwards.
    vec2 p = vec2(inCell.x, 1.0 - inCell.y) * 512.0;
    float coverage = mark(p, 512.0 / cell);
    // A faint wash of the colour behind the marks, so the shape still reads where they're sparse.
    float alpha = max(coverage, 0.15) * f.opacity;
    return vec4(f.color.rgb, f.color.a * alpha);
}
