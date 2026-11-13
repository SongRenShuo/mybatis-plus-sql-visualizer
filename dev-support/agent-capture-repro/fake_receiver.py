# -*- coding: utf-8 -*-
"""
Fake IDE-side receiver for the SQL visualizer agent protocol.

Authoritative contract (read from source this run, do not trust memory):
  - Frame: [4 bytes big-endian length][1 byte command][length bytes UTF-8 JSON body]
    src/main/kotlin/cn/song/mybatisplus/visualizer/log/agent/AgentProtocol.kt:7
    sub-projects/sql-visualizer-agent/.../transport/SenderLoop.java:549 writeFrame
  - The AGENT dials 127.0.0.1:<port> as a TCP *client* (SenderLoop.java:225 dialer.dial("127.0.0.1", serverPort)),
    so this fake receiver must be a TCP *server* (listener).
  - Commands: 1=EVENT, 2=HEARTBEAT, 3=CONFIG(IDE->agent), 4=HELLO, 5=CONFIG_ACK(agent->IDE)
    (AgentProtocol.kt:18-38 / EventBus.java:17-28)
  - CONFIG body: {"disabled":<bool>,"desensitize":<bool>} (AgentProtocol.kt:65)

Usage: python fake_receiver.py <port> <logfile> [--release-after N]
Writes one line per frame to <logfile>, flush after each.
"""
import json
import socket
import struct
import sys
import time

CMD_NAMES = {1: "EVENT", 2: "HEARTBEAT", 3: "CONFIG", 4: "HELLO", 5: "CONFIG_ACK"}


def log(fh, msg):
    line = "[%.3f] %s" % (time.time(), msg)
    fh.write(line + "\n")
    fh.flush()
    print(line, flush=True)


def send_frame(conn, cmd, body):
    payload = body.encode("utf-8")
    conn.sendall(struct.pack(">i", len(payload)) + bytes([cmd]) + payload)


def read_exact(conn, n):
    buf = b""
    while len(buf) < n:
        chunk = conn.recv(n - len(buf))
        if not chunk:
            return None
        buf += chunk
    return buf


def describe(cmd, body):
    name = CMD_NAMES.get(cmd, "UNKNOWN(%d)" % cmd)
    if cmd == 1:
        try:
            ev = json.loads(body)
        except Exception as e:  # noqa
            return "%s len=%d JSON_PARSE_FAIL %s raw=%r" % (name, len(body), e, body[:200])
        sql = ev.get("sql")
        sql_short = (sql[:160] + "...") if sql and len(sql) > 160 else sql
        return ("%s len=%d sql=%r ts=%s dn=%s u=%s sf=%s sl=%s mk=%s th=%s url=%s err=%s"
                % (name, len(body), sql_short, ev.get("ts"), ev.get("dn"), ev.get("u"),
                   ev.get("sf"), ev.get("sl"), ev.get("mk"), ev.get("th"), ev.get("url"), ev.get("err")))
    return "%s len=%d body=%s" % (name, len(body), body[:400])


def serve(port, fh, release_after):
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("127.0.0.1", port))
    srv.listen(5)
    log(fh, "LISTEN 127.0.0.1:%d (agent is the dialer; this is the server)" % port)
    conn_idx = 0
    while True:
        conn, addr = srv.accept()
        conn_idx += 1
        log(fh, "ACCEPT #%d from %s:%d" % (conn_idx, addr[0], addr[1]))
        send_frame(conn, 3, '{"disabled":false,"desensitize":false}')
        log(fh, "SENT CONFIG(3) {\"disabled\":false,\"desensitize\":false}")
        counts = {"EVENT": 0, "HEARTBEAT": 0, "HELLO": 0, "CONFIG_ACK": 0, "OTHER": 0}
        try:
            while True:
                hdr = read_exact(conn, 4)
                if hdr is None:
                    log(fh, "EOF on connection #%d" % conn_idx)
                    break
                (ln,) = struct.unpack(">i", hdr)
                if ln <= 0 or ln > (1 << 20):
                    log(fh, "BAD FRAME LEN %d on connection #%d -- aborting read" % (ln, conn_idx))
                    break
                cmd_byte = read_exact(conn, 1)
                if cmd_byte is None:
                    log(fh, "EOF before cmd byte on connection #%d" % conn_idx)
                    break
                cmd = cmd_byte[0]
                body_b = read_exact(conn, ln)
                if body_b is None:
                    log(fh, "EOF mid-body on connection #%d" % conn_idx)
                    break
                body = body_b.decode("utf-8", errors="replace")
                key = CMD_NAMES.get(cmd, "OTHER")
                counts[key] = counts.get(key, 0) + 1
                log(fh, "FRAME#%-4d %s" % (sum(counts.values()), describe(cmd, body)))
                if key == "EVENT" and counts["EVENT"] in (1, 5, 20):
                    log(fh, "  >>> event #%d summary: %s" % (counts["EVENT"], describe(cmd, body)))
        except Exception as e:  # noqa
            log(fh, "EXC on connection #%d: %r" % (conn_idx, e))
        finally:
            try:
                conn.close()
            except Exception:  # noqa
                pass
            log(fh, "CONN #%d CLOSED frames=%s" % (conn_idx, counts))


if __name__ == "__main__":
    port = int(sys.argv[1])
    logfile = sys.argv[2]
    release_after = int(sys.argv[3]) if len(sys.argv) > 3 else 0
    import os
    with open(logfile + ".pid", "w") as pf:
        pf.write(str(os.getpid()))
    with open(logfile, "a", encoding="utf-8") as fh:
        log(fh, "PID=%d port=%d logfile=%s" % (os.getpid(), port, logfile))
        serve(port, fh, release_after)
