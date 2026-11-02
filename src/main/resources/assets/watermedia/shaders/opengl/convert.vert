#version 150 core
// FULLSCREEN QUAD OF THE CONVERSION PASS; GLSL 150 CORE IS WHAT MINECRAFT 1.17+ GUARANTEES
in vec2 position;
in vec2 uv;
out vec2 texCoord;
void main() {
    texCoord = uv;
    gl_Position = vec4(position, 0.0, 1.0);
}
