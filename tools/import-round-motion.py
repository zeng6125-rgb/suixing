"""Import the delivered motion/round GIFs without redrawing or masking their artwork.

Usage: python tools/import-round-motion.py [delivered-icon-directory]
Only application-owned frame resources are replaced; the delivered originals stay intact.
"""
from pathlib import Path
import re
import sys

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
SOURCE = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else ROOT / 'dist/jagent-icon-final-A'
DESTINATION = (ROOT / 'app/src/main/assets/ball_motion').resolve()
MODES = {
    'blink': '01-blink.gif',
    'head': '02-head-turn.gif',
    'roll': '03-eye-roll.gif',
    'sparks': '04-sparks-in.gif',
    'signal': '05-signal-blink.gif',
}
EDGE = 192


def import_motion(mode: str, filename: str) -> list[tuple[Image.Image, int]]:
    destination = (DESTINATION / mode).resolve()
    if destination.parent != DESTINATION:
        raise ValueError('Frame destination is outside the application motion directory')
    frames: list[tuple[Image.Image, int]] = []
    previous_pixels = None
    with Image.open(SOURCE / 'motion/round' / filename) as source:
        for index in range(source.n_frames):
            source.seek(index)
            frame = source.convert('RGBA').resize((EDGE, EDGE), Image.Resampling.LANCZOS)
            duration = int(source.info.get('duration', 30))
            pixels = frame.tobytes()
            if pixels == previous_pixels:
                previous, previous_duration = frames[-1]
                frames[-1] = (previous, previous_duration + duration)
            else:
                frames.append((frame, duration))
                previous_pixels = pixels
    destination.mkdir(parents=True, exist_ok=True)
    # These are generated application resources, not files in the delivered reference set.
    for previous in destination.iterdir():
        if previous.is_file() and re.fullmatch(r'\d+_\d+\.(?:png|webp)', previous.name):
            if previous.resolve().parent != destination:
                raise ValueError('Unexpected frame destination')
            previous.unlink()
    for index, (frame, duration) in enumerate(frames):
        frame.save(destination / f'{index:03d}_{duration}.webp', 'WEBP', lossless=True, method=4)
    print(f'{mode}: {len(frames)} frames, {sum(duration for _, duration in frames)} ms, {EDGE}px')
    return frames


def main() -> None:
    sys.stdout.reconfigure(encoding='utf-8')
    imported = {mode: import_motion(mode, filename) for mode, filename in MODES.items()}
    # Match the animation's canvas so switching from a still to a loop never changes its scale.
    with Image.open(SOURCE / 'motion/round/01-blink.gif') as blink:
        blink.convert('RGBA').resize((384, 384), Image.Resampling.LANCZOS).save(
            ROOT / 'app/src/main/res/drawable-xxxhdpi/brand_mark_round.png')
    preview = Image.new('RGB', (EDGE * 4, (EDGE + 24) * len(imported)), '#EAE5DF')
    labels = ImageDraw.Draw(preview)
    for row, (mode, frames) in enumerate(imported.items()):
        labels.text((8, row * (EDGE + 24) + 4), mode, fill='#302820')
        for column in range(4):
            frame = frames[min(len(frames) - 1, column * len(frames) // 4)][0]
            preview.paste(frame, (column * EDGE, row * (EDGE + 24) + 24), frame)
    preview_path = ROOT / 'verify-out/round-motion-import.png'
    preview_path.parent.mkdir(exist_ok=True)
    preview.save(preview_path)
    print(f'Artwork preview: {preview_path}')


if __name__ == '__main__':
    main()
