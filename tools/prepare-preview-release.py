#!/usr/bin/env python3
"""One-off publisher for the verified 0.3.1 three-platform preview.

Run inside the manually dispatched CI helper. All application bytes come from
successful builds of SOURCE_COMMIT; publication requires uploaded SHA-256 checks.
"""
import base64
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tarfile
import zipfile

REPO = os.environ.get('GH_REPO', 'GHOST-AKU/wozai')
SOURCE = os.environ.get('SOURCE_COMMIT', '26cf485a54247decd71127f98f206a895251d00b')
DESKTOP = int(os.environ.get('DESKTOP_RUN', '37112914941'))
ANDROID = int(os.environ.get('ANDROID_RUN', '37114436270'))
TAG = os.environ.get('RELEASE_TAG', 'v0.3.1-preview.1')
OUTPUT = Path('release-assets')


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def api(path):
    return json.loads(subprocess.check_output(['gh', 'api', f'repos/{REPO}/{path}'], text=True))


def digest(path):
    result = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            result.update(chunk)
    return result.hexdigest()


def provenance():
    config = api(f'contents/i18n/config.json?ref={SOURCE}')
    require(json.loads(base64.b64decode(config['content']))['appVersion'] == '0.3.1', 'Wrong application version')
    records = []
    for run_id, expected_jobs, names in [
        (DESKTOP, {'windows', 'linux-compatibility / linux'},
         {'NearbyIM-0.3.1-windows-x64', 'NearbyIM-0.3.1-linux-x64'}),
        (ANDROID, {'build', 'android-localization (26)', 'android-localization (34)'},
         {'NearbyIM-0.3.1-android-debug'}),
    ]:
        run = api(f'actions/runs/{run_id}')
        require(run['head_sha'] == SOURCE, f'Run {run_id} has a different source commit')
        require(run['status'] == 'completed' and run['conclusion'] == 'success', f'Run {run_id} has not passed all checks')
        jobs = api(f'actions/runs/{run_id}/jobs?per_page=100')['jobs']
        require(expected_jobs <= {job['name'] for job in jobs}, f'Run {run_id} is missing platform checks')
        require(all(job['conclusion'] == 'success' for job in jobs if job['name'] in expected_jobs), 'Failed platform validation')
        artifacts = api(f'actions/runs/{run_id}/artifacts?per_page=100')['artifacts']
        selected = [a for a in artifacts if a['name'] in names]
        require({a['name'] for a in selected} == names and not any(a['expired'] for a in selected), 'Missing or expired application artifacts')
        records.append({'run_id': run_id, 'url': run['html_url'], 'source_commit': run['head_sha'],
                        'jobs': [{'name': j['name'], 'conclusion': j['conclusion']} for j in jobs],
                        'artifacts': [{k: a[k] for k in ('id', 'name', 'digest', 'size_in_bytes')} for a in selected]})
    return records


def prepare():
    records = provenance()
    require(not OUTPUT.exists(), 'Release output must be a fresh directory')
    OUTPUT.mkdir()
    android = Path('release-input/android')
    apk, = android.rglob('app-debug.apk')
    signatures, = android.rglob('signature.txt')
    checksums, = android.rglob('checksums.txt')
    expected_apk = [line.split()[0] for line in checksums.read_text().splitlines() if line.split()[1].endswith('/debug/app-debug.apk')]
    require(expected_apk == [digest(apk)], 'Android application differs from CI signature/alignment-verified APK')
    with zipfile.ZipFile(apk) as archive:
        require({'AndroidManifest.xml', 'classes.dex'} <= set(archive.namelist()), 'Wrong Android application archive')
    shutil.copyfile(apk, OUTPUT / 'NearbyIM-0.3.1-android-debug.apk')
    shutil.copyfile(signatures, OUTPUT / 'ANDROID-SIGNATURE.txt')

    windows = Path('release-input/windows/NearbyIM')
    for entry in ('NearbyIM.exe', 'app/nearbyim-desktop.jar', 'app/wozai_bluetooth.dll', 'runtime/bin/java.exe', 'THIRD_PARTY_NOTICES.md'):
        require((windows / entry).is_file(), f'Missing Windows component: {entry}')
    require(not (windows / 'data').exists(), 'Windows package contains a personal data directory')
    with zipfile.ZipFile(OUTPUT / 'NearbyIM-0.3.1-windows-x64.zip', 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
        for file in sorted(windows.rglob('*')):
            if file.is_file():
                archive.write(file, 'NearbyIM/' + file.relative_to(windows).as_posix())

    linux = Path('release-input/linux')
    linux_sums, = linux.rglob('linux-checksums.txt')
    for line in linux_sums.read_text().splitlines():
        expected, name = line.split()
        candidates = list(linux.rglob(name))
        require(len(candidates) == 1 and digest(candidates[0]) == expected, f'Linux CI checksum mismatch: {name}')
    tarball, = linux.rglob('NearbyIM-0.3.1-linux-x64.tar.gz')
    deb, = linux.rglob('NearbyIM-0.3.1-linux-x64.deb')
    require(subprocess.check_output(['dpkg-deb', '-f', str(deb), 'Version'], text=True).strip() == '0.3.1', 'Wrong Linux deb version')
    require(subprocess.check_output(['dpkg-deb', '-f', str(deb), 'Architecture'], text=True).strip() == 'amd64', 'Wrong Linux architecture')
    with tarfile.open(tarball) as archive:
        require(not any('/data/' in item.name or item.name.endswith('/identity.properties') for item in archive.getmembers()), 'Linux image contains personal data')
        requirements = archive.extractfile('NearbyIM/runtime-requirements.txt').read().decode()
    for file in (tarball, deb):
        shutil.copyfile(file, OUTPUT / file.name)
    (OUTPUT / 'LINUX-RUNTIME-REQUIREMENTS.txt').write_text(requirements)
    manifest = {'release_tag': TAG, 'application_version': '0.3.1', 'android_version_code': 5,
                'source_commit': SOURCE, 'builds': records,
                'windows_packaging': 'ZIP recreated from the validated CI application image; application bytes unchanged',
                'application_assets': {p.name: {'sha256': digest(p), 'size': p.stat().st_size}
                                       for p in sorted(OUTPUT.iterdir())}}
    (OUTPUT / 'BUILD-MANIFEST.json').write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + '\n')
    (OUTPUT / 'RELEASE-NOTES.md').write_text(f'''# 我在 · NearbyIM 0.3.1 Preview 1

Android、Windows、Linux 三端统一预览版，应用内部版本均为 **0.3.1**。三端支持局域网与经典蓝牙文字聊天，共用 NIM2 签名身份、首次认可、应用内信任、保存回执与历史重连；提供简体中文、英文、繁体中文、日语、韩语。

| 平台 | 下载文件 | 使用方式 |
|---|---|---|
| Android 8.0+（API 26+） | `NearbyIM-0.3.1-android-debug.apk` | 安装 APK，versionCode 5，调试签名测试包 |
| Windows 10/11 x64 | `NearbyIM-0.3.1-windows-x64.zip` | 解压后运行 `NearbyIM/NearbyIM.exe` |
| Linux x64 | `NearbyIM-0.3.1-linux-x64.tar.gz` | 解压后运行 `NearbyIM/bin/NearbyIM` |
| Debian／Ubuntu amd64 | `NearbyIM-0.3.1-linux-x64.deb` | `sudo apt install ./NearbyIM-0.3.1-linux-x64.deb` |

桌面包自带 Java 17、字体和蓝牙桥接库。Windows 数据默认位于程序旁的 `data`；Linux 数据默认位于 XDG 用户目录，需要 X11／XWayland 和系统运行库，蓝牙使用 BlueZ 与经典蓝牙适配器。Linux 的实际运行库下限见 `LINUX-RUNTIME-REQUIREMENTS.txt`。

## 验证与来源

全部应用来自提交 [`{SOURCE[:7]}`](https://github.com/{REPO}/commit/{SOURCE})。Windows ZIP 从已验证 CI 应用目录重新压缩，程序文件保持原样；Linux 包与 Android APK保持 CI 原始字节。

- [Android 编译、Lint、签名、对齐与 API 26／34 原生语言验证](https://github.com/{REPO}/actions/runs/{ANDROID})全部通过。
- [Windows／Linux 构建、原生 GUI、自带运行时、mDNS、100–200% 缩放与软件包启动验证](https://github.com/{REPO}/actions/runs/{DESKTOP})全部通过。
- Linux BlueZ 契约与 JNI 联合测试 107 项、NIM2／保存回执／重连链路 16 项通过；测试使用 D-Bus 服务替身及真实 Unix 描述符。
- `SHA256SUMS.txt` 提供全部发布资产的 SHA-256；`BUILD-MANIFEST.json` 记录源提交和构建产物来源；Android 证书见 `ANDROID-SIGNATURE.txt`。

## 预览版已知限制

- Windows／Linux 蓝牙已包含在程序中，真实适配器、系统配对以及 Android ↔ Windows ↔ Linux 双向射频互传尚未完成本轮验收，详见 [Linux 验证记录](https://github.com/{REPO}/blob/{SOURCE}/docs/linux-verification.md)。
- Android 使用本次 CI 的临时调试证书。只有证书匹配时才能覆盖升级；与其他 CI 包或旧版签名不同的设备不能直接覆盖安装。已有聊天数据请保留原应用与原签名密钥，卸载会删除聊天数据和设备身份；请勿通过卸载来规避签名错误。
- 局域网内容为明文，NIM2 签名提供身份连续性和完整性；暂无端到端加密、附件、群聊或互联网中继。
- 这是预览版 Release；稳定版仍为 v0.2.0。Linux 源码所在 PR #4 尚未合并，本次标签直接指向已验证的预览提交。
''')
    (OUTPUT / 'SHA256SUMS.txt').write_text(''.join(f'{digest(p)}  {p.name}\n' for p in sorted(OUTPUT.iterdir())))
    print('Prepared release assets:', ', '.join(p.name for p in sorted(OUTPUT.iterdir())))


def verify(published=False):
    release = api(f'releases/tags/{TAG}')
    require(release['prerelease'] and release['draft'] != published, 'Incorrect release visibility or prerelease flag')
    expected = {p.name: p for p in OUTPUT.iterdir()}
    require(set(expected) == {a['name'] for a in release['assets']}, 'Uploaded asset set differs from prepared assets')
    for asset in release['assets']:
        file = expected[asset['name']]
        require(asset['state'] == 'uploaded' and asset['size'] == file.stat().st_size, f'Incomplete release asset: {file.name}')
        require(asset.get('digest') == 'sha256:' + digest(file), f'Uploaded SHA-256 mismatch: {file.name}')
    ref = api(f'git/ref/tags/{TAG}')['object']
    if ref['type'] == 'tag':
        ref = api(f'git/tags/{ref["sha"]}')['object']
    require(ref['type'] == 'commit' and ref['sha'] == SOURCE, 'Release tag points at the wrong source commit')
    if published:
        require(api('releases/latest')['tag_name'] == 'v0.2.0', 'Preview replaced the stable latest release')
    print('Verified', 'published prerelease' if published else 'draft assets', release['html_url'])


if __name__ == '__main__':
    operation = sys.argv[1]
    if operation == 'check':
        print(json.dumps(provenance(), indent=2))
    elif operation == 'prepare':
        prepare()
    elif operation in ('verify', 'published'):
        verify(operation == 'published')
    else:
        raise ValueError('Unknown operation')
