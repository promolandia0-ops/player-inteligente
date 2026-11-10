// Quadro intermediário em t∈[0,1] entre A (t=0) e B (t=1), com consciência de oclusão:
//  - warp "para trás" por iteração de ponto fixo no fluxo de cada lado (acompanha bordas de movimento, sem splat);
//  - confiança por consistência frente-trás do fluxo + custo do casamento + convergência;
//  - só mistura A e B quando concordam (|A_w − B_w| pequeno); se discordam, usa o lado mais confiável (sem fantasma);
//  - sem confiança nenhuma (corte de cena, fluxo ruim): REPETE o quadro mais próximo (sem mistura).
uniform sampler2D uA;
uniform sampler2D uB;
uniform sampler2D uFab;       // fluxo A→B em px do nível 0 (z = custo)
uniform sampler2D uFba;       // fluxo B→A
uniform float uT;
uniform int uModo;            // 0 = interpola, 1 = só A, 2 = só B
in vec2 vUv;
layout(location = 0) out vec4 oColor;

float confianca(float err, float custo, float resid) {
    float c = exp(-0.5 * err * err / 1.0);                 // erro frente-trás ~1 px (nível 0)
    c *= 1.0 - smoothstep(0.05, 0.13, custo);              // casamento ruim
    c *= exp(-0.5 * resid * resid / 1.0);                  // ponto fixo não convergiu (desoclusão)
    return c;
}

void main() {
    vec2 x = vUv;
    vec3 ca = texture(uA, x).rgb;
    vec3 cb = texture(uB, x).rgb;
    if (uModo == 1) { oColor = vec4(ca, 1.0); return; }
    if (uModo == 2) { oColor = vec4(cb, 1.0); return; }
    vec2 fs = vec2(textureSize(uFab, 0));
    float t = uT;

    vec2 a = x;
    for (int i = 0; i < 3; i++) a = x - t * texture(uFab, a).xy / fs;
    vec4 fabA = texture(uFab, a);
    vec2 aEnd = a + fabA.xy / fs;
    vec2 fbaEnd = texture(uFba, aEnd).xy;
    float errA = length(fabA.xy + fbaEnd);
    float residA = length((a + t * fabA.xy / fs - x) * fs);
    float cA = confianca(errA, fabA.z, residA);

    vec2 b = x;
    for (int i = 0; i < 3; i++) b = x - (1.0 - t) * texture(uFba, b).xy / fs;
    vec4 fbaB = texture(uFba, b);
    vec2 bEnd = b + fbaB.xy / fs;
    vec2 fabEnd = texture(uFab, bEnd).xy;
    float errB = length(fbaB.xy + fabEnd);
    float residB = length((b + (1.0 - t) * fbaB.xy / fs - x) * fs);
    float cB = confianca(errB, fbaB.z, residB);

    vec3 wa = texture(uA, a).rgb;
    vec3 wb = texture(uB, b).rgb;
    float w0 = (1.0 - t) * cA;
    float w1 = t * cB;
    // no ponto de fixação perfeita t=0/1 o resultado é exatamente o quadro (sem tremida na troca de quadro)
    if (w0 + w1 < 0.03) { oColor = vec4(t < 0.5 ? ca : cb, 1.0); return; }
    vec3 mix_ = (w0 * wa + w1 * wb) / (w0 + w1);
    vec3 win = (w0 >= w1) ? wa : wb;
    float d = dot(abs(wa - wb), vec3(0.3333));
    float k = 1.0 - smoothstep(0.05, 0.12, d);
    vec3 outc = mix(win, mix_, k);
    // confiança parcial: dilui em direção ao quadro mais próximo (nunca em mistura dos dois sem warp)
    float cc = max(w0, w1) / max(t < 0.5 ? (1.0 - t) : t, 1e-4);
    vec3 near_ = t < 0.5 ? ca : cb;
    outc = mix(near_, outc, smoothstep(0.03, 0.35, cc));
    oColor = vec4(outc, 1.0);
}
