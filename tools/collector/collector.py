#!/usr/bin/env python3
"""
Коллектор диагностики: принимает NDJSON-события от приложения и разбирает их
в реальном времени.

Зачем именно так. Внутри песочницы Minis нет adb и нет Shizuku (проверено:
Shizuku NOT_RUNNING, а `adb devices` пуст — доступа к logcat устройства нет).
Зато проверено экспериментально: страница внутри WebView и системные
приложения МОГУТ достучаться до HTTP-сервера, поднятого в песочнице на
127.0.0.1. Значит петля обратной связи строится не через logcat, а через
собственный HTTP-приёмник: приложение отправляет NDJSON, коллектор его
разбирает, а агент читает готовую сводку.

Запуск:
    python3 collector.py [--port 8799] [--dir /var/minis/shared/webapp_clone/diag]

Эндпоинты:
    POST /ingest    — тело: одна или несколько строк NDJSON (по строке на событие)
    GET  /health    — {"ok":true,...}
    GET  /summary   — агрегированная сводка (что сломано и сколько раз)
    GET  /tail?n=50 — последние n событий
    GET  /report    — человекочитаемый отчёт (то, что читает агент)

Файлы:
    <dir>/events.ndjson   — сырой поток, append-only
    <dir>/report.txt      — отчёт, перезаписывается при каждом /report
    <dir>/collector.log   — служебный лог
"""
import argparse
import collections
import datetime
import http.server
import json
import os
import socketserver
import threading

# Пороги, при которых событие считается блокирующим релиз.
# Основано на каталоге дефектов PLAN.md §4: эти нарушения означают, что
# функциональность у пользователя ТОЧНО не работает, а не «возможно».
BLOCKING_CODES = {
    "FILE_CHOOSER_CALLBACK_LOST",
    "URL_LOADED_FROM_OVERRIDE",
    "RENDER_PROCESS_GONE",
    "CONFIG_CORRUPTED",
    "DOWNLOAD_BLOB_UNHANDLED",
    "SHORTCUT_ID_COLLISION",
    "ISOLATION_MODE_MISMATCH",
    "IME_OVERLAPS_INPUT",
    "INSETS_NOT_CONSUMED",
}

SEV_ORDER = {"TRACE": 0, "WARN": 1, "ERROR": 2, "FATAL": 3}


class Store:
    """Потокобезопасное хранилище событий. Пишет на диск и держит агрегаты."""

    def __init__(self, directory: str, keep_in_memory: int = 5000):
        self.dir = directory
        os.makedirs(directory, exist_ok=True)
        self.events_path = os.path.join(directory, "events.ndjson")
        self.report_path = os.path.join(directory, "report.txt")
        self.log_path = os.path.join(directory, "collector.log")
        self._lock = threading.Lock()
        self._recent = collections.deque(maxlen=keep_in_memory)
        self._by_code = collections.Counter()
        self._by_sev = collections.Counter()
        self._by_app = collections.Counter()
        self._first_seen = {}
        self._last_seen = {}
        self._examples = {}
        self._bad_lines = 0
        self._total = 0

    def add_raw(self, line: str):
        line = line.strip()
        if not line:
            return
        try:
            ev = json.loads(line)
        except Exception:
            with self._lock:
                self._bad_lines += 1
            self._log(f"BAD LINE: {line[:200]}")
            return
        self.add(ev)

    def add(self, ev: dict):
        code = str(ev.get("code", "UNKNOWN"))
        sev = str(ev.get("sev", "TRACE")).upper()
        app = str(ev.get("app") or "-")
        now = datetime.datetime.now().isoformat(timespec="seconds")
        with self._lock:
            self._total += 1
            self._recent.append(ev)
            self._by_code[code] += 1
            self._by_sev[sev] += 1
            if sev in ("ERROR", "FATAL"):
                self._by_app[app] += 1
            self._first_seen.setdefault(code, now)
            self._last_seen[code] = now
            if code not in self._examples and sev in ("WARN", "ERROR", "FATAL"):
                self._examples[code] = ev
            with open(self.events_path, "a", encoding="utf-8") as f:
                f.write(json.dumps(ev, ensure_ascii=False) + "\n")

    def _log(self, msg: str):
        with open(self.log_path, "a", encoding="utf-8") as f:
            f.write(f"{datetime.datetime.now().isoformat(timespec='seconds')} {msg}\n")

    def summary(self) -> dict:
        with self._lock:
            blocking = {c: n for c, n in self._by_code.items() if c in BLOCKING_CODES}
            return {
                "total_events": self._total,
                "bad_lines": self._bad_lines,
                "by_severity": dict(self._by_sev),
                "by_code": dict(self._by_code.most_common()),
                "blocking": blocking,
                "release_blocked": bool(blocking) or self._by_sev.get("FATAL", 0) > 0,
                "apps_with_errors": dict(self._by_app.most_common(20)),
                "first_seen": dict(self._first_seen),
                "last_seen": dict(self._last_seen),
            }

    def tail(self, n: int) -> list:
        with self._lock:
            return list(self._recent)[-n:]

    def report(self) -> str:
        s = self.summary()
        lines = []
        lines.append("=" * 68)
        lines.append(f"ОТЧЁТ ДИАГНОСТИКИ  {datetime.datetime.now().isoformat(timespec='seconds')}")
        lines.append("=" * 68)
        lines.append(f"событий принято: {s['total_events']}   битых строк: {s['bad_lines']}")
        sev = s["by_severity"]
        lines.append(
            "по важности: "
            + "  ".join(f"{k}={sev.get(k, 0)}" for k in ("FATAL", "ERROR", "WARN", "TRACE"))
        )
        lines.append("")

        if s["release_blocked"]:
            lines.append("!!! РЕЛИЗ ЗАБЛОКИРОВАН — нарушены контракты:")
            for code, n in sorted(s["blocking"].items(), key=lambda x: -x[1]):
                ex = self._examples.get(code, {})
                issue = ex.get("issue") or "-"
                lines.append(f"  [{n:4d}] {code}   (известен как {issue})")
                if ex.get("msg"):
                    lines.append(f"         {ex['msg']}")
                extra = {k[2:]: v for k, v in ex.items() if k.startswith("f_")}
                if extra:
                    lines.append(f"         поля: {extra}")
        else:
            lines.append("Блокирующих нарушений контрактов нет.")
        lines.append("")

        warn_codes = [
            (c, n)
            for c, n in s["by_code"].items()
            if c not in BLOCKING_CODES
            and SEV_ORDER.get(str(self._examples.get(c, {}).get("sev", "TRACE")).upper(), 0) >= 1
        ]
        if warn_codes:
            lines.append("Предупреждения (работает, но подозрительно):")
            for code, n in sorted(warn_codes, key=lambda x: -x[1]):
                ex = self._examples.get(code, {})
                lines.append(f"  [{n:4d}] {code}  {ex.get('msg', '')[:90]}")
            lines.append("")

        if s["apps_with_errors"]:
            lines.append("Веб-приложения с ошибками (по числу ERROR/FATAL):")
            for app, n in s["apps_with_errors"].items():
                lines.append(f"  {n:5d}  {app}")
            lines.append("")

        trace = {c: n for c, n in s["by_code"].items() if c not in [w[0] for w in warn_codes] and c not in BLOCKING_CODES}
        if trace:
            lines.append("Трассировка (для контекста): " + ", ".join(f"{c}={n}" for c, n in list(trace.items())[:12]))

        text = "\n".join(lines) + "\n"
        with open(self.report_path, "w", encoding="utf-8") as f:
            f.write(text)
        return text


def make_handler(store: Store):
    class Handler(http.server.BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, fmt, *args):
            pass  # свой лог, чтобы не засорять stdout

        def _send(self, code: int, body: bytes, ctype="application/json; charset=utf-8"):
            self.send_response(code)
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(len(body)))
            # Страница внутри WebView отправляет события кросс-origin.
            self.send_header("Access-Control-Allow-Origin", "*")
            self.send_header("Access-Control-Allow-Headers", "content-type")
            self.end_headers()
            self.wfile.write(body)

        def do_OPTIONS(self):
            self._send(204, b"")

        def do_POST(self):
            if not self.path.startswith("/ingest"):
                self._send(404, b'{"error":"not found"}')
                return
            n = int(self.headers.get("content-length") or 0)
            raw = self.rfile.read(n).decode("utf-8", "replace")
            count = 0
            for line in raw.splitlines():
                if line.strip():
                    store.add_raw(line)
                    count += 1
            self._send(200, json.dumps({"accepted": count}).encode())

        def do_GET(self):
            if self.path.startswith("/health"):
                self._send(200, json.dumps({"ok": True, "total": store.summary()["total_events"]}).encode())
            elif self.path.startswith("/summary"):
                self._send(200, json.dumps(store.summary(), ensure_ascii=False, indent=2).encode())
            elif self.path.startswith("/tail"):
                n = 50
                if "n=" in self.path:
                    try:
                        n = int(self.path.split("n=")[1].split("&")[0])
                    except Exception:
                        pass
                body = "\n".join(json.dumps(e, ensure_ascii=False) for e in store.tail(n))
                self._send(200, body.encode(), "text/plain; charset=utf-8")
            elif self.path.startswith("/report"):
                self._send(200, store.report().encode(), "text/plain; charset=utf-8")
            else:
                self._send(404, b'{"error":"not found"}')

    return Handler


class ThreadingServer(socketserver.ThreadingMixIn, socketserver.TCPServer):
    allow_reuse_address = True
    daemon_threads = True


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8799)
    ap.add_argument("--dir", default="/var/minis/shared/webapp_clone/diag")
    args = ap.parse_args()

    store = Store(args.dir)
    srv = ThreadingServer(("0.0.0.0", args.port), make_handler(store))
    store._log(f"collector started on :{args.port}, dir={args.dir}")
    print(f"collector on 0.0.0.0:{args.port} -> {args.dir}", flush=True)
    srv.serve_forever()


if __name__ == "__main__":
    main()
