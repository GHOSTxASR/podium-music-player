`song.mp3` and `song.flac`: the same 32 s, 330 Hz sine at 8 kHz, as a lossy stream (standing in
for the catalogue's) and a lossless one (standing in for a custom source's), for the mid-song
switch to a custom source's copy (D-92). Generated with:

    ffmpeg -f lavfi -i "sine=frequency=330:duration=32:sample_rate=8000" -ac 1 -c:a libmp3lame -b:a 8k song.mp3
    ffmpeg -f lavfi -i "sine=frequency=330:duration=32:sample_rate=8000" -ac 1 -c:a flac -sample_fmt s16 song.flac
