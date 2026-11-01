"""Exercise repository path routing without requiring a YAML dependency in CI."""
import ast
import fnmatch
from pathlib import Path
import unittest


WORKFLOWS = Path(__file__).resolve().parents[2] / '.github/workflows'


def path_filters(name):
    """Read only the quoted block/inline path lists used by these workflows."""
    filters = {}
    in_pull_request = False
    active = None
    for line in (WORKFLOWS / name).read_text().splitlines():
        if line == '  pull_request:':
            in_pull_request = True
            continue
        if not in_pull_request or not line.strip() or line.lstrip().startswith('#'):
            continue
        indent = len(line) - len(line.lstrip())
        if indent <= 2:
            break
        if indent == 4:
            key, _, value = line.strip().partition(':')
            active = key if key in ('paths', 'paths-ignore') else None
            if active:
                filters[active] = ast.literal_eval(value.strip()) if value.strip() else []
        elif active and line.startswith('      - '):
            filters[active].append(ast.literal_eval(line.strip()[2:]))
    if not filters or any(not isinstance(pattern, str) for values in filters.values() for pattern in values):
        raise AssertionError(f'unsupported path filters in {name}')
    return filters


def routes(name, changed):
    filters = path_filters(name)
    matches = lambda path, patterns: any(fnmatch.fnmatchcase(path, pattern) for pattern in patterns)
    if 'paths' in filters:
        return any(matches(path, filters['paths']) for path in changed)
    return any(not matches(path, filters['paths-ignore']) for path in changed)


class WorkflowRoutingTests(unittest.TestCase):
    def test_mesh_only_changes_skip_existing_client_packages(self):
        changed = ['mesh/src/core.rs', 'mesh/android-probe/src/Probe.java',
                   'docs/mesh/validation.md', '.github/workflows/mesh.yml']
        for name in ['android.yml', 'linux.yml', 'windows.yml']:
            with self.subTest(workflow=name):
                self.assertFalse(routes(name, changed))

    def test_mixed_client_changes_still_run_existing_clients(self):
        for name in ['android.yml', 'linux.yml', 'windows.yml']:
            for path in ['app/src/main/java/Example.java', 'desktop/src/Example.java',
                         f'.github/workflows/{name}']:
                with self.subTest(workflow=name, path=path):
                    self.assertTrue(routes(name, ['mesh/src/core.rs', path]))

    def test_mesh_workflow_covers_its_sources_tools_probe_and_docs(self):
        for path in ['mesh/Cargo.lock', 'mesh/src/main.rs', 'mesh/tools/licenses.py',
                     'mesh/android-probe/AndroidManifest.xml', 'docs/mesh/protocol.md',
                     '.github/workflows/mesh.yml']:
            with self.subTest(path=path):
                self.assertTrue(routes('mesh.yml', [path]))

    def test_unrelated_client_changes_do_not_run_mesh(self):
        self.assertFalse(routes('mesh.yml', ['app/src/main/java/Example.java']))

    def test_mesh_build_configuration_changes_run_mesh(self):
        for path in ['.cargo/config', '.cargo/config.toml', 'mesh/.cargo/config.toml',
                     'rust-toolchain', 'rust-toolchain.toml']:
            with self.subTest(path=path):
                self.assertTrue(routes('mesh.yml', [path]))


if __name__ == '__main__':
    unittest.main()
