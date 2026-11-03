"""Evaluate a JavaScript expression in a page over the Chrome DevTools protocol.

Stdlib only (minimal WebSocket client). Used to inspect Lenshift's own debug WebView
through `adb forward tcp:PORT localabstract:webview_devtools_remote_PID`.

Usage: python -I cdp_eval.py PORT 'expression'
"""
import base64
import json
import os
import socket
import struct
import sys
import urllib.request


def ws_connect(url):
    host_port, path = url[len("ws://"):].split("/", 1)
    host, port = host_port.split(":")
    sock = socket.create_connection((host, int(port)), timeout=20)
    key = base64.b64encode(os.urandom(16)).decode()
    sock.sendall((
        f"GET /{path} HTTP/1.1\r\nHost: {host_port}\r\nUpgrade: websocket\r\n"
        f"Connection: Upgrade\r\nSec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n\r\n"
    ).encode())
    response = b""
    while b"\r\n\r\n" not in response:
        response += sock.recv(4096)
    if b" 101 " not in response.split(b"\r\n", 1)[0]:
        raise SystemExit("websocket handshake failed: " + response.decode(errors="replace"))
    return sock


def ws_send(sock, text):
    data = text.encode()
    header = bytearray([0x81])
    mask = os.urandom(4)
    if len(data) < 126:
        header.append(0x80 | len(data))
    elif len(data) < 65536:
        header.append(0x80 | 126)
        header += struct.pack(">H", len(data))
    else:
        header.append(0x80 | 127)
        header += struct.pack(">Q", len(data))
    sock.sendall(bytes(header) + mask + bytes(b ^ mask[i % 4] for i, b in enumerate(data)))


def recv_exact(sock, n):
    buf = b""
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise SystemExit("connection closed")
        buf += chunk
    return buf


def ws_recv(sock):
    message = b""
    while True:
        b1, b2 = recv_exact(sock, 2)
        length = b2 & 0x7F
        if length == 126:
            length = struct.unpack(">H", recv_exact(sock, 2))[0]
        elif length == 127:
            length = struct.unpack(">Q", recv_exact(sock, 8))[0]
        payload = recv_exact(sock, length)
        message += payload
        if b1 & 0x80:
            return message.decode()


def main():
    port, expression = sys.argv[1], sys.argv[2]
    targets = json.load(urllib.request.urlopen(f"http://127.0.0.1:{port}/json"))
    page = next(t for t in targets if t.get("type") == "page")
    sock = ws_connect(page["webSocketDebuggerUrl"])
    ws_send(sock, json.dumps({
        "id": 1,
        "method": "Runtime.evaluate",
        "params": {"expression": expression, "returnByValue": True, "awaitPromise": True},
    }))
    while True:
        reply = json.loads(ws_recv(sock))
        if reply.get("id") == 1:
            result = reply.get("result", {})
            if "exceptionDetails" in result:
                print("EXCEPTION", json.dumps(result["exceptionDetails"])[:2000])
            else:
                value = result.get("result", {}).get("value")
                print(value if isinstance(value, str) else json.dumps(value, indent=1))
            return


if __name__ == "__main__":
    main()
