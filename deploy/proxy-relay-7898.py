#!/usr/bin/env python3
"""TCP relay: 0.0.0.0:7898 -> 127.0.0.1:7897
让 k3s Pod 可以复用 Windows 侧代理（Clash 等，仅监听 loopback）。
Pod 内使用 HTTP(S)_PROXY=http://10.42.0.1:7898 或 http://<node-ip>:7898。
"""
import socket
import threading

LISTEN = ('0.0.0.0', 7898)
TARGET = ('127.0.0.1', 7897)


def pipe(src, dst):
    try:
        while True:
            data = src.recv(65536)
            if not data:
                break
            dst.sendall(data)
    except Exception:
        pass
    finally:
        try:
            dst.shutdown(socket.SHUT_WR)
        except Exception:
            pass


def handle(conn):
    try:
        upstream = socket.create_connection(TARGET, timeout=10)
    except Exception:
        conn.close()
        return
    threading.Thread(target=pipe, args=(conn, upstream), daemon=True).start()
    threading.Thread(target=pipe, args=(upstream, conn), daemon=True).start()


def main():
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    s.bind(LISTEN)
    s.listen(128)
    print('proxy-relay listening on', LISTEN, flush=True)
    while True:
        conn, _ = s.accept()
        threading.Thread(target=handle, args=(conn,), daemon=True).start()


if __name__ == '__main__':
    main()
