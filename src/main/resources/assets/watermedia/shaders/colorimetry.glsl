// SHARED BY THE OPENGL AND VULKAN CONVERSION SHADERS; THE vec4 ARGUMENTS COME FROM Colorimetry.constants(). ycbcr() EXPANDS
// THE RANGE AND APPLIES THE MATRIX; display() LINEARIZES PQ/HLG, MAPS BT.2020 TO BT.709, KNEES HIGHLIGHTS AND RE-ENCODES BT.1886
vec3 ycbcr(float y, float cb, float cr, vec4 range, vec4 m) {
    y = y * range.x - range.y;
    cb = cb * range.z - range.w;
    cr = cr * range.z - range.w;
    return vec3(y + m.x * cr, y - m.y * cb - m.z * cr, y + m.w * cb);
}

vec3 display(vec3 rgb, vec4 d) {
    if (d.x == 0.0 && d.y == 0.0) return rgb;
    vec3 e = clamp(rgb, 0.0, 1.0);
    vec3 lin;
    // LINEAR LIGHT WHERE 1.0 IS SDR WHITE (203 NITS, BT.2408)
    if (d.x == 1.0) {
        vec3 p = pow(e, vec3(1.0 / 78.84375));
        lin = pow(max(p - 0.8359375, 0.0) / (18.8515625 - 18.6875 * p), vec3(1.0 / 0.1593017578125)) * (10000.0 / 203.0);
    } else if (d.x == 2.0) {
        vec3 s = mix(e * e / 3.0, (exp((e - 0.55991073) / 0.17883277) + 0.28466892) / 12.0, step(0.5, e));
        lin = s * pow(max(dot(s, vec3(0.2627, 0.6780, 0.0593)), 1e-6), 0.2) * (1000.0 / 203.0);
    } else {
        lin = pow(e, vec3(2.4));
    }
    if (d.y != 0.0) lin = max(mat3(1.6605, -0.1246, -0.0182, -0.5876, 1.1329, -0.1006, -0.0728, -0.0083, 1.1187) * lin, 0.0);
    // HDR KEEPS EVERYTHING BELOW 0.75 OF SDR WHITE AND BENDS THE REST SO A 1000-NIT PEAK LANDS ON SDR WHITE;
    // SCALING BY THE BRIGHTEST CHANNEL KEEPS THE HUE OF SATURATED HIGHLIGHTS
    float peak = max(max(lin.r, lin.g), lin.b);
    if (d.x != 0.0 && peak > 0.75) {
        float x = (peak - 0.75) * 4.0;
        const float top = (1000.0 / 203.0 - 0.75) * 4.0;
        lin *= (0.75 + 0.25 * x * (1.0 + x / (top * top)) / (1.0 + x)) / peak;
    }
    return pow(clamp(lin, 0.0, 1.0), vec3(1.0 / 2.4));
}
