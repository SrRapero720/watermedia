#version 150 core
in vec2 position;
in vec2 texCoord;
in vec4 vertColor;
uniform mat4 projection;
out vec2 vTexCoord;
out vec4 vColor;
void main() {
    vTexCoord = texCoord;
    vColor = vertColor;
    gl_Position = projection * vec4(position, 0.0, 1.0);
}
