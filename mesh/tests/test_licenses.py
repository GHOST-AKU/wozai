"""Notice checks must reject drift without repairing or deleting audit evidence."""
import json
import hashlib
from pathlib import Path
import shutil
import subprocess
import sys
import tarfile
import tempfile
import unittest


SOURCE = Path(__file__).resolve().parents[1] / 'tools/licenses.py'
HEADER = '''# Third-party dependency notices

The module itself follows the repository AGPL-3.0-only license. No implementation from the mesh systems researched in docs/mesh was copied here.
This generated inventory covers the locked Cargo graph, including optional target, build and test dependencies. It is not a claim that every crate is linked into the Linux executable.
Original license and copyright notices are retained in `vendor-licenses/`. Dependencies keep their upstream licenses. Regenerate using `python3 tools/licenses.py` after changing Cargo.lock.

| Crate | Version | Upstream license expression | Notices |
| --- | --- | --- | --- |
'''


class LicenseCheckTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name) / 'mesh'
        (self.root / 'tools').mkdir(parents=True)
        shutil.copyfile(SOURCE, self.root / 'tools/licenses.py')
        self.upstream = Path(self.temp.name) / 'registry/src/fixture-index/example-1.0.0'
        self.upstream.mkdir(parents=True)
        (self.upstream / 'Cargo.toml').write_text('[package]\nname="example"\nversion="1.0.0"\n')
        (self.upstream / 'LICENSE-MIT').write_text('Original example permission and copyright.\n')
        self.target = self.root / 'vendor-licenses/example-1.0.0'
        self.target.mkdir(parents=True)
        shutil.copyfile(self.upstream / 'LICENSE-MIT', self.target / 'LICENSE-MIT')
        self.checksum = self.archive(self.upstream)
        self.metadata = {'packages': [{
            'name': 'example', 'version': '1.0.0', 'license': 'MIT',
            'source': 'registry+https://github.com/rust-lang/crates.io-index',
            'repository': 'https://github.com/example/example',
            'manifest_path': str(self.upstream / 'Cargo.toml'),
        }]}
        self.records = [{
            'name': 'example', 'version': '1.0.0', 'license': 'MIT',
            'source': 'https://crates.io/crates/example/1.0.0',
            'repository': 'https://github.com/example/example',
            'notices': ['vendor-licenses/example-1.0.0/LICENSE-MIT'],
        }]
        (self.root / 'Cargo.lock').write_text('''version = 4

[[package]]
name = "example"
version = "1.0.0"
source = "registry+https://github.com/rust-lang/crates.io-index"
checksum = "CHECKSUM"
'''.replace('CHECKSUM', self.checksum))
        (self.root / 'THIRD_PARTY.md').write_text(
            HEADER + '| [example](https://crates.io/crates/example/1.0.0) | 1.0.0 | MIT | [texts](vendor-licenses/example-1.0.0/) |\n')
        self.write_inputs()

    def archive(self, folder):
        path = folder.parents[2] / 'cache' / folder.parent.name / (folder.name + '.crate')
        path.parent.mkdir(parents=True, exist_ok=True)
        with tarfile.open(path, 'w:gz') as archive:
            archive.add(folder, arcname=folder.name)
        return hashlib.sha256(path.read_bytes()).hexdigest()

    def write_inputs(self):
        self.metadata_path = Path(self.temp.name) / 'metadata.json'
        self.metadata_path.write_text(json.dumps(self.metadata))
        (self.root / 'vendor-licenses/sources.json').write_text(json.dumps(self.records, indent=2) + '\n')

    def snapshot(self):
        root = Path(self.temp.name)
        return {str(p.relative_to(root)): p.read_bytes()
                for p in root.rglob('*') if p.is_file()}

    def check(self, diagnostic=None):
        before = self.snapshot()
        result = subprocess.run([sys.executable, str(self.root / 'tools/licenses.py'),
                                 '--metadata', str(self.metadata_path), '--check'],
                                text=True, capture_output=True, timeout=5)
        self.assertEqual(self.snapshot(), before, '--check modified audit files')
        if diagnostic is None:
            self.assertEqual(result.returncode, 0, result.stderr)
        else:
            self.assertNotEqual(result.returncode, 0, 'invalid notices were accepted')
            self.assertIn(diagnostic, result.stderr)

    def test_current_notices_pass_without_writes(self):
        self.check()

    def test_changed_notice_is_rejected_without_repair(self):
        (self.target / 'LICENSE-MIT').write_text('Changed license without original copyright.\n')
        self.check('notice differs')

    def test_missing_notice_is_rejected_without_recreation(self):
        (self.target / 'LICENSE-MIT').unlink()
        self.check('missing notice')

    def test_extra_inventory_package_is_rejected(self):
        self.records.append(dict(self.records[0], name='unlocked'))
        self.write_inputs()
        self.check('inventory differs')

    def test_metadata_cannot_omit_a_locked_package(self):
        self.metadata['packages'].clear()
        self.write_inputs()
        self.check('Cargo.lock')

    def test_metadata_source_must_match_the_lock(self):
        self.metadata['packages'][0]['source'] = 'registry+https://example.invalid/index'
        self.write_inputs()
        self.check('Cargo.lock')

    def test_changed_license_expression_is_rejected(self):
        self.metadata['packages'][0]['license'] = 'Apache-2.0'
        self.write_inputs()
        self.check('inventory differs')

    def test_stale_markdown_inventory_is_rejected(self):
        (self.root / 'THIRD_PARTY.md').write_text(HEADER)
        self.check('THIRD_PARTY.md differs')

    def test_unlisted_extra_notice_is_rejected(self):
        (self.target / 'NOTICE').write_text('Untracked stale notice.\n')
        self.check('unexpected notice')

    def test_manual_notice_without_provenance_is_rejected(self):
        (self.upstream / 'LICENSE-MIT').unlink()
        lock = self.root / 'Cargo.lock'
        lock.write_text(lock.read_text().replace(self.checksum, self.archive(self.upstream)))
        self.check('SOURCE.json')

    def manual_fixture(self, repository='https://github.com/example/example'):
        donor = self.upstream.parent / 'example-project-0.9.0'
        donor.mkdir()
        (donor / 'Cargo.toml').write_text('[package]\nname="example-project"\nversion="0.9.0"\n')
        shutil.copyfile(self.upstream / 'LICENSE-MIT', donor / 'LICENSE-MIT')
        (self.upstream / 'LICENSE-MIT').unlink()
        donor_checksum = self.archive(donor)
        lock = self.root / 'Cargo.lock'
        lock.write_text(lock.read_text().replace(self.checksum, self.archive(self.upstream)))
        package = dict(self.metadata['packages'][0], name='example-project', version='0.9.0',
                       repository=repository, manifest_path=str(donor / 'Cargo.toml'))
        self.metadata['packages'].append(package)
        donor_target = self.root / 'vendor-licenses/example-project-0.9.0'
        donor_target.mkdir()
        shutil.copyfile(donor / 'LICENSE-MIT', donor_target / 'LICENSE-MIT')
        self.records.append(dict(self.records[0], name='example-project', version='0.9.0',
                                 source='https://crates.io/crates/example-project/0.9.0',
                                 repository=repository,
                                 notices=['vendor-licenses/example-project-0.9.0/LICENSE-MIT']))
        with (self.root / 'Cargo.lock').open('a') as lock:
            lock.write('''
[[package]]
name = "example-project"
version = "0.9.0"
source = "registry+https://github.com/rust-lang/crates.io-index"
checksum = "CHECKSUM"
'''.replace('CHECKSUM', donor_checksum))
        with (self.root / 'THIRD_PARTY.md').open('a') as document:
            document.write('| [example-project](https://crates.io/crates/example-project/0.9.0) | 0.9.0 | MIT | [texts](vendor-licenses/example-project-0.9.0/) |\n')
        project = repository.removeprefix('https://github.com/')
        (self.target / 'SOURCE.json').write_text(json.dumps({
            'urls': [f'https://raw.githubusercontent.com/{project}/0.9.0/LICENSE-MIT']}))
        self.write_inputs()

    def test_manual_notice_is_bound_to_a_locked_upstream_text(self):
        self.manual_fixture()
        self.check()

    def test_changed_manual_notice_is_rejected(self):
        self.manual_fixture()
        (self.target / 'LICENSE-MIT').write_text('Altered manual permission.\n')
        self.check('notice differs')

    def test_manual_notice_cannot_borrow_an_unrelated_projects_license(self):
        self.manual_fixture(repository='https://github.com/unrelated/project')
        self.check('cannot verify manual notice')

    def test_corrupt_archive_is_rejected_even_if_extracted_notices_match(self):
        archive = self.upstream.parents[2] / 'cache/fixture-index/example-1.0.0.crate'
        with archive.open('ab') as output:
            output.write(b'archive corruption')
        self.check('archive checksum')

    def test_changed_cached_upstream_notice_is_not_accepted_as_proof(self):
        changed = 'Replaced upstream and inventory license.\n'
        (self.upstream / 'LICENSE-MIT').write_text(changed)
        (self.target / 'LICENSE-MIT').write_text(changed)
        self.check('cached notice')


if __name__ == '__main__':
    unittest.main()
