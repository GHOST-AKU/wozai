#!/usr/bin/env python3
"""Guard literal message keys and prevent Chinese prose returning to Java UI code."""
import json
from pathlib import Path
import re

root = Path(__file__).resolve().parents[1]
keys = set(json.loads((root / 'i18n/messages/en.json').read_text(encoding='utf-8')))
sources = list((root / 'app/src/main/java').rglob('*.java')) + list((root / 'desktop/src/main/java').rglob('*.java'))
errors = []
checked = 0
for path in sorted(sources):
    if path.name in ('I18nResources.java', 'LanguageRegistry.java'):
        continue
    text = path.read_text(encoding='utf-8')
    # Old on-disk Chinese states must remain recognizable during schema migration.
    for token in re.finditer(r'//[^\n]*|/\*.*?\*/|(?P<literal>"(?:[^"\\]|\\.)*")', text, re.DOTALL):
        if token.group('literal') is None:
            continue
        literal = token
        if re.search(r'[\u3400-\u9fff]', literal.group()) and not (
                path.name == 'StoreSchema.java' and 'UPDATE messages SET state=' in literal.group()):
            line = text.count('\n', 0, literal.start()) + 1
            errors.append(f'{path.relative_to(root)}:{line}: hardcoded Chinese string {literal.group()[:70]}')
    patterns = (r'UiText\.of\(\s*"([A-Za-z][A-Za-z0-9]*)"',
                r'\b(?:strings\.text|AndroidText\.get|t)\(\s*"([A-Za-z][A-Za-z0-9]*)"')
    for pattern in patterns:
        for match in re.finditer(pattern, text):
            checked += 1
            if match.group(1) not in keys:
                line = text.count('\n', 0, match.start()) + 1
                errors.append(f'{path.relative_to(root)}:{line}: missing catalog key {match.group(1)}')
for error in errors:
    print(error)
print(f'i18n source: {checked} literal message references, {len(errors)} errors')
raise SystemExit(bool(errors))
