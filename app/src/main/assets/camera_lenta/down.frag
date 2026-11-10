// luma (R) -> metade da resolução (média 2×2 por amostra bilinear no canto comum).
uniform sampler2D uSrc;
in vec2 vUv;
layout(location = 0) out vec4 oColor;
void main() {
    oColor = vec4(texture(uSrc, vUv).r, 0.0, 0.0, 1.0);
}
