"""Compare Malayalam ASR models usable by the app on the same audio (run in CI).

Uses FLEURS Malayalam test utterances (read speech, with references) to compute
character error rate, plus real-time factor on the CI CPU for relative speed.
"""
import csv
import os
import sys
import tarfile
import time
import unicodedata
import wave

import numpy as np
import sherpa_onnx

N = int(os.environ.get("N_UTTS", "40"))
root = sys.argv[1]


def read_wav(path):
    with wave.open(path) as f:
        assert f.getnchannels() == 1 and f.getsampwidth() == 2, path
        sr = f.getframerate()
        x = np.frombuffer(f.readframes(f.getnframes()), dtype=np.int16).astype(np.float32) / 32768
    return x, sr


def norm(s):
    s = unicodedata.normalize("NFC", s)
    return "".join(c for c in s if c.isalpha() or "ഀ" <= c <= "ൿ")


def edit_distance(a, b):
    prev = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        cur = [i]
        for j, cb in enumerate(b, 1):
            cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (ca != cb)))
        prev = cur
    return prev[-1]


def load_fleurs():
    tsv = os.path.join(root, "fleurs/test.tsv")
    tgz = os.path.join(root, "fleurs/test.tar.gz")
    if not (os.path.exists(tsv) and os.path.exists(tgz)):
        print("FLEURS not available")
        return []
    rows = list(csv.reader(open(tsv, encoding="utf-8"), delimiter="\t", quoting=csv.QUOTE_NONE))[:N]
    wanted = {r[1]: r[2] for r in rows}
    out = []
    with tarfile.open(tgz) as t:
        for m in t:
            name = os.path.basename(m.name)
            if name in wanted:
                p = os.path.join(root, "fleurs", name)
                with open(p, "wb") as f:
                    f.write(t.extractfile(m).read())
                out.append((p, wanted[name]))
    return out


def recognizers():
    d = os.path.join(root, "models")
    specs = {
        "omnilingual-300M-v2": ("omni", f"{d}/omni300/model.int8.onnx", f"{d}/omni300/tokens.txt"),
        "omnilingual-1B-v2": ("omni", f"{d}/omni1b/model.int8.onnx", f"{d}/omni1b/tokens.txt"),
        "indicconformer-ml-int8 (csndhanasekar)": ("nemo", f"{d}/indic_csn/model.int8.onnx", f"{d}/indic_csn/tokens.txt"),
        "indicconformer-ml-fp32 (jeswinjestin)": ("nemo", f"{d}/indic_jj/model.onnx", f"{d}/indic_jj/tokens.txt"),
    }
    for name, (kind, model, tokens) in specs.items():
        if not os.path.exists(model):
            print(f"[skip] {name}: missing {model}")
            continue
        try:
            if kind == "omni":
                r = sherpa_onnx.OfflineRecognizer.from_omnilingual_asr_ctc(model=model, tokens=tokens, num_threads=2)
            else:
                r = sherpa_onnx.OfflineRecognizer.from_nemo_ctc(model=model, tokens=tokens, num_threads=2)
            yield name, r
        except Exception as e:  # noqa: BLE001
            print(f"[fail] {name}: {e}")


def decode(r, x, sr):
    s = r.create_stream()
    s.accept_waveform(sr, x)
    r.decode_stream(s)
    return s.result.text


utts = load_fleurs()
extra = [p for p in [os.path.join(root, "extra/0.wav")] if os.path.exists(p)]
print(f"{len(utts)} FLEURS utterances, {len(extra)} extra clips\n")

summary = []
for name, r in recognizers():
    errs = chars = 0
    audio_sec = proc_sec = 0.0
    samples = []
    for i, (p, ref) in enumerate(utts):
        x, sr = read_wav(p)
        t0 = time.time()
        hyp = decode(r, x, sr)
        proc_sec += time.time() - t0
        audio_sec += len(x) / sr
        errs += edit_distance(norm(hyp), norm(ref))
        chars += len(norm(ref))
        if i < 4:
            samples.append((ref, hyp))
    cer = 100.0 * errs / max(chars, 1)
    rtf = proc_sec / max(audio_sec, 1e-6)
    summary.append((name, cer, rtf))
    print(f"### {name}: CER {cer:.1f}%  RTF {rtf:.3f}")
    for ref, hyp in samples:
        print(f"  REF: {ref}\n  HYP: {hyp}")
    for p in extra:
        x, sr = read_wav(p)
        print(f"  EXTRA {os.path.basename(p)}: {decode(r, x, sr)}")
    print()

print("SUMMARY (lower CER is better; RTF = processing time / audio time on CI CPU)")
for name, cer, rtf in sorted(summary, key=lambda s: s[1]):
    print(f"  {cer:6.1f}%  RTF {rtf:.3f}  {name}")
