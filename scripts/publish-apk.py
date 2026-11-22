#!/usr/bin/env python3
"""Publish only verified artifacts from trusted main/tag Actions runs."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import urllib.error
import urllib.parse
import urllib.request

repository = os.environ['GITHUB_REPOSITORY']
sha = os.environ['GITHUB_SHA']
ref = os.environ['GITHUB_REF']
run_id = os.environ['GITHUB_RUN_ID']
if os.environ.get('GITHUB_EVENT_NAME') == 'pull_request':
    raise SystemExit('Pull requests cannot publish releases.')
if ref.startswith('refs/tags/v'):
    tag = os.environ['GITHUB_REF_NAME']
    title = f'JRFANMAP {tag}'
    prerelease = '-' in tag
elif ref == 'refs/heads/main':
    number = os.environ['GITHUB_RUN_NUMBER']
    attempt = os.environ['GITHUB_RUN_ATTEMPT']
    tag = f'dev-{number}.{attempt}'
    title = f'JRFANMAP development build {number}.{attempt}'
    prerelease = True
else:
    raise SystemExit('Publishing is restricted to main or v* tags.')

root = Path('dist')
files = [root / 'JRFANMAP-debug.apk', root / 'JRFANMAP-debug.apk.sha256']
digests = {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in files}
assert files[1].read_text().split() == [digests[files[0].name], files[0].name], 'APK checksum mismatch'

base = f'https://api.github.com/repos/{repository}'


def api(path, method='GET', data=None, missing_ok=False):
    request = urllib.request.Request(
        base + path, method=method,
        data=None if data is None else json.dumps(data).encode(),
        headers={'Authorization': 'Bearer ' + os.environ['GH_TOKEN'],
                 'Accept': 'application/vnd.github+json', 'Content-Type': 'application/json',
                 'X-GitHub-Api-Version': '2022-11-28'})
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            return json.load(response)
    except urllib.error.HTTPError as error:
        if error.code == 404 and missing_ok:
            return None
        raise


notes = f'''下载 Assets 中的 **JRFANMAP-debug.apk**，传到 Android 手机安装，无需合并分卷。

- Android 8.0+，需要支持 OpenGL ES 3.0；这是调试签名的试用构建。
- 来源提交：{sha}
- 自动构建：https://github.com/{repository}/actions/runs/{run_id}
- APK 构建、JVM 单元测试、Android Lint 与 APK 签名检查通过。
- 提供离线铁路库、GPS 诊断和 LIVE 前方站序。线路及站点按设备观测推断，实际停站以运营方为准。

'''
if os.environ.get('SIGNING_KEY_CONFIGURED', '').lower() == 'true':
    notes += '使用仓库配置的固定调试签名密钥。请始终从可信发布页面安装 APK。\n'
else:
    notes += ('本次使用 CI 临时调试签名。不同运行的签名可能不同，覆盖安装可能失败；'
              '卸载会删除本机轨迹，请先导出。要稳定升级，请配置 '
              'ANDROID_DEBUG_KEYSTORE_BASE64 仓库 Actions Secret。\n')

release = api('/releases/tags/' + urllib.parse.quote(tag, safe=''), missing_ok=True)
if release is not None and not release['draft']:
    raise SystemExit('Refusing to overwrite a published release; use a new tag or rerun number.')
if release is None:
    release = api('/releases', 'POST', {'tag_name': tag, 'target_commitish': sha,
                  'name': title, 'body': notes, 'draft': True, 'prerelease': prerelease})

subprocess.run(['gh', 'release', 'upload', tag, *map(str, files), '--repo', repository,
                '--clobber'], check=True)
release = api(f"/releases/{release['id']}")
assets = {asset['name']: asset for asset in release['assets']}
for path in files:
    asset = assets[path.name]
    assert asset['state'] == 'uploaded' and asset['size'] == path.stat().st_size
    assert asset['digest'] == 'sha256:' + digests[path.name], 'Remote asset checksum mismatch'

release = api(f"/releases/{release['id']}", 'PATCH', {
    'tag_name': tag, 'target_commitish': sha, 'name': title, 'body': notes,
    'draft': False, 'prerelease': prerelease, 'make_latest': 'false'})
assert release['tag_name'] == tag and not release['draft']
url = release['html_url']
summary = os.environ.get('GITHUB_STEP_SUMMARY')
if summary:
    with open(summary, 'a') as output:
        output.write(f'### APK published\n\n[Download APK](https://github.com/{repository}/releases/download/{tag}/JRFANMAP-debug.apk) · [Release]({url})\n')
print('Published:', url)
