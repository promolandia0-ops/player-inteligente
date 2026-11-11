// Estatística GLOBAL de confiança (v1.3.24): 16×9 blocos; R = fração de pixels com confiança baixa, G = diferença média de luma
// entre A e B SEM movimento (×8): quase igual → crossfade não deixa fantasma; diferente → repetição. Lida pela CPU junto com
// stat.frag (576 bytes): decide crossfade/repetição do quadro inteiro quando há blocos ruins demais.
uniform sampler2D uFlow;      // fluxo final (w = confiança) A→B
uniform sampler2D uLumaA;
uniform sampler2D uLumaB;
in vec2 vUv;
layout(location = 0) out vec4 oColor;
void main() {
    ivec2 sz = textureSize(uFlow, 0);
    ivec2 tile = ivec2(gl_FragCoord.xy) - ivec2(0, 9);
    ivec2 g = ivec2(16, 9);
    ivec2 x0 = tile * sz / g;
    ivec2 x1 = (tile + 1) * sz / g;
    float bad = 0.0; float dif = 0.0; float n = 0.0;
    for (int y = x0.y; y < x1.y; y += 2) for (int x = x0.x; x < x1.x; x += 2) {
        vec4 f = texelFetch(uFlow, ivec2(x, y), 0);
        bad += 1.0 - smoothstep(0.25, 0.45, f.w);
        dif += abs(texelFetch(uLumaA, ivec2(x, y), 0).r - texelFetch(uLumaB, ivec2(x, y), 0).r);
        n += 1.0;
    }
    n = max(n, 1.0);
    oColor = vec4(clamp(bad / n, 0.0, 1.0), clamp(dif / n * 8.0, 0.0, 1.0), 0.0, 1.0);
}
