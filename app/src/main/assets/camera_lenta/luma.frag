// RGBA (tamanho cheio) -> luma (R) em resolução reduzida: média de N×N amostras (filtro caixa por amostras bilineares).
uniform sampler2D uSrc;
uniform int uTaps;            // amostras por lado (1..4)
uniform vec2 uDst;            // tamanho do destino (px)
in vec2 vUv;
layout(location = 0) out vec4 oColor;
void main() {
    vec2 step_ = 1.0 / uDst;
    float s = 0.0;
    for (int j = 0; j < 4; j++) for (int i = 0; i < 4; i++) {
        if (i >= uTaps || j >= uTaps) continue;
        vec2 o = (vec2(float(i), float(j)) + 0.5) / float(uTaps) - 0.5;
        vec3 c = texture(uSrc, vUv + o * step_).rgb;
        s += dot(c, vec3(0.299, 0.587, 0.114));
    }
    oColor = vec4(s / float(uTaps * uTaps), 0.0, 0.0, 1.0);
}
