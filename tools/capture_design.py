#!/usr/bin/env python3
"""Capture the debug-only fixtures on a running local emulator, never hardware."""
import argparse
from contextlib import suppress
from pathlib import Path
import subprocess
import struct
import re
import time
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--adb', required=True)
parser.add_argument('--serial', default='emulator-5554')
parser.add_argument('--only-device', action='append', default=[])
parser.add_argument('--smoke', action='store_true', help='Check real navigation and language switching; no hardware actions.')
args = parser.parse_args()
if not args.serial.startswith('emulator-'):
    parser.error('Only a local emulator is accepted.')
root = Path(__file__).resolve().parents[1]
out = root / 'output/design'
out.mkdir(parents=True, exist_ok=True)
base = [args.adb, '-s', args.serial]


def adb(*parts):
    return subprocess.check_output(base + list(parts), stderr=subprocess.STDOUT, timeout=45)


# Pixel dimensions / density are chosen to exercise logical Compose dimensions,
# not to assert a specific controller's vendor-configured Android density.
configs = [
    ('rc2', '1080x1920', '400', '1', [
        ('disconnected', 'tr', 0), ('fcc', 'tr', 0), ('busy', 'tr', 0),
        ('applying', 'tr', 0), ('4g', 'tr', 0), ('4g_error', 'en', 0),
        ('fcc', 'en', 0), ('info', 'tr', 1), ('log', 'tr', 2),
        ('update', 'tr', 3), ('download', 'en', 3), ('install', 'tr', 3)
    ]),
    ('portrait', '1080x1920', '400', '0', [('fcc', 'tr', 0), ('4g', 'en', 0)]),
    ('rcpro2', '1080x1920', '320', '1', [('fcc', 'en', 0)]),
    ('rcpro2-portrait', '1080x1920', '320', '0', [('fcc', 'tr', 0)]),
    ('rc2-large-text', '1080x1920', '400', '1', [('fcc', 'tr', 0)]),
    ('rc2-dense', '1080x1920', '480', '1', [('fcc', 'tr', 0), ('busy', 'en', 0)]),
    ('expanded', '2160x2560', '320', '0', [('fcc', 'tr', 0)]),
]
records = []


def hierarchy():
    adb('shell', 'uiautomator', 'dump', '/sdcard/dronepeak-design.xml')
    return ET.fromstring(adb('exec-out', 'cat', '/sdcard/dronepeak-design.xml'))


def tap_description(tree, descriptions):
    node = next(n for n in tree.iter('node')
                if n.get('clickable') == 'true' and (
                    n.get('content-desc') in descriptions or
                    any(child.get('text') in descriptions for child in n.iter('node'))))
    left, top, right, bottom = map(int, re.findall(r'\d+', node.get('bounds')))
    adb('shell', 'input', 'tap', str((left + right) // 2), str((top + bottom) // 2))
    time.sleep(1)


try:
    for _ in range(45):
        if adb('shell', 'getprop', 'sys.boot_completed').strip() == b'1':
            break
        time.sleep(1)
    else:
        raise RuntimeError('The emulator did not finish booting.')
    adb('install', '-r', str(root / 'app/build/outputs/apk/debug/app-debug.apk'))
    adb('shell', 'settings', 'put', 'global', 'device_provisioned', '1')
    adb('shell', 'settings', 'put', 'secure', 'user_setup_complete', '1')
    adb('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP')
    adb('shell', 'wm', 'dismiss-keyguard')
    adb('shell', 'settings', 'put', 'system', 'accelerometer_rotation', '0')
    for device, size, density, rotation, fixtures in configs:
        if args.only_device and device not in args.only_device:
            continue
        adb('shell', 'settings', 'put', 'system', 'font_scale',
            '1.3' if device == 'rc2-large-text' else '1.0')
        adb('shell', 'wm', 'size', size)
        adb('shell', 'wm', 'density', density)
        adb('shell', 'settings', 'put', 'system', 'user_rotation', rotation)
        time.sleep(1)
        for scenario, language, page in fixtures:
            adb('shell', 'am', 'force-stop', 'com.dronepeak.app')
            adb('shell', 'am', 'start', '-W', '-n', 'com.dronepeak.app/.DesignPreviewActivity',
                '--es', 'scenario', scenario, '--es', 'language', language, '--ei', 'page', str(page),
                '--es', 'controller', 'rcpro2' if device.startswith('rcpro2') else 'rc2')
            time.sleep(1.2)
            name = f'{device}-{scenario}-{language}'
            png = adb('exec-out', 'screencap', '-p')
            (out / f'{name}.png').write_bytes(png)
            width, height = struct.unpack('>II', png[16:24])
            adb('shell', 'uiautomator', 'dump', '/sdcard/dronepeak-design.xml')
            xml = adb('exec-out', 'cat', '/sdcard/dronepeak-design.xml')
            (out / f'{name}.xml').write_bytes(xml)
            tree = ET.fromstring(xml)
            controls = [n.attrib for n in tree.iter('node')
                        if n.get('clickable') == 'true' and n.get('package') == 'com.dronepeak.app']
            if page == 0:
                for control in controls:
                    left, top, right, bottom = map(int, re.findall(r'\d+', control['bounds']))
                    assert 0 <= left < right <= width and 0 <= top < bottom <= height, control
                    assert right - left >= 48 * int(density) / 160 - 1, control
                    assert bottom - top >= 48 * int(density) / 160 - 1, control
                labels = {n.get('text') for n in tree.iter('node')}
                required = {'Auto-FCC', 'Keepalive', 'DJI Fly',
                            'Kontrol' if language == 'tr' else 'Control',
                            'Bilgi' if language == 'tr' else 'Info',
                            'Günlük' if language == 'tr' else 'Log',
                            'Güncelle' if language == 'tr' else 'Update',
                            'LED AÇ' if language == 'tr' else 'LED ON',
                            'LED KAPAT' if language == 'tr' else 'LED OFF',
                            '4G gönder' if language == 'tr' else 'Send 4G'}
                assert required <= labels, f'Missing visible actions: {required - labels}'
            record = f'{name}: {len(controls)} clickable controls; {size} at {density}dpi; rotation={rotation}'
            records.append(record)
            print(record, flush=True)
    if args.smoke:
        adb('shell', 'wm', 'size', '1080x1920')
        adb('shell', 'wm', 'density', '400')
        adb('shell', 'settings', 'put', 'system', 'user_rotation', '1')
        adb('shell', 'am', 'force-stop', 'com.dronepeak.app')
        adb('shell', 'am', 'start', '-W', '-n', 'com.dronepeak.app/.MainActivity')
        time.sleep(3)
        tree = hierarchy()
        (out / 'production-start.png').write_bytes(adb('exec-out', 'screencap', '-p'))
        (out / 'production-start.xml').write_bytes(ET.tostring(tree))
        original_tr = any(n.get('text') == 'Kontrol' for n in tree.iter('node'))
        tap_description(tree, {'Dil', 'Language', 'TR', 'EN'})
        tree = hierarchy()
        expected = 'Control' if original_tr else 'Kontrol'
        assert any(n.get('text') == expected for n in tree.iter('node')), 'Language did not switch'
        tap_description(tree, {'Info', 'Bilgi'})
        tree = hierarchy()
        assert any(n.get('text') in {'Controller details and aircraft serial number.',
                                    'Kumanda bilgileri ve hava aracı seri numarası.'}
                   for n in tree.iter('node')), 'Info navigation failed'
        (out / 'production-info.png').write_bytes(adb('exec-out', 'screencap', '-p'))
        tap_description(tree, {'Log', 'Günlük'})
        tree = hierarchy()
        assert any(n.get('text') in {'Recent operations · newest first.',
                                    'Son işlemler · en yeni kayıt üstte.'}
                   for n in tree.iter('node')), 'Log navigation failed'
        tap_description(tree, {'Dil', 'Language', 'TR', 'EN'})
        records.append('PASS production language switch and Info/Log navigation; no hardware actions invoked')
        print(records[-1], flush=True)
finally:
    for command in [('wm', 'size', 'reset'), ('wm', 'density', 'reset'),
                    ('settings', 'put', 'system', 'user_rotation', '0'),
                    ('settings', 'put', 'system', 'font_scale', '1.0'),
                    ('settings', 'put', 'system', 'accelerometer_rotation', '1')]:
        with suppress(subprocess.SubprocessError):
            adb('shell', *command)
summary = 'capture-subset.txt' if args.only_device else 'capture-summary.txt'
(out / summary).write_text('\n'.join(records) + '\n')
