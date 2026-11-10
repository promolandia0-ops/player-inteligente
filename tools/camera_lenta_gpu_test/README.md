Validação dos shaders da "Câmera lenta suave" fora do aparelho (Mesa llvmpipe, sem GPU real):
  python3 -m venv v && v/bin/pip install moderngl glcontext numpy pillow
  v/bin/python es_compile.py   # compila/linka todos os shaders como GLSL ES 3.00 com o cabeçalho exato do Kotlin
  v/bin/python run1.py         # PSNR da interpolação vs verdade-terreno (panorâmica, oclusão, movimento lento/rápido)
  v/bin/python run2.py         # corte de cena, fantasma na borda de objeto, ruído, movimento rápido demais
  v/bin/python run3.py / run4.py   # estatística de corte (razão custo/contraste) e decodificação do stat.frag
NÃO mede desempenho real de GPU móvel.
