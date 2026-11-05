# Player Inteligente

Player de vídeo Android 100% offline (Kotlin + Jetpack Compose + Media3/ExoPlayer) que encontra cenas por parte do corpo
(**Peito**, **Bunda**, **Buceta**) com o detector **NudeNet 320n** (YOLOv8n, ONNX, 12 MB) rodando no aparelho via ONNX Runtime Mobile
(NNAPI quando disponível, senão CPU/XNNPACK). O APK não declara permissão de Internet.

- Biblioteca (MediaStore) em grade + seletor de arquivos do sistema
- Player: play/pausa, busca, velocidade, tela cheia, toque duplo ±10 s, pinça para zoom (até 5x) e pan, modos Ajustar/Preencher/Esticar/100%/Auto,
  deslizar vertical = brilho (esq.) / volume 0–200% (dir.; acima de 100% ganho real até 3x/+9,5 dB com limitador suave)
- Botão **Buscar** (nunca automático) → WorkManager em primeiro plano com notificação e Cancelar; 2 quadros/s por padrão, reduzidos para 320 px antes da inferência (4K/8K sem estourar memória)
- Segmentos por categoria, marcas coloridas na barra, lista com miniaturas, Próxima/Anterior cena saltando N s antes (padrão 3 s)
- Cache JSON por vídeo (uri+tamanho+mtime); a sensibilidade muda sem reanalisar

Build: `./gradlew testReleaseUnitTest assembleRelease` (keystore fora do git em `/workspace/player-inteligente-keystore/`).
