#version 450
// SDF SOFT RECT, MATCHING THE OPENGL SDF PATH: SOLID INSIDE, POWER FALLOFF OVER softness OUTSIDE. THE PUSH CONSTANT
// ORDER MATCHES VulkanRenderBackend.PUSH_SDF_BYTES (proj, color, center, halfExt, radius, softness)
layout(location = 0) in vec2 pos;
layout(push_constant) uniform PC {
    mat4 proj; vec4 color; vec2 center; vec2 halfExt; float radius; float softness;
} pc;
layout(location = 0) out vec2 vPos;
void main() {
    vPos = pos;
    gl_Position = pc.proj * vec4(pos, 0.0, 1.0);
}
