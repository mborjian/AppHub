"""Build App Hub as a *release* APK signed with the Android platform key.

Why bother
----------
The platform certificate is the one the head unit's own apps are signed with
(SHA-256 ``c8a2e9bc…92ab8``). Signing with it is what makes the open-apps
feature work at all: ``REAL_GET_TASKS`` and ``FORCE_STOP_PACKAGES`` are
``signature|privileged`` permissions, so a *platform-signed* APK is granted
them at install time - the signature alone is enough, with no root at runtime,
no ``/system`` write and no ``privapp-permissions`` allowlist to get wrong.

That is the difference between App Hub listing the apps that are genuinely
open and force-stopping them for real, and a plain build that can only ask for
usage access and watch ``killBackgroundProcesses()`` do nothing (Android 14+
limits that call to the caller's own processes). ``--system`` is the other way
to hold the same permissions, at the cost of a remount, a reboot and the
allowlist above.

Gradle on its own cannot sign with a raw ``platform.pk8`` + ``platform.x509.pem``
pair, so the release APK is built unsigned and signed here with ``apksigner`` -
the same known-good sequence used for the launcher.

Usage
-----
    python tools/release.py                 # build + zipalign + sign + verify
    python tools/release.py --install       # ... then adb install -r
    python tools/release.py --install --reinstall  # uninstall first (key switch)
    python tools/release.py --online        # let Gradle use the network

    python tools/release.py --system        # privileged: /system/priv-app, remount + reboot

Keys are taken from ``--pk8``/``--pem``, then ``PLATFORM_PK8``/``PLATFORM_PEM``
environment variables, then ``tools/signing.properties`` (``pk8=`` / ``pem=``),
then the usual place on this machine.
"""
from __future__ import annotations

import argparse
import hashlib
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
APP = ROOT / "app"
RELEASE_DIR = APP / "build" / "outputs" / "apk" / "release"
UNSIGNED = RELEASE_DIR / "app-release-unsigned.apk"
ALIGNED = RELEASE_DIR / "app-release-aligned.apk"
SIGNED = RELEASE_DIR / "app-release-platform.apk"

PACKAGE = "com.mimskydo.apphub"
SYSTEM_DIR = "/system/priv-app/AppHub"
SYSTEM_APK = f"{SYSTEM_DIR}/AppHub.apk"
DEVICE_TMP = "/data/local/tmp/apphub.apk"

PLATFORM_CERT_SHA256 = "c8a2e9bccf597c2fb6dc66bee293fc13f2fc47ec77bc6b2b0d52c11f51192ab8"

DEFAULT_KEYS = [
    Path("C:/chery-launcher/keys"),
    ROOT.parent / "launcher_tool" / "workdir" / "keys",
    Path.home() / "chery-launcher" / "keys",
]

JAVA_CANDIDATES = [
    Path("C:/Program Files/Android/Android Studio1/jbr"),
    Path("C:/Program Files/Android/Android Studio/jbr"),
    Path("C:/Program Files/Java"),
]

def log(message: str) -> None:
    print(message, flush=True)

def die(message: str) -> None:
    log(f"\nERROR: {message}")
    sys.exit(1)

def java_major(home: Path) -> int | None:
    """Major version of a JDK, or None when it cannot be executed."""
    exe = home / "bin" / ("java.exe" if os.name == "nt" else "java")
    if not exe.exists():
        return None
    try:
        out = subprocess.run([str(exe), "-version"], capture_output=True,
                             text=True, timeout=60)
    except Exception:
        return None
    match = re.search(r'version "(\d+)(?:\.(\d+))?', out.stdout + out.stderr)
    if not match:
        return None
    major = int(match.group(1))
    return major if major != 1 else int(match.group(2) or 0)

def find_java_home() -> Path:
    """A JDK Gradle can actually run on (17-23; JDK 24/25 is too new)."""
    candidates = []
    if os.environ.get("JAVA_HOME"):
        candidates.append(Path(os.environ["JAVA_HOME"]))
    candidates += JAVA_CANDIDATES
    for jetbrains in sorted(Path("C:/Program Files/JetBrains").glob("*/jbr")) \
            if os.name == "nt" else []:
        candidates.append(jetbrains)
    for candidate in candidates:
        major = java_major(candidate)
        if major and 17 <= major <= 23:
            log(f"JDK        : {candidate} (Java {major})")
            return candidate
    die("no JDK 17-23 found. Set JAVA_HOME to one (Gradle 8.10 cannot run "
        "on JDK 24+). Android Studio's bundled jbr is a good choice.")

def android_sdk(prop: Path) -> Path | None:
    value = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if value:
        return Path(value)
    if prop.exists():
        for line in prop.read_text(encoding="utf-8").splitlines():
            if line.startswith("sdk.dir="):
                return Path(line.split("=", 1)[1].strip().replace("\\\\", "\\"))
    return None

def newest_build_tool(sdk: Path, name: str) -> Path | None:
    tools = sdk / "build-tools"
    if not tools.is_dir():
        return None
    for version in sorted(tools.iterdir(), reverse=True):
        for candidate in (version / name, version / f"{name}.exe",
                          version / f"{name}.bat"):
            if candidate.exists():
                return candidate
    return None

def run(argv, cwd=None, env=None, timeout=1800) -> subprocess.CompletedProcess:
    return subprocess.run([str(a) for a in argv], cwd=str(cwd) if cwd else None,
                          env=env, capture_output=True, text=True, timeout=timeout)

def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for block in iter(lambda: handle.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()

def signing_keys(args) -> tuple[Path, Path]:
    pk8 = Path(args.pk8) if args.pk8 else None
    pem = Path(args.pem) if args.pem else None
    if not pk8 and os.environ.get("PLATFORM_PK8"):
        pk8 = Path(os.environ["PLATFORM_PK8"])
    if not pem and os.environ.get("PLATFORM_PEM"):
        pem = Path(os.environ["PLATFORM_PEM"])

    props = ROOT / "tools" / "signing.properties"
    if props.exists():
        for line in props.read_text(encoding="utf-8").splitlines():
            if line.startswith("pk8=") and not pk8:
                pk8 = Path(line.split("=", 1)[1].strip())
            if line.startswith("pem=") and not pem:
                pem = Path(line.split("=", 1)[1].strip())

    if not (pk8 and pem):
        for directory in DEFAULT_KEYS:
            if (directory / "platform.pk8").exists():
                pk8 = pk8 or directory / "platform.pk8"
                pem = pem or directory / "platform.x509.pem"
                break

    if not (pk8 and pk8.exists()):
        die("platform.pk8 not found. Pass --pk8/--pem, set PLATFORM_PK8/"
            "PLATFORM_PEM, or create tools/signing.properties.")
    if not (pem and pem.exists()):
        die("platform.x509.pem not found (same options as --pk8).")
    return pk8, pem

def adb_path(sdk: Path) -> Path | None:
    for candidate in (sdk / "platform-tools" / "adb.exe",
                      sdk / "platform-tools" / "adb"):
        if candidate.exists():
            return candidate
    found = shutil.which("adb")
    return Path(found) if found else None

def adb(adb_exe: Path, *args, check=True, timeout=300) -> str:
    result = run([adb_exe, *args], timeout=timeout)
    output = (result.stdout or "") + (result.stderr or "")
    if check and result.returncode != 0:
        die(f"adb {' '.join(args)} failed:\n{output.strip()}")
    return output

def device_sha256(adb_exe: Path, remote: str) -> str:
    out = adb(adb_exe, "shell", f"sha256sum {remote}", check=False)
    parts = out.split()
    return parts[0] if parts else ""

def list_devices(adb_exe: Path) -> list[str]:
    out = adb(adb_exe, "devices", check=False)
    return [line.split("\t")[0] for line in out.splitlines()[1:]
            if "\tdevice" in line]

def build(args, java_home: Path) -> None:
    wrapper = ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")
    if not wrapper.exists():
        die(f"{wrapper} is missing; run `gradle wrapper` in {ROOT} first.")
    argv = [wrapper, "assembleRelease"]
    if not args.online:
        argv += ["--offline"]
    env = os.environ.copy()
    env["JAVA_HOME"] = str(java_home)
    env["PATH"] = str(java_home / "bin") + os.pathsep + env.get("PATH", "")
    log(f"\n[1/4] gradle {' '.join(str(a) for a in argv[1:])}")
    result = run(argv, cwd=ROOT, env=env)
    if result.returncode != 0:
        die("gradle assembleRelease failed:\n"
            + (result.stdout or "")[-2000:] + (result.stderr or "")[-2000:])
    if not UNSIGNED.exists():
        die(f"expected {UNSIGNED} after the build")
    log(f"      built {UNSIGNED.name} ({UNSIGNED.stat().st_size:,} bytes)")

def sign(args, sdk: Path, java_home: Path, pk8: Path, pem: Path) -> None:
    zipalign = newest_build_tool(sdk, "zipalign")
    apksigner = newest_build_tool(sdk, "apksigner")
    if not zipalign or not apksigner:
        die(f"zipalign/apksigner not found under {sdk / 'build-tools'}")

    env = os.environ.copy()
    env["JAVA_HOME"] = str(java_home)
    env["PATH"] = str(java_home / "bin") + os.pathsep + env.get("PATH", "")

    log("\n[2/4] zipalign -p -f 4")
    for stale in (ALIGNED, SIGNED):
        stale.unlink(missing_ok=True)
    result = run([zipalign, "-p", "-f", "4", UNSIGNED, ALIGNED], env=env)
    if result.returncode != 0 or not ALIGNED.exists():
        die(f"zipalign failed: {(result.stderr or result.stdout)[-500:]}")

    log("[3/4] apksigner sign (v1 off, v2+v3 on, platform key)")
    result = run([apksigner, "sign",
                  "--key", pk8, "--cert", pem,
                  "--v1-signing-enabled", "false",
                  "--v2-signing-enabled", "true",
                  "--v3-signing-enabled", "true",
                  "--out", SIGNED, ALIGNED], env=env)
    if result.returncode != 0 or not SIGNED.exists():
        die(f"apksigner failed: {(result.stderr or result.stdout)[-800:]}")

    log("[4/4] verify")
    result = run([apksigner, "verify", "--verbose", "--print-certs", SIGNED],
                 env=env)
    output = (result.stdout or "") + (result.stderr or "")
    if result.returncode != 0:
        die(f"apksigner verify failed:\n{output[-800:]}")
    match = re.search(r"certificate SHA-256 digest:\s*([0-9a-f]{64})", output)
    if not match:
        die("could not read the signer certificate digest")
    digest = match.group(1)
    log(f"      cert  {digest}")
    log(f"      sha256 {sha256(SIGNED)}")
    if digest != PLATFORM_CERT_SHA256:
        log(f"      WARNING: not the expected platform certificate "
            f"({PLATFORM_CERT_SHA256})")
    else:
        log("      certificate is the AOSP platform key (matches the unit)")

def need_device(adb_exe: Path | None) -> Path:
    if not adb_exe:
        die("adb not found (set ANDROID_HOME or put it on PATH)")
    devices = list_devices(adb_exe)
    if not devices:
        die("no device attached (`adb devices` is empty). Connect the head "
            "unit over USB and enable USB debugging.")
    return adb_exe

def install_normal(args, adb_exe: Path) -> None:
    if args.reinstall:
        log(f"\n      adb uninstall {PACKAGE} (may fail if it is not there)")
        adb(adb_exe, "uninstall", PACKAGE, check=False)
    log(f"\n[install] adb install -r {SIGNED.name}")
    out = adb(adb_exe, "install", "-r", str(SIGNED), check=False)
    log("      " + out.strip().replace("\n", "\n      "))
    if "Success" not in out:
        die("install failed. If it mentions signatures, re-run with "
            "--reinstall (the debug build used a different key).")

def install_system(args, adb_exe: Path) -> None:
    log("\n[system] installing as a privileged app in /system/priv-app")

    control = adb(adb_exe, "shell", "getprop ro.control_privapp_permissions",
                  check=False).strip()
    log(f"      ro.control_privapp_permissions = {control or '(unset)'}")
    if control == "enforce":
        log("      WARNING: this build enforces the privileged-permission")
        log("      allowlist. FORCE_STOP_PACKAGES is not in it by default, so")
        log("      the unit may refuse to boot with this app in /system/priv-app.")
        log("      Safer: install normally (adb install) and remove the")
        log("      FORCE_STOP_PACKAGES line from AndroidManifest.xml first.")
        if not args.force:
            die("--system refused. Re-run with --force if you are sure "
                "(and have a way to restore /system).")
    log("      adb root / adb remount (the root fs is ro after every boot)")
    adb(adb_exe, "root", check=False, timeout=120)
    adb(adb_exe, "wait-for-device", check=False, timeout=120)
    adb(adb_exe, "remount", check=False, timeout=180)
    mount = adb(adb_exe, "shell", "mount | grep ' / '", check=False)
    log("      " + mount.strip())
    if " rw," not in mount and " rw " not in mount:
        die("the root filesystem is still read-only; nothing was written. "
            "Run `adb root` then `adb remount` and try again.")

    log(f"\n      push -> {DEVICE_TMP}")
    adb(adb_exe, "push", str(SIGNED), DEVICE_TMP)
    if device_sha256(adb_exe, DEVICE_TMP) != sha256(SIGNED):
        die("the pushed file does not match the local APK; aborting.")

    log(f"      adb uninstall {PACKAGE} (clears any user-data install)")
    adb(adb_exe, "uninstall", PACKAGE, check=False)

    log(f"      cp -> {SYSTEM_APK} (+ owner, mode, SELinux label)")
    adb(adb_exe, "shell", f"mkdir -p {SYSTEM_DIR}")
    adb(adb_exe, "shell", f"cp {DEVICE_TMP} {SYSTEM_APK}")
    adb(adb_exe, "shell", f"chown root:root {SYSTEM_APK}")
    adb(adb_exe, "shell", f"chmod 0644 {SYSTEM_APK}")
    adb(adb_exe, "shell", f"restorecon {SYSTEM_APK}")

    installed = device_sha256(adb_exe, SYSTEM_APK)
    log("      " + adb(adb_exe, "shell", f"ls -lZ {SYSTEM_APK}",
                       check=False).strip())
    if installed != sha256(SIGNED):
        die(f"installed hash {installed} != local {sha256(SIGNED)}; "
            "nothing is rebooted.")
    log("      installed hash matches")

    if args.reboot:
        log("\n      adb reboot")
        adb(adb_exe, "reboot", check=False)
        log("      the app is installed as a system app; it will only be "
            "recognised after the unit boots")

def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--pk8", help="platform private key (PKCS#8 DER)")
    parser.add_argument("--pem", help="platform certificate (X.509 PEM)")
    parser.add_argument("--online", action="store_true",
                        help="let Gradle resolve dependencies over the network")
    parser.add_argument("--install", action="store_true",
                        help="adb install -r the signed release APK")
    parser.add_argument("--reinstall", action="store_true",
                        help="uninstall the existing package first")
    parser.add_argument("--system", action="store_true",
                        help="install into /system/priv-app as a privileged app")
    parser.add_argument("--no-reboot", dest="reboot", action="store_false",
                        default=True,
                        help="with --system, do not reboot afterwards")
    parser.add_argument("--force", action="store_true",
                        help="with --system, proceed even when the build "
                             "enforces the privileged-permission allowlist")
    args = parser.parse_args()

    sdk = android_sdk(ROOT / "local.properties")
    if not sdk:
        die("Android SDK not found: set ANDROID_HOME or add sdk.dir to "
            "local.properties")
    log(f"SDK        : {sdk}")
    java_home = find_java_home()
    pk8, pem = signing_keys(args)
    log(f"key        : {pk8}")
    log(f"cert       : {pem}")

    build(args, java_home)
    sign(args, sdk, java_home, pk8, pem)

    log(f"\nAPK        : {SIGNED}")
    if args.system:
        install_system(args, need_device(adb_path(sdk)))
    elif args.install:
        install_normal(args, need_device(adb_path(sdk)))
    else:
        log("Nothing was installed. Add --install (normal app) or --system "
            "(privileged system app).")

if __name__ == "__main__":
    main()
