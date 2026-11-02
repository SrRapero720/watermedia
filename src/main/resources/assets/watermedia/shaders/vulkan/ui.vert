#version 450
layout(location = 0) in vec2 pos;
layout(location = 1) in vec2 uv;
layout(location = 2) in vec4 col;
layout(push_constant) uniform PC { mat4 proj; int useTexture; } pc;
layout(location = 0) out vec2 vUV;
layout(location = 1) out vec4 vCol;
void main() {
    vUV = uv;
    vCol = col;
    gl_Position = pc.proj * vec4(pos, 0.0, 1.0);
}
