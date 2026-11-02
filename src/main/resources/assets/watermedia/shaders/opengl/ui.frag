#version 150 core
in vec2 vTexCoord;
in vec4 vColor;
uniform sampler2D tex;
uniform float useTexture;
out vec4 fragColor;
void main() {
    // 2 SAMPLES A CACHED LAYER, WHOSE COLOR IS PREMULTIPLIED: DIVIDING IT BACK LETS THE NORMAL BLEND APPLY ALPHA ONCE
    if (useTexture > 1.5) {
        vec4 layer = texture(tex, vTexCoord);
        fragColor = layer.a > 0.0 ? vec4(layer.rgb / layer.a, layer.a) : vec4(0.0);
    } else {
        fragColor = useTexture > 0.5 ? texture(tex, vTexCoord) * vColor : vColor;
    }
}
