// "Melhorar imagem" (v1.3.24): pós-processamento leve em tempo real, 1 passada, ~13 amostras. Sem IA.
//  1) nitidez adaptativa ao contraste (estilo AMD CAS): afia mais onde há pouco contraste local, quase nada perto de bordas fortes (sem halo);
//     não afia áreas planas (ruído/blocos de compressão);
//  2) desbloqueio/debanding leve: em áreas quase planas mistura com a média da vizinhança (±2 px) + dither fixo de ±0,5/255 (quebra faixas);
//  3) saturação +6% e contraste +4% (suave).
uniform sampler2D uSrc;
uniform float uForca;         // 0..1 (nitidez); padrão 0.6
in vec2 vUv;
layout(location = 0) out vec4 oColor;
void main() {
    vec2 px = 1.0 / vec2(textureSize(uSrc, 0));
    vec3 e = texture(uSrc, vUv).rgb;
    vec3 b = texture(uSrc, vUv + vec2(0.0, -px.y)).rgb;
    vec3 h = texture(uSrc, vUv + vec2(0.0,  px.y)).rgb;
    vec3 d = texture(uSrc, vUv + vec2(-px.x, 0.0)).rgb;
    vec3 f = texture(uSrc, vUv + vec2( px.x, 0.0)).rgb;
    vec3 mn = min(min(min(d, e), min(f, b)), h);
    vec3 mx = max(max(max(d, e), max(f, b)), h);
    // alcance local de luma (decide "área plana")
    float lr = dot(mx - mn, vec3(0.299, 0.587, 0.114));
    float plano = 1.0 - smoothstep(0.02, 0.07, lr);
    // CAS
    vec3 amp = clamp(min(mn, 1.0 - mx) / max(mx, vec3(1e-3)), 0.0, 1.0);
    amp = sqrt(amp);
    float peak = -1.0 / mix(8.0, 5.0, uForca);
    vec3 wc = amp * peak * (1.0 - plano);
    vec3 cas = (wc * (b + d + f + h) + e) / (1.0 + 4.0 * wc);
    // desbloqueio / debanding em áreas planas
    vec3 m2 = 0.25 * (texture(uSrc, vUv + vec2(-2.0 * px.x, 0.0)).rgb + texture(uSrc, vUv + vec2(2.0 * px.x, 0.0)).rgb
                    + texture(uSrc, vUv + vec2(0.0, -2.0 * px.y)).rgb + texture(uSrc, vUv + vec2(0.0, 2.0 * px.y)).rgb);
    vec3 avg = 0.5 * (m2 + 0.25 * (b + d + f + h));
    vec2 q = floor(gl_FragCoord.xy);
    float dith = fract(52.9829189 * fract(dot(q, vec2(0.06711056, 0.00583715)))) - 0.5;   // fixo no espaço (sem cintilar no tempo)
    vec3 c = mix(cas, avg, 0.6 * plano) + (dith / 255.0) * plano;
    // saturação + contraste
    float l = dot(c, vec3(0.299, 0.587, 0.114));
    c = mix(vec3(l), c, 1.06);
    c = (c - 0.5) * 1.04 + 0.5;
    oColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}
