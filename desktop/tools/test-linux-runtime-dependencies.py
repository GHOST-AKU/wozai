#!/usr/bin/env python3
"""Real ELF/symbol regressions for generated Debian package dependencies."""
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import unittest

TOOL = Path(__file__).with_name('linux-runtime-dependencies.py')


class RuntimeDependenciesTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='nearbyim-dependency-tests-')
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.image = self.root / 'program with spaces 我在'
        self.library(self.image / 'bin/NearbyIM', '#include <stdio.h>\nint launcher(void) { return puts("test"); }')
        self.library(self.image / 'lib/app/libwozai_bluetooth.so', 'int bridge(void) { return 0; }')

    def library(self, output, source, extra=()):
        output.parent.mkdir(parents=True, exist_ok=True)
        subprocess.run(['cc', '-shared', '-fPIC', '-x', 'c', '-', '-o', str(output), *extra],
                       input=source, text=True, capture_output=True, check=True)

    def invoke(self):
        return subprocess.run([sys.executable, str(TOOL), str(self.image)], text=True, capture_output=True)

    def new_runtime_library(self, path):
        # statx requires GLIBC_2.28, while the launcher's puts needs GLIBC_2.2.5.
        self.library(path, '#define _GNU_SOURCE\n#include <sys/stat.h>\n#include <fcntl.h>\n'
                     'int runtime_probe(void) { struct statx result; return statx(AT_FDCWD, ".", 0, STATX_BASIC_STATS, &result); }')

    def test_newer_bundled_jvm_glibc_is_a_package_dependency(self):
        self.new_runtime_library(self.image / 'lib/runtime/lib/server/libjvm.so')
        result = self.invoke()
        self.assertEqual(result.returncode, 0, result.stderr)
        version = re.search(r'libc6 \(>= ([^)]+)\)', result.stdout).group(1)
        self.assertEqual(subprocess.run(['dpkg', '--compare-versions', version, 'ge', '2.28']).returncode, 0,
                         'The runtime requires GLIBC_2.28 but apt would allow an older libc6: ' + result.stdout)
        self.assertIn('GLIBC_2.28', (self.image / 'runtime-requirements.txt').read_text())

    def test_private_jvm_libraries_resolve_inside_the_image(self):
        runtime = self.image / 'lib/runtime/lib'
        self.new_runtime_library(runtime / 'libjli.so')
        self.library(runtime / 'libjava.so', 'extern int runtime_probe(void); int java_probe(void) { return runtime_probe(); }',
                     ('-L' + str(runtime), '-ljli', '-Wl,-rpath,$ORIGIN'))
        result = self.invoke()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('libc6 (>= 2.28)', result.stdout)
        self.assertNotIn('libjli', result.stdout) # Bundled private libraries are not apt packages.

    def test_missing_external_library_fails_instead_of_omitting_its_dependency(self):
        external = self.root / 'external'
        self.library(external / 'libmissing.so.1', 'int missing(void) { return 1; }', ('-Wl,-soname,libmissing.so.1',))
        self.library(self.image / 'lib/runtime/lib/libjvm.so', 'extern int missing(void); int jvm(void) { return missing(); }',
                     ('-L' + str(external), '-l:libmissing.so.1'))
        result = self.invoke()
        self.assertNotEqual(result.returncode, 0, 'A missing runtime dependency was silently ignored')
        self.assertIn('libmissing.so.1', result.stderr)

    def versioned_library(self, version, required=True):
        private = self.image / 'lib/runtime/lib'
        private.mkdir(parents=True, exist_ok=True)
        script = self.root / 'versions.map'
        script.write_text(version + ' { global: runtime_probe; local: *; };\n')
        self.library(private / 'libprobe.so', 'int runtime_probe(void) { return 1; }',
                     ('-Wl,--version-script=' + str(script),))
        if required:
            self.library(private / 'libjvm.so', 'extern int runtime_probe(void); int jvm(void) { return runtime_probe(); }',
                         ('-L' + str(private), '-lprobe', '-Wl,-rpath,$ORIGIN'))

    def test_newer_required_abis_fail_before_a_package_can_be_shipped(self):
        for version in ('GLIBC_2.36', 'GLIBCXX_3.4.32', 'CXXABI_1.3.14', 'GLIBC_ABI_DT_RELR'):
            with self.subTest(version=version):
                self.versioned_library(version)
                result = self.invoke()
                self.assertNotEqual(result.returncode, 0, 'Ubuntu 22.04 cannot provide ' + version)
                self.assertIn('Ubuntu 22.04', result.stderr)
                self.assertIn('lib/runtime/lib/libjvm.so', result.stderr)
                self.assertIn(version, result.stderr)

    def test_exported_version_definitions_are_not_system_requirements(self):
        self.versioned_library('GLIBCXX_99.0', required=False)
        result = self.invoke()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertNotIn('GLIBCXX_99.0', (self.image / 'runtime-requirements.txt').read_text())

    def test_baseline_boundary_versions_are_accepted(self):
        for version in ('GLIBC_2.35', 'GLIBCXX_3.4.30', 'CXXABI_1.3.13'):
            with self.subTest(version=version):
                self.versioned_library(version)
                result = self.invoke()
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertIn(version, (self.image / 'runtime-requirements.txt').read_text())


if __name__ == '__main__':
    unittest.main()
