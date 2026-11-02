#version 150 core
in vec2 vPos;
uniform vec2 uCenter;
uniform vec2 uHalf;
uniform float uRadius;
uniform float uSoftness;
uniform vec4 uColor;
out vec4 fragColor;
float sdRoundBox(vec2 p, vec2 b, float r) {
    vec2 q = abs(p) - b + vec2(r);
    return min(max(q.x, q.y), 0.0) + length(max(q, vec2(0.0))) - r;
}
void main() {
    float d = sdRoundBox(vPos - uCenter, uHalf, uRadius);
    // A HIGH POWER CONCENTRATES THE INTENSITY NEAR THE EDGE WITH A FAINT TAIL, UNLIKE THE FLAT RAMP OF A smoothstep
    float t = clamp(d / uSoftness, 0.0, 1.0);
    float falloff = pow(1.0 - t, 2.2);
    fragColor = vec4(uColor.rgb, uColor.a * falloff);
}
