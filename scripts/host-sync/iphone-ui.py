"""Drive the isolated iPhone simulator app using AXe; import the actual QR via Photos."""
import json
import os
import subprocess
import sys
import time
from pathlib import Path

sim = os.environ['QD_IOS_SIM']
pkg = 'saien.quotadog.hostsync.local'


def call(*args):
    return subprocess.check_output(args, timeout=15, stderr=subprocess.DEVNULL)


def simctl(command, *args):
    return call('xcrun', 'simctl', command, sim, *args)


def elements():
    def descend(node):
        yield node
        for child in node.get('children', []):
            yield from descend(child)
    trees = json.loads(call('axe', 'describe-ui', '--udid', sim))
    return [node for tree in trees for node in descend(tree)]


def find(predicate, description):
    end = time.monotonic() + 30
    while time.monotonic() < end:
        for node in elements():
            if predicate(node):
                return node
        time.sleep(.2)
    raise AssertionError('Could not find ' + description)


def label(text):
    return find(lambda n: n.get('AXLabel') == text, 'comparison digits' if text.isdigit() else text)


def touch(x, y):
    call('axe', 'tap', '--udid', sim, '--tap-style', 'physical', '--post-delay', '0.4', '-x', str(x), '-y', str(y))


def tap(node):
    f = node['frame']
    touch(f['x'] + f['width'] / 2, f['y'] + f['height'] / 2)


def main(action, *args):
    if action == 'reset':
        # Reusing a disposable simulator after an interrupted test can leave a sheet open.
        subprocess.run(['xcrun', 'simctl', 'terminate', sim, pkg], capture_output=True)
        simctl('launch', pkg)
        state = find(lambda n: n.get('AXLabel') in ('Forget computer…', 'Open QR image'), 'saved pairing state')
        if state['AXLabel'] == 'Forget computer…':
            tap(label('Forget computer…')); tap(label('Remove from phone'))
    elif action == 'foreground':
        simctl('launch', pkg)
    elif action == 'background':
        simctl('launch', 'com.apple.Preferences')
        find(lambda n: n.get('type') == 'Application' and n.get('AXLabel') == 'Settings', 'foreground Settings app')
    elif action == 'restart':
        simctl('terminate', pkg); simctl('launch', pkg)
    elif action == 'import':
        screen = find(lambda n: n.get('type') == 'Application', 'phone screen')['frame']
        simctl('addmedia', args[0]); tap(label('Open QR image'))
        end = time.monotonic() + 25
        # Some simctl versions treat '-' as a literal filename. Keep images in the temporary profile.
        capture = Path(args[0]).with_name('picker.png')
        while time.monotonic() < end:
            simctl('io', 'screenshot', str(capture))
            match = subprocess.run([os.environ['QD_FIND_PICKER_QR'], args[0]],
                                   input=capture.read_bytes(), capture_output=True, timeout=10)
            capture.unlink()
            if match.returncode == 0:
                x, y = map(float, match.stdout.split())
                touch(screen['width'] * x, screen['height'] * y)
                break
            time.sleep(.3)
        else:
            raise AssertionError('No matching invitation thumbnail in the Photos picker')
        label('Compare both screens')
    elif action == 'match':
        label(sys.stdin.read().strip())
    elif action == 'expect':
        for text in args: label(text)
    elif action == 'absent':
        visible = {n.get('AXLabel') for n in elements()}
        assert not visible.intersection(args), 'Unexpected account or action'
    elif action == 'tap':
        tap(label(args[0]))
    elif action == 'collected':
        node = find(lambda n: (n.get('AXLabel') or '').startswith('Collected · '), 'collection time')
        print(node['AXLabel'].removeprefix('Collected · '))
    elif action == 'capture':
        simctl('io', 'screenshot', str(Path(os.environ['QD_EVIDENCE'], f'host-sync-{args[0]}.png')))
    elif action != 'clean':  # The harness owns and deletes the whole simulator, including test QR images.
        raise ValueError('Unknown iPhone UI action')


if __name__ == '__main__':
    main(*sys.argv[1:])
