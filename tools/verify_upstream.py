#!/usr/bin/env python3
"""Check hardware-source parity with the user-approved, pinned FreeFCC revision.

No network or hardware access. Run from any working directory with Python 3.
Identity, translations and DronePeak's updater are the only allowed adaptations.
"""
import difflib
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
COMMIT = "597157bd52120dfeb9677f79a8ad46b6027ce8dc"
APP = ROOT / "app/src/main/java/com/dronepeak/app"
failures = []


def upstream(path):
    return subprocess.check_output(
        ["git", "show", f"{COMMIT}:{path}"], cwd=ROOT, text=True
    ).replace("com.freefcc.app", "com.dronepeak.app")


def check(label, expected, actual):
    if expected.strip() != actual.strip():
        failures.append(label)
        print("".join(difflib.unified_diff(
            expected.splitlines(True), actual.splitlines(True),
            fromfile=f"upstream/{label}", tofile=f"local/{label}"
        )))
    else:
        print(f"PASS {label}")


def method(source, name):
    match = re.search(r"^    (?:private )?fun " + name + r"\([^\n]*", source, re.M)
    if not match:
        raise ValueError(f"Missing method: {name}")
    if "{" not in match.group():
        return match.group()
    start = source.index("{", match.start())
    depth = 0
    for pos in range(start, len(source)):
        if source[pos] == "{":
            depth += 1
        elif source[pos] == "}":
            depth -= 1
            if depth == 0:
                return source[match.start():pos + 1]
    raise ValueError(f"Unclosed method: {name}")


for name in ("DumlTransport.kt", "HardwareLock.kt", "BootReceiver.kt", "FccKeepaliveService.kt"):
    expected = upstream(f"app/src/main/java/com/freefcc/app/{name}")
    actual = (APP / name).read_text()
    if name in ("BootReceiver.kt", "FccKeepaliveService.kt"):
        expected = expected.replace('"freefcc"', '"dronepeak"')
    if name == "FccKeepaliveService.kt":
        actual = actual.replace('''        val language = AppLanguage.fromPref(
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString("language", null)
        )
''', "")
        actual = actual.replace('.setContentTitle("DronePeak-FCC")', '.setContentTitle("FreeFCC")')
        actual = actual.replace(
            '.setContentText(if (language == AppLanguage.TR) "FCC modu korunuyor..." else "Maintaining FCC mode...")',
            '.setContentText("Maintaining FCC mode...")'
        )
    check(name, expected, actual)

for asset in sorted((ROOT / "app/src/main/assets/profiles").glob("*.json")):
    check(f"profiles/{asset.name}", upstream(f"app/src/main/assets/profiles/{asset.name}"), asset.read_text())

# Loading downloaded profiles is part of the explicitly retained update channel.
expected = upstream("app/src/main/java/com/freefcc/app/Profiles.kt")
actual = (APP / "Profiles.kt").read_text().replace("import java.io.File\n", "")
actual = actual.replace('readProfileJson(context, fileName)', 'readAsset(context, "profiles/$fileName")')
actual = actual.replace('readProfileJson(context, "4g.json")', 'readAsset(context, "profiles/4g.json")')
actual = actual.replace(
    "/** Loads a static profile (FCC, CE restore, LED, device info) from storage override first, then bundled asset. */",
    "/** Loads a static profile (FCC, CE restore, LED, device info) from a JSON asset. */"
)
start = actual.index("    private fun readProfileJson(")
end = actual.index("    private fun hexToBytes(", start)
actual = actual[:start] + actual[end:]
check("Profiles.kt (update storage adapter excluded)", expected, actual)

expected = upstream("app/src/main/java/com/freefcc/app/FccViewModel.kt")
actual = (APP / "FccViewModel.kt").read_text()
for declaration in (r'private val MODELS_WITH_4G = setOf\([^\n]+',
                    r'private val transport = DumlTransport\(\)',
                    r'private val _state = MutableStateFlow\(AppState\(\)\)'):
    check("FccViewModel configuration", re.search(declaration, expected).group(),
          re.search(declaration, actual).group())
# Every upstream field keeps its original default. Additional fields are
# restricted to UI language and the DronePeak installer flow.
for declaration in re.findall(r'^    val \w+: [^\n]+', expected[expected.index('data class AppState('):
                             expected.index('/**\n * Manages all app state')], re.M):
    field = re.search(r'val (\w+):', declaration).group(1)
    check(f"AppState.{field}", declaration,
          re.search(r'^    val ' + field + r': [^\n]+', actual, re.M).group())
# Equivalent pure helper extractions are covered separately by JVM tests.
actual = actual.replace("SerialResolution.modelHint(serial)",
                        'Regex("[wW][aAmM][0-9]{3}").find(serial)?.value?.lowercase()')
actual = actual.replace('''        var serial = SerialResolution.pick("", _state.value.aircraftSerial,
            prefs.getString("aircraft_serial", "").orEmpty())''', '''        var serial = _state.value.aircraftSerial
        if (serial.isEmpty()) serial = prefs.getString("aircraft_serial", "").orEmpty()''')
core = expected[:expected.index("    // --- Updates ---")] + expected[expected.index("    // --- Helpers ---"):]
names = re.findall(r"^    (?:private )?fun (\w+)\(", core, re.M)
for name in names:
    check(f"FccViewModel.{name}", method(expected, name), method(actual, name))

if failures:
    sys.exit(f"Parity failed: {', '.join(failures)}")
print(f"Hardware parity verified against {COMMIT}; identity, UI, and updater adapters excluded.")
