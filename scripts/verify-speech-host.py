"""Real model compatibility smoke, Windows x64 only; never Android accuracy proof."""
import argparse
import gc
import hashlib
import json
import platform
import sys
import time
import wave
from datetime import datetime, timezone
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dependencies", type=Path)
    parser.add_argument("--spoken-fixtures", action="store_true")
    parser.add_argument("--use-2025-candidate", action="store_true")
    args = parser.parse_args()
    root = Path(__file__).resolve().parent.parent
    cache = root / ".tools/models/speech"
    name = "host-tts-smoke" if args.spoken_fixtures else "host-smoke"
    if args.use_2025_candidate:
        name += "-2025"
    destination = cache / "validation" / (name + ".json")
    destination.parent.mkdir(parents=True, exist_ok=True)
    # A failed new attempt must not leave a previous successful report looking current.
    destination.write_text(json.dumps({"provenance": "HOST_WINDOWS_X64_SYNTHETIC", "passed": False,
        "state": "incomplete", "generatedAtUtc": datetime.now(timezone.utc).isoformat()}), encoding="utf-8")
    dependencies = args.dependencies or root / ".tools/sherpa-host"
    sys.path.insert(0, str(dependencies.resolve()))
    import numpy as np
    import sherpa_onnx as sherpa

    if sys.platform != "win32" or platform.machine().lower() not in ("amd64", "x86_64"):
        raise RuntimeError("This smoke report is specifically Windows x64, not Android")
    if sherpa.__version__ != "1.13.8" or np.__version__ != "2.2.6":
        raise RuntimeError("Install the pinned host dependencies first")
    lock = json.loads((root / "scripts/speech-models.lock.json").read_text(encoding="utf-8"))
    if args.use_2025_candidate:
        lock["models"] = [lock["asrAlternatives"][0]] + [model for model in lock["models"] if model["id"] != "sensevoice"]
    paths = {}
    identities = []
    for model in lock["models"]:
        directory = cache / model["id"] / model["version"]
        paths[model["id"]] = {}
        for spec in model["files"]:
            asset = directory / spec["name"]
            if asset.stat().st_size != spec["size"]:
                raise RuntimeError(f"Model size mismatch: {asset.name}")
            # Protect the bytes actually passed to native inference, not source-code changes.
            with asset.open("rb") as stream:
                actual = hashlib.file_digest(stream, "sha256").hexdigest()
            if actual != spec["sha256"]:
                raise RuntimeError(f"Model digest mismatch: {asset.name}")
            paths[model["id"]][spec["name"]] = str(asset)
        identities.append({"id": model["id"], "version": model["version"],
                           "files": [{"name": spec["name"], "sha256": spec["sha256"]}
                                     for spec in model["files"]]})

    checks = []
    silence = np.zeros(32000, dtype=np.float32)
    seconds = np.arange(32000, dtype=np.float32) / 16000
    tone = (0.15 * np.sin(2 * np.pi * 220 * seconds)
            + 0.05 * np.sin(2 * np.pi * 440 * seconds)).astype(np.float32)
    for run in range(2):
        started = time.perf_counter()
        config = sherpa.VadModelConfig()
        config.silero_vad.model = paths["silero-vad"]["silero_vad.onnx"]
        config.silero_vad.threshold = 0.5
        config.silero_vad.window_size = 512
        config.silero_vad.min_silence_duration = 0.4
        config.silero_vad.min_speech_duration = 0.25
        config.silero_vad.max_speech_duration = 5.0
        config.sample_rate = 16000
        config.num_threads = 1
        if not config.validate():
            raise RuntimeError("VAD config rejected")
        vad = sherpa.VoiceActivityDetector(config, buffer_size_in_seconds=10)
        for offset in range(0, len(silence), 320):
            vad.accept_waveform(silence[offset:offset + 320])
        vad.flush()
        if not vad.empty() or vad.is_speech_detected():
            raise RuntimeError("VAD reported speech on this synthetic silence fixture")
        vad.reset()
        checks.append({"module": "silero-vad", "cycle": run + 1, "passed": True,
                       "input": "synthetic float32 silence, 2s, 16kHz, 320-sample chunks",
                       "hostWallMs": round((time.perf_counter() - started) * 1000, 3)})
        del vad, config
        gc.collect()

        started = time.perf_counter()
        asr = paths["sensevoice"]
        recognizer = sherpa.OfflineRecognizer.from_sense_voice(
            model=asr["model.onnx"], tokens=asr["tokens.txt"], num_threads=2,
            sample_rate=16000, language="auto", use_itn=True, provider="cpu")
        stream = recognizer.create_stream()
        stream.accept_waveform(16000, silence)
        recognizer.decode_stream(stream)
        result = stream.result
        if not isinstance(result.text, str) or not isinstance(result.lang, str):
            raise RuntimeError("Unexpected native SenseVoice result structure")
        checks.append({"module": "sensevoice", "cycle": run + 1, "passed": True,
                       "input": "synthetic float32 silence, 2s, 16kHz",
                       "resultTextLength": len(result.text), "resultLanguageTag": result.lang,
                       "accuracyAssertion": False,
                       "hostWallMs": round((time.perf_counter() - started) * 1000, 3)})
        del stream, recognizer
        gc.collect()

        started = time.perf_counter()
        config = sherpa.SpeakerEmbeddingExtractorConfig(
            model=paths["speaker"]["speaker.onnx"], num_threads=1, provider="cpu")
        if not config.validate():
            raise RuntimeError("Speaker config rejected")
        extractor = sherpa.SpeakerEmbeddingExtractor(config)
        stream = extractor.create_stream()
        stream.accept_waveform(16000, tone)
        stream.input_finished()
        if not extractor.is_ready(stream):
            raise RuntimeError("Speaker stream is not ready for 2s synthetic input")
        vector = np.asarray(extractor.compute(stream), dtype=np.float32)
        if extractor.dim != 192 or vector.shape != (192,) or not np.isfinite(vector).all() or np.linalg.norm(vector) <= 0:
            raise RuntimeError("Unexpected CAM++ embedding shape or non-finite/zero output")
        checks.append({"module": "speaker", "cycle": run + 1, "passed": True,
                       "input": "synthetic two-frequency tone, 2s, 16kHz",
                       "embeddingDimension": 192, "accuracyAssertion": False,
                       "hostWallMs": round((time.perf_counter() - started) * 1000, 3)})
        del vector, stream, extractor, config
        gc.collect()

    if args.spoken_fixtures:
        fixtures = cache / "tts-fixtures"
        manifest = json.loads((fixtures / "manifest.json").read_text(encoding="utf-8"))
        if manifest["provenance"] != "HOST_WINDOWS_LOCAL_TTS" or {case["id"] for case in manifest["cases"]} != {"en", "zh", "ja"}:
            raise RuntimeError("Expected the three locally generated TTS cases")
        for case in manifest["cases"]:
            if case["file"] != case["id"] + ".wav":
                raise RuntimeError("Unexpected fixture path")
            with wave.open(str(fixtures / case["file"]), "rb") as wav:
                if (wav.getframerate(), wav.getnchannels(), wav.getsampwidth(), wav.getcomptype()) != (16000, 1, 2, "NONE") or not 0 < wav.getnframes() <= 16000 * 30:
                    raise RuntimeError("Expected at most 30s mono PCM16 16kHz fixture")
                samples = np.frombuffer(wav.readframes(wav.getnframes()), dtype="<i2").astype(np.float32) / 32768
            config = sherpa.VadModelConfig()
            config.silero_vad.model = paths["silero-vad"]["silero_vad.onnx"]
            config.silero_vad.min_silence_duration = 0.4
            config.silero_vad.min_speech_duration = 0.25
            config.silero_vad.max_speech_duration = 5.0
            config.sample_rate = 16000
            config.num_threads = 1
            vad = sherpa.VoiceActivityDetector(config, buffer_size_in_seconds=40)
            for offset in range(0, len(samples), 320):
                vad.accept_waveform(samples[offset:offset + 320])
            vad.flush()
            segments = 0
            while not vad.empty():
                segments += 1
                vad.pop()
            if segments < 1:
                raise RuntimeError(f"No speech detected for {case['id']} TTS fixture")
            recognizer = sherpa.OfflineRecognizer.from_sense_voice(
                model=paths["sensevoice"]["model.onnx"], tokens=paths["sensevoice"]["tokens.txt"],
                num_threads=2, sample_rate=16000, language="auto", use_itn=True, provider="cpu")
            stream = recognizer.create_stream()
            stream.accept_waveform(16000, samples)
            recognizer.decode_stream(stream)
            result = stream.result
            expected = "<|" + case["id"] + "|>"
            matched = result.lang == expected and bool(result.text.strip())
            checks.append({"module": "sensevoice-and-vad-tts", "passed": matched, "fixtureLanguage": case["id"],
                           "voice": case["voice"], "inputSeconds": len(samples) / 16000,
                           "referenceText": case["referenceText"], "hypothesis": result.text,
                           "resultLanguageTag": result.lang, "vadSegmentCount": segments,
                           "scope": "Whole-file ASR and independent VAD. Not the Android streaming pipeline or human accuracy."})
            del result, stream, recognizer, vad, config
            gc.collect()

    report = {"provenance": "HOST_WINDOWS_X64_SYNTHETIC", "generatedAtUtc": datetime.now(timezone.utc).isoformat(),
              "runtime": {"sherpaOnnx": sherpa.__version__, "numpy": np.__version__, "python": platform.python_version()},
              "models": identities, "checks": checks, "state": "complete", "passed": all(check["passed"] for check in checks),
              "notValidated": ["Android ARM64 JNI", "microphone capture", "human speech accuracy or LID", "speaker identity or cross-language stability",
                               "Foxlet translation", "Android resource release", "PSS, thermal, battery or device latency"]}
    destination.write_text(json.dumps(report, ensure_ascii=True, indent=2), encoding="utf-8")
    print(json.dumps({"provenance": report["provenance"], "passed": report["passed"], "checks": len(checks), "report": str(destination)}))
    if not report["passed"]:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
