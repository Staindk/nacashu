# Makes the watch's shutter sound, resources/sounds/shutter.raw: two short noise bursts (the
# shutter opening and closing), each with a quick metallic ring. Raw signed 8-bit mono PCM at
# 16 kHz, the format the watch plays. The noise is seeded, so the file is the same every run.
# Usage, from watch/: python3 tools/make_shutter_sound.py
import math
import random

RATE = 16000
rng = random.Random(1)


def samples(ms):
    return int(RATE * ms / 1000)


def burst(ms, decay, smooth, gain):
    """White noise, softened by averaging `smooth` neighbours, fading out over `decay` seconds."""
    noise = [rng.uniform(-1, 1) for _ in range(samples(ms))]
    out = []
    for i in range(len(noise)):
        window = noise[max(0, i - smooth // 2):i + (smooth + 1) // 2]
        out.append(sum(window) / smooth * math.exp(-i / (RATE * decay)) * gain)
    return out


def ring(ms, hz, decay, gain):
    return [math.sin(2 * math.pi * hz * i / RATE) * math.exp(-i / (RATE * decay)) * gain
            for i in range(samples(ms))]


def mix(a, b):
    return [(a[i] if i < len(a) else 0) + (b[i] if i < len(b) else 0) for i in range(max(len(a), len(b)))]


sound = (mix(burst(25, .004, 2, 2.0), ring(40, 3000, .008, .5)) + [0] * samples(50) +
         mix(burst(40, .008, 3, 2.0), ring(60, 2400, .012, .5)))
data = bytes(max(-127, min(127, round(s * 127))) & 0xFF for s in sound)
with open('resources/sounds/shutter.raw', 'wb') as f:
    f.write(data)
print(f'{len(data)} bytes')
