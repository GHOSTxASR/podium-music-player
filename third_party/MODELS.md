# Bundled models and on-device runtimes

| File | What | License | Source |
|---|---|---|---|
| `app/src/main/assets/models/magic_touch.tflite` | MediaPipe MagicTouch interactive segmenter, float32, unmodified: a point on a picture → the mask of the object, animal or person at that point. Input `[1,512,512,4]` (RGB 0–1 and a prior map marking the point), output `[1,512,512,1]` foreground probability. 6,227,884 bytes. | Apache License 2.0 (model card: "Licensed under Apache License, Version 2.0") | `storage.googleapis.com/mediapipe-models/interactive_segmenter/magic_touch/float32/latest/magic_touch.tflite`, fetched 2026-10-07, sha256 `e24338a717c1b7ad8d159666677ef400babb7f33b8ad60c4d96db4ecf694cd25`. Model card: `storage.googleapis.com/mediapipe-assets/Model Card MagicTouch.pdf` (Google; Valentin Bazarevsky, Ben Hahn; 2023-03-13). |

| Library | Use | License | Source |
|---|---|---|---|
| LiteRT 2.3.0 (`com.google.ai.edge.litert:litert`, with `litert-api`) | Runs the model above on the phone (`org.tensorflow.lite.Interpreter`). The bare runtime only: no MediaPipe Tasks, no Play services, nothing that reports usage. | Apache License 2.0 | Google Maven |

## How Podium uses the model (D-55)

- Only when the listener makes a sticker, on a picture they chose in the system photo picker. The
  picture and the mask never leave the phone; nothing is downloaded at run time.
- The model card's intended use is "object segmentation from photos in interactive applications"
  (photo editing). Its out-of-scope uses — surveillance, identity recognition, segmenting people
  who haven't consented — are not things Podium does: it cuts out one subject the listener taps, to
  make a sticker for their own player.
- Known limits (from the card): very small or distant subjects, thin features such as fingers,
  neighbouring objects merging. Podium's Add and Erase brushes are there for those.
