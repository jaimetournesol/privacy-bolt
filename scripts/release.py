#!/usr/bin/env python3
"""Build and optionally publish a signed APK locally after required CI passes.

Run from the clean release tag. The Android keystore remains in its existing
ignored local configuration; no signing material is uploaded to GitHub Actions.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile

ROOT=Path(__file__).resolve().parents[1]
REPO='jaimetournesol/privacy-bolt'
CERT='96a71aa672046c0153134accc8ff3039d3bd27fbfadf8a407ba30289716de357'


def run(*args,cwd=ROOT):
    return subprocess.check_output(args,cwd=cwd,text=True,stderr=subprocess.STDOUT).strip()


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('version');p.add_argument('--notes',type=Path,required=True)
    p.add_argument('--publish',action='store_true');a=p.parse_args()
    assert re.fullmatch(r'\d+\.\d+\.\d+',a.version), 'Invalid version'
    assert a.notes.is_file(), 'Missing notes'
    assert not run('git','status','--porcelain'), 'Checkout must be clean'
    sha=run('git','rev-parse','HEAD');tag='v'+a.version
    assert run('git','rev-parse',tag+'^{commit}')==sha, 'Checkout must match tag'
    checks=json.loads(run('gh','api',f'repos/{REPO}/commits/{sha}/check-runs'))['check_runs']
    for name in ['android','emulator']:
        matching=[c for c in checks if c['name']==name and c['app']['slug']=='github-actions']
        assert matching and matching[0]['conclusion']=='success', 'Required check is not green'
    release=subprocess.run(['gh','api',f'repos/{REPO}/releases/tags/{tag}'],capture_output=True,text=True)
    if release.returncode==0:raise AssertionError('Release already exists; inspect it rather than overwrite')
    assert json.loads(release.stdout).get('status')=='404', 'Cannot determine existing release state'
    android=ROOT/'apps/android'
    assert (android/'keystore.properties').is_file(), 'Local signing configuration missing'
    run('./gradlew',':app:assembleRelease',cwd=android)
    sdk=Path(os.environ.get('ANDROID_HOME',str(Path.home()/'Android/Sdk')))
    versions=sorted((sdk/'build-tools').iterdir(),key=lambda p:tuple(int(x) for x in re.findall(r'\d+',p.name)))
    build=versions[-1];apk=android/'app/build/outputs/apk/release/app-release.apk'
    cert=run(str(build/'apksigner'),'verify','--print-certs',str(apk))
    assert 'Signer #1 certificate SHA-256 digest: '+CERT in cert, 'Signing certificate changed'
    metadata=run(str(build/'aapt'),'dump','badging',str(apk)).splitlines()[0]
    assert "name='ai.tournesol.privacybolt'" in metadata and "versionName='"+a.version+"'" in metadata
    print('PASS: matching clean tag, Android/emulator CI, APK version and durable signing certificate')
    if not a.publish:
        print('Signed APK:',apk);return
    with tempfile.TemporaryDirectory(prefix='bolt-release-') as d:
        import shutil
        out=Path(d)/f'privacy-bolt-{a.version}.apk';shutil.copyfile(apk,out)
        sums=Path(d)/'SHA256SUMS';sums.write_text(hashlib.sha256(out.read_bytes()).hexdigest()+'  '+out.name+'\n')
        run('gh','release','create',tag,'--repo',REPO,'--verify-tag','--draft','--title','Privacy Bolt '+a.version,
            '--notes-file',str(a.notes.resolve()),str(out),str(sums))
        result=json.loads(run('gh','api',f'repos/{REPO}/releases/tags/{tag}'))
        for asset in result['assets']:
            f=Path(d)/asset['name'];assert f.is_file()
            assert asset['digest']=='sha256:'+hashlib.sha256(f.read_bytes()).hexdigest()
        run('gh','release','edit',tag,'--repo',REPO,'--draft=false','--latest')
        print('Published verified signed APK and checksum')

if __name__=='__main__':
    try:main()
    except Exception:
        raise SystemExit('Release stopped; check the tag, CI and local signing setup. Existing assets are never overwritten.') from None
