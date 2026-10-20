"""Exports the Robolectric screenshots (UiShots* tests) to docs/images: selection, cropping, 420 px, 256 colors.

Usage:
  ./gradlew :app:testDebugUnitTest --tests '*UiShots*' --tests '*UiScreenshotTest*' -PuiShots=/tmp/captures
  python3 -m venv /tmp/imgvenv && /tmp/imgvenv/bin/pip install pillow
  /tmp/imgvenv/bin/python -I tools/export-screenshots.py /tmp/captures docs/images [name,name...]
  (optional third argument: export only these public names)

The screenshots use the test reference map and fictitious data (robot Luba-VS-DEMO, simulated signals).
"""
import sys, os
from PIL import Image, ImageChops
src, dst = sys.argv[1], sys.argv[2]
os.makedirs(dst, exist_ok=True)
# public name: (screenshot, processing); top = keep the top of a long screen, trim = crop to the content
SEL = {
 'home': ('home', ''), 'home-dark': ('home-dark', ''), 'me': ('me', ''),
 'map': ('carte-map', ''), 'map-dark': ('carte-map-dark', ''), 'map-layers': ('carte-layers', ''),
 'map-settings': ('carte-reglages', ''), 'map-obstacles': ('carte-obstacles-trajet', ''),
 'map-status': ('carte-status', ''), 'zones': ('carte-zones-selected', ''), 'task-settings': ('carte-task', ''),
 'survey-settings': ('carte-task-survey', ''), 'route-preview': ('carte-preview', ''), 'animated-trace': ('atout-trace-anime', 'trim'),
 'simulation': ('carte-simulation', ''), 'mowing-in-progress': ('carte-route-running', ''), 'resume': ('carte-checkpoint', ''),
 'station': ('carte-station', ''), 'move-to-point': ('carte-point', ''), 'robot-locked': ('carte-locked', ''),
 'heatmap-wifi': ('atout-heatmap-wifi', 'trim'), 'heatmap-mobile': ('atout-heatmap-mobile', 'trim'),
 'heatmap-rtk': ('atout-heatmap-rtk', 'trim'), 'terrain-3d': ('relief-3d', ''), 'terrain-shading': ('relief-heatmap', ''),
 'manual-control': ('manual', ''), 'record-by-driving': ('service-pilotage-enregistrement', ''), 'service': ('service-hub', 'top'), 'blades': ('blades-height', 'top'),
 'painted-zone': ('atout-zone-peinte', 'trim'), 'debug-mowing-paint': ('mowdebug-paint', 'top'),
 'border': ('mowdebug-border', 'top'), 'obstacles': ('obstacles-list', 'top'), 'obstacle-simulation': ('obstacle-sim', 'top'),
 'command-cutoff': ('service-coupure', 'top'), 'notifications': ('notifications', ''), 'notification-settings': ('notifications-settings', ''), 'weather': ('weather-service', ''),
 'garden-location': ('garden-location', ''), 'account': ('account-robot', ''), 'gateway-pilot': ('service-passerelle-pilote', 'top'),
 'gateway-robot': ('service-passerelle-passerelle', 'top'), 'gateway-home': ('passerelle-accueil', 'top'),
 'setup-wizard': ('assistant-accueil', 'top'), 'first-mowing': ('accueil-premiere-tonte', 'trim'), 'connection': ('service-connexion-recherche', ''),
 'logs': ('service-journaux', ''), 'diagnostics': ('service-diagnostic', 'top'),
}
only = set(sys.argv[3].split(',')) if len(sys.argv) > 3 else None
W, H = 1236, 2745
total = 0
for public, (name, mode) in SEL.items():
    if only is not None and public not in only:
        continue
    im = Image.open(os.path.join(src, name + '.png')).convert('RGB')
    if mode == 'top' and im.height > H:
        im = im.crop((0, 0, im.width, H))
    if mode == 'trim':
        bg = Image.new('RGB', im.size, im.getpixel((im.width - 1, im.height - 1)))
        box = ImageChops.difference(im, bg).point(lambda v: 255 if v > 12 else 0).getbbox()
        if box:
            m = 40
            box = (max(0, box[0] - m), max(0, box[1] - m), min(im.width, box[2] + m), min(im.height, box[3] + m))
            im = im.crop(box)
    im.thumbnail((420, 2000), Image.LANCZOS)
    out = os.path.join(dst, public + '.png')
    im.quantize(colors=256, method=Image.Quantize.MEDIANCUT, dither=Image.Dither.NONE).save(out, optimize=True)
    total += os.path.getsize(out)
    print(f'{public}.png {im.size} {os.path.getsize(out)//1024} KB')
print('total', total // 1024, 'KB')
