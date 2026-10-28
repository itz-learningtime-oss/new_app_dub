#!/usr/bin/env python3
"""Fetches a few short candidate clips for built-in voices from royalty-free speech datasets,
one clip at a time through the Hugging Face dataset viewer API (no full dataset download).

Output: artifacts/voice-candidates/<candidate>/take-N.wav (16 kHz mono PCM, 5-9 s) with
take-N.txt (exact transcript) and SOURCE.txt (dataset, licence, original file ids).
Requests are spaced out and back off on HTTP 429. Usage: python3 -I scripts/fetch-voice-candidates.py
Licences: Hi-Fi TTS and VCTK are CC BY 4.0 (credit required), LJ Speech is public domain,
Jenny (Dioco) allows commercial use with the credit "Jenny (Dioco)". Mirrors are used only for
access; the original dataset licence applies.
"""
import warnings
warnings.filterwarnings("ignore", category=DeprecationWarning)
import aifc, audioop, json, os, subprocess, sys, tempfile, time, urllib.error, urllib.parse, urllib.request, wave

API = "https://datasets-server.huggingface.co/rows"
ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "artifacts", "voice-candidates")
LICENCE = {
    "hifi": "Hi-Fi TTS (NVIDIA), CC BY 4.0. Credit: Hi-Fi Multi-Speaker English TTS Dataset, Bakhturina et al. 2021. Source audio: LibriVox (public domain).",
    "lj": "LJ Speech 1.1 (Keith Ito, Linda Johnson / LibriVox), public domain.",
    "jenny": "Jenny TTS dataset (Dioco). Commercial use allowed; credit the voice as \"Jenny (Dioco)\". Confirm against github.com/dioco-group/jenny-tts-dataset before release.",
    "vctk": "CSTR VCTK Corpus 0.92 (University of Edinburgh), CC BY 4.0. Credit: CSTR VCTK Corpus, University of Edinburgh.",
}
last_request = 0.0


def get(params):
    """One viewer API call, at most every 3 s, retrying patiently on rate limits."""
    global last_request
    url = API + "?" + urllib.parse.urlencode(params)
    for attempt in range(12):
        wait = 3 - (time.time() - last_request)
        if wait > 0: time.sleep(wait)
        last_request = time.time()
        try:
            return json.load(urllib.request.urlopen(url, timeout=60))
        except urllib.error.HTTPError as e:
            if e.code in (429, 500, 502, 503): print(f"  HTTP {e.code}, waiting {20 * (attempt + 1)} s", flush=True); time.sleep(20 * (attempt + 1)); continue
            raise
        except (urllib.error.URLError, TimeoutError): time.sleep(10)
    raise RuntimeError("gave up on " + url)


def to_wav(src_url, dest):
    """Downloads one audio asset and converts it to 16 kHz mono 16-bit PCM with afconvert."""
    with tempfile.TemporaryDirectory() as work:
        raw = os.path.join(work, "clip" + os.path.splitext(urllib.parse.urlparse(src_url).path)[1])
        urllib.request.urlretrieve(src_url, raw)
        # afconvert's WAVE output uses the extensible header, so convert to AIFF and write plain PCM WAV.
        aiff = os.path.join(work, "clip.aiff")
        subprocess.run(["afconvert", "-f", "AIFF", "-d", "BEI16@16000", "-c", "1", raw, aiff], check=True)
        with aifc.open(aiff) as a: frames = a.readframes(a.getnframes())
        with wave.open(dest, "wb") as w:
            w.setnchannels(1); w.setsampwidth(2); w.setframerate(16000); w.writeframes(audioop.byteswap(frames, 2))


def seconds(path):
    with wave.open(path) as w: return w.getnframes() / w.getframerate()


def trimmed(path, threshold=400, margin=0.12):
    """PCM frames with leading/trailing silence removed, keeping a short natural margin."""
    with wave.open(path) as w: frames = w.readframes(w.getnframes())
    samples = memoryview(frames).cast("h")
    loud = [i for i in range(0, len(samples), 160) if max(abs(x) for x in samples[i:i + 160]) > threshold]
    if not loud: return frames
    start = max(0, loud[0] - int(16000 * margin)); end = min(len(samples), loud[-1] + 160 + int(16000 * margin))
    return frames[2 * start:2 * end]


def join(parts, dest, gap=0.25):
    """Concatenates same-speaker utterances, each trimmed of silence, with a small pause between."""
    frames = b""
    for p in parts:
        frames += trimmed(p) + b"\0\0" * int(16000 * gap)
    with wave.open(dest, "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(16000); w.writeframes(frames[: -2 * int(16000 * gap)])


def save(name, source, takes):
    folder = os.path.join(ROOT, name); os.makedirs(folder, exist_ok=True)
    notes = [LICENCE[source], ""]
    for i, (parts, text, ids) in enumerate(takes, 1):
        dest = os.path.join(folder, f"take-{i}.wav")
        with tempfile.TemporaryDirectory() as work:
            pieces = []
            for j, url in enumerate(parts):
                piece = os.path.join(work, f"{j}.wav"); to_wav(url, piece); pieces.append(piece)
            join(pieces, dest)
        open(os.path.join(folder, f"take-{i}.txt"), "w").write(text.strip() + "\n")
        notes.append(f"take-{i}: {seconds(dest):.1f} s from {', '.join(ids)}")
        print(f"  {name}/take-{i}.wav {seconds(dest):.1f} s", flush=True)
    open(os.path.join(folder, "SOURCE.txt"), "w").write("\n".join(notes) + "\n")


def hifi(speakers):
    rows = []
    # Some narrators appear only in the "other" test split.
    for split, total in (("test.clean", 300), ("test.other", 700)):
        for offset in range(0, total, 100):
            rows += [r["row"] for r in get({"dataset": "MikhailT/hifi-tts", "config": "all", "split": split, "offset": offset, "length": 100})["rows"]]
        if all(any(r["speaker"] == s and 5.0 <= float(r["duration"]) <= 10.0 for r in rows) for s, _ in speakers): break
    for speaker, name in speakers:
        good = [r for r in rows if r["speaker"] == speaker and 5.0 <= float(r["duration"]) <= 10.0][:2]
        save(name, "hifi", [([r["audio"][0]["src"]], r["text_no_preprocessing"], [r["file"]]) for r in good])


def single(dataset, name, source, offset, text_key, id_key, count=2):
    rows = [r["row"] for r in get({"dataset": dataset, "config": "default", "split": "full" if source == "lj" else "train", "offset": offset, "length": 40})["rows"]]
    # Prefer one-sentence clips around 80-140 characters (about 5-9 s of speech).
    good = [r for r in rows if 80 <= len(r[text_key]) <= 140][:count]
    save(name, source, [([r["audio"][0]["src"]], r[text_key], [str(r[id_key])]) for r in good])


def vctk(speaker, name, start, count=2):
    """Scans forward from a row offset for the speaker, then joins consecutive sentences to 5-9 s."""
    offset = start
    while True:
        rows = [r["row"] for r in get({"dataset": "sanchit-gandhi/vctk", "config": "default", "split": "train", "offset": offset, "length": 100})["rows"]]
        # The mirror lists each sentence once per microphone; keep mic 1 so sentences never repeat.
        mine = [r for r in rows if r["speaker_id"] == speaker and r["file"].endswith("mic1.flac")]
        if mine: break
        if rows and rows[0]["speaker_id"] > speaker: raise RuntimeError(f"{speaker} not found after {start}")
        offset += 100
    # Sentences 001-024 are long standard passages; later ones are short newspaper sentences.
    page = offset + 100
    while len([r for r in mine if int(r["text_id"]) > 24]) < 12:
        more = [r["row"] for r in get({"dataset": "sanchit-gandhi/vctk", "config": "default", "split": "train", "offset": page, "length": 100})["rows"]]
        mine += [r for r in more if r["speaker_id"] == speaker and r["file"].endswith("mic1.flac")]
        if not more or more[-1]["speaker_id"] != speaker: break
        page += 100
    print(f"  {speaker}: {mine[0]['gender']} {mine[0]['accent']} {mine[0]['region']}", flush=True)
    short = [r for r in mine if int(r["text_id"]) > 24 and 25 <= len(r["text"]) <= 90]
    takes = []
    for a, b in zip(short[0::2], short[1::2]):  # two sentences, about 5-7 s together
        takes.append(([a["audio"][0]["src"], b["audio"][0]["src"]], a["text"] + " " + b["text"], [a["file"].split("/")[-1], b["file"].split("/")[-1]]))
        if len(takes) == count: break
    save(name, "vctk", takes)


if __name__ == "__main__":
    only = set(sys.argv[1:])  # optional candidate names, e.g. hifi-9017-male vctk-p248
    wanted = lambda name: not only or name in only
    if only:
        hifi([s for s in [("11614", "hifi-11614-female"), ("9017", "hifi-9017-male")] if wanted(s[1])]) if any(wanted(n) for n in ("hifi-11614-female", "hifi-9017-male")) else None
        for speaker, name, start in [("p248", "vctk-p248", 14000), ("p237", "vctk-p237-scottish-male", 8700)]:
            if wanted(name): vctk(speaker, name, start)
        sys.exit()
    print("Hi-Fi TTS", flush=True)
    hifi([("92", "hifi-92-female"), ("11614", "hifi-11614-female"), ("6097", "hifi-6097-male"), ("9017", "hifi-9017-male")])
    print("LJ Speech", flush=True)
    single("MikhailT/lj-speech", "lj-female-us", "lj", 200, "spoken_text", "file")
    print("Jenny", flush=True)
    single("reach-vb/jenny_tts_dataset", "jenny-female", "jenny", 100, "transcription", "file_name")
    print("VCTK", flush=True)
    for speaker, name, start in [("p248", "vctk-p248", 12000), ("p251", "vctk-p251", 14000), ("p376", "vctk-p376", 42000), ("p237", "vctk-p237-scottish-male", 8700)]:
        try: vctk(speaker, name, start)
        except Exception as e: print(f"  {speaker} skipped: {e}", flush=True)
