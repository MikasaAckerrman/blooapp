#!/bin/sh
# Локальная проверка фундамента в песочнице Minis.
#
# Проверено: Alpine aarch64 в песочнице НЕ имеет Android SDK (в репозитории
# Google нет linux+aarch64 сборок build-tools — 0 архивов), поэтому модуль :app
# здесь не собирается. Зато Gradle 8.11.1 + JDK 17/21 есть, и чистые
# Kotlin/JVM-модули тестируются полноценно, включая проверку падений.
#
# Использование:
#   sh tools/verify.sh            — прогнать тесты и показать сводку
#   sh tools/verify.sh --mutate   — дополнительно убедиться, что тесты РЕАЛЬНО
#                                   падают при поломке кода (защита от
#                                   «зелёных, но ничего не проверяющих» тестов)
set -e
cd "$(dirname "$0")/.."

echo "== gradle test =="
timeout 900 gradle --console=plain --no-daemon test "$@" 2>&1 | tail -6 || true

echo
echo "== сводка =="
python3 - <<'PY'
import glob, xml.etree.ElementTree as ET
rows, tot, fail = [], 0, 0
for p in glob.glob("*/build/test-results/test/*.xml"):
    r = ET.parse(p).getroot()
    n = int(r.get('tests', 0)); f = int(r.get('failures', 0)) + int(r.get('errors', 0))
    rows.append((r.get('name'), n, f)); tot += n; fail += f
for name, n, f in sorted(rows):
    mark = "OK " if f == 0 else "FAIL"
    print(f"  {mark} {name}: {n} тестов, {f} провалов")
print(f"  ИТОГО: {tot} тестов, {fail} провалов")
raise SystemExit(1 if fail else 0)
PY
