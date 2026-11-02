#version 450
layout(location = 0) in vec2 vUV;
layout(location = 1) in vec4 vCol;
layout(set = 0, binding = 0) uniform sampler2D samp;
layout(push_constant) uniform PC { mat4 proj; int useTexture; } pc;
layout(location = 0) out vec4 fragColor;
void main() {
    // 2 SAMPLES A CACHED LAYER, WHOSE COLOR IS PREMULTIPLIED: DIVIDING IT BACK LETS THE NORMAL BLEND APPLY ALPHA ONCE
    if (pc.useTexture == 2) {
        vec4 layer = texture(samp, vUV);
        fragColor = layer.a > 0.0 ? vec4(layer.rgb / layer.a, layer.a) : vec4(0.0);
    } else {
        fragColor = pc.useTexture != 0 ? texture(samp, vUV) * vCol : vCol;
    }
}
