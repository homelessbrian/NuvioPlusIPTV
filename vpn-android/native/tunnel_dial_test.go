package main

import (
	"bufio"
	"context"
	"crypto/rand"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/netip"
	"net/url"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"golang.org/x/crypto/curve25519"
	"golang.org/x/net/dns/dnsmessage"
	"golang.zx2c4.com/wireguard/conn"
	"golang.zx2c4.com/wireguard/device"
	"nuvio-vpn-http/internal/netstack"
)

// fixture exchanges actual encrypted WireGuard UDP packets locally. Channel
// origins and DNS are reachable solely inside the userspace VPN network.
func wireGuardFixture(t *testing.T, v4, v6, dropAAAA bool) *httpTunnel {
	return wireGuardDNSFixture(t, v4, v6, dropAAAA, false, true)
}

func wireGuardDNSFixture(t *testing.T, v4, v6, dropAAAA, dropA, clientV6 bool) *httpTunnel {
	return wireGuardDNSFixtureObserved(t, v4, v6, dropAAAA, dropA, clientV6, nil)
}

func wireGuardDNSFixtureObserved(t *testing.T, v4, v6, dropAAAA, dropA, clientV6 bool, dropped chan<- struct{}) *httpTunnel {
	t.Helper()
	sp, cp := make([]byte, 32), make([]byte, 32)
	if _, err := rand.Read(sp); err != nil {
		t.Fatal(err)
	}
	if _, err := rand.Read(cp); err != nil {
		t.Fatal(err)
	}
	spu, _ := curve25519.X25519(sp, curve25519.Basepoint)
	cpu, _ := curve25519.X25519(cp, curve25519.Basepoint)
	addresses := []netip.Addr{netip.MustParseAddr("10.252.0.1")}
	if v6 {
		addresses = append(addresses, netip.MustParseAddr("fd00::1"))
	}
	tun, network, err := netstack.CreateNetTUN(addresses, nil, 1280)
	if err != nil {
		t.Fatal(err)
	}
	quiet := func(string, ...any) {}
	peer := device.NewDevice(tun, conn.NewDefaultBind(), &device.Logger{Verbosef: quiet, Errorf: quiet})
	t.Cleanup(peer.Close)
	if peer.IpcSet("private_key="+hex.EncodeToString(sp)+"\nlisten_port=0\npublic_key="+hex.EncodeToString(cpu)+"\nallowed_ip=10.252.0.2/32\nallowed_ip=fd00::2/128\n") != nil || peer.Up() != nil {
		t.Fatal("peer init")
	}
	state, _ := peer.IpcGet()
	port := ""
	for _, l := range strings.Split(state, "\n") {
		if strings.HasPrefix(l, "listen_port=") {
			port = strings.TrimPrefix(l, "listen_port=")
		}
	}
	if port == "" || port == "0" {
		t.Fatal("no peer port")
	}
	for _, addr := range addresses {
		if addr.Is4() && !v4 {
			continue
		}
		listener, err := network.ListenTCPAddrPort(netip.AddrPortFrom(addr, 8080))
		if err != nil {
			t.Fatal(err)
		}
		server := &http.Server{Handler: http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { io.WriteString(w, "channel-ready") })}
		t.Cleanup(func() { server.Close() })
		go server.Serve(listener)
	}
	dns, err := network.ListenUDPAddrPort(netip.MustParseAddrPort("10.252.0.1:53"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { dns.Close() })
	go func() {
		buf := make([]byte, 4096)
		for {
			n, a, e := dns.ReadFrom(buf)
			if e != nil {
				return
			}
			var q dnsmessage.Message
			if q.Unpack(buf[:n]) != nil || len(q.Questions) != 1 {
				continue
			}
			if (dropAAAA && q.Questions[0].Type == dnsmessage.TypeAAAA) || (dropA && q.Questions[0].Type == dnsmessage.TypeA) {
				if dropped != nil {
					dropped <- struct{}{}
				}
				continue
			}
			response := dnsmessage.Message{Header: dnsmessage.Header{ID: q.ID, Response: true, RecursionAvailable: true}, Questions: q.Questions}
			hdr := dnsmessage.ResourceHeader{Name: q.Questions[0].Name, Type: q.Questions[0].Type, Class: dnsmessage.ClassINET, TTL: 30}
			if hdr.Type == dnsmessage.TypeA {
				ip := [4]byte{10, 252, 0, 1}
				if !v4 {
					ip = [4]byte{10, 252, 0, 99}
				}
				response.Answers = []dnsmessage.Resource{{Header: hdr, Body: &dnsmessage.AResource{A: ip}}}
			} else if hdr.Type == dnsmessage.TypeAAAA {
				ip := netip.MustParseAddr("fd00::99")
				if v6 {
					ip = netip.MustParseAddr("fd00::1")
				}
				response.Answers = []dnsmessage.Resource{{Header: hdr, Body: &dnsmessage.AAAAResource{AAAA: ip.As16()}}}
			}
			p, _ := response.Pack()
			dns.WriteTo(p, a)
		}
	}()
	input := tunnelInput{UAPI: "private_key=" + hex.EncodeToString(cp) + "\npublic_key=" + hex.EncodeToString(spu) + "\nallowed_ip=0.0.0.0/0\nallowed_ip=::/0\nendpoint=127.0.0.1:" + port + "\n", Addresses: []string{"10.252.0.2"}, DNS: []string{"10.252.0.1"}, MTU: 1280, Token: testToken, TunnelHTTPS: true}
	if clientV6 {
		input.Addresses = append(input.Addresses, "fd00::2")
	}
	encoded, _ := json.Marshal(input)
	proxy, err := newHTTPTunnel(string(encoded))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(proxy.close)
	return proxy
}

func TestTunnelChannelFallbackHTTPAndCONNECT(t *testing.T) {
	for _, scenario := range []struct {
		name   string
		v4, v6 bool
	}{{"ipv6-blackhole", true, false}, {"ipv4-blackhole", false, true}, {"both-working", true, true}} {
		t.Run(scenario.name, func(t *testing.T) {
			proxy := wireGuardFixture(t, scenario.v4, scenario.v6, false)
			target, _ := url.Parse("http://" + proxy.listener.Addr().String())
			client := proxyClient(t, target, true)
			start := time.Now()
			response, err := client.Get("http://playback.test:8080/video")
			if err != nil {
				t.Fatal(err)
			}
			body, err := io.ReadAll(response.Body)
			response.Body.Close()
			if err != nil || string(body) != "channel-ready" {
				t.Fatalf("bad response %s %v", body, err)
			}
			elapsed := time.Since(start)
			if elapsed > 2*time.Second {
				t.Fatalf("HTTP setup too slow: %v", elapsed)
			}
			t.Logf("HTTP completed in %v", elapsed)
			clientConn, err := net.Dial("tcp", target.Host)
			if err != nil {
				t.Fatal(err)
			}
			defer clientConn.Close()
			clientConn.SetDeadline(time.Now().Add(3 * time.Second))
			auth := base64.StdEncoding.EncodeToString([]byte("nuvio:" + testToken))
			start = time.Now()
			fmt.Fprintf(clientConn, "CONNECT playback.test:8080 HTTP/1.1\r\nHost: playback.test:8080\r\nProxy-Authorization: Basic %s\r\n\r\n", auth)
			reader := bufio.NewReader(clientConn)
			response, err = http.ReadResponse(reader, &http.Request{Method: http.MethodConnect})
			if err != nil || response.StatusCode != 200 {
				t.Fatalf("CONNECT failed: %v", err)
			}
			fmt.Fprint(clientConn, "GET /video HTTP/1.1\r\nHost: playback.test\r\nConnection: close\r\n\r\n")
			response, err = http.ReadResponse(reader, &http.Request{Method: http.MethodGet})
			if err != nil {
				t.Fatal(err)
			}
			body, err = io.ReadAll(response.Body)
			response.Body.Close()
			if err != nil || string(body) != "channel-ready" {
				t.Fatalf("CONNECT payload %q %v", body, err)
			}
			elapsed = time.Since(start)
			if elapsed > 2*time.Second {
				t.Fatalf("CONNECT setup too slow: %v", elapsed)
			}
			t.Logf("CONNECT completed in %v", elapsed)
		})
	}
}

func TestTunnelDialTimeoutAndCancellation(t *testing.T) {
	proxy := wireGuardFixture(t, false, false, false)
	// Short timer invokes the identical production wrapper without a 15-second wait.
	dial := tunnelDial(proxy.transport.DialContext, 150*time.Millisecond, 25*time.Millisecond)
	start := time.Now()
	_, err := dial(context.Background(), "tcp", "playback.test:8080")
	if !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("got %v", err)
	}
	if time.Since(start) > time.Second {
		t.Fatal("timeout unbounded")
	}
	t.Logf("unreachable server bounded in %v", time.Since(start))
	ctx, cancel := context.WithCancel(context.Background())
	time.AfterFunc(50*time.Millisecond, cancel)
	start = time.Now()
	_, err = proxy.transport.DialContext(ctx, "tcp", "playback.test:8080")
	if !errors.Is(err, context.Canceled) {
		t.Fatalf("got %v", err)
	}
	if time.Since(start) > time.Second {
		t.Fatal("cancellation unbounded")
	}
}

func TestTunnelDialCanceledBeforeStart(t *testing.T) {
	var attempts atomic.Int32
	dial := tunnelDial(func(context.Context, string, string) (net.Conn, error) {
		attempts.Add(1)
		return nil, errors.New("unexpected dial")
	}, time.Second, 10*time.Millisecond)
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if _, err := dial(ctx, "tcp", "unused:80"); !errors.Is(err, context.Canceled) {
		t.Fatalf("canceled dial: %v", err)
	}
	if attempts.Load() != 0 {
		t.Fatal("canceled request started a network connection")
	}
}

type trackedConn struct {
	net.Conn
	closed atomic.Bool
}

func (c *trackedConn) Close() error { c.closed.Store(true); return c.Conn.Close() }
func TestTunnelRaceClosesLateWinner(t *testing.T) {
	late := make(chan *trackedConn, 1)
	release := make(chan struct{})
	dial := tunnelDial(func(ctx context.Context, network, address string) (net.Conn, error) {
		a, b := net.Pipe()
		b.Close()
		c := &trackedConn{Conn: a}
		if network == "tcp6" {
			<-release
			late <- c
		}
		return c, nil
	}, time.Second, 10*time.Millisecond)
	winner, err := dial(context.Background(), "tcp", "unused:80")
	if err != nil {
		t.Fatal(err)
	}
	winner.Close()
	close(release)
	loser := <-late
	deadline := time.Now().Add(time.Second)
	for !loser.closed.Load() && time.Now().Before(deadline) {
		time.Sleep(time.Millisecond)
	}
	if !loser.closed.Load() {
		t.Fatal("late result connection leaked")
	}
}

// DNS lanes must not hold each other hostage once the dialer races families.
func TestTunnelDroppedDNSFamilyHTTPAndCONNECT(t *testing.T) {
	for _, scenario := range []struct {
		name                              string
		v4, v6, dropAAAA, dropA, clientV6 bool
	}{
		{"dualstack-dropped-AAAA", true, false, true, false, true},
		{"dualstack-dropped-A", false, true, false, true, true},
		{"ipv4-only-dropped-AAAA", true, false, true, false, false},
	} {
		t.Run(scenario.name, func(t *testing.T) {
			proxy := wireGuardDNSFixture(t, scenario.v4, scenario.v6, scenario.dropAAAA, scenario.dropA, scenario.clientV6)
			target, _ := url.Parse("http://" + proxy.listener.Addr().String())
			client := proxyClient(t, target, true)
			start := time.Now()
			response, err := client.Get("http://playback.test:8080/video")
			if err != nil {
				t.Fatal(err)
			}
			body, err := io.ReadAll(response.Body)
			response.Body.Close()
			if err != nil || string(body) != "channel-ready" {
				t.Fatalf("bad payload: %q %v", body, err)
			}
			elapsed := time.Since(start)
			if elapsed > 2*time.Second {
				t.Fatalf("DNS family blocked HTTP: %v", elapsed)
			}
			t.Logf("HTTP completed in %v", elapsed)
			clientConn, err := net.Dial("tcp", target.Host)
			if err != nil {
				t.Fatal(err)
			}
			defer clientConn.Close()
			clientConn.SetDeadline(time.Now().Add(3 * time.Second))
			auth := base64.StdEncoding.EncodeToString([]byte("nuvio:" + testToken))
			start = time.Now()
			fmt.Fprintf(clientConn, "CONNECT playback.test:8080 HTTP/1.1\r\nHost: playback.test:8080\r\nProxy-Authorization: Basic %s\r\n\r\n", auth)
			reader := bufio.NewReader(clientConn)
			response, err = http.ReadResponse(reader, &http.Request{Method: http.MethodConnect})
			if err != nil || response.StatusCode != 200 {
				t.Fatalf("CONNECT failed: %v", err)
			}
			fmt.Fprint(clientConn, "GET /video HTTP/1.1\r\nHost: playback.test\r\nConnection: close\r\n\r\n")
			response, err = http.ReadResponse(reader, &http.Request{Method: http.MethodGet})
			if err != nil {
				t.Fatal(err)
			}
			body, err = io.ReadAll(response.Body)
			response.Body.Close()
			if err != nil || string(body) != "channel-ready" {
				t.Fatalf("bad CONNECT payload: %q %v", body, err)
			}
			elapsed = time.Since(start)
			if elapsed > 2*time.Second {
				t.Fatalf("DNS family blocked CONNECT: %v", elapsed)
			}
			t.Logf("CONNECT completed in %v", elapsed)
		})
	}
}

func TestTunnelDNSUnavailableFailsClosed(t *testing.T) {
	proxy := wireGuardDNSFixture(t, false, false, true, true, true)
	ctx, cancel := context.WithTimeout(context.Background(), 150*time.Millisecond)
	defer cancel()
	start := time.Now()
	connection, err := proxy.transport.DialContext(ctx, "tcp", "playback.test:8080")
	if connection != nil {
		connection.Close()
		t.Fatal("unreachable VPN DNS opened a connection")
	}
	if err == nil {
		t.Fatal("unreachable VPN DNS succeeded")
	}
	if time.Since(start) > time.Second {
		t.Fatal("VPN DNS cancellation not bounded")
	}
	t.Logf("both DNS lanes dropped: failed closed in %v: %v", time.Since(start), err)
}

func TestTunnelWinningFamilyCancelsDroppedDNSRetry(t *testing.T) {
	dropped := make(chan struct{}, 4)
	proxy := wireGuardDNSFixtureObserved(t, true, false, true, false, true, dropped)
	ctx, cancel := context.WithTimeout(context.Background(), 8*time.Second)
	defer cancel()
	connection, err := proxy.transport.DialContext(ctx, "tcp", "playback.test:8080")
	if err != nil {
		t.Fatal(err)
	}
	connection.Close()
	select {
	case <-dropped:
	case <-time.After(time.Second):
		t.Fatal("IPv6 DNS query did not reach the WireGuard peer")
	}
	// An uncanceled loser retries after the resolver's five-second deadline.
	// Observe that deadline to catch work that outlives a successful dial.
	select {
	case <-dropped:
		t.Fatal("losing address family retried DNS after the IPv4 connection won")
	case <-time.After(5 * time.Second):
	}
}
