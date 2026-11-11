// Quadro intermediário em t∈[0,1] entre A (t=0) e B (t=1) — v1.3.24: sem chaveamento duro por bloco/pixel.
//  - warp "para trás" por ponto fixo no fluxo de cada lado (acompanha bordas de movimento, sem splat);
//  - a confiança (w do fluxo) já vem do conf.frag/temporal.frag: consistência frente-trás + vizinhança + custo, ESPALHADA (feather)
//    e estável no tempo; aqui só é amostrada (bilinear) → máscara de oclusão suave;
//  - peso de cada lado contínuo (sem "vence o maior"): se A e B warpados discordam (concordância medida em vizinhança, não por pixel),
//    migra suavemente para o lado mais confiável / mais próximo no tempo;
//  - baixa confiança dilui suavemente para o quadro mais próximo (repetição); uFb leva o quadro INTEIRO ao crossfade/repetição
//    quando blocos ruins demais; uModo 1/2 = só A / só B (corte de cena, sem par).
uniform sampler2D uA;
uniform sampler2D uB;
uniform sampler2D uFab;       // fluxo A→B em px do nível 0 (z = custo, w = confiança)
uniform sampler2D uFba;       // fluxo B→A
uniform float uT;
uniform int uModo;            // 0 = interpola, 1 = só A, 2 = só B
uniform float uFb;            // 0..1: fração do quadro que cai para o plano B (global, suavizada no tempo)
uniform float uFbCross;       // plano B: 1 = crossfade, 0 = repetição do quadro mais próximo
in vec2 vUv;
layout(location = 0) out vec4 oColor;

void main() {
    vec2 x = vUv;
    vec3 ca = texture(uA, x).rgb;
    vec3 cb = texture(uB, x).rgb;
    if (uModo == 1) { oColor = vec4(ca, 1.0); return; }
    if (uModo == 2) { oColor = vec4(cb, 1.0); return; }
    float t = uT;
    float tn = smoothstep(0.45, 0.55, t);
    vec3 near_ = mix(ca, cb, tn);
    vec3 plano = mix(near_, mix(ca, cb, t), uFbCross);     // plano B (global)
    if (uFb > 0.999) { oColor = vec4(plano, 1.0); return; }
    vec2 fs = vec2(textureSize(uFab, 0));

    vec2 a = x;
    for (int i = 0; i < 3; i++) a = x - t * texture(uFab, a).xy / fs;
    vec4 fA = texture(uFab, a);
    float residA = length((a + t * fA.xy / fs - x) * fs);
    float cA = fA.w * exp(-0.5 * residA * residA / 4.0);

    vec2 b = x;
    for (int i = 0; i < 3; i++) b = x - (1.0 - t) * texture(uFba, b).xy / fs;
    vec4 fB = texture(uFba, b);
    float residB = length((b + (1.0 - t) * fB.xy / fs - x) * fs);
    float cB = fB.w * exp(-0.5 * residB * residB / 4.0);

    vec3 wa = texture(uA, a).rgb;
    vec3 wb = texture(uB, b).rgb;
    float w0 = (1.0 - t) * cA;
    float w1 = t * cB;
    float beta = w1 / max(w0 + w1, 1e-4);                   // peso de B quando os dois concordam
    // concordância em vizinhança (média de 5 amostras de |A_w − B_w| ≈ ±1,5 px): não decide por pixel isolado
    vec2 o = 1.5 / vec2(textureSize(uA, 0));
    float d = dot(abs(wa - wb), vec3(0.3333));
    d += dot(abs(texture(uA, a + vec2(o.x, 0.0)).rgb - texture(uB, b + vec2(o.x, 0.0)).rgb), vec3(0.3333));
    d += dot(abs(texture(uA, a - vec2(o.x, 0.0)).rgb - texture(uB, b - vec2(o.x, 0.0)).rgb), vec3(0.3333));
    d += dot(abs(texture(uA, a + vec2(0.0, o.y)).rgb - texture(uB, b + vec2(0.0, o.y)).rgb), vec3(0.3333));
    d += dot(abs(texture(uA, a - vec2(0.0, o.y)).rgb - texture(uB, b - vec2(0.0, o.y)).rgb), vec3(0.3333));
    d *= 0.2;
    float k = smoothstep(0.05, 0.14, d);
    float pref = smoothstep(-0.35, 0.35, cB - cA);          // lado mais confiável (suave)
    float betaD = mix(tn, pref, smoothstep(0.0, 0.5, abs(cA - cB)));
    beta = mix(beta, betaD, k);
    vec3 outc = mix(wa, wb, beta);
    // confiança parcial → dilui suavemente em direção ao quadro mais próximo (repetição)
    float cc = max(w0, w1) / max(t < 0.5 ? (1.0 - t) : t, 1e-4);
    outc = mix(near_, outc, smoothstep(0.05, 0.45, cc));
    outc = mix(outc, plano, uFb);
    oColor = vec4(outc, 1.0);
}
