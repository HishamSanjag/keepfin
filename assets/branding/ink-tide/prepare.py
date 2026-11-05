"""Package the approved original artwork without generating or redrawing it.

Requires Pillow. Run this file from any directory to reproduce the PNG assets.
"""
from collections import deque
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent
source = Image.open(ROOT / 'source/approved-concept-sheet.png').convert('RGB')
original = source.crop((0, 0, 620, 615))
original.save(ROOT / 'keepfin-original.png')

# Remove only edge-connected near-black background. Never threshold the whole
# illustration: enclosed dark ink, ivory texture and cobalt pixels stay intact.
width, height = original.size
pixels = original.load()
outside = Image.new('L', original.size)
mask = outside.load()
pending = deque()
for x in range(width):
    pending.extend(((x, 0), (x, height - 1)))
for y in range(height):
    pending.extend(((0, y), (width - 1, y)))
while pending:
    x, y = pending.popleft()
    if x < 0 or y < 0 or x >= width or y >= height or mask[x, y]:
        continue
    if max(pixels[x, y]) > 24:
        continue
    mask[x, y] = 255
    pending.extend(((x - 1, y), (x + 1, y), (x, y - 1), (x, y + 1)))
alpha = outside.point(lambda value: 255 - value)
art = original.convert('RGBA')
art.putalpha(alpha)

# A cut through the existing empty space between wave and lettering preserves
# the original diagonal brush tips; a straight rectangular cut would clip them.
boundary = [(0, 420), (140, 420), (170, 425), (210, 425),
            (220, 438), (270, 460), (310, 461), (360, 452),
            (420, 443), (480, 435), (535, 446), (552, 448),
            (558, 410), (620, 405)]
region = Image.new('L', art.size)
ImageDraw.Draw(region).polygon([(0, 0), (620, 0)] + boundary[::-1], fill=255)
# The final cobalt wave stroke curls above the K. Assign those original blue
# pixels to the emblem even where they dip across the separating curve.
for y in range(390, 475):
    for x in range(width):
        red, green, blue = pixels[x, y]
        if blue > red * 1.8 and blue > 70:
            region.putpixel((x, y), 255)
emblem = art.copy()
wordmark = art.copy()
emblem.putalpha(Image.composite(alpha, Image.new('L', art.size), region))
wordmark.putalpha(Image.composite(Image.new('L', art.size), alpha, region))

def ink(image):
    """The lettering for light backgrounds: ivory turns to ink, the cobalt stroke stays cobalt (each channel follows blue minus red)."""
    result = image.copy()
    data = result.load()
    for y in range(result.height):
        for x in range(result.width):
            red, green, blue, opacity = data[x, y]
            cobalt = (blue - red) / 255
            data[x, y] = tuple(max(0, min(255, round(255 * (base + gain * cobalt)))) for base, gain in ((.07, .1), (.09, .33), (.13, .95))) + (opacity,)
    return result

art_light = Image.alpha_composite(emblem, ink(wordmark))
emblem = emblem.crop(emblem.getbbox())
wordmark = wordmark.crop(wordmark.getbbox())

def padded(image, padding):
    canvas = Image.new('RGBA', (image.width + 2 * padding, image.height + 2 * padding))
    canvas.alpha_composite(image, (padding, padding))
    return canvas

stacked = padded(art, 40)
emblem_padded = padded(emblem, 40)
wordmark_padded = padded(wordmark, 32)
horizontal = Image.new('RGBA', (emblem.width + wordmark.width + 128, max(emblem.height, wordmark.height) + 80))
horizontal.alpha_composite(emblem, (40, (horizontal.height - emblem.height) // 2))
horizontal.alpha_composite(wordmark, (emblem.width + 88, (horizontal.height - wordmark.height) // 2))

def dark_version(image):
    background = Image.new('RGBA', image.size, '#08090b')
    background.alpha_composite(image)
    return background.convert('RGB')

for name, image in [('stacked', stacked), ('horizontal', horizontal),
                    ('emblem', emblem_padded), ('wordmark', wordmark_padded)]:
    image.save(ROOT / f'keepfin-{name}-transparent.png')
    dark_version(image).save(ROOT / f'keepfin-{name}-dark.png')

# The transparent logos for light backgrounds: the emblem as it is, the lettering in ink.
padded(art_light, 40).save(ROOT / 'keepfin-stacked-light.png')
ink(wordmark_padded).save(ROOT / 'keepfin-wordmark-light.png')

avatar_size = max(emblem.size) + 100
avatar = Image.new('RGBA', (avatar_size, avatar_size))
avatar.alpha_composite(emblem, ((avatar_size - emblem.width) // 2, (avatar_size - emblem.height) // 2))
dark_version(avatar).save(ROOT / 'keepfin-avatar-dark.png')

# Contact sheet for visual inspection only; asset files remain at native size.
preview = Image.new('RGB', (1500, 1160), '#08090b')
draw = ImageDraw.Draw(preview)
try:
    font = ImageFont.truetype('C:/Windows/Fonts/segoeui.ttf', 22)
except OSError:
    font = ImageFont.load_default()
panels = [('STACKED', stacked, (30, 20, 720, 700)),
          ('EMBLEM / AVATAR', avatar, (770, 20, 1460, 700)),
          ('HORIZONTAL', horizontal, (30, 735, 970, 1120)),
          ('WORDMARK', wordmark_padded, (1030, 735, 1460, 1120))]
for title, image, (left, top, right, bottom) in panels:
    draw.text((left, top), title, fill='#a5adbd', font=font)
    sample = image.copy()
    sample.thumbnail((right - left, bottom - top - 40), Image.Resampling.LANCZOS)
    x = left + (right - left - sample.width) // 2
    y = top + 40 + (bottom - top - 40 - sample.height) // 2
    preview.paste(sample, (x, y), sample)
preview.save(ROOT / 'keepfin-ink-tide-preview.png')

# Verify retained source pixels have never been recolored or regenerated.
assert art.convert('RGB').tobytes() == original.tobytes()
for name in ['stacked', 'horizontal', 'emblem', 'wordmark']:
    image = Image.open(ROOT / f'keepfin-{name}-transparent.png')
    assert image.mode == 'RGBA' and image.getextrema()[3][0] == 0
print('Saved original + 4 transparent layouts + 4 dark layouts + avatar + preview.')
print('Source RGB pixels preserved; transparent exports verified.')
