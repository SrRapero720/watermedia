#version 150 core
// GRAY: ONE LUMA PLANE BROADCAST TO RGB
in vec2 texCoord;
out vec4 fragColor;
uniform sampler2D plane0;
uniform float bitScale;
uniform vec4 colorimetry[4];
#include "colorimetry.glsl"
void main() {
    float luma = texture(plane0, texCoord).r * bitScale * colorimetry[0].x - colorimetry[0].y;
    fragColor = vec4(display(vec3(luma), colorimetry[3]), 1.0);
}
