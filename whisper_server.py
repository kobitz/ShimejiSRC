#!/usr/bin/env python3
"""
Persistent Whisper transcription server for Shimeji.
Reads requests from stdin (one per line), transcribes each WAV,
writes the transcript to stdout (one line), flushes immediately.
Keeps the model loaded between requests.

Request format (one line per request):
  mic_path              -- mic audio only; stationary NR applied for fan/wind noise
  mic_path<TAB>ref_path -- mic + system audio reference; non-stationary NR strips
                           speaker echo using the reference as the noise profile

Runs on CPU only (int8). Thread count passed as argv[2] from Java (WhisperThreads setting).

Usage: python whisper_server.py [model_size] [thread_count]
  model_size:   tiny (default), base, small, medium
  thread_count: number of CPU threads (default 1)

Noise reduction (optional but strongly recommended):
  pip install noisereduce numpy
"""
import sys
import os
import tempfile

# ── Optional noise reduction ──────────────────────────────────────────────────
try:
    import numpy as _np
    import noisereduce as _nr
    import wave as _wave_mod
    _HAS_NR = True
except ImportError:
    _HAS_NR = False

_MIC_PREFIXES = ("shimeji_voice_", "shimeji_mic_")


def _is_repetitive(text, max_ratio=0.40, min_words=5):
    """
    Returns True if any 2-4 word phrase repeats suspiciously often.
    Catches Whisper hallucination loops like 'he died, he died, he died'.
    """
    words = text.lower().split()
    if len(words) < min_words:
        return False
    for n in (2, 3, 4):
        grams = [' '.join(words[i:i+n]) for i in range(len(words) - n + 1)]
        if not grams:
            break
        best_count = max(grams.count(g) for g in set(grams))
        if best_count / len(grams) > max_ratio:
            return True
    return False


def _is_mic_file(path):
    return os.path.basename(path).startswith(_MIC_PREFIXES)


def _load_wav_mono_f32(path):
    """Load a WAV file as a float32 mono array. Returns (array, sample_rate)."""
    with _wave_mod.open(path, 'rb') as wf:
        rate  = wf.getframerate()
        n_ch  = wf.getnchannels()
        sw    = wf.getsampwidth()
        raw   = wf.readframes(wf.getnframes())
    dtype = _np.int16 if sw == 2 else _np.int32
    audio = _np.frombuffer(raw, dtype=dtype).astype(_np.float32)
    if n_ch > 1:
        audio = audio.reshape(-1, n_ch).mean(axis=1)
    audio /= 32768.0
    return audio, rate


def _write_temp_wav(audio, rate):
    """Write a float32 mono array to a temp WAV. Returns (path, True)."""
    fd, path = tempfile.mkstemp(suffix='.wav')
    os.close(fd)
    with _wave_mod.open(path, 'wb') as wf:
        wf.setnchannels(1)
        wf.setsampwidth(2)
        wf.setframerate(rate)
        pcm = (audio * 32768.0).clip(-32768, 32767).astype(_np.int16)
        wf.writeframes(pcm.tobytes())
    return path, True


def _resample_linear(audio, src_rate, dst_rate):
    """Simple linear interpolation resample."""
    if src_rate == dst_rate or len(audio) == 0:
        return audio
    n_out = int(len(audio) * dst_rate / src_rate)
    return _np.interp(
        _np.linspace(0, len(audio) - 1, n_out),
        _np.arange(len(audio)), audio
    )


def _nr_process(wav_path, ref_path=None):
    """
    Apply noise reduction to a mic WAV file.
    - ref_path provided: non-stationary NR using system audio as echo profile.
      Reduces speaker bleed while preserving the user's voice.
    - ref_path absent: stationary NR for constant background (fan, AC, wind).
    Returns (path_to_use, is_temp). Original path returned on failure or if
    NR is unavailable / not a mic file.
    """
    if not _HAS_NR or not _is_mic_file(wav_path):
        return wav_path, False
    try:
        mic, rate = _load_wav_mono_f32(wav_path)
        if len(mic) == 0:
            return wav_path, False

        if ref_path and os.path.exists(ref_path):
            ref, ref_rate = _load_wav_mono_f32(ref_path)
            ref = _resample_linear(ref, ref_rate, rate)
            if len(ref) == 0:
                ref = None

            if ref is not None:
                # Non-stationary: strips speaker echo using system audio as profile.
                reduced = _nr.reduce_noise(
                    y=mic, sr=rate,
                    y_noise=ref,
                    stationary=False,
                    prop_decrease=0.95,
                )
                # Stationary: removes remaining constant noise (fan, AC) after echo stripped.
                reduced = _nr.reduce_noise(y=reduced, sr=rate, stationary=True,
                                           prop_decrease=0.80)
            else:
                reduced = _nr.reduce_noise(y=mic, sr=rate, stationary=True,
                                           prop_decrease=0.80)
        else:
            # No reference — stationary NR targets constant-spectrum noise.
            reduced = _nr.reduce_noise(y=mic, sr=rate, stationary=True,
                                       prop_decrease=0.90)

        post_rms = float(_np.sqrt(_np.mean(reduced ** 2)))
        pre_rms  = float(_np.sqrt(_np.mean(mic ** 2)))
        try:
            _nr_log = os.path.join(os.path.dirname(os.path.abspath(__file__)), "nr_energy.log")
            with open(_nr_log, "a") as _f:
                _f.write(f"pre={pre_rms:.4f} post={post_rms:.4f} ratio={post_rms/max(pre_rms,1e-9):.3f} ref={'yes' if ref_path else 'no'}\n")
        except Exception:
            pass

        if post_rms < 0.005:
            sys.stderr.write(f"[whisper_server] Post-NR energy {post_rms:.4f} < 0.005 — skipping Whisper\n")
            sys.stderr.flush()
            return None, False

        return _write_temp_wav(reduced, rate)

    except Exception as e:
        sys.stderr.write(f"[whisper_server] NR error: {e}\n")
        sys.stderr.flush()
        return wav_path, False


def main():
    model_size    = sys.argv[1] if len(sys.argv) > 1 else "tiny"
    intra_threads = int(sys.argv[2]) if len(sys.argv) > 2 else 1

    try:
        from faster_whisper import WhisperModel
    except ImportError:
        sys.stderr.write("faster-whisper not installed. Run: pip install faster-whisper\n")
        sys.stderr.flush()
        sys.exit(1)

    sys.stderr.write(f"[whisper_server] Loading model: {model_size}, threads: {intra_threads}\n")
    sys.stderr.flush()
    if _HAS_NR:
        sys.stderr.write("[whisper_server] Noise reduction: enabled (noisereduce)\n")
    else:
        sys.stderr.write(
            "[whisper_server] Noise reduction: DISABLED "
            "(pip install noisereduce numpy to enable)\n"
        )
    sys.stderr.flush()

    model = WhisperModel(model_size, device="cpu", compute_type="int8",
                         cpu_threads=intra_threads)
    sys.stderr.write("[whisper_server] Using CPU (int8)\n")

    _script_dir = os.path.dirname(os.path.abspath(__file__))
    _prompt_file = os.path.join(_script_dir, "whisper_prompt.txt")
    _initial_prompt = None
    if os.path.exists(_prompt_file):
        try:
            with open(_prompt_file, encoding="utf-8") as _f:
                _initial_prompt = _f.read().strip() or None
        except Exception:
            pass
    if _initial_prompt:
        sys.stderr.write(f"[whisper_server] Initial prompt: {_initial_prompt!r}\n")
    sys.stderr.flush()

    sys.stdout.write("READY\n")
    sys.stdout.flush()

    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        if line == "QUIT":
            break

        # Parse: "mic_path" or "mic_path<TAB>ref_path"
        parts    = line.split('\t', 1)
        wav_path = parts[0]
        ref_path = parts[1] if len(parts) > 1 else None

        if not os.path.exists(wav_path):
            sys.stdout.write("\n")
            sys.stdout.flush()
            continue

        mic = _is_mic_file(wav_path)
        nr_path, nr_is_temp = _nr_process(wav_path, ref_path)

        if nr_path is None:
            sys.stdout.write("\n")
            sys.stdout.flush()
            continue

        try:
            # Mic files are short voice commands — beam_size=1 (greedy) is fast
            # and accurate enough. System audio uses beam_size=5 for translate quality.
            transcribe_kwargs = dict(
                task="transcribe" if mic else "translate",
                beam_size=1 if mic else 5,
                vad_filter=True,
                vad_parameters=dict(
                    min_silence_duration_ms=500,
                    speech_pad_ms=200,
                    threshold=0.5,
                ),
                no_speech_threshold=0.90,
                condition_on_previous_text=False,
                temperature=0.0,
                compression_ratio_threshold=1.5,
            )
            if mic and _initial_prompt:
                transcribe_kwargs["initial_prompt"] = _initial_prompt
            segments, info = model.transcribe(nr_path, **transcribe_kwargs)
            _LOGPROB_THRESHOLD = -1.0
            kept, dropped = [], []
            for seg in segments:
                if seg.avg_logprob >= _LOGPROB_THRESHOLD:
                    kept.append(seg)
                else:
                    dropped.append(seg)
            if dropped:
                try:
                    _nr_log = os.path.join(os.path.dirname(os.path.abspath(__file__)), "nr_energy.log")
                    with open(_nr_log, "a") as _f:
                        for seg in dropped:
                            _f.write(f"DROPPED logprob={seg.avg_logprob:.3f} no_speech={seg.no_speech_prob:.3f} text={seg.text.strip()!r}\n")
                        for seg in kept:
                            _f.write(f"KEPT    logprob={seg.avg_logprob:.3f} no_speech={seg.no_speech_prob:.3f} text={seg.text.strip()!r}\n")
                except Exception:
                    pass
            text = " ".join(seg.text.strip() for seg in kept).strip()
            if _is_repetitive(text):
                sys.stderr.write(f"[whisper_server] Repetition detected — discarding: {text!r}\n")
                text = ""
            sys.stdout.write(text.replace("\n", " ") + "\n")
        except Exception as e:
            sys.stderr.write(f"[whisper_server] Error: {e}\n")
            sys.stdout.write("\n")
        finally:
            if nr_is_temp:
                try:
                    os.unlink(nr_path)
                except Exception:
                    pass

        sys.stdout.flush()
        sys.stderr.flush()


if __name__ == "__main__":
    main()
