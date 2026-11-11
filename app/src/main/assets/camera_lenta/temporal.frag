// Espalha (feather) e estabiliza no tempo a confiança/fluxo (v1.3.24).
//  - confiança: caixa 5×5 (passo 2 = ±4 px do nível 0) misturada com o mínimo local → máscara de oclusão/queda SUAVE, sem corte por bloco;
//  - tempo: se o vetor deste par concorda com o do par anterior no mesmo ponto, mistura (menos tremida); se discorda, só reduz
//    um pouco a confiança (nunca alterna de repente entre "interpola" e "repete" de um par para o outro).
uniform sampler2D uCur;       // saída do conf.frag (este par)
uniform sampler2D uPrev;      // resultado deste mesmo passo no par anterior
uniform float uPrevOn;        // 0 = sem histórico (1º par, seek, corte)
in vec2 vUv;
layout(location = 0) out vec4 oColor;
void main() {
    ivec2 sz = textureSize(uCur, 0);
    ivec2 p = ivec2(gl_FragCoord.xy);
    vec4 c = texelFetch(uCur, p, 0);
    float s = 0.0;
    for (int j = -2; j <= 2; j++) for (int i = -2; i <= 2; i++)
        s += texelFetch(uCur, clamp(p + ivec2(i, j) * 2, ivec2(0), sz - 1), 0).w;
    s /= 25.0;
    float conf = mix(s, min(s, c.w), 0.5);
    vec2 v = c.xy;
    if (uPrevOn > 0.5) {
        vec4 q = texelFetch(uPrev, p, 0);
        float ok = 1.0 - smoothstep(1.0, 3.0, length(q.xy - v));
        v = mix(v, q.xy, 0.5 * ok);
        conf = mix(conf, q.w, 0.4 * ok) * mix(0.8, 1.0, ok);
    }
    oColor = vec4(v, c.z, clamp(conf, 0.0, 1.0));
}
