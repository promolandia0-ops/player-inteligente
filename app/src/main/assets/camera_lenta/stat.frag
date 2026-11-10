// Estatística para detectar corte de cena / fluxo não confiável: 16×9 blocos; em cada um, custo médio do casamento (flow.z)
// e contraste médio do quadro (luma), codificados em 16 bits (R,G = custo×4; B,A = contraste×4). Lido pela CPU (576 bytes).
uniform sampler2D uFlow;
uniform sampler2D uLuma;
in vec2 vUv;
layout(location = 0) out vec4 oColor;
vec2 enc(float v) {
    float s = clamp(v * 4.0, 0.0, 0.9999) * 255.0;
    float hi = floor(s);
    return vec2(hi / 255.0, fract(s));
}
void main() {
    ivec2 sz = textureSize(uFlow, 0);
    ivec2 tile = ivec2(gl_FragCoord.xy);
    ivec2 g = ivec2(16, 9);
    ivec2 x0 = tile * sz / g;
    ivec2 x1 = (tile + 1) * sz / g;
    float c = 0.0; float k = 0.0; float n = 0.0;
    for (int y = x0.y; y < x1.y; y += 2) for (int x = x0.x; x < x1.x; x += 2) {
        c += texelFetch(uFlow, ivec2(x, y), 0).z;
        float l = texelFetch(uLuma, ivec2(x, y), 0).r;
        float lx = texelFetch(uLuma, min(ivec2(x + 3, y), sz - 1), 0).r;
        float ly = texelFetch(uLuma, min(ivec2(x, y + 3), sz - 1), 0).r;
        k += 0.5 * (abs(l - lx) + abs(l - ly));
        n += 1.0;
    }
    n = max(n, 1.0);
    vec2 ec = enc(c / n);
    vec2 ek = enc(k / n);
    oColor = vec4(ec, ek);
}
