"""Write the Python YOLO reference boxes with OpenCV's ONNX engine.

`python_reference.py` uses Ultralytics and is the canonical reference, but it
needs `torch`. This script is the fallback for machines that only have
`numpy` + `opencv-python`: it runs the *same* ONNX weights through `cv2.dnn`,
implements the Ultralytics letterbox/decode/NMS in numpy, and writes the same
`python.json` schema `ai-engine/tests/parity_with_python.rs` reads. Two
independent implementations of one specification is exactly what the parity
harness is for - a difference is either a real bug or a documented rounding
choice, never a model difference.

It can also cut the frames out of a clip, so one command turns a recording into
`frames/*.rgb` + `python.json`:

    python tools/model-export/opencv_reference.py \
        --model "E:\\temp\\yolo11n.onnx" \
        --video testdata/videos/clip.mp4 --count 12 \
        --output "$env:TEMP\\cctv-parity" \
        --conf 0.35 --iou 0.5

Then, from `rust-core/`:

    $env:CCTV_PARITY_MODEL = "E:\\temp\\yolo11n.onnx"
    $env:CCTV_PARITY_DIR = "$env:TEMP\\cctv-parity"
    $env:CCTV_PARITY_THRESHOLD = "0.35"
    cargo test -p ai-engine --test parity_with_python -- --nocapture

`--conf` and `--iou` default to the Rust detector's defaults
(`DEFAULT_CONFIDENCE = 0.35`, `DEFAULT_IOU = 0.5`): comparing two runtimes at
different floors reports the floor difference as a port error. Frames, model,
and `python.json` stay outside the repo.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

#: Class index of `person` in the COCO models this repo uses.
PERSON_CLASS = 0

#: Letterbox fill value, matching `preprocess::PAD_VALUE`.
PAD_VALUE = 114


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model", required=True, help="ONNX weights to run")
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--video", help="clip to cut frames from")
    source.add_argument("--frames", help="directory of frames to reuse")
    parser.add_argument("--output", required=True, help="directory for frames + python.json")
    parser.add_argument(
        "--count", type=int, default=12, help="frames to sample from --video"
    )
    parser.add_argument(
        "--conf", type=float, default=0.35, help="confidence floor; match Rust"
    )
    parser.add_argument("--iou", type=float, default=0.5, help="NMS IoU; match Rust")
    parser.add_argument("--imgsz", type=int, default=640, help="inference size; match Rust")
    return parser.parse_args()


def frame_name(index: int, width: int, height: int) -> str:
    """`frame-<n>-<w>x<h>.rgb`, the name the Rust harness reads sizes from."""
    return f"frame-{index:04d}-{width}x{height}.rgb"


def sample_frames(video: Path, count: int):
    """Evenly spaced BGR frames out of a clip, without ffmpeg."""
    import cv2

    capture = cv2.VideoCapture(str(video))
    if not capture.isOpened():
        raise SystemExit(f"cannot open {video}")
    total = int(capture.get(cv2.CAP_PROP_FRAME_COUNT) or 0)
    if total <= 0:
        raise SystemExit(f"{video} reports no frames")

    frames = []
    # Half a step of margin keeps the first and last decodable frame in range.
    step = max(1, total // max(1, count))
    for index in range(count):
        position = min(total - 1, index * step + step // 2)
        capture.set(cv2.CAP_PROP_POS_FRAMES, position)
        ok, frame = capture.read()
        if ok:
            frames.append(frame)
    capture.release()
    if not frames:
        raise SystemExit(f"no frame could be decoded from {video}")
    return frames


def load_rgb_frames(directory: Path):
    """Reuse existing raw frames, decoding `<n>-<w>x<h>` from the name."""
    import numpy as np

    frames = []
    for path in sorted(directory.glob("*.rgb")):
        dimensions = path.stem.rsplit("-", 1)[-1]
        width, height = (int(value) for value in dimensions.split("x"))
        payload = np.fromfile(path, dtype=np.uint8)
        frames.append(payload.reshape((height, width, 3)))
    if not frames:
        raise SystemExit(f"no .rgb frames in {directory}")
    return frames


def letterbox(frame, size: int):
    """Scale + centre-pad one frame exactly like `ai_engine::preprocess`.

    Returns the padded BGR canvas and the geometry needed to map boxes back:
    the scale actually used and the padding rounded the same way Rust rounds it.
    """
    import cv2
    import numpy as np

    height, width = frame.shape[:2]
    scale = min(size / width, size / height)
    scaled_width = max(1, min(size, round(width * scale)))
    scaled_height = max(1, min(size, round(height * scale)))
    pad_x = round((size - scaled_width) / 2)
    pad_y = round((size - scaled_height) / 2)

    canvas = np.full((size, size, 3), PAD_VALUE, dtype=np.uint8)
    canvas[pad_y:pad_y + scaled_height, pad_x:pad_x + scaled_width] = cv2.resize(
        frame, (scaled_width, scaled_height), interpolation=cv2.INTER_LINEAR
    )
    return canvas, scale, pad_x, pad_y


def decode(output, conf: float, iou: float, geometry, width: int, height: int):
    """Turn one YOLO output into normalised person boxes.

    `output` is `(1, 4 + classes, anchors)` with `cx, cy, w, h` in letterboxed
    pixels and class probabilities already applied by the exported graph. Boxes
    are undone from the letterbox, clipped to the source frame, filtered to the
    person class, then NMS'd by the same greedy rule the Rust decoder uses.
    """
    import numpy as np

    _, scale, pad_x, pad_y = geometry
    predictions = output[0].T if output.shape[1] < output.shape[2] else output[0]
    scores_all = predictions[:, 4:]
    classes = scores_all.argmax(axis=1)
    scores = scores_all.max(axis=1)

    keep = (classes == PERSON_CLASS) & (scores >= conf)
    if not keep.any():
        return []

    candidates = predictions[keep]
    kept_scores = scores[keep]
    centre_x = candidates[:, 0]
    centre_y = candidates[:, 1]
    box_width = candidates[:, 2]
    box_height = candidates[:, 3]

    left = np.clip((centre_x - box_width / 2 - pad_x) / scale, 0, width)
    top = np.clip((centre_y - box_height / 2 - pad_y) / scale, 0, height)
    right = np.clip((centre_x + box_width / 2 - pad_x) / scale, 0, width)
    bottom = np.clip((centre_y + box_height / 2 - pad_y) / scale, 0, height)

    # Geometry and score are carried through one loop so a rejected degenerate
    # box can never shift the score list against the boxes that remain.
    boxes = []
    for x1, y1, x2, y2, score in zip(left, top, right, bottom, kept_scores):
        if x2 <= x1 or y2 <= y1:
            continue
        boxes.append(
            {
                "x": round(float(x1) / width, 6),
                "y": round(float(y1) / height, 6),
                "w": round(float(x2 - x1) / width, 6),
                "h": round(float(y2 - y1) / height, 6),
                "confidence": round(float(score), 6),
            }
        )
    return _nms(boxes, iou)


def _nms(boxes, iou: float):
    """Greedy NMS over normalised boxes, highest confidence first."""
    ordered = sorted(boxes, key=lambda box: box["confidence"], reverse=True)
    kept = []
    for candidate in ordered:
        if all(_overlap(candidate, other) <= iou for other in kept):
            kept.append(candidate)
    return kept


def _overlap(first, second) -> float:
    """IoU of two normalised boxes."""
    left = max(first["x"], second["x"])
    top = max(first["y"], second["y"])
    right = min(first["x"] + first["w"], second["x"] + second["w"])
    bottom = min(first["y"] + first["h"], second["y"] + second["h"])
    intersection = max(0.0, right - left) * max(0.0, bottom - top)
    union = first["w"] * first["h"] + second["w"] * second["h"] - intersection
    return 0.0 if union <= 0 else intersection / union


def run(model_path: str, frames, args, *, swap_rb: bool) -> list:
    """Run the ONNX graph over every frame and collect the reference boxes.

    `swap_rb` tells the blob builder which order the caller's frames are in:
    OpenCV decodes video as BGR, while the raw `.rgb` files are already RGB.
    """
    import cv2
    import numpy as np

    net = cv2.dnn.readNetFromONNX(model_path)
    entries = []
    for index, frame in enumerate(frames, start=1):
        height, width = frame.shape[:2]
        canvas, scale, pad_x, pad_y = letterbox(frame, args.imgsz)
        blob = cv2.dnn.blobFromImage(
            canvas,
            1 / 255.0,
            (args.imgsz, args.imgsz),
            swapRB=swap_rb,
            crop=False,
        )
        net.setInput(blob)
        output = net.forward().astype(np.float32)
        boxes = decode(
            output, args.conf, args.iou, (canvas, scale, pad_x, pad_y), width, height
        )
        entries.append({"frame": frame_name(index, width, height), "boxes": boxes})
    return entries


def main() -> int:
    args = parse_args()
    output = Path(args.output)
    frames_dir = output / "frames"
    frames_dir.mkdir(parents=True, exist_ok=True)

    from_video = args.video is not None
    if from_video:
        raw_frames = sample_frames(Path(args.video), args.count)
    else:
        raw_frames = load_rgb_frames(Path(args.frames))

    # Video frames arrive BGR (needs swapRB), reused `.rgb` files are already
    # RGB. Getting this backwards would shift every colour channel and turn a
    # working detector into a broken one for reasons unrelated to the port.
    entries = run(args.model, raw_frames, args, swap_rb=from_video)

    # Frames are written in the layout the Rust harness reads: raw RGB888, name
    # carrying the size. Written after inference so a failed run leaves no
    # half-set behind.
    import numpy as np

    for entry, frame in zip(entries, raw_frames):
        rgb = frame[:, :, ::-1] if from_video else frame
        rgb.astype(np.uint8).tofile(frames_dir / entry["frame"])

    (output / "python.json").write_text(json.dumps(entries, indent=2), encoding="utf-8")
    total = sum(len(entry["boxes"]) for entry in entries)
    print(f"wrote {total} person boxes over {len(entries)} frames to {output}")
    print(
        "compare with: "
        f'CCTV_PARITY_MODEL="{args.model}" CCTV_PARITY_DIR="{output}" '
        f"CCTV_PARITY_THRESHOLD={args.conf} cargo test -p ai-engine --test parity_with_python -- --nocapture"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
