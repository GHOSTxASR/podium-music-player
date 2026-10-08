# Device sounds

`boot_chord.py` synthesizes Podium's startup chord, `app/src/main/res/raw/podium_boot.ogg` (D-71).
It is an original sound made from scratch with NumPy: no samples or recordings. The random seed is
fixed, so the same chord comes out every time.

To rebuild it:

```bash
python3 tools/sounds/boot_chord.py podium_boot.wav
ffmpeg -y -i podium_boot.wav -c:a libvorbis -q:a 5 app/src/main/res/raw/podium_boot.ogg
```

The result is 5 s of 48 kHz stereo Ogg Vorbis that peaks at -1 dBFS. `DeviceSounds` plays it once,
at full volume, when the power button turns the device on.
