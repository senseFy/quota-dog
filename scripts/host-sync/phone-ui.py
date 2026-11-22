"""Drive only the isolated local QuotaDog APK via Android's public UI. No test intent or pairing bypass."""
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

adb = str(Path(os.environ['ANDROID_HOME']) / 'platform-tools/adb')
serial = os.environ['QD_TEST_SERIAL']
assert serial.startswith('emulator-'), 'Pass a disposable emulator serial'
pkg = 'saien.quotadog.hostsync.local'
qr = '/sdcard/Download/QuotaDog-invitation.png'
tree = '/data/local/tmp/qd-host-sync-ui.xml'


def call(*args):
    return subprocess.check_output([adb, '-s', serial, *args], stdin=subprocess.DEVNULL, timeout=15)


def nodes():
    # During cold launch Android may not have an accessibility root yet. Never read a stale dump.
    for _ in range(6):
        call('shell', 'rm', '-f', tree)
        if b'dumped to:' in call('shell', 'uiautomator', 'dump', tree):
            return list(ET.fromstring(call('exec-out', 'cat', tree)).iter('node'))
        time.sleep(.25)
    raise AssertionError('Android did not provide a current UI hierarchy')


def labels(n):
    return [n.get('text', ''), n.get('content-desc', '')]


def find(label, *, timeout=30):
    deadline = time.monotonic() + timeout
    seen = []
    while time.monotonic() < deadline:
        ns = nodes()
        seen = [v for n in ns for v in labels(n) if v and not re.fullmatch(r'\d{6}', v)]
        if label == 'Compare both screens' and any(v.startswith('Pairing did not finish') for v in seen):
            raise AssertionError('Enrollment failed before comparison')
        for n in ns:
            if label in labels(n) or (label.endswith('.png') and n.get('content-desc', '').startswith(label + ',')):
                return n
        time.sleep(.15)
    raise AssertionError(f'Expected UI element not found; visible: {seen}')


def tap(n):
    x, y, r, b = map(int, re.findall(r'\d+', n.get('bounds')))
    call('shell', 'input', 'tap', str((x+r)//2), str((y+b)//2))


def reveal(label, prefix=False):
    # Remote cards and their actions share the normal, vertically scrollable dashboard. Walk
    # only the visible accessibility tree, just as a person scrolling through the app would.
    w, h = map(int, re.findall(r'(\d+)x(\d+)', call('shell', 'wm', 'size').decode())[-1])
    def move(down):
        start, end = (.75, .40) if down else (.40, .75)
        call('shell', 'input', 'swipe', str(w//3), str(int(h*start)), str(w//3), str(int(h*end)), '360')
    down, previous = not label.startswith(('All providers (', 'Computer ', 'Share from', 'Hide phone', 'Forget computer')), None
    deadline = time.monotonic() + 85
    while time.monotonic() < deadline:
        ns = nodes()
        for n in ns:
            if any((v.startswith(label) if prefix else v == label) for v in labels(n)):
                _, y, _, b = map(int, re.findall(r'\d+', n.get('bounds')))
                if b > y and .04*h < (b+y)/2 < .91*h: return n
        seen = [(labels(n), n.get('bounds')) for n in ns]
        if seen == previous: down = not down
        previous = seen
        move(down)
    raise AssertionError(f'Dashboard did not show {label}; visible: {[v for n in ns for v in labels(n) if v]}')


def start():
    call('shell', 'am', 'start', '-n', pkg + '/saien.quotadog.hostsync.LocalPhoneActivity')


def main(action, *args):
    if action == 'reset':
        call('shell', 'pm', 'clear', pkg); start()
    elif action == 'local-codex':
        # Only the disposable pilot sandbox. Seed one synthetic saved login, not a reading or a
        # permission. The app must fetch its own quota and the user still grants every direction.
        call('shell', 'am', 'force-stop', pkg)
        path = 'shared_prefs/quotadog.xml'
        prior = call('shell', '-T', f"run-as {pkg} sh -c 'if [ -f {path} ]; then cat {path}; fi'")
        root = ET.fromstring(prior) if prior else ET.Element('map')
        ET.SubElement(root, 'string', name='oauth_token_codex').text = sys.stdin.read()
        call('shell', 'run-as', pkg, 'mkdir', '-p', 'shared_prefs')
        subprocess.run([adb, '-s', serial, 'shell', '-T', f'run-as {pkg} sh -c "cat > {path}"'],
                       input=ET.tostring(root), timeout=10, check=True)
        start()
    elif action == 'restart':
        call('shell', 'am', 'force-stop', pkg); start()
    elif action == 'network':
        mode, = args
        assert mode in ('offline', 'cellular', 'wifi')
        call('shell', 'svc', 'wifi', 'enable' if mode == 'wifi' else 'disable')
        call('shell', 'svc', 'data', 'disable' if mode == 'offline' else 'enable')
    elif action == 'import':
        call('push', args[0], qr)
        call('shell', 'am', 'broadcast', '-a', 'android.intent.action.MEDIA_SCANNER_SCAN_FILE', '-d', 'file://' + qr)
        tap(find('Open QR image'))
        try:
            n = find('QuotaDog-invitation.png', timeout=4)
        except AssertionError:
            tap(find('Show roots')); tap(find('Downloads')); n = find('QuotaDog-invitation.png')
        tap(n); find('Compare both screens'); call('shell', 'rm', '-f', qr)
    elif action == 'match':
        find(sys.stdin.read().strip())
    elif action in ('expect', 'reveal'):
        for label in args: reveal(label)
    elif action == 'absent':
        visible = [v for n in nodes() for v in labels(n)]
        assert all(a not in visible for a in args), 'Unexpected card or action'
    elif action == 'tap':
        tap(reveal(args[0]))
    elif action == 'collected':
        print(next(v.split(' · ')[-1] for v in labels(reveal('Collected ·', prefix=True)) if v.startswith('Collected ·')))
    elif action == 'capture':
        Path(os.environ['QD_EVIDENCE'], 'host-sync-' + args[0] + '.png').write_bytes(call('exec-out', 'screencap', '-p'))
    elif action == 'clean':
        call('shell', 'rm', '-f', qr, tree)
    else:
        raise ValueError('Unknown UI action')


if __name__ == '__main__':
    main(*sys.argv[1:])
