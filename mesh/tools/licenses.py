#!/usr/bin/env python3
"""Collect or check notices for the exact Cargo lock graph, including target-only crates."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tarfile
import tomllib


ROOT = Path(__file__).resolve().parents[1]
NOTICE_PREFIXES = ('license', 'copying', 'notice', 'copyright', 'authors')


def notice_files(folder):
    return sorted(p for p in folder.iterdir()
                  if p.is_file() and p.name.lower().startswith(NOTICE_PREFIXES))


def archive_notices(package, checksum):
    """Check Cargo's extracted cache against its checksum-bound crate archive."""
    folder = Path(package['manifest_path']).parent
    archive = folder.parents[2] / 'cache' / folder.parent.name / (folder.name + '.crate')
    if hashlib.sha256(archive.read_bytes()).hexdigest() != checksum:
        raise ValueError(f'archive checksum differs from Cargo.lock: {archive}')
    originals = {}
    with tarfile.open(archive) as source:
        for member in source.getmembers():
            parts = member.name.split('/')
            if (member.isfile() and len(parts) == 2 and parts[0] == folder.name
                    and parts[1].lower().startswith(NOTICE_PREFIXES)):
                originals[parts[1]] = source.extractfile(member).read()
    cached = {p.name: p for p in notice_files(folder)}
    if cached.keys() != originals.keys():
        raise ValueError(f'cached notice list differs from archive: {folder}')
    for name, path in cached.items():
        if path.read_bytes() != originals[name]:
            raise ValueError(f'cached notice differs from archive: {path}')
    return cached


def manual_notices(target, packages, repository):
    """Bind existing supplements to their locked upstream project texts, without fetching URLs."""
    provenance = target / 'SOURCE.json'
    if not provenance.is_file():
        raise ValueError(f'missing SOURCE.json for manually supplied notices: {target}')
    urls = json.loads(provenance.read_text()).get('urls', [])
    if not isinstance(urls, list) or not all(isinstance(url, str) for url in urls):
        raise ValueError(f'invalid SOURCE.json URLs: {provenance}')
    originals = {}
    for notice in notice_files(target):
        for package in packages:
            upstream_repository = (package.get('repository') or '').rstrip('/')
            if upstream_repository != (repository or '').rstrip('/') or not upstream_repository.startswith('https://github.com/'):
                continue
            project = upstream_repository.removeprefix('https://github.com/')
            expected_urls = [f'https://raw.githubusercontent.com/{project}/{tag}/{notice.name}'
                             for tag in (package['version'], 'v' + package['version'])]
            source = Path(package['manifest_path']).parent / notice.name
            if any(url in urls for url in expected_urls) and source.is_file():
                originals[notice.name] = source
                break
        if notice.name not in originals:
            raise ValueError(f'cannot verify manual notice from locked upstream: {notice}')
    if not originals:
        raise ValueError(f'missing notice texts: {target}')
    return originals


def inventory(root, metadata):
    packages = [p for p in metadata['packages'] if p['source']]
    key = lambda p: (p['name'], p['version'], p['source'])
    locked = [p for p in tomllib.loads((root / 'Cargo.lock').read_text())['package'] if p.get('source')]
    if len({key(p) for p in packages}) != len(packages) or {key(p) for p in packages} != {key(p) for p in locked}:
        raise ValueError('metadata package/source graph differs from Cargo.lock')
    checksums = {key(p): p['checksum'] for p in locked}
    upstreams = {}
    for package in packages:
        if package['source'] != 'registry+https://github.com/rust-lang/crates.io-index':
            raise ValueError(f'unsupported dependency source; review notice provenance: {package["source"]}')
        upstreams[key(package)] = archive_notices(package, checksums[key(package)])
    records = []
    originals = {}
    for package in sorted(packages, key=lambda p: (p['name'], p['version'])):
        target = root / 'vendor-licenses' / f"{package['name']}-{package['version']}"
        files = upstreams[key(package)]
        if not files:
            files = manual_notices(target, packages, package.get('repository'))
        for name, source in files.items():
            originals[target / name] = source
        records.append({
            'name': package['name'], 'version': package['version'], 'license': package['license'],
            'source': f"https://crates.io/crates/{package['name']}/{package['version']}",
            'repository': package.get('repository'),
            'notices': [str((target / name).relative_to(root)) for name in sorted(files)],
        })
    return records, originals


def markdown(records):
    rows = ['# Third-party dependency notices', '',
            'The module itself follows the repository AGPL-3.0-only license. No implementation from the mesh systems researched in docs/mesh was copied here.',
            'This generated inventory covers the locked Cargo graph, including optional target, build and test dependencies. It is not a claim that every crate is linked into the Linux executable.',
            'Original license and copyright notices are retained in `vendor-licenses/`. Dependencies keep their upstream licenses. Regenerate using `python3 tools/licenses.py` after changing Cargo.lock.', '',
            '| Crate | Version | Upstream license expression | Notices |', '| --- | --- | --- | --- |']
    for record in records:
        rows.append(f"| [{record['name']}]({record['source']}) | {record['version']} | {record['license']} | [texts](vendor-licenses/{record['name']}-{record['version']}/) |")
    return '\n'.join(rows) + '\n'


def check(root, records, originals):
    output = root / 'vendor-licenses'
    for target, source in originals.items():
        if not target.is_file():
            raise ValueError(f'missing notice: {target}')
        if target.read_bytes() != source.read_bytes():
            raise ValueError(f'notice differs from upstream: {target}')
    for target in output.rglob('*'):
        if target.is_file() and target not in originals and target != output / 'sources.json':
            if target.name != 'SOURCE.json' or target.parent not in {path.parent for path in originals}:
                raise ValueError(f'unexpected notice or package: {target}')
    if json.loads((output / 'sources.json').read_text()) != records:
        raise ValueError('sources.json inventory differs from locked metadata/notices')
    if (root / 'THIRD_PARTY.md').read_text() != markdown(records):
        raise ValueError('THIRD_PARTY.md differs from locked metadata/notices')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--metadata', type=Path,
                        help='Existing cargo metadata JSON; otherwise run cargo metadata --locked --offline')
    parser.add_argument('--check', action='store_true', help='Check existing notices without writing files or fetching licenses')
    args = parser.parse_args()
    try:
        metadata = json.loads(args.metadata.read_text() if args.metadata else subprocess.check_output([
            'cargo', 'metadata', '--manifest-path', str(ROOT / 'Cargo.toml'), '--locked', '--offline',
            '--format-version', '1'], text=True))
        records, originals = inventory(ROOT, metadata)
        if args.check:
            check(ROOT, records, originals)
        else:
            for target, source in originals.items():
                target.parent.mkdir(parents=True, exist_ok=True)
                if source != target:
                    shutil.copyfile(source, target)
            (ROOT / 'vendor-licenses/sources.json').write_text(json.dumps(records, indent=2) + '\n')
            (ROOT / 'THIRD_PARTY.md').write_text(markdown(records))
    except (ValueError, KeyError, OSError, tarfile.TarError, subprocess.CalledProcessError) as error:
        parser.exit(1, f'Notice check failed: {error}\n')
    print(f"{'Checked' if args.check else 'Preserved'} license notices for {len(records)} locked dependency packages")


if __name__ == '__main__':
    main()
