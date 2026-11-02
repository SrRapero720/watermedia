#version 150 core
// SDF SOFT RECT: ONE QUAD WHOSE ALPHA IS SOLID INSIDE AND FADES OVER uSoftness OUTSIDE THE EDGE, FOR GLOWS AND SHADOWS;
// SHARES THE UI VERTEX LAYOUT BUT READS ONLY position
in vec2 position;
uniform mat4 projection;
out vec2 vPos;
void main() {
    vPos = position;
    gl_Position = projection * vec4(position, 0.0, 1.0);
}
