#!/bin/sh
# Управление коллектором диагностики.
#
# КОНТЕКСТ (проверено 28.08.2026 экспериментально в этой песочнице):
#   - Shizuku НЕ запущен  -> logcat устройства недоступен;
#   - `adb devices` пуст, беспроводная отладка не включена -> adb logcat тоже нет;
#   - НО: страница внутри WebView и системные приложения УСПЕШНО достучались
#     до HTTP-сервера песочницы на 127.0.0.1 (подтверждено логом с User-Agent
#     "Mozilla/5.0 (Linux; Android 14; Pixel 8) ... Chrome/134").
# Значит петля «нашли ошибку в реальном времени» строится через собственный
# HTTP-приёмник, а не через системные средства отладки. Это и делает коллектор.
#
# Использование:
#   sh tools/diag.sh start        — поднять коллектор на 127.0.0.1:8799
#   sh tools/diag.sh stop         — остановить
#   sh tools/diag.sh status       — жив ли
#   sh tools/diag.sh report       — человекочитаемый отчёт (что сломано)
#   sh tools/diag.sh tail [N]     — последние N событий
#   sh tools/diag.sh reset        — обнулить накопленные события
#   sh tools/diag.sh selftest     — прогнать через коллектор набор известных
#                                   дефектов и убедиться, что он их ловит
set -e
PORT=${DIAG_PORT:-8799}
DIR=${DIAG_DIR:-/var/minis/shared/webapp_clone/diag}
HERE=$(cd "$(dirname "$0")" && pwd)

case "${1:-status}" in
  start)
    if curl -s --max-time 3 "http://127.0.0.1:$PORT/health" >/dev/null 2>&1; then
      echo "уже запущен на :$PORT"; exit 0
    fi
    mkdir -p "$DIR"
    setsid nohup python3 "$HERE/collector/collector.py" --port "$PORT" --dir "$DIR" \
      > "$DIR/stdout.log" 2>&1 < /dev/null &
    sleep 2
    curl -s --max-time 4 "http://127.0.0.1:$PORT/health" && echo
    ;;
  stop)
    pkill -f "collector.py --port $PORT" 2>/dev/null || true
    sleep 1
    echo "остановлен"
    ;;
  status)
    curl -s --max-time 4 "http://127.0.0.1:$PORT/health" 2>/dev/null && echo || echo "не запущен"
    ;;
  report)
    curl -s --max-time 8 "http://127.0.0.1:$PORT/report"
    ;;
  tail)
    curl -s --max-time 8 "http://127.0.0.1:$PORT/tail?n=${2:-30}"; echo
    ;;
  reset)
    sh "$0" stop
    # Обнуляем содержимое, а не удаляем файлы: в песочнице Minis обёртка
    # над rm/truncate блокирует удаление, и это правильно — данные под
    # /var/minis/shared принадлежат пользователю.
    : > "$DIR/events.ndjson" 2>/dev/null || true
    : > "$DIR/report.txt" 2>/dev/null || true
    sh "$0" start
    ;;
  selftest)
    sh "$0" start >/dev/null
    curl -s --max-time 5 -X POST --data-binary '{"ts":1,"sev":"ERROR","code":"FILE_CHOOSER_CALLBACK_LOST","issue":"NA#33","app":"selftest","msg":"selftest: потерянный callback"}
{"ts":2,"sev":"FATAL","code":"RENDER_PROCESS_GONE","issue":"","app":"selftest","msg":"selftest: вернули false"}
{"ts":3,"sev":"WARN","code":"URL_SCHEME_UNKNOWN","issue":"NA#177","app":"selftest","msg":"selftest: неизвестная схема"}' \
      "http://127.0.0.1:$PORT/ingest" >/dev/null
    sleep 1
    OUT=$(curl -s --max-time 8 "http://127.0.0.1:$PORT/summary")
    echo "$OUT" | python3 -c '
import sys, json
d = json.load(sys.stdin)
ok = d["release_blocked"] and "FILE_CHOOSER_CALLBACK_LOST" in d["blocking"]
print("SELFTEST:", "OK — коллектор ловит блокирующие дефекты" if ok else "FAIL")
raise SystemExit(0 if ok else 1)
'
    ;;
  *)
    echo "неизвестная команда: $1"; exit 2 ;;
esac
