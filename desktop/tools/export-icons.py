#!/usr/bin/env python3
"""Regenerate desktop icons from the shared app icon master and Android vectors.
Developer-only dependencies: Pillow and CairoSVG. Packaged builds use saved assets.
"""
from pathlib import Path
import xml.etree.ElementTree as ET
from PIL import Image
import cairosvg

root = Path(__file__).resolve().parents[2]
output = root / 'desktop/src/main/resources/dev/ghost/wozai/icons'
output.mkdir(parents=True, exist_ok=True)
android = '{http://schemas.android.com/apk/res/android}'
for name in ('chat_bubble', 'wifi_tethering', 'settings', 'search', 'bluetooth', 'add', 'more_vert'):
    vector = ET.parse(root / f'app/src/main/res/drawable/outline_{name}_24.xml').getroot()
    paths = ''.join('<path fill="#ffffff" d="' + path.attrib[android + 'pathData'] + '"/>' for path in vector)
    svg = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">' + paths + '</svg>'
    cairosvg.svg2png(bytestring=svg.encode(), write_to=str(output / (name + '.png')), output_width=96, output_height=96)
Image.open(root / 'desktop/assets/icons/master/icon-master-1024.png').save(
    root / 'desktop/assets/icons/windows/nearbyim.ico',
    sizes=[(16,16),(24,24),(32,32),(48,48),(64,64),(128,128),(256,256)])
