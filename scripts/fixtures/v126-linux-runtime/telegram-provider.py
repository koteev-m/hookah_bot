#!/usr/bin/env python3
"""Synthetic TLS provider inside an isolated internal Docker network only.

No proxy/forwarding code exists. Unexpected hosts, tokens, routes and methods fail.
Only aggregate progress counters are retained; request payloads are never logged.
"""
import http.server
import json
import os
from pathlib import Path
import ssl
import threading
import time
from urllib.parse import parse_qs, urlsplit

ROOT = Path('/fixture')
TOKEN = os.environ['FIXTURE_TELEGRAM_TOKEN']
STATE = {'getUpdates': 0, 'getWebhookInfo': 0, 'unexpected': 0,
         'last_offset': None, 'denied_delivered': False, 'outbound': 0}
LOCK = threading.Lock()


class Handler(http.server.BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def reply(self, status, data):
        payload = json.dumps(data, sort_keys=True).encode()
        self.send_response(status)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(payload)))
        self.end_headers()
        try:
            self.wfile.write(payload)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def do_GET(self):
        parsed = urlsplit(self.path)
        host = self.headers.get('Host', '').split(':', 1)[0]
        if parsed.path == '/__fixture__/stats' and host in ('127.0.0.1', 'localhost'):
            with LOCK:
                value = dict(STATE)
            self.reply(200, value)
            return
        if host != 'api.telegram.org' or not parsed.path.startswith('/bot' + TOKEN + '/'):
            with LOCK:
                STATE['unexpected'] += 1
            self.reply(403, {'ok': False})
            return
        method = parsed.path.rsplit('/', 1)[1]
        if method == 'getWebhookInfo':
            with LOCK:
                STATE[method] += 1
            self.reply(200, {'ok': True, 'result': {'url': '', 'pending_update_count': 0}})
        elif method == 'getUpdates':
            offset = parse_qs(parsed.query).get('offset', [None])[0]
            with LOCK:
                STATE[method] += 1
                STATE['last_offset'] = int(offset) if offset is not None else None
            time.sleep(0.2)
            updates = []
            if (ROOT / 'deny-update').exists() and (offset is None or int(offset) <= 81001):
                updates = [{'update_id': 81001, 'message': {
                    'message_id': 1, 'date': int(time.time()), 'text': '/start',
                    'from': {'id': 81001999, 'is_bot': False, 'first_name': 'Synthetic'},
                    'chat': {'id': 81001999, 'type': 'private', 'first_name': 'Synthetic'}}}]
                with LOCK:
                    STATE['denied_delivered'] = True
            self.reply(200, {'ok': True, 'result': updates})
        else:
            with LOCK:
                STATE['unexpected'] += 1
            self.reply(403, {'ok': False})

    def do_POST(self):
        # Startup command-menu calls are allowed; all message/data operations refuse.
        parsed = urlsplit(self.path)
        host = self.headers.get('Host', '').split(':', 1)[0]
        length = int(self.headers.get('Content-Length', '0'))
        if length > 16384:
            self.reply(413, {'ok': False})
            return
        self.rfile.read(length)
        expected = '/bot' + TOKEN + '/'
        if (host == 'api.telegram.org' and parsed.path.startswith(expected)
                and parsed.path[len(expected):] in ('deleteMyCommands', 'setMyCommands')):
            self.reply(200, {'ok': True, 'result': True})
            return
        with LOCK:
            STATE['outbound'] += 1
            STATE['unexpected'] += 1
        self.reply(403, {'ok': False})


server = http.server.ThreadingHTTPServer(('0.0.0.0', 443), Handler)
context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
context.load_cert_chain(ROOT / 'provider.crt', ROOT / 'provider.key')
server.socket = context.wrap_socket(server.socket, server_side=True)
server.serve_forever(poll_interval=0.1)
