import subprocess
import sys
from PIL import Image
import io

# Run adb screencap and capture binary
out = subprocess.run(["adb", "exec-out", "screencap", "-p"], capture_output=True, check=True)
print(f"Got {len(out.stdout)} bytes from adb")
with open(r"C:\Users\Nokel\Documents\AI_crap\chatterbox-AI\sumatra-android\shot2.png", "wb") as f:
    f.write(out.stdout)

img = Image.open(io.BytesIO(out.stdout))
print(f"Size: {img.size}, mode: {img.mode}")
# Sample colors at known points
for y in [20, 60, 100, 200, 500, 1000, 1158, 1500, 2000, 2200, 2280]:
    if y < img.height:
        samples = [img.getpixel((img.width // 4, y)), img.getpixel((img.width // 2, y)), img.getpixel((3 * img.width // 4, y))]
        print(f"y={y}: {samples}")

# Check if the page area is mostly white (rendered PDF) or grey (empty)
import statistics
pixels = []
for x in range(0, img.width, 50):
    for y in range(400, 1500, 50):
        px = img.getpixel((x, y))
        if isinstance(px, tuple):
            pixels.append(sum(px[:3]) / 3)
        else:
            pixels.append(px)
print(f"\nMiddle region (y=400-1500) brightness stats:")
print(f"  mean: {statistics.mean(pixels):.0f}, stdev: {statistics.stdev(pixels):.0f}, min: {min(pixels):.0f}, max: {max(pixels):.0f}")
print(f"  pixels with brightness > 200: {sum(1 for p in pixels if p > 200)} / {len(pixels)}")
