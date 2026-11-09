"""Generate the "default" sound theme of the Android app (app/src/main/res/raw/alarm_default_*).

Each sound is a short, soft chime of about two seconds: bell-like notes with a gentle attack
and a natural decay, instead of beeps. A non-continuous alarm plays it once; a continuous one
loops it, the silence at the end of the file making the pause between two chimes. The chimes
differ so that the alarm can be recognized by ear in the dark:

- urgent low: three descending notes, quicker and higher (the most insistent)
- low (also low soon, fast drop and no recovery): two descending notes
- high: two ascending low notes
- urgent high: three ascending low notes
- stale data: a single low note

The other themes (alarm_<theme>_*) are recorded files, not generated; the private ones live in
app/src/private/res/raw, outside git.

Usage: python tools/sounds/generate_sounds.py
"""

import math
import struct
import wave
from pathlib import Path

SAMPLE_RATE = 22050
ATTACK_SECONDS = 0.015
VOLUME = 0.6
OUTPUT_DIR = Path(__file__).resolve().parents[2] / "app" / "src" / "main" / "res" / "raw"

# Each chime: its notes as (start in seconds, frequency in Hz, decay time constant in seconds),
# and the total length of the file in seconds, silence included.
CHIMES = {
    "alarm_default_urgent_low": ([(0.0, 1319, 0.25), (0.2, 1047, 0.25), (0.4, 880, 0.35)], 1.6),
    "alarm_default_low": ([(0.0, 784, 0.35), (0.3, 659, 0.5)], 2.0),
    "alarm_default_high": ([(0.0, 523, 0.35), (0.3, 659, 0.5)], 2.0),
    "alarm_default_urgent_high": ([(0.0, 523, 0.25), (0.2, 659, 0.25), (0.4, 784, 0.35)], 1.6),
    "alarm_default_stale_data": ([(0.0, 440, 0.6)], 2.5),
}


def add_note(samples: list[float], start: float, frequency: float, decay: float) -> None:
    """Adds a bell-like note: a soft attack, then an exponential decay over about 5 [decay]."""
    first = int(start * SAMPLE_RATE)
    length = min(int(5 * decay * SAMPLE_RATE), len(samples) - first)
    attack = int(ATTACK_SECONDS * SAMPLE_RATE)
    for i in range(length):
        t = i / SAMPLE_RATE
        envelope = min(1.0, i / attack) * math.exp(-t / decay)
        # A faint octave keeps the note audible on phone speakers without sounding harsh.
        value = math.sin(2 * math.pi * frequency * t) + 0.2 * math.sin(4 * math.pi * frequency * t)
        samples[first + i] += envelope * value / 1.2


def write_wav(path: Path, samples: list[float]) -> None:
    peak = max(1.0, max(abs(s) for s in samples))
    with wave.open(str(path), "wb") as wav:
        wav.setnchannels(1)
        wav.setsampwidth(2)
        wav.setframerate(SAMPLE_RATE)
        wav.writeframes(b"".join(struct.pack("<h", int(s / peak * VOLUME * 32767)) for s in samples))


def main() -> None:
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    for name, (notes, seconds) in CHIMES.items():
        samples = [0.0] * int(seconds * SAMPLE_RATE)
        for start, frequency, decay in notes:
            add_note(samples, start, frequency, decay)
        # A short fade-out, so that a looping chime does not click when it starts over.
        fade = int(0.05 * SAMPLE_RATE)
        for i in range(fade):
            samples[-1 - i] *= i / fade
        path = OUTPUT_DIR / f"{name}.wav"
        write_wav(path, samples)
        print(f"{path.name}: {len(samples) / SAMPLE_RATE:.2f} s")


if __name__ == "__main__":
    main()
