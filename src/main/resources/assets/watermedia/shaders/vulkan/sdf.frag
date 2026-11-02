#version 450
layout(location = 0) in vec2 vPos;
layout(push_constant) uniform PC {
    mat4 proj; vec4 color; vec2 center; vec2 halfExt; float radius; float softness;
} pc;
layout(location = 0) out vec4 fragColor;
float sdRoundBox(vec2 p, vec2 b, float r) {
    vec2 q = abs(p) - b + vec2(r);
    return min(max(q.x, q.y), 0.0) + length(max(q, vec2(0.0))) - r;
}
void main() {
    float d = sdRoundBox(vPos - pc.center, pc.halfExt, pc.radius);
    float t = clamp(d / pc.softness, 0.0, 1.0);
    float falloff = pow(1.0 - t, 2.2);
    fragColor = vec4(pc.color.rgb, pc.color.a * falloff);
}
