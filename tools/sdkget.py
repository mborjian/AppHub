#!/usr/bin/env python3
"""Install Android SDK packages from a reachable mirror of Google's SDK repo.

Why
---
``dl.google.com`` - which hosts Google's Maven repo *and* the Android SDK
repository - is unreachable from this network: every request gets Google's
404 page. That breaks both ``sdkmanager`` (whose CLI this machine does not
even have installed) and AGP's own SDK auto-download, which is why
``android.builder.sdkDownload=false`` is set in gradle.properties.

Tencent mirrors the SDK repository with the exact same paths as
``dl.google.com/android/repository/`` under
``https://mirrors.cloud.tencent.com/AndroidSDK/``. So this script reads
Google's own repository manifest from the mirror, downloads the package and
unpacks it into the SDK the way ``sdkmanager`` would. Every package zip ships
its own ``source.properties`` (``Pkg.Revision``), which is all the SDK loader
and AGP need to recognise the package.

Usage
-----
    python tools/sdkget.py --list platform          # search package paths
    python tools/sdkget.py platforms;android-36     # install / update
    python tools/sdkget.py --force build-tools;36.0.0
    python tools/sdkget.py --sdk F:\\Android\\Sdk platforms;android-36

The SDK location defaults to ``sdk.dir`` from local.properties, then
``ANDROID_HOME`` / ``ANDROID_SDK_ROOT``.
"""

from __future__ import annotations

import argparse
import os
import shutil
import sys
import tempfile
import urllib.request
import zipfile
from pathlib import Path
from xml.etree import ElementTree as ET

MIRROR = "https://mirrors.cloud.tencent.com/AndroidSDK/"

# Google's repository manifests are split across several files; the modern
# packages (platforms, build-tools, NDK) live in -4, the tooling in -3/-2.
# First hit wins, so -4 is consulted first.
MANIFESTS = [
    "repository2-4.xml",
    "repository2-3.xml",
    "repository2-2.xml",
    "repository2-1.xml",
]

GOOGLE_URL_PREFIXES = (
    "https://dl.google.com/android/repository/",
    "http://dl.google.com/android/repository/",
    "https://redirector.gvt1.com/edgedl/android/repository/",
)

HOST = "windows" if sys.platform.startswith("win") else (
    "macosx" if sys.platform == "darwin" else "linux"
)


def fetch(url: str, attempts: int = 3) -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": "sdkget"})
    last: Exception | None = None
    for attempt in range(attempts):
        try:
            with urllib.request.urlopen(req, timeout=60) as resp:
                return resp.read()
        except Exception as exc:  # mirrors occasionally cut transfers short
            last = exc
    raise RuntimeError(f"fetch failed after {attempts} attempts: {url}: {last}")


def local_tag(tag: str) -> str:
    """Strip an XML namespace: '{ns}remotePackage' -> 'remotePackage'."""
    return tag.rsplit("}", 1)[-1]


def ser(el: ET.Element) -> str:
    """Serialize an element with namespaces stripped (package.xml uses
    unqualified child names inside <type-details>/<revision>)."""
    name = local_tag(el.tag)
    attrs = "".join(f' {local_tag(k)}="{v}"' for k, v in el.attrib.items())
    text = (el.text or "").strip()
    inner = text + "".join(ser(c) for c in el)
    return f"<{name}{attrs}>{inner}</{name}>" if inner else f"<{name}{attrs}/>"


# Namespace header of a sdkmanager-generated package.xml (copied verbatim from
# a real platform install; license-2802B623 is the standard android-sdk-license
# hash, and platform packages always depend on 'tools' rev >= 22).
PACKAGE_XML_HEADER = (
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
    '<ns2:repository xmlns:ns2="http://schemas.android.com/repository/android/common/02" '
    'xmlns:ns3="http://schemas.android.com/repository/android/common/01" '
    'xmlns:ns4="http://schemas.android.com/repository/android/generic/01" '
    'xmlns:ns5="http://schemas.android.com/repository/android/generic/02" '
    'xmlns:ns6="http://schemas.android.com/sdk/android/repo/repository2/04" '
    'xmlns:ns7="http://schemas.android.com/sdk/android/repo/repository2/01" '
    'xmlns:ns8="http://schemas.android.com/sdk/android/repo/repository2/02" '
    'xmlns:ns9="http://schemas.android.com/sdk/android/repo/repository2/03" '
    'xmlns:ns10="http://schemas.android.com/sdk/android/repo/addon2/01" '
    'xmlns:ns11="http://schemas.android.com/sdk/android/repo/addon2/02" '
    'xmlns:ns12="http://schemas.android.com/sdk/android/repo/addon2/03" '
    'xmlns:ns13="http://schemas.android.com/sdk/android/repo/addon2/04" '
    'xmlns:ns14="http://schemas.android.com/sdk/android/repo/sys-img2/05" '
    'xmlns:ns15="http://schemas.android.com/sdk/android/repo/sys-img2/04" '
    'xmlns:ns16="http://schemas.android.com/sdk/android/repo/sys-img2/03" '
    'xmlns:ns17="http://schemas.android.com/sdk/android/repo/sys-img2/02" '
    'xmlns:ns18="http://schemas.android.com/sdk/android/repo/sys-img2/01">'
    '<license id="license-2802B623" type="text"/>'
)


def mirror_url(url: str) -> str:
    """Rewrite a dl.google.com package URL onto the mirror."""
    for prefix in GOOGLE_URL_PREFIXES:
        if url.startswith(prefix):
            return MIRROR + url[len(prefix):]
    if url.startswith(("http://", "https://")):
        # Unknown host - try the path after 'android/repository/' anyway.
        marker = "/android/repository/"
        idx = url.find(marker)
        if idx != -1:
            return MIRROR + url[idx + len(marker):]
    return MIRROR + url  # already a relative path


class Package:
    def __init__(self, path: str, revision: str, display: str, archives: list,
                 type_details: str = "", revision_xml: str = ""):
        self.path = path
        self.revision = revision
        self.display = display
        self.archives = archives  # list of (host_os or None, url)
        self.type_details = type_details  # inner XML of <type-details>
        self.revision_xml = revision_xml  # XML of <revision>

    def archive_url(self) -> str | None:
        for osname, url in self.archives:
            if osname == HOST:
                return url
        for osname, url in self.archives:
            if osname is None:
                return url
        return self.archives[0][1] if self.archives else None


def load_packages() -> dict[str, Package]:
    packages: dict[str, Package] = {}
    for name in MANIFESTS:
        try:
            data = fetch(MIRROR + name)
        except Exception as exc:  # manifest may not exist on the mirror
            print(f"  (skipping {name}: {exc})", file=sys.stderr)
            continue
        try:
            root = ET.fromstring(data)
        except ET.ParseError:
            continue
        for pkg in root.iter():
            if local_tag(pkg.tag) != "remotePackage":
                continue
            path = pkg.get("path")
            if not path or path in packages:
                continue
            revision, display, archives = "", "", []
            type_details, revision_xml = "", ""
            for node in pkg.iter():
                tag = local_tag(node.tag)
                if tag == "revision" and not revision:
                    revision_xml = ser(node)
                    parts = [
                        int(c.text) for c in node
                        if (c.text or "").strip().isdigit()
                    ]
                    revision = ".".join(str(p) for p in parts) if parts else ""
                elif tag == "type-details" and not type_details:
                    type_details = "".join(ser(c) for c in node)
                elif tag == "display-name" and not display:
                    display = (node.text or "").strip()
                elif tag == "archive":
                    osname, url = None, None
                    for field in node.iter():
                        ftag = local_tag(field.tag)
                        if ftag == "host-os" and (field.text or "").strip():
                            osname = field.text.strip()
                        elif ftag in ("url", "complete-url") and not url:
                            url = (field.text or "").strip() or None
                    if url:
                        archives.append((osname, url))
            if archives:
                packages[path] = Package(path, revision, display, archives,
                                         type_details, revision_xml)
    return packages


def installed_revision(dest: Path) -> str | None:
    props = dest / "source.properties"
    if not props.is_file():
        return None
    for line in props.read_text(errors="replace").splitlines():
        if line.startswith("Pkg.Revision="):
            return line.split("=", 1)[1].strip()
    return None


def unwrap_root(tmp: Path) -> Path:
    """Package zips wrap their payload in a single folder (e.g. build-tools
    extracts to 'android-16/'). Descend while the root has no
    source.properties but exactly one directory does the holding."""
    for _ in range(4):
        if (tmp / "source.properties").is_file():
            return tmp
        entries = [p for p in tmp.iterdir() if p.name != "__MACOSX"]
        if len(entries) == 1 and entries[0].is_dir():
            tmp = entries[0]
            continue
        break
    return tmp


def write_package_xml(pkg: Package, dest: Path) -> bool:
    """Platform zips do not all ship a package.xml (platform-37.0 does not),
    and source.properties alone is not enough for the SDK loader to resolve a
    compileSdk target - AGP fails with 'Failed to find target with hash
    string'. Generate the file from the repository-manifest metadata, exactly
    as sdkmanager would have written it."""
    from xml.sax.saxutils import escape

    target = dest / "package.xml"
    if target.exists() or not pkg.type_details or not pkg.path.startswith("platforms;"):
        return False
    xml = (
        PACKAGE_XML_HEADER
        + f'<localPackage path="{escape(pkg.path)}" obsolete="false">'
        + '<type-details xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" '
        + 'xsi:type="ns6:platformDetailsType">'
        + pkg.type_details
        + "</type-details>"
        + (pkg.revision_xml or "<revision><major>1</major></revision>")
        + f"<display-name>{escape(pkg.display or pkg.path)}"
        + (f", rev {pkg.revision}" if pkg.revision else "")
        + "</display-name>"
        + '<uses-license ref="license-2802B623"/>'
        + '<dependencies><dependency path="tools"><min-revision><major>22'
        + "</major></min-revision></dependency></dependencies>"
        + "</localPackage></ns2:repository>"
    )
    target.write_text(xml, encoding="utf-8")
    return True


def install(pkg: Package, sdk: Path, force: bool) -> None:
    dest = sdk.joinpath(*pkg.path.split(";"))
    current = installed_revision(dest)
    if current is not None and current == pkg.revision and not force:
        print(f"{pkg.path}: already installed ({current})")
        if write_package_xml(pkg, dest):
            print(f"  wrote {dest / 'package.xml'}")
        return

    url = mirror_url(pkg.archive_url() or "")
    print(f"{pkg.path}: revision {pkg.revision or '?'}"
          f"{f' (installed {current})' if current else ''}")
    print(f"  <- {url}")

    with tempfile.TemporaryDirectory(prefix="sdkget-") as td:
        td = Path(td)
        zipto = td / "pkg.zip"
        data = fetch(url)
        zipto.write_bytes(data)
        if not zipfile.is_zipfile(zipto):
            raise SystemExit(f"download is not a zip ({len(data)} bytes): {url}")
        extract_to = td / "x"
        extract_to.mkdir()
        with zipfile.ZipFile(zipto) as zf:
            zf.extractall(extract_to)
        payload = unwrap_root(extract_to)

        if dest.exists():
            # Refuse to wipe anything that does not look like an SDK package.
            if installed_revision(dest) is None:
                raise SystemExit(
                    f"refusing to replace {dest}: not an SDK package"
                    " (no source.properties); remove it manually if intended"
                )
            shutil.rmtree(dest)
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.move(str(payload), str(dest))
    if write_package_xml(pkg, dest):
        print(f"  wrote {dest / 'package.xml'}")
    print(f"  -> {dest} ({installed_revision(dest) or 'no source.properties'})")


def default_sdk() -> Path:
    props = Path(__file__).resolve().parent.parent / "local.properties"
    if props.is_file():
        for line in props.read_text().splitlines():
            if line.startswith("sdk.dir="):
                return Path(line.split("=", 1)[1].strip().replace("\\", "/"))
    for var in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        if os.environ.get(var):
            return Path(os.environ[var])
    raise SystemExit("no SDK location: pass --sdk or set ANDROID_HOME")


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Install Android SDK packages from a reachable mirror "
                    "(dl.google.com is blocked on this network).",
        epilog="example: python tools/sdkget.py platforms;android-36",
    )
    parser.add_argument("packages", nargs="*",
                        help="package paths, e.g. platforms;android-36")
    parser.add_argument("--list", dest="filter", metavar="FILTER",
                        help="list matching package paths and exit")
    parser.add_argument("--sdk", help="SDK location (default: local.properties)")
    parser.add_argument("--force", action="store_true",
                        help="reinstall even if the revision is up to date")
    args = parser.parse_args()

    print(f"reading manifests from {MIRROR} ...", file=sys.stderr)
    packages = load_packages()
    if not packages:
        raise SystemExit("no packages found - is the mirror reachable?")

    if args.filter is not None:
        needle = args.filter.lower()
        for path in sorted(packages):
            if needle in path.lower():
                pkg = packages[path]
                print(f"{path:50s} {pkg.revision:10s} {pkg.display}")
        return

    if not packages or not args.packages:
        parser.error("give at least one package path (or use --list)")

    sdk = Path(args.sdk) if args.sdk else default_sdk()
    if not sdk.is_dir():
        raise SystemExit(f"SDK directory does not exist: {sdk}")
    for spec in args.packages:
        pkg = packages.get(spec)
        if pkg is None:
            raise SystemExit(
                f"unknown package {spec!r} - try: python tools/sdkget.py "
                f"--list {spec.split(';')[0]}"
            )
        install(pkg, sdk, args.force)


if __name__ == "__main__":
    main()
