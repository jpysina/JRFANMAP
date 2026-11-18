#!/usr/bin/env python3
"""Reproduce the development toolchain under /workspace; never stores credentials."""
import hashlib
import os
from pathlib import Path
import re
import subprocess
import tarfile
import urllib.parse
import urllib.request
import zipfile

ROOT = Path(os.environ.get('JRFANMAP_TOOLCHAIN_ROOT', '/workspace'))
CHECKOUT = Path(__file__).resolve().parents[1]
SDK = ROOT / 'android-sdk'
TOOLS = ROOT / 'toolchains'
USER = ROOT / 'android-user'
GRADLE = ROOT / '.gradle'
for directory in (SDK, TOOLS, USER, GRADLE):
    directory.mkdir(parents=True, exist_ok=True)


def download(url, filename, expected, algorithm='sha256'):
    path = Path('/tmp') / filename
    if not path.exists() or hashlib.new(algorithm, path.read_bytes()).hexdigest() != expected:
        urllib.request.urlretrieve(url, path)
    if hashlib.new(algorithm, path.read_bytes()).hexdigest() != expected:
        raise RuntimeError(f'Checksum verification failed: {filename}')
    return path


jdk = TOOLS / 'jdk-21.0.9+10'
if not (jdk / 'bin/javac').exists():
    archive = download(
        'https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.9%2B10/OpenJDK21U-jdk_x64_linux_hotspot_21.0.9_10.tar.gz',
        'jdk21.tar.gz', '810d3773df7e0d6c4394e4e244b264c8b30e0b05a0acf542d065fd78a6b65c2f')
    with tarfile.open(archive) as package:
        package.extractall(TOOLS, filter='data')
if not (TOOLS / 'jdk').exists():
    (TOOLS / 'jdk').symlink_to(jdk, target_is_directory=True)

cli = SDK / 'cmdline-tools/latest/bin/android'
if not cli.exists():
    archive = download('https://dl.google.com/android/repository/commandlinetools-linux-16111833_latest.zip',
                       'android-commandline.zip', 'e025545c62a8e64c7559119566a569fb1dec5f60', 'sha1')
    staging = SDK / 'bootstrap'
    with zipfile.ZipFile(archive) as package:
        package.extractall(staging)
    (SDK / 'cmdline-tools').mkdir(exist_ok=True)
    (staging / 'cmdline-tools').rename(SDK / 'cmdline-tools/latest')
    for executable in cli.parent.iterdir():
        executable.chmod(0o755)

# Only manage our marked proxy/trust section, retaining other user settings.
properties = GRADLE / 'gradle.properties'
text = properties.read_text() if properties.exists() else ''
text = re.sub(r'\n?# BEGIN JRFANMAP\n.*?# END JRFANMAP\n?', '', text, flags=re.S)
lines = []
proxy = urllib.parse.urlparse(os.environ.get('HTTPS_PROXY', ''))
if proxy.hostname:
    for protocol in ('https', 'http'):
        lines += [f'systemProp.{protocol}.proxyHost={proxy.hostname}',
                  f'systemProp.{protocol}.proxyPort={proxy.port or 80}']
    lines += ['systemProp.http.nonProxyHosts=localhost|127.*']
trust = Path('/etc/ssl/certs/java/cacerts')
if trust.exists():
    lines += [f'systemProp.javax.net.ssl.trustStore={trust}']
properties.write_text(text.rstrip() + '\n# BEGIN JRFANMAP\n' + '\n'.join(lines) + '\n# END JRFANMAP\n')

environment = dict(os.environ, JAVA_HOME=str(TOOLS / 'jdk'), ANDROID_HOME=str(SDK),
                   # Hosted runners predefine this deprecated variable; it must agree.
                   ANDROID_SDK_ROOT=str(SDK),
                   ANDROID_USER_HOME=str(USER), GRADLE_USER_HOME=str(GRADLE))
if trust.exists():
    environment['JAVA_TOOL_OPTIONS'] = (environment.get('JAVA_TOOL_OPTIONS', '') +
        f' -Djavax.net.ssl.trustStore={trust} -Djava.util.prefs.userRoot={USER}/java-prefs').strip()
for package in ('platforms;android-37.2', 'build-tools;36.0.0', 'platform-tools'):
    subprocess.run([str(cli), '--no-metrics', f'--sdk={SDK}', 'sdk', 'install', package],
                   env=environment, check=True)
subprocess.run(['./gradlew', ':app:assembleDebug', ':app:testDebugUnitTest', ':app:lintDebug'],
               cwd=CHECKOUT, env=environment, check=True)
print('JRFANMAP toolchain and checks complete.')
