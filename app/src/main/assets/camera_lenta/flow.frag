// Fluxo óptico por casamento de blocos (SAD 5×5) em um nível da pirâmide, de A para B.
// Candidatos: ±RADIUS px em torno do fluxo do nível mais grosso (já ampliado), refino sub-pixel por parábola.
// Saída: xy = fluxo (px deste nível), z = custo (diferença média absoluta 0..1 do melhor casamento), w = 1.
#ifndef RADIUS
#define RADIUS 2
#endif
uniform sampler2D uA;
uniform sampler2D uB;
uniform sampler2D uPrior;     // fluxo do nível mais grosso (RGBA16F), ignorado se uPriorOn = 0
uniform float uPriorOn;
uniform float uLambda;        // penalidade por px de desvio do prior (favorece movimento suave)
in vec2 vUv;
layout(location = 0) out vec4 oColor;

float luma(sampler2D t, ivec2 p, ivec2 sz) {
    return texelFetch(t, clamp(p, ivec2(0), sz - 1), 0).r;
}

void main() {
    ivec2 sz = textureSize(uA, 0);
    ivec2 p = ivec2(gl_FragCoord.xy);
    vec2 prior = vec2(0.0);
    if (uPriorOn > 0.5) {
        ivec2 psz = textureSize(uPrior, 0);
        prior = texture(uPrior, vUv).xy * (vec2(sz) / vec2(psz));
    }
    ivec2 c0 = ivec2(floor(prior + 0.5));
    float a[25];
    for (int j = 0; j < 5; j++) for (int i = 0; i < 5; i++) a[j * 5 + i] = luma(uA, p + ivec2(i - 2, j - 2), sz);

    const int N = 2 * RADIUS + 1;
    float costs[N * N];
    float best = 1e9; ivec2 bo = ivec2(0);
    for (int dy = -RADIUS; dy <= RADIUS; dy++) for (int dx = -RADIUS; dx <= RADIUS; dx++) {
        ivec2 q = p + c0 + ivec2(dx, dy);
        float s = 0.0;
        for (int j = 0; j < 5; j++) for (int i = 0; i < 5; i++)
            s += abs(a[j * 5 + i] - luma(uB, q + ivec2(i - 2, j - 2), sz));
        s /= 25.0;
        float cost = s + uLambda * length(vec2(float(dx), float(dy)) + vec2(c0) - prior);
        costs[(dy + RADIUS) * N + (dx + RADIUS)] = cost;
        if (cost < best) { best = cost; bo = ivec2(dx, dy); }
    }
    vec2 sub = vec2(0.0);
    if (abs(bo.x) < RADIUS && abs(bo.y) < RADIUS) {
        int k = (bo.y + RADIUS) * N + (bo.x + RADIUS);
        float cc = costs[k];
        float dxm = costs[k - 1], dxp = costs[k + 1], dym = costs[k - N], dyp = costs[k + N];
        float ax = dxm - 2.0 * cc + dxp, ay = dym - 2.0 * cc + dyp;
        if (ax > 1e-5) sub.x = clamp(0.5 * (dxm - dxp) / ax, -0.5, 0.5);
        if (ay > 1e-5) sub.y = clamp(0.5 * (dym - dyp) / ay, -0.5, 0.5);
    }
    // custo "puro" (sem penalidade) do melhor candidato inteiro
    float raw = best - uLambda * length(vec2(bo) + vec2(c0) - prior);
    oColor = vec4(vec2(c0 + bo) + sub, max(raw, 0.0), 1.0);
}
