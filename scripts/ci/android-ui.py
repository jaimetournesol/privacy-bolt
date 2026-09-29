#!/usr/bin/env python3
"""Fresh-emulator onboarding UI regression. Refuses physical/persistent devices."""
import os
import subprocess
import time
import xml.etree.ElementTree as ET

serial = os.environ.get('ANDROID_SERIAL', '')
if os.environ.get('GITHUB_ACTIONS') != 'true' or not serial.startswith('emulator-'):
    raise SystemExit('Run only on the disposable GitHub Actions emulator')
package = 'ai.tournesol.privacybolt.debug'

def adb(*args):
    return subprocess.check_output(['adb','-s',serial,*args],text=True)

def nodes():
    try:
        adb('shell','rm','-f','/sdcard/ci-ui.xml')
        adb('shell','uiautomator','dump','/sdcard/ci-ui.xml')
        return list(ET.fromstring(adb('shell','cat','/sdcard/ci-ui.xml')).iter('node'))
    except (subprocess.CalledProcessError, ET.ParseError):
        return []  # Android may not expose an accessibility root during launch.

def find(text):
    deadline=time.monotonic()+45
    while time.monotonic()<deadline:
        for n in nodes():
            if n.get('text') == text or n.get('content-desc') == text:return n
        time.sleep(1)
    raise AssertionError('UI control not found: '+text)

def tap(n):
    import re
    a,b,c,d=map(int,re.findall(r'\d+',n.get('bounds')))
    adb('shell','input','tap',str((a+c)//2),str((b+d)//2))

adb('shell','pm','grant',package,'android.permission.POST_NOTIFICATIONS')
adb('shell','am','start','-n',package+'/ai.tournesol.privacybolt.MainActivity')
find('Scan setup code')
tap(find('or sign in manually'))
find('Lodge (.onion)'); find('Password')
# Empty credentials must not submit; password starts concealed.
assert find('Connect over Tor').get('enabled') == 'false'
find('Show password')
# Android process recreation must return to a usable, unauthenticated screen.
adb('shell','am','force-stop',package)
adb('shell','am','start','-n',package+'/ai.tournesol.privacybolt.MainActivity')
find('Scan setup code')
assert adb('shell','pidof',package).strip()
print('PASS: onboarding, manual login, empty-credential guard, concealed password and process recreation')
