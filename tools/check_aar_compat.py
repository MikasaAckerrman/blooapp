#!/usr/bin/env python3
"""
Проверка совместимости AAR-зависимостей с нашим compileSdk и AGP.

Зачем: у androidx-библиотек внутри AAR лежит файл aar-metadata.properties с
полями minCompileSdk и minAndroidGradlePluginVersion. Если взять «самую новую»
версию каждой библиотеки, сборка падает на checkDebugAarMetadata — что и
случилось с androidx.core 1.19.0, требующим compileSdk 37 и AGP 9.1.

Скрипт скачивает AAR указанных версий и печатает их требования, чтобы выбор
версий был основан на факте, а не на «взял последнюю из maven-metadata».

Использование:
    python3 tools/check_aar_compat.py            # проверить версии из каталога
    python3 tools/check_aar_compat.py --sdk 36 --agp 8.13.2
"""
import argparse
import io
import re
import sys
import urllib.request
import zipfile

GOOGLE = "https://dl.google.com/dl/android/maven2"
CENTRAL = "https://repo1.maven.org/maven2"


def fetch_metadata(group: str, artifact: str, version: str):
    """Вернуть dict из aar-metadata.properties или None."""
    path = group.replace(".", "/")
    for base in (GOOGLE, CENTRAL):
        url = f"{base}/{path}/{artifact}/{version}/{artifact}-{version}.aar"
        try:
            with urllib.request.urlopen(url, timeout=90) as r:
                data = r.read()
        except Exception:
            continue
        try:
            z = zipfile.ZipFile(io.BytesIO(data))
        except Exception:
            return None
        names = [n for n in z.namelist() if "aar-metadata" in n or "aar_metadata" in n]
        if not names:
            return {}
        text = z.read(names[0]).decode("utf-8", "replace")
        out = {}
        for line in text.splitlines():
            if "=" in line:
                k, v = line.split("=", 1)
                out[k.strip()] = v.strip()
        return out
    return None


def version_tuple(v: str):
    return tuple(int(x) for x in re.findall(r"\d+", v)[:3] or [0])


def parse_catalog(path: str):
    """Грубый разбор libs.versions.toml: {group:artifact: version}."""
    versions, libs = {}, {}
    section = None
    for raw in open(path, encoding="utf-8"):
        line = raw.split("#")[0].strip()
        if not line:
            continue
        if line.startswith("["):
            section = line.strip("[]")
            continue
        if section == "versions" and "=" in line:
            k, v = line.split("=", 1)
            versions[k.strip()] = v.strip().strip('"')
        elif section == "libraries" and "=" in line:
            name, body = line.split("=", 1)
            g = re.search(r'group\s*=\s*"([^"]+)"', body)
            a = re.search(r'name\s*=\s*"([^"]+)"', body)
            vr = re.search(r'version\.ref\s*=\s*"([^"]+)"', body)
            vlit = re.search(r'version\s*=\s*"([^"]+)"', body)
            if not (g and a):
                continue
            ver = versions.get(vr.group(1)) if vr else (vlit.group(1) if vlit else None)
            if ver:
                libs[f"{g.group(1)}:{a.group(1)}"] = ver
    return libs


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--catalog", default="gradle/libs.versions.toml")
    ap.add_argument("--sdk", type=int, default=36)
    ap.add_argument("--agp", default="8.13.2")
    args = ap.parse_args()

    libs = parse_catalog(args.catalog)
    agp = version_tuple(args.agp)
    problems = []

    print(f"compileSdk={args.sdk}  AGP={args.agp}\n")
    for coord, ver in sorted(libs.items()):
        group, artifact = coord.split(":")
        meta = fetch_metadata(group, artifact, ver)
        if meta is None:
            print(f"  ?    {coord}:{ver} — нет AAR (возможно, jar-библиотека)")
            continue
        need_sdk = int(meta.get("minCompileSdk", 0) or 0)
        need_agp = meta.get("minAndroidGradlePluginVersion", "0")
        ok = need_sdk <= args.sdk and version_tuple(need_agp) <= agp
        mark = "OK  " if ok else "БЕДА"
        print(f"  {mark} {coord}:{ver} — нужен compileSdk>={need_sdk}, AGP>={need_agp}")
        if not ok:
            problems.append((coord, ver, need_sdk, need_agp))

    if problems:
        print("\nНесовместимые зависимости:")
        for coord, ver, s, a in problems:
            print(f"  {coord}:{ver} требует compileSdk {s} и AGP {a}")
        return 1
    print("\nВсе зависимости совместимы.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
