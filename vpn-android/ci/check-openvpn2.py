#!/usr/bin/env python3
"""Rootless synthetic tests of a real OpenVPN 2 engine's Android fd protocol.
Private generated certs/configs and native output are kept transient; results
contain assertions/counts only. Test addresses use loopback and documentation
ranges; no user profiles are read. This does not exercise Android VpnService.
"""
from pathlib import Path
import argparse
import array
import ipaddress
import json
import os
import re
import select
import socket
import struct
import subprocess
import sys
import tempfile
import threading
import time

def run_ssl(args):
    p = subprocess.run(['openssl', *args], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if p.returncode:
        raise RuntimeError('Synthetic certificate generation failed')

def certs(path):
    path.chmod(0o700)
    ca = path / 'ca'
    ca.mkdir()
    run_ssl(['req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-keyout', str(ca / 'key.pem'), '-out', str(ca / 'cert.pem'), '-subj', '/CN=Harness CA', '-days', '2'])
    wrong = path / 'wrong'
    wrong.mkdir()
    run_ssl(['req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-keyout', str(wrong / 'key.pem'), '-out', str(wrong / 'cert.pem'), '-subj', '/CN=Untrusted Harness CA', '-days', '2'])
    for name, role in [('server', 'serverAuth'), ('client', 'clientAuth'), ('wrongrole', 'clientAuth')]:
        folder = path / name
        folder.mkdir()
        run_ssl(['req', '-new', '-newkey', 'rsa:2048', '-nodes', '-keyout', str(folder / 'key.pem'), '-out', str(folder / 'req.pem'), '-subj', '/CN=' + name])
        (folder / 'ext.cnf').write_text('basicConstraints=CA:FALSE\nkeyUsage=digitalSignature,keyEncipherment\nextendedKeyUsage=' + role + '\n')
        run_ssl(['x509', '-req', '-in', str(folder / 'req.pem'), '-CA', str(ca / 'cert.pem'), '-CAkey', str(ca / 'key.pem'), '-set_serial', str({'server': 2, 'client': 3, 'wrongrole': 4}[name]), '-out', str(folder / 'cert.pem'), '-days', '2', '-extfile', str(folder / 'ext.cnf')])
    for file in path.rglob('*'):
        if file.is_file():
            file.chmod(0o600)

def inline(name, path):
    return f'<{name}>\n{path.read_text()}</{name}>\n'

def free_port():
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
        s.bind(('127.0.0.1', 0))
        return s.getsockname()[1]

class Peer:

    def __init__(self, binary, root, name, config, cancel_tun=False, auth_test=False, reject_type=None, launcher_owner=False):
        self.reject_type = reject_type
        self.dns_rejected = False
        self.auth_test = auth_test
        self.auth_denied = False
        self.auth_allowed = False
        self.auth_requested = False
        self.native_pid = None
        self.pending_auth = None
        self.auth_fields = {}
        self.auth_token_events = 0
        self.name = name
        self.ready = threading.Event()
        self.up = threading.Event()
        self.done = threading.Event()
        self.states = []
        self.types = []
        self.errors = []
        self.cancel_tun = cancel_tun
        self.tun = None
        self.fd_count = 0
        self.verification_failure = False
        self.output_tags = []
        self.listen = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self.path = root / (name + '.sock')
        self.listen.bind(str(self.path))
        os.chmod(self.path, 0o600)
        self.listen.listen(1)
        self.listen.settimeout(8)
        command = [str(binary), '--config', 'stdin', '--management', str(self.path), 'unix', '--management-client', '--management-hold', '--management-query-passwords', '--management-signal']
        if launcher_owner:
            owner = "import subprocess,sys; p=subprocess.Popen(sys.argv[1:],stdin=sys.stdin); print('PID:'+str(p.pid),flush=True); p.wait()"
            command = [sys.executable, '-c', owner, *command]
        self.proc = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        self.proc.stdin.write(config.encode())
        self.proc.stdin.close()
        self.reader = threading.Thread(target=self.read_output, daemon=True)
        self.reader.start()
        self.thread = threading.Thread(target=self.manage, daemon=True)
        self.thread.start()

    def read_output(self):
        while True:
            line = self.proc.stdout.readline()
            if not line:
                break
            if line.startswith(b'PID:'):
                self.native_pid = int(line[4:].strip())
                continue
            if b'VERIFY' in line and (b'ERROR' in line or b'FAIL' in line):
                self.verification_failure = True
            tags = [tag.decode() for tag in (b'Options error', b'--server', b'--remote', b'--pull', b'--tls-server', b'--ifconfig-ipv6', b'--server-ipv6', b'--management-client-auth', b'--auth-user-pass', b'AUTH_FAILED', b'Exit', b'TLS Error') if tag in line]
            if tags:
                self.output_tags.append(tags)
            if b'Android compatibility engine' in line and (b'DNS' in line or b'dns' in line):
                self.dns_rejected = True

    def send(self, text, fd=None):
        data = (text + '\n').encode()
        if fd is None:
            self.conn.sendall(data)
        else:
            self.conn.sendmsg([data], [(socket.SOL_SOCKET, socket.SCM_RIGHTS, array.array('i', [fd]))])

    def manage(self):
        fds = []
        buf = b''
        try:
            self.conn, _ = self.listen.accept()
            self.conn.settimeout(1)
            self.send('state on')
            self.send('bytecount 1')
            self.send('hold release')
            self.ready.set()
            while not self.done.is_set():
                try:
                    data, anc, flags, _ = self.conn.recvmsg(65536, socket.CMSG_SPACE(4 * 8))
                except socket.timeout:
                    continue
                if not data:
                    break
                for level, typ, raw in anc:
                    if level == socket.SOL_SOCKET and typ == socket.SCM_RIGHTS:
                        a = array.array('i')
                        a.frombytes(raw[:len(raw) // a.itemsize * a.itemsize])
                        fds.extend(a)
                buf += data
                while b'\n' in buf:
                    line, buf = buf.split(b'\n', 1)
                    text = line.decode(errors='replace').strip()
                    if text.startswith('>STATE:'):
                        parts = text[7:].split(',')
                        state = parts[1] if len(parts) > 1 else 'MALFORMED'
                        self.states.append(state)
                        if state == 'CONNECTED':
                            self.up.set()
                        elif state in ('RECONNECTING', 'EXITING', 'AUTH'):
                            self.up.clear()
                    elif text.startswith('>NEED-OK:'):
                        m = re.match(">NEED-OK:Need '([^']+)' confirmation MSG:(.*)", text)
                        if not m:
                            raise RuntimeError('Malformed NEED-OK')
                        typ, msg = m.groups()
                        self.types.append(typ)
                        if typ == self.reject_type:
                            for fd in fds:
                                os.close(fd)
                            fds.clear()
                            self.send('needok ' + typ + ' cancel')
                        elif typ == 'PERSIST_TUN_ACTION':
                            self.send('needok ' + typ + ' OPEN_BEFORE_CLOSE')
                        elif typ == 'OPENTUN':
                            if self.cancel_tun:
                                self.send('needok ' + typ + ' cancel')
                            else:
                                a, b = socket.socketpair(socket.AF_UNIX, socket.SOCK_DGRAM)
                                if self.tun:
                                    self.tun.close()
                                self.tun = a
                                self.send('needok ' + typ + ' ok', b.fileno())
                                b.close()
                        elif typ == 'PROTECTFD':
                            if len(fds) != 1:
                                raise RuntimeError('Missing protect descriptor')
                            os.close(fds.pop(0))
                            self.fd_count += 1
                            self.send('needok ' + typ + ' ok')
                        elif typ in ('IFCONFIG', 'IFCONFIG6', 'ROUTE', 'ROUTE6', 'DNSSERVER', 'DNS6SERVER', 'DNSDOMAIN'):
                            self.send('needok ' + typ + ' ok')
                        else:
                            self.send('needok ' + typ + ' cancel')
                    elif text.startswith(('>CLIENT:CONNECT,', '>CLIENT:REAUTH,')):
                        _, cid, kid = text.split(',')
                        self.pending_auth = (cid, kid)
                        self.auth_fields = {}
                    elif text.startswith('>CLIENT:ENV,') and self.pending_auth:
                        item = text[len('>CLIENT:ENV,'):]
                        if item == 'END':
                            cid, kid = self.pending_auth
                            self.pending_auth = None
                            if self.auth_fields.get('username') == 'synthetic-user' and self.auth_fields.get('password') == 'synthetic-password':
                                self.auth_allowed = True
                                self.send('client-auth-nt ' + cid + ' ' + kid)
                            else:
                                self.auth_denied = True
                                self.send('client-deny ' + cid + ' ' + kid + ' Synthetic-rejection')
                            self.auth_fields.clear()
                        elif item.startswith(('username=', 'password=')):
                            key, value = item.split('=', 1)
                            self.auth_fields[key] = value
                    elif text.startswith('>PASSWORD:Auth-Token:'):
                        self.auth_token_events += 1
                    elif text.startswith('>PASSWORD:'):
                        self.auth_requested = True
                        if self.auth_test and "Need 'Auth'" in text:
                            self.send('username Auth synthetic-user')
                            self.send('password Auth ' + ('wrong-password' if self.auth_test == 'auth_reject' else 'synthetic-password'))
                        else:
                            self.errors.append('AUTH_REQUIRED')
                            self.send('signal SIGTERM')
                    elif text.startswith('>FATAL:'):
                        self.errors.append('FATAL')
        except (OSError, RuntimeError) as e:
            if not self.done.is_set():
                self.errors.append(type(e).__name__)
        finally:
            for fd in fds:
                os.close(fd)
            self.up.clear()
            self.done.set()

    def close(self):
        self.done.set()
        try:
            self.proc.terminate()
            self.proc.wait(timeout=3)
        except subprocess.TimeoutExpired:
            self.proc.kill()
            self.proc.wait(timeout=3)
        try:
            self.conn.close()
        except AttributeError:
            pass
        self.listen.close()
        if self.tun:
            self.tun.close()
        self.thread.join(timeout=2)
        self.reader.join(timeout=2)
        try:
            self.path.unlink()
        except FileNotFoundError:
            pass

class Relay:

    def __init__(self, server_port):
        self.server = ('127.0.0.1', server_port)
        self.client = None
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.sock.bind(('127.0.0.1', 0))
        self.port = self.sock.getsockname()[1]
        self.sock.settimeout(0.1)
        self.stop = threading.Event()
        self.sizes = []
        self.reorder = False
        self.held = None
        self.drop = False
        self.thread = threading.Thread(target=self.run, daemon=True)
        self.thread.start()

    def run(self):
        while not self.stop.is_set():
            try:
                data, addr = self.sock.recvfrom(65536)
            except socket.timeout:
                continue
            except OSError:
                break
            target = self.client if addr == self.server else self.server
            if addr != self.server:
                self.client = addr
            if target is None:
                continue
            # P_DATA_V1=6 and P_DATA_V2=9; preserve control handshakes.
            data_packet = data[0] >> 3 in (6, 9)
            if data_packet:
                self.sizes.append(len(data))
            if self.drop and data_packet:
                self.drop = False
                continue
            if self.reorder and data_packet:
                if self.held is None:
                    self.held = (data, target)
                    continue
                self.sock.sendto(data, target)
                self.sock.sendto(*self.held)
                self.held = None
                self.reorder = False
                continue
            self.sock.sendto(data, target)

    def close(self):
        self.stop.set()
        self.sock.close()
        self.thread.join(timeout=1)

def checksum(data):
    if len(data) & 1:
        data += b'\x00'
    val = sum(struct.unpack('!%dH' % (len(data) // 2), data))
    val = (val >> 16) + (val & 65535)
    val += val >> 16
    return ~val & 65535

def packet(version, size, seq, reverse=False):
    payload = bytes(((i + seq) % 251 for i in range(size - (28 if version == 4 else 48))))
    udp = struct.pack('!HHHH', 19000, 19001, 8 + len(payload), 0) + payload
    if version == 4:
        src = ipaddress.IPv4Address('192.0.2.1').packed
        dst = ipaddress.IPv4Address('192.0.2.2').packed
        if reverse:
            src, dst = (dst, src)
        hdr = struct.pack('!BBHHHBBH4s4s', 69, 0, size, seq, 0, 64, 17, 0, src, dst)
        return hdr[:10] + struct.pack('!H', checksum(hdr)) + hdr[12:] + udp
    src = ipaddress.IPv6Address('2001:db8::1').packed
    dst = ipaddress.IPv6Address('2001:db8::2').packed
    if reverse:
        src, dst = (dst, src)
    csum = checksum(src + dst + struct.pack('!I3xB', len(udp), 17) + udp)
    udp = udp[:6] + struct.pack('!H', csum) + udp[8:]
    return struct.pack('!IHBB16s16s', 6 << 28, len(udp), 17, 64, src, dst) + udp

def config(pki, side, server_port, relay_port, wrong_ca=False, wrong_name=False, wrong_role=False):
    is_server = side == 'server'
    ident = 'wrongrole' if is_server and wrong_role else side
    cfg = 'dev tun\nproto udp4\ntun-mtu 1500\nfragment 600\nmssfix 600\nallow-compression no\ndata-ciphers AES-256-GCM:AES-128-GCM\nauth SHA256\nverb 3\nconnect-retry-max 1\ntls-timeout 1\nhand-window 4\nresolv-retry 0\n'
    cfg += 'tls-server\nlocal 127.0.0.1\nport ' + str(server_port) + '\nremote 127.0.0.1 ' + str(relay_port) + '\nifconfig 192.0.2.1 192.0.2.2\nifconfig-ipv6 2001:db8::1/64 2001:db8::2\ndh none\nremote-cert-tls client\n' if is_server else 'tls-client\npull\nremote 127.0.0.1 ' + str(relay_port) + '\nnobind\nifconfig 192.0.2.2 192.0.2.1\nifconfig-ipv6 2001:db8::2/64 2001:db8::1\nremote-cert-tls server\nverify-x509-name ' + ('not-server' if wrong_name else 'server') + ' name\n'
    cfg += 'dhcp-option DNS 192.0.2.53\ndhcp-option DNS6 2001:db8::53\n'
    cfg += inline('ca', pki / ('wrong' if wrong_ca and (not is_server) else 'ca') / 'cert.pem')
    cfg += inline('cert', pki / ident / 'cert.pem') + inline('key', pki / ident / 'key.pem')
    return cfg

def expect(condition, label):
    if not condition:
        raise AssertionError(label)

def wait_connected(peers, timeout=10):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        if all((p.up.is_set() for p in peers)):
            return True
        if any((p.proc.poll() is not None for p in peers)):
            return False
        time.sleep(0.02)
    return False

def recv_frame(peer, expected, timeout=2):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        ready, _, _ = select.select([peer.tun], [], [], max(0, end - time.monotonic()))
        if ready:
            got = peer.tun.recv(65536)
            if got == expected:
                return True
    return False

def test_validation(launcher):
    profile = 'client\ndev tun\nproto udp\nremote vpn.invalid 1194\nnobind\nauth-user-pass\n<ca>\nsynthetic-ca-placeholder\n</ca>\nfragment 1300\n'
    command = [str(launcher), '--nuvio-validate', '--config', 'stdin', '--remote-cert-tls', 'server', '--allow-compression', 'asym']
    cases = [('valid_options', profile, True), ('bad_fragment', profile.replace('fragment 1300', 'fragment invalid'), False), ('unknown_option', profile + 'synthetic-unknown-option\n', False), ('tcp_fragment', profile.replace('proto udp', 'proto tcp-client'), False)]
    for name, config_text, valid in cases:
        result = subprocess.run(command, input=config_text.encode(), stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=5)
        expect((result.returncode == 0) == valid, 'Native option validation: ' + name)
        expect(not result.stdout and (not result.stderr), 'Launcher must not expose native diagnostics')
    env = dict(os.environ, NUVIO_OPENVPN2_VALIDATE_ONLY='1')
    result = subprocess.run([str(launcher), '--config', 'stdin'], input=profile.encode(), stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=env, timeout=5)
    expect(result.returncode != 0, 'Launcher clears inherited validation bypass variable')
    expect(not result.stdout and (not result.stderr), 'Normal launcher suppresses native diagnostics')
    return {'case': 'native_validation', 'passed': True, 'option_cases': len(cases), 'inherited_environment_bypass_rejected': True}

def test_pair(binary, root, pki, kind, server_binary=None, force_protect_binary=None, launcher_binary=None):
    server_port = free_port()
    relay = Relay(server_port)
    peers = []
    try:
        neg = {'wrong_ca': kind == 'wrong_ca', 'wrong_name': kind == 'wrong_name', 'wrong_role': kind == 'wrong_role'}
        server_config = config(pki, 'server', server_port, relay.port, **neg)
        client_config = config(pki, 'client', server_port, relay.port, **neg)
        if kind.startswith('auth_') or kind.startswith('pushed_dns_'):
            server_config = server_config.replace('connect-retry-max 1\n', '').replace('remote 127.0.0.1 ' + str(relay.port) + '\n', '').replace('ifconfig 192.0.2.1 192.0.2.2\n', 'server 192.0.2.0 255.255.255.0\ntopology subnet\nmanagement-client-auth\n')
            if kind.startswith('auth_'):
                client_config += 'auth-user-pass\n'
            else:
                server_config = server_config.replace('management-client-auth\n', '')
            if kind == 'auth_renewal':
                server_config += 'auth-gen-token 60 8\nreneg-sec 2\n'
        if kind == 'cbc':
            server_config = server_config.replace('data-ciphers AES-256-GCM:AES-128-GCM', 'data-ciphers AES-256-CBC')
            client_config = client_config.replace('data-ciphers AES-256-GCM:AES-128-GCM', 'data-ciphers AES-256-CBC')
        dns_options = {'plain': 'dns server 0 address 192.0.2.53 2001:db8::53\n', 'plain_first': 'dns server 0 address 192.0.2.53\ndns server 1 address 192.0.2.54\ndns server 1 transport DoH\n', 'secure_first': 'dns server 0 address 192.0.2.54\ndns server 0 transport DoH\ndns server 1 address 192.0.2.53\n', 'dnssec': 'dns server 0 address 192.0.2.53\ndns server 0 dnssec yes\n', 'custom_port': 'dns server 0 address 192.0.2.53:5353\n', 'split': 'dns server 0 address 192.0.2.53\ndns server 0 resolve-domains fixture.invalid\n', 'search': 'dns search-domains fixture.invalid\n'}
        if kind.startswith('local_dns_'):
            client_config += dns_options[kind[len('local_dns_'):]]
        if kind.startswith('pushed_dns_'):
            for line in dns_options[kind[len('pushed_dns_'):]].splitlines():
                server_config += 'push ' + chr(34) + line + chr(34) + '\n'
        server_exe = server_binary if kind.startswith('auth_') or kind.startswith('pushed_dns_') else binary
        client_exe = force_protect_binary if kind in ('protect_denied', 'force_protect_success') else launcher_binary if kind == 'launcher_parent_death' else binary
        reject_type = {'protect_denied': 'PROTECTFD', 'cancel_ifconfig': 'IFCONFIG', 'cancel_dns': 'DNSSERVER'}.get(kind)
        peers = [Peer(server_exe, root, 'server', server_config, auth_test=kind if kind.startswith('auth_') else False), Peer(client_exe, root, 'client', client_config, cancel_tun=kind == 'cancel_tun', auth_test=kind if kind.startswith('auth_') else False, reject_type=reject_type, launcher_owner=kind == 'launcher_parent_death')]
        connected = wait_connected(peers)
        if kind not in ('success', 'force_protect_success', 'management_eof', 'auth_success', 'auth_renewal', 'cbc', 'launcher_parent_death', 'pushed_dns_plain'):
            expect(not connected, kind + ' must not authenticate')
            expect(not peers[1].up.is_set(), kind + ' client must remain disconnected')
            if kind == 'cancel_tun':
                expect('OPENTUN' in peers[1].types, 'Cancellation reached TUN establishment')
            elif kind == 'auth_reject':
                expect(peers[0].auth_denied and peers[1].auth_requested, 'Actual server auth rejection observed')
                expect('OPENTUN' not in peers[1].types, 'Rejected authentication never establishes client TUN')
            elif kind.startswith(('local_dns_', 'pushed_dns_')):
                expect(peers[1].dns_rejected, 'Explicit unsupported DNS rejection must be observed')
            elif kind in ('protect_denied', 'cancel_ifconfig', 'cancel_dns'):
                expect(reject_type in peers[1].types, 'Denied callback was requested')
                expect(peers[1].proc.poll() is not None, 'Denied protection/configuration callback must terminate engine')
                expect('OPENTUN' not in peers[1].types, 'Denial occurs before TUN establishment')
                expect(not peers[1].verification_failure, 'Denial is independent of a TLS verification failure')
            else:
                expect(peers[1].verification_failure, 'Certificate rejection must be observed, not a startup error')
            return {'case': kind, 'passed': True, 'connected': False}
        if not connected:
            print(json.dumps({'failure': kind, 'states': [p.states for p in peers], 'callbacks': [p.types for p in peers], 'errors': [p.errors for p in peers], 'tags': [p.output_tags for p in peers]}), flush=True)
        expect(connected, 'TLS peers connect')
        if kind in ('auth_success', 'auth_renewal'):
            expect(peers[0].auth_allowed and peers[1].auth_requested, 'Real daemon accepted service credentials')
        if kind == 'auth_renewal':
            end = time.monotonic() + 12
            while peers[1].auth_token_events < 2 and time.monotonic() < end:
                time.sleep(0.05)
            expect(peers[1].auth_token_events >= 2, 'Authentication token renews during key renegotiation')
            expect(peers[1].up.is_set(), 'Session remains connected after authentication token renewal')
        if kind == 'launcher_parent_death':
            expect(peers[1].native_pid is not None, 'Launcher child identity received')
            peers[1].proc.terminate()
            peers[1].proc.wait(timeout=3)
            expect(peers[1].done.wait(3), 'Owner death closes native management descriptor')
            try:
                state = (Path('/proc') / str(peers[1].native_pid) / 'stat').read_text().split()[2]
                expect(state == 'Z', 'Native child must not survive owner death')
            except FileNotFoundError:
                pass
            return {'case': kind, 'passed': True, 'native_child_terminated_on_owner_death': True}
        if kind == 'management_eof':
            peers[1].conn.shutdown(socket.SHUT_RDWR)
            peers[1].conn.close()
            peers[1].proc.wait(timeout=3)
            expect(peers[1].proc.returncode is not None, 'Management EOF terminates native engine')
            return {'case': kind, 'passed': True, 'management_eof_terminates': True}
        if kind == 'force_protect_success':
            expect(peers[1].fd_count >= 1, 'Transport descriptor protection callback was acknowledged')
        if not kind.startswith(('auth_', 'pushed_dns_')):
            expect(all(('IFCONFIG6' in p.types for p in peers)), 'IPv6 configured')
        expect(all(('DNSSERVER' in p.types and 'DNS6SERVER' in p.types for p in peers)), 'Dual-family DNS delivered')
        counts = 0
        for ver in (4,) if kind.startswith(('auth_', 'pushed_dns_')) else (4, 6):
            for size in (80, 590, 600, 601, 1200, 1499, 1500):
                for index, (sender, receiver) in enumerate((peers, peers[::-1])):
                    data = packet(ver, size, counts + index, reverse=index == 1)
                    start = len(relay.sizes)
                    sender.tun.send(data)
                    expect(recv_frame(receiver, data), 'Packet round-trip matches')
                    if size >= 1200:
                        expect(len(relay.sizes) - start >= 2, 'Large packet uses real multiple encrypted fragments')
                    counts += 1
        data = packet(4, 1500, 100)
        relay.reorder = True
        peers[0].tun.send(data)
        expect(recv_frame(peers[1], data), 'Reordered fragments reassemble')
        data = packet(4 if kind.startswith(('auth_', 'pushed_dns_')) else 6, 1500, 101)
        relay.drop = True
        peers[0].tun.send(data)
        expect(not recv_frame(peers[1], data, 0.35), 'Missing fragment never yields a partial packet')
        data = packet(4 if kind.startswith(('auth_', 'pushed_dns_')) else 6, 1500, 102)
        peers[0].tun.send(data)
        expect(recv_frame(peers[1], data), 'Following complete packet recovers after loss')
        expect(max(relay.sizes) <= 600, 'Encrypted UDP data respects fragment size')
        peers[1].proc.terminate()
        peers[1].proc.wait(timeout=3)
        return {'case': kind, 'passed': True, 'bidirectional_frames': counts, 'ip_families': [4] if kind.startswith(('auth_', 'pushed_dns_')) else [4, 6], 'reordered_packet': True, 'dropped_fragment_withheld': True, 'recovers_after_loss': True, 'max_encrypted_datagram': max(relay.sizes), 'sigterm_exit': True}
    finally:
        for p in peers:
            p.close()
        relay.close()
CASES = ['native_validation', 'success', 'force_protect_success', 'wrong_ca', 'wrong_name', 'wrong_role', 'cancel_tun', 'cancel_ifconfig', 'cancel_dns', 'protect_denied', 'management_eof', 'auth_reject', 'auth_success', 'auth_renewal', 'cbc', 'launcher_parent_death', 'pushed_dns_plain'] + [prefix + suffix for prefix in ('local_dns_', 'pushed_dns_') for suffix in ('plain_first', 'secure_first', 'dnssec', 'custom_port', 'split', 'search')]

def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--binaries', type=Path, required=True, help='Output directory from build-openvpn2-host.py')
    p.add_argument('--report', type=Path, help='Optional sanitized JSON results')
    p.add_argument('--case', choices=CASES, help='Run one case; the default runs all cases')
    args = p.parse_args()
    results = []
    began = time.monotonic()
    cases = [args.case] if args.case else CASES
    if 'native_validation' in cases:
        result = test_validation(args.binaries / 'engine-launcher')
        results.append(result)
        print(json.dumps(result), flush=True)
    network_cases = [case for case in cases if case != 'native_validation']
    if network_cases:
        with tempfile.TemporaryDirectory(prefix='ov2-') as tmp:
            root = Path(tmp)
            root.chmod(0o700)
            pki = root / 'pki'
            pki.mkdir()
            certs(pki)
            for kind in network_cases:
                result = test_pair(args.binaries / 'engine-client', root, pki, kind, args.binaries / 'engine-test-server', args.binaries / 'engine-client-force-protect', args.binaries / 'engine-launcher')
                results.append(result)
                print(json.dumps(result), flush=True)
    report = {'passed': all((r['passed'] for r in results)), 'elapsed_seconds': round(time.monotonic() - began, 3), 'results': results, 'scope': 'Synthetic Linux host executable using upstream Android management fd path; no Android VpnService or actual socket-protection proof.'}
    if args.report:
        args.report.write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps({'passed': report['passed'], 'elapsed_seconds': report['elapsed_seconds']}))
if __name__ == '__main__':
    main()
