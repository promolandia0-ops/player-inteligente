// Mediana 3×3 (por componente) do fluxo: remove casamentos isolados errados sem borrar bordas de movimento.
uniform sampler2D uFlow;
in vec2 vUv;
layout(location = 0) out vec4 oColor;
void main() {
    ivec2 sz = textureSize(uFlow, 0);
    ivec2 p = ivec2(gl_FragCoord.xy);
    float vx[9]; float vy[9]; float cz = 0.0;
    for (int j = 0; j < 3; j++) for (int i = 0; i < 3; i++) {
        vec4 f = texelFetch(uFlow, clamp(p + ivec2(i - 1, j - 1), ivec2(0), sz - 1), 0);
        vx[j * 3 + i] = f.x; vy[j * 3 + i] = f.y;
        if (i == 1 && j == 1) cz = f.z;
    }
    for (int i = 1; i < 9; i++) {
        float kx = vx[i]; float ky = vy[i]; int j = i - 1;
        while (j >= 0 && vx[j] > kx) { vx[j + 1] = vx[j]; j--; }
        vx[j + 1] = kx;
        j = i - 1;
        while (j >= 0 && vy[j] > ky) { vy[j + 1] = vy[j]; j--; }
        vy[j + 1] = ky;
    }
    oColor = vec4(vx[4], vy[4], cz, 1.0);
}
