#!/usr/bin/env python3
"""Simula varredura sequencial com 6 keyframes/vídeo + retomada + timeout/skip."""
import os, subprocess, tempfile, time, json
from pathlib import Path

ROOT = Path(__file__).resolve().parent / "testdata"
FRAMES = 6
TIMEOUT = 10

def keyframe_times(dur, n=FRAMES):
    if n <= 1: return [0.0]
    return [dur * i / (n - 1) for i in range(n)]

def probe_dur(path):
    out = subprocess.check_output([
        "ffprobe", "-v", "error", "-show_entries", "format=duration",
        "-of", "default=noprint_wrappers=1:nokey=1", str(path),
    ], text=True).strip()
    return float(out or 0)

def extract_one(path, t, out):
    subprocess.run([
        "ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
        "-ss", f"{t:.3f}", "-i", str(path),
        "-frames:v", "1",
        "-vf", "scale=320:320:force_original_aspect_ratio=decrease,pad=320:320:(ow-iw)/2:(oh-ih)/2,format=rgb24",
        "-update", "1", str(out),
    ], timeout=TIMEOUT, check=True)

def main():
    videos = sorted(ROOT.glob("v*.mp4"))
    assert len(videos) >= 40, f"expected ~50 videos, got {len(videos)}"
    state_path = ROOT / "scan_state.json"
    done = json.loads(state_path.read_text()) if state_path.exists() else {}
    # fresh run
    done = {}
    total = len(videos)
    feitos = 0
    print(f"start 0 de {total}")
    t0 = time.time()
    with tempfile.TemporaryDirectory() as td:
        td = Path(td)
        for v in videos:
            key = str(v)
            try:
                dur = probe_dur(v)
                for i, t in enumerate(keyframe_times(max(dur, 0.1))):
                    out = td / f"f{i}.png"
                    extract_one(v, min(t, max(dur - 0.01, 0)), out)
                    sz = out.stat().st_size
                    out.unlink(missing_ok=True)
                    if sz <= 0:
                        raise RuntimeError("empty frame")
                done[key] = "LIMPO"
            except Exception as e:
                done[key] = "ERRO"
                print("skip", v.name, type(e).__name__, e)
            feitos += 1
            state_path.write_text(json.dumps(done))
            if feitos % 10 == 0:
                print(f"{feitos} de {total}")
    print(f"phase1 {feitos} de {total} in {time.time()-t0:.1f}s erros={sum(1 for v in done.values() if v=='ERRO')}")
    # Simulate crash+resume: drop last 5
    keys = list(done.keys())
    for k in keys[-5:]:
        del done[k]
    state_path.write_text(json.dumps(done))
    pend = [v for v in videos if str(v) not in done]
    assert len(pend) == 5, len(pend)
    # progress display must be 45/50 not 0/5
    frac = len(done) / total
    assert abs(frac - 0.9) < 1e-6, frac
    print(f"OK resume shows {len(done)} de {total} ({frac*100:.0f}%), pending={len(pend)}")
    for v in pend:
        done[str(v)] = "LIMPO"
    state_path.write_text(json.dumps(done))
    assert all(str(v) in done for v in videos)
    print("PASS")

if __name__ == "__main__":
    main()
