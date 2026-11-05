#!/usr/bin/env python3
"""Estima speedup da análise nova (grosso 0.5 fps + refino local) vs antiga (2 fps denso)."""
import time, sys, math
try:
    import numpy as np, onnxruntime as ort
    from PIL import Image
except Exception as e:
    print("deps", e); sys.exit(0)

onnx = "/workspace/player-inteligente/app/src/main/assets/nudenet_320n.onnx"
sess = ort.InferenceSession(onnx, providers=["CPUExecutionProvider"])
inp = sess.get_inputs()[0].name

def infer_once():
    x = np.random.rand(1, 3, 320, 320).astype(np.float32)
    t0 = time.time()
    sess.run(None, {inp: x})
    return time.time() - t0

# warm
for _ in range(3): infer_once()
samples = [infer_once() for _ in range(12)]
ms = 1000 * (sum(samples) / len(samples))
print(f"ONNX CPU infer ~{ms:.1f} ms/frame")

# decode cost assumptions (phone S24 Ultra, rough):
# old MMR CLOSEST ~120 ms/frame; new keyframe SYNC ~25 ms; MediaCodec seek~15-30 ms
mmr_closest = 120
mmr_sync = 25
mc_seek = 20

def secs(frames, decode_ms, infer_ms=ms):
    return frames * (decode_ms + infer_ms) / 1000

dur = 5 * 60  # 5 min
old_frames = dur * 2  # 2 fps
coarse = dur // 2 + 1  # 1 / 2s
# assume ~8% of coarse are hits → refine ±2s at 2fps = 8 frames/hit
hits = max(1, int(coarse * 0.08))
refine = hits * 8
new_frames = coarse + refine

old_t = secs(old_frames, mmr_closest)
new_t_mmr = secs(coarse, mmr_sync) + secs(refine, mmr_closest)
new_t_mc = secs(coarse, mc_seek) + secs(refine, mc_seek)
print(f"5 min video:")
print(f"  OLD  {old_frames} frames @ MMR-CLOSEST+infer → ~{old_t:.0f}s ({old_t/60:.1f} min)")
print(f"  NEW  {coarse} coarse + {refine} refine = {new_frames} frames")
print(f"       MMR-SYNC+CLOSEST → ~{new_t_mmr:.0f}s  (speedup {old_t/new_t_mmr:.1f}x)")
print(f"       MediaCodec       → ~{new_t_mc:.0f}s  (speedup {old_t/new_t_mc:.1f}x)")
print(f"  Target S24 Ultra NNAPI (~0.6x infer): ~{new_t_mc*0.7:.0f}s")
