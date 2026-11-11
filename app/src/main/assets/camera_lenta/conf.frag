// Confiança por vetor (v1.3.24), nível 0. Entrada: fluxo já mediano/limitado deste sentido (uF) e do sentido oposto (uG).
//  - frente-trás: o vetor de volta no ponto de chegada deve cancelar o de ida;
//  - vizinhança: fração dos 8 vizinhos (a ±2 px) que concordam com o vetor (vetor isolado = casamento errado = "pipoco");
//  - custo do casamento; vetor cortado pelo limite.
// Também devolve o vetor refinado (média dos vizinhos concordantes).
// Saída: xy = fluxo, z = custo, w = confiança 0..1 (ainda sem suavizar: o temporal.frag espalha/estabiliza).
uniform sampler2D uF;
uniform sampler2D uG;
in vec2 vUv;
layout(location = 0) out vec4 oColor;
void main() {
    ivec2 sz = textureSize(uF, 0);
    ivec2 p = ivec2(gl_FragCoord.xy);
    vec4 f = texelFetch(uF, p, 0);
    vec2 fs = vec2(sz);
    vec2 g = texture(uG, (vec2(p) + 0.5 + f.xy) / fs).xy;
    float fb = length(f.xy + g);
    float agree = 0.0;
    for (int j = -1; j <= 1; j++) for (int i = -1; i <= 1; i++) {
        if (i == 0 && j == 0) continue;
        vec2 n = texelFetch(uF, clamp(p + ivec2(i, j) * 2, ivec2(0), sz - 1), 0).xy;
        agree += 1.0 - smoothstep(0.8, 2.0, length(n - f.xy));
    }
    agree /= 8.0;
    // vetor refinado: média 5×5 só dos vizinhos que concordam (≤ 1,5 px) → tira a tremida sub-pixel do ruído sem borrar bordas de movimento
    vec2 acc = vec2(0.0); float wsum = 0.0;
    for (int j = -2; j <= 2; j++) for (int i = -2; i <= 2; i++) {
        vec2 n = texelFetch(uF, clamp(p + ivec2(i, j), ivec2(0), sz - 1), 0).xy;
        float w = 1.0 - smoothstep(0.75, 1.5, length(n - f.xy));
        acc += w * n; wsum += w;
    }
    vec2 vr = acc / max(wsum, 1e-3);
    float c = exp(-0.5 * fb * fb / 1.44);                    // erro frente-trás ~1,2 px
    c *= 1.0 - smoothstep(0.05, 0.13, f.z);                  // casamento ruim
    c *= smoothstep(0.12, 0.45, agree);                      // vetor isolado
    c *= mix(0.15, 1.0, f.w);                                // vetor cortado pelo limite
    oColor = vec4(vr, f.z, c);
}
