"""Drive the isolated iPhone simulator app using AXe; import the actual QR via Photos."""
import json
import os
import subprocess
import sys
import time
from pathlib import Path

sim = os.environ['QD_IOS_SIM']
pkg = 'saien.quotadog.hostsync.local'


def call(*args, timeout=15):
    return subprocess.check_output(args, timeout=timeout, stdin=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def simctl(command, *args):
    # The first import may need to initialize the Photos library on a fresh simulator.
    return call('xcrun', 'simctl', command, sim, *args, timeout=90 if command == 'addmedia' else 15)


def elements():
    def descend(node):
        yield node
        for child in node.get('children', []):
            yield from descend(child)
    # SpringBoard may provide no window, or a zero-sized window, during an app transition.
    # Never interpret that as an empty screen (in particular for an absence assertion).
    end = time.monotonic() + 15
    while time.monotonic() < end:
        try:
            trees = json.loads(call('axe', 'describe-ui', '--udid', sim))
            for tree in trees:
                frame = tree.get('frame') or {}
                if tree.get('type') == 'Application' and frame.get('width', 0) > 0 and frame.get('height', 0) > 0:
                    return list(descend(tree))
        except (subprocess.CalledProcessError, json.JSONDecodeError):
            pass
        time.sleep(.25)
    raise AssertionError('iOS did not provide a current accessibility hierarchy')


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


def reveal(text, prefix=False):
    down, previous = not text.startswith(('All providers (', 'Computer ', 'Share from', 'Hide phone', 'Forget computer')), None
    end = time.monotonic() + 50
    while time.monotonic() < end:
        ns = elements()
        screen = next(n['frame'] for n in ns if n.get('type') == 'Application')
        h = screen['height']; x = str(int(screen['width'] / 3))
        seen = []
        for n in ns:
            name = n.get('AXLabel') or ''
            f = n['frame']; y = f['y'] + f['height'] / 2
            # Accessibility also reports content underneath the status bar and fixed Add button.
            if name and .085*h < y < .875*h and 0 < f['x'] + f['width'] / 2 < screen['width']:
                seen.append((name, int(y)))
                if (name.startswith(text) if prefix else name == text): return n
        if seen == previous: down = not down
        previous = seen
        a, b = (.75, .40) if down else (.40, .75)
        call('axe', 'swipe', '--udid', sim, '--start-x', x, '--end-x', x, '--start-y', str(int(h*a)),
             '--end-y', str(int(h*b)), '--duration', '0.38', '--post-delay', '0.35')
    raise AssertionError(f'Dashboard did not show {text}; visible: {previous}')


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
    elif action == 'local-codex':
        # Store a string in the disposable app's actual preferences. Launch arguments on iOS
        # interpret JSON-looking values as property lists rather than token-store strings.
        simctl('terminate', pkg)
        data = Path(simctl('get_app_container', pkg, 'data').decode().strip())
        simctl('spawn', 'defaults', 'write', str(data / 'Library' / 'Preferences' / pkg),
               'oauth_token_codex', '-string', sys.stdin.read())
        simctl('launch', pkg)
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
    elif action in ('expect', 'reveal'):
        for text in args: reveal(text)
    elif action == 'absent':
        visible = {n.get('AXLabel') for n in elements()}
        assert not visible.intersection(args), 'Unexpected account or action'
    elif action == 'tap':
        tap(reveal(args[0]))
    elif action == 'collected':
        node = reveal('Collected · ', prefix=True)
        print(node['AXLabel'].removeprefix('Collected · '))
    elif action == 'capture':
        simctl('io', 'screenshot', str(Path(os.environ['QD_EVIDENCE'], f'host-sync-{args[0]}.png')))
    elif action != 'clean':  # The harness owns and deletes the whole simulator, including test QR images.
        raise ValueError('Unknown iPhone UI action')


if __name__ == '__main__':
    main(*sys.argv[1:])
