#version 150 core
// PLANAR YUV WITH AN ALPHA PLANE (plane3), WHICH IS ALWAYS FULL RANGE AT FULL RESOLUTION
in vec2 texCoord;
out vec4 fragColor;
uniform sampler2D plane0;
uniform sampler2D plane1;
uniform sampler2D plane2;
uniform sampler2D plane3;
uniform float bitScale;
uniform vec4 colorimetry[4];
#include "colorimetry.glsl"
void main() {
    vec2 chroma = texCoord * colorimetry[2].xy + colorimetry[2].zw;
    vec3 rgb = ycbcr(texture(plane0, texCoord).r * bitScale, texture(plane1, chroma).r * bitScale,
            texture(plane2, chroma).r * bitScale, colorimetry[0], colorimetry[1]);
    fragColor = vec4(display(rgb, colorimetry[3]), texture(plane3, texCoord).r * bitScale);
}
