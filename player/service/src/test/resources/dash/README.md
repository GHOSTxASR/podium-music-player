`tone.mpd` + `tone.mp4`: a 1.5 s 440 Hz sine, FLAC in fragmented MP4, as a static single-representation
DASH manifest with a relative BaseURL (the shape some custom sources serve, D-91). Generated with:

    ffmpeg -f lavfi -i "sine=frequency=440:duration=1.5:sample_rate=44100" -ac 1 -c:a flac -strict -2 \
      -f dash -single_file 1 -single_file_name tone.mp4 -use_template 0 -use_timeline 0 tone.mpd
