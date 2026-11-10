# Player Inteligente

Player de vídeo Android 100% offline (Kotlin + Jetpack Compose + Media3/ExoPlayer) que encontra cenas por parte do corpo
(**Peito**, **Bunda**, **Buceta**, **Cuzinho**) com o detector **NudeNet 320n** (YOLOv8n, ONNX, 12 MB) rodando no aparelho via ONNX Runtime Mobile
(NNAPI quando disponível, senão CPU/XNNPACK). O APK não declara permissão de Internet.

- **v1.1.1:** varredura da galeria em processo `:scan` (OOM não mata a UI), progresso persistido/retomável (`x de N`), Pausar/Continuar, 6 keyframes/vídeo com timeout 10s; botão **Analisar vídeo** bem visível no player
- Biblioteca em pastas (MediaStore BUCKET) + álbuns virtuais **Putaria** / **Sem nudez** / **Não analisados** (botão Organizar galeria; não move arquivos) + grade com busca/ordenar
- Player imersivo (vídeo em tela cheia) com abas Peito/Bunda/Buceta/Cuzinho + números de cena no overlay
- Player: play/pausa, busca, velocidade, tela cheia, toque duplo ±10 s, pinça para zoom (até 5x) e pan, modos Ajustar/Preencher/Esticar/100%/Auto,
  deslizar vertical = brilho (esq.) / volume 0–200% (dir.; acima de 100% ganho real até 3x/+9,5 dB com limitador suave)
- Botão **Buscar** (nunca automático) → WorkManager; análise em **2 passagens** (grosso 1 quadro/2 s via MediaCodec/keyframe, depois refino nas janelas) a partir da posição atual, com marcas progressivas
- No player: abas **Peito / Bunda / Buceta / Cuzinho** (topo) com badge de contagem + botões numerados 1…N para pular à cena; auto-esconde com os controles
- Segmentos por categoria, marcas coloridas na barra, lista com miniaturas, Próxima/Anterior cena saltando N s antes (padrão 3 s)
- Cache JSON por vídeo (uri+tamanho+mtime); a sensibilidade muda sem reanalisar; tamanhos < 1 MB em KB

Build: `./gradlew testReleaseUnitTest assembleRelease` (keystore fora do git em `/workspace/player-inteligente-keystore/`).

## Pessoas (v1.3.21)
Agrupa vídeos por rosto, 100% offline, módulo isolado (`Rosto*.kt`, `Pessoas*.kt`, `pessoas.db`). Modelos do OpenCV Zoo:
YuNet `face_detection_yunet_2023mar` (MIT) e SFace `face_recognition_sface_2021dec_int8` (Apache-2.0).
