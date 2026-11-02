#version 150 core
// NV12/NV21/P010/P016: plane0 (R8/R16) IS LUMA AT FULL RESOLUTION, plane1 (RG8/RG16) THE INTERLEAVED CHROMA PAIR AT HALF
// RESOLUTION SAMPLED AT THE SITED COORDINATES colorimetry[2]; uvSwap 1.0 READS THE PAIR AS V,U (NV21)
in vec2 texCoord;
out vec4 fragColor;
uniform sampler2D plane0;
uniform sampler2D plane1;
uniform float bitScale;
uniform float uvSwap;
uniform vec4 colorimetry[4];
#include "colorimetry.glsl"
void main() {
    vec2 raw = texture(plane1, texCoord * colorimetry[2].xy + colorimetry[2].zw).rg * bitScale;
    vec3 rgb = ycbcr(texture(plane0, texCoord).r * bitScale, mix(raw.x, raw.y, uvSwap), mix(raw.y, raw.x, uvSwap),
            colorimetry[0], colorimetry[1]);
    fragColor = vec4(display(rgb, colorimetry[3]), 1.0);
}
