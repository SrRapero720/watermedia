#version 150 core
// PACKED YUYV/UYVY (8-BIT): THE RGBA8 TEXTURE HOLDS ONE PIXEL PAIR PER TEXEL, ceil(WIDTH / 2) WIDE; EACH PIXEL FETCHES ITS
// PAIR BY INDEX, SINCE NORMALIZED SAMPLING DRIFTS TO THE NEXT PAIR ON ODD WIDTHS; uvSwap 1.0 READS THE UYVY BYTE ORDER
in vec2 texCoord;
out vec4 fragColor;
uniform sampler2D plane0;
uniform float uvSwap;
uniform float outputWidth;
uniform vec4 colorimetry[4];
#include "colorimetry.glsl"
void main() {
    int px = int(texCoord.x * outputWidth);
    vec4 pair = texelFetch(plane0, ivec2(px >> 1, int(texCoord.y * float(textureSize(plane0, 0).y))), 0);
    float isOdd = float(px & 1);
    float Y0 = mix(pair.r, pair.g, uvSwap);
    float Cb = mix(pair.g, pair.r, uvSwap);
    float Y1 = mix(pair.b, pair.a, uvSwap);
    float Cr = mix(pair.a, pair.b, uvSwap);
    vec3 rgb = ycbcr(mix(Y0, Y1, isOdd), Cb, Cr, colorimetry[0], colorimetry[1]);
    fragColor = vec4(display(rgb, colorimetry[3]), 1.0);
}
