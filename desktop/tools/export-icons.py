#!/usr/bin/env python3
"""Regenerate desktop toolbar icons from the Material Android vectors.
Developer-only dependency: CairoSVG. Packaged builds use saved assets.
"""
from pathlib import Path
import xml.etree.ElementTree as ET
import cairosvg

root = Path(__file__).resolve().parents[2]
output = root / 'desktop/src/main/resources/dev/ghost/wozai/icons'
output.mkdir(parents=True, exist_ok=True)
android = '{http://schemas.android.com/apk/res/android}'
for name in ('chat_bubble', 'wifi_tethering', 'settings', 'search', 'bluetooth', 'add', 'more_vert', 'attach_file', 'send', 'emoji_emotions', 'photo', 'description', 'file_download', 'zoom_in', 'zoom_out', 'close'):
    vector = ET.parse(root / f'app/src/main/res/drawable/outline_{name}_24.xml').getroot()
    paths = ''.join('<path fill="#ffffff" d="' + path.attrib[android + 'pathData'] + '"/>' for path in vector)
    svg = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">' + paths + '</svg>'
    cairosvg.svg2png(bytestring=svg.encode(), write_to=str(output / (name + '.png')), output_width=96, output_height=96)
