package main

import (
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"errors"
	"io"
	"math/big"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

// Requests retain the real endpoint URLs and TLS verification. Only the private
// tunnel dialer maps those destinations to this trusted, local-only fixture.
func probeFixture(t *testing.T, handler http.HandlerFunc, dial func(context.Context, string) error) *http.Transport {
	t.Helper()
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	certificate := &x509.Certificate{SerialNumber: big.NewInt(1), Subject: pkix.Name{CommonName: "local VPN probe fixture"},
		NotBefore: time.Now().Add(-time.Hour), NotAfter: time.Now().Add(time.Hour),
		DNSNames: []string{"www.gstatic.com", "cp.cloudflare.com"}, IPAddresses: []net.IP{net.ParseIP("1.1.1.1")},
		KeyUsage: x509.KeyUsageDigitalSignature, ExtKeyUsage: []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth}}
	encoded, err := x509.CreateCertificate(rand.Reader, certificate, certificate, &key.PublicKey, key)
	if err != nil {
		t.Fatal(err)
	}
	trusted, err := x509.ParseCertificate(encoded)
	if err != nil {
		t.Fatal(err)
	}
	server := httptest.NewUnstartedServer(handler)
	server.TLS = &tls.Config{Certificates: []tls.Certificate{{Certificate: [][]byte{encoded}, PrivateKey: key}}}
	server.StartTLS()
	t.Cleanup(server.Close)
	transport := privateTransport(func(ctx context.Context, _ string, address string) (net.Conn, error) {
		if dial != nil {
			if err := dial(ctx, address); err != nil {
				return nil, err
			}
		}
		return (&net.Dialer{}).DialContext(ctx, "tcp", server.Listener.Addr().String())
	})
	roots := x509.NewCertPool()
	roots.AddCert(trusted)
	transport.TLSClientConfig = &tls.Config{RootCAs: roots}
	t.Cleanup(transport.CloseIdleConnections)
	return transport
}

func shortProbe(t *testing.T, transport *http.Transport, timeout time.Duration) bool {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()
	return probeHTTPS(ctx, transport)
}

func TestProbeBlockedGoogleUsesHealthyCloudflareAndCancelsLosers(t *testing.T) {
	var active atomic.Int32
	started := make(chan struct{}, 2)
	var google, trace sync.Once
	transport := probeFixture(t, func(w http.ResponseWriter, r *http.Request) {
		if r.Host == "cp.cloudflare.com" {
			for i := 0; i < 2; i++ {
				select {
				case <-started:
				case <-r.Context().Done():
					return
				}
			}
			w.WriteHeader(http.StatusNoContent)
			return
		}
		http.Error(w, "unexpected origin", http.StatusServiceUnavailable)
	}, func(ctx context.Context, address string) error {
		if address == "cp.cloudflare.com:443" {
			return nil
		}
		active.Add(1)
		defer active.Add(-1)
		if address == "www.gstatic.com:443" {
			google.Do(func() { started <- struct{}{} })
		} else {
			trace.Do(func() { started <- struct{}{} })
		}
		<-ctx.Done()
		return ctx.Err()
	})
	tunnel := &httpTunnel{transport: transport, packetLink: io.NopCloser(strings.NewReader(""))}
	start := time.Now()
	if !tunnel.probe() {
		t.Fatal("working tunnel was rejected when Google was blocked")
	}
	deadline := time.Now().Add(500 * time.Millisecond)
	for active.Load() != 0 && time.Now().Before(deadline) {
		time.Sleep(time.Millisecond)
	}
	if elapsed := time.Since(start); elapsed >= 750*time.Millisecond || active.Load() != 0 {
		t.Fatalf("losing checks were not promptly canceled: elapsed=%s active=%d", elapsed, active.Load())
	}
}

func TestProbeStrictResponses(t *testing.T) {
	for _, tc := range []struct {
		name, body string
		status     int
		want       bool
	}{
		{"trace IPv4", "fl=local\nip=203.0.113.7\ntls=TLSv1.3\n", 200, true},
		{"trace IPv6", "ip=2001:db8::7\r\n", 200, true},
		{"invalid IP", "ip=working\n", 200, false},
		{"embedded IP", "message=ip=203.0.113.7\n", 200, false},
		{"empty IP", "ip=\n", 200, false},
		{"oversized trace", "ip=203.0.113.7\n" + strings.Repeat("x", tunnelProbeBodyLimit), 200, false},
		{"wrong trace status", "ip=203.0.113.7\n", 204, false},
		{"redirect", "ip=203.0.113.7\n", 302, false},
	} {
		t.Run(tc.name, func(t *testing.T) {
			transport := probeFixture(t, func(w http.ResponseWriter, r *http.Request) {
				if r.Host != "1.1.1.1" {
					w.WriteHeader(http.StatusOK) // A 200 cannot satisfy a generate_204 check.
					return
				}
				w.Header().Set("Location", "https://cp.cloudflare.com/generate_204")
				w.WriteHeader(tc.status)
				_, _ = io.WriteString(w, tc.body)
			}, nil)
			if got := shortProbe(t, transport, time.Second); got != tc.want {
				t.Fatalf("got %v, want %v", got, tc.want)
			}
		})
	}
}

func TestProbeRejectsUntrustedTLS(t *testing.T) {
	transport := probeFixture(t, func(w http.ResponseWriter, _ *http.Request) { w.WriteHeader(http.StatusNoContent) }, nil)
	transport.TLSClientConfig = &tls.Config{RootCAs: x509.NewCertPool()}
	if shortProbe(t, transport, time.Second) {
		t.Fatal("untrusted TLS verified the tunnel")
	}
}

func TestProbeAllFailuresNeverUseDirectTransport(t *testing.T) {
	var tunneled, direct atomic.Int32
	tunnel, _ := testProxy(t, func(context.Context, string, string) (net.Conn, error) {
		tunneled.Add(1)
		return nil, errors.New("VPN endpoint unavailable")
	})
	tunnel.direct = probeFixture(t, func(w http.ResponseWriter, _ *http.Request) { w.WriteHeader(http.StatusNoContent) },
		func(context.Context, string) error { direct.Add(1); return nil })
	// OpenVPN has no WireGuard statistics gate, so healthy direct fallback would
	// incorrectly verify this fixture if the probe ever selected it.
	tunnel.packetLink = io.NopCloser(strings.NewReader(""))
	if tunnel.probe() || tunneled.Load() != int32(len(tunnelProbeChecks)) || direct.Load() != 0 {
		t.Fatalf("probe escaped tunnel: tunneled=%d direct=%d", tunneled.Load(), direct.Load())
	}
	tunnel.transport = nil
	if tunnel.probe() || direct.Load() != 0 {
		t.Fatal("missing tunnel selected the healthy direct transport")
	}
}

func TestProbeOneDeadlineIncludesSlowTraceBodyAndDrainsWorkers(t *testing.T) {
	var active, peak atomic.Int32
	transport := probeFixture(t, func(w http.ResponseWriter, r *http.Request) {
		now := active.Add(1)
		defer active.Add(-1)
		for previous := peak.Load(); now > previous && !peak.CompareAndSwap(previous, now); previous = peak.Load() {
		}
		if r.Host == "1.1.1.1" {
			w.WriteHeader(http.StatusOK)
			_, _ = io.WriteString(w, "ip=203.0.113.7\n")
			w.(http.Flusher).Flush()
		}
		<-r.Context().Done()
	}, nil)
	for i := 0; i < 5; i++ {
		start := time.Now()
		if shortProbe(t, transport, 100*time.Millisecond) {
			t.Fatal("incomplete trace body verified the tunnel")
		}
		if elapsed := time.Since(start); elapsed > 750*time.Millisecond {
			t.Fatalf("per-endpoint waits exceeded the shared deadline: %s", elapsed)
		}
		deadline := time.Now().Add(time.Second)
		for active.Load() != 0 && time.Now().Before(deadline) {
			time.Sleep(time.Millisecond)
		}
		if active.Load() != 0 || peak.Load() > int32(len(tunnelProbeChecks)) {
			t.Fatalf("checks accumulated: active=%d peak=%d", active.Load(), peak.Load())
		}
	}
}

func TestProbeParentCancellationAndTunnelCloseCancelRequests(t *testing.T) {
	for _, closeTunnel := range []bool{false, true} {
		t.Run(map[bool]string{false: "parent", true: "tunnel close"}[closeTunnel], func(t *testing.T) {
			started := make(chan struct{}, len(tunnelProbeChecks))
			transport := probeFixture(t, func(_ http.ResponseWriter, r *http.Request) {
				started <- struct{}{}
				<-r.Context().Done()
			}, nil)
			tunnel, _ := testProxy(t, nil)
			tunnel.transport.CloseIdleConnections()
			tunnel.transport = transport
			tunnel.packetLink = io.NopCloser(strings.NewReader(""))
			ctx, cancel := context.WithTimeout(context.Background(), time.Second)
			defer cancel()
			done := make(chan bool, 1)
			go func() { done <- tunnel.probeContext(ctx) }()
			for range tunnelProbeChecks {
				select {
				case <-started:
				case <-ctx.Done():
					t.Fatal("checks never started")
				}
			}
			if closeTunnel {
				tunnel.close()
			} else {
				cancel()
			}
			select {
			case got := <-done:
				if got {
					t.Fatal("canceled check verified the tunnel")
				}
			case <-time.After(500 * time.Millisecond):
				t.Fatal("cancellation did not stop pending checks")
			}
		})
	}
}

func TestProbeSerializesConcurrentCallsWithinTheirOwnDeadline(t *testing.T) {
	var active, peak atomic.Int32
	tunnel, _ := testProxy(t, func(ctx context.Context, _, _ string) (net.Conn, error) {
		now := active.Add(1)
		defer active.Add(-1)
		for previous := peak.Load(); now > previous && !peak.CompareAndSwap(previous, now); previous = peak.Load() {
		}
		<-ctx.Done()
		return nil, ctx.Err()
	})
	ctx, cancel := context.WithTimeout(context.Background(), 100*time.Millisecond)
	defer cancel()
	var callers sync.WaitGroup
	for i := 0; i < 8; i++ {
		callers.Add(1)
		go func() {
			defer callers.Done()
			if tunnel.probeContext(ctx) {
				t.Error("blocked checks verified tunnel")
			}
		}()
	}
	callers.Wait()
	if active.Load() != 0 || peak.Load() > int32(len(tunnelProbeChecks)) {
		t.Fatalf("concurrent calls created unbounded work: active=%d peak=%d", active.Load(), peak.Load())
	}
}

func TestProbeHTTPResponseStillRequiresWireGuardHandshake(t *testing.T) {
	tunnel, _ := testProxy(t, nil)
	tunnel.transport.CloseIdleConnections()
	tunnel.transport = probeFixture(t, func(w http.ResponseWriter, _ *http.Request) { w.WriteHeader(http.StatusNoContent) }, nil)
	if tunnel.probe() {
		t.Fatal("HTTP success bypassed WireGuard traffic and handshake checks")
	}
	tunnel.packetLink = io.NopCloser(strings.NewReader(""))
	if !tunnel.probe() {
		t.Fatal("OpenVPN probe unexpectedly required WireGuard statistics")
	}
}

func TestProbeDeadlineClosesLosingTLSHandshakes(t *testing.T) {
	var active atomic.Int32
	closed := make(chan struct{}, len(tunnelProbeChecks))
	transport := &http.Transport{TLSHandshakeTimeout: 5 * time.Second,
		DialContext: func(context.Context, string, string) (net.Conn, error) {
			client, peer := net.Pipe()
			active.Add(1)
			go func() {
				defer func() { peer.Close(); active.Add(-1); closed <- struct{}{} }()
				_, _ = io.Copy(io.Discard, peer) // Accept ClientHello, never answer it.
			}()
			return client, nil
		}}
	t.Cleanup(transport.CloseIdleConnections)
	start := time.Now()
	if shortProbe(t, transport, 100*time.Millisecond) {
		t.Fatal("unfinished TLS verified the tunnel")
	}
	for range tunnelProbeChecks {
		select {
		case <-closed:
		case <-time.After(500 * time.Millisecond):
			t.Fatal("cancellation left TLS connection setup running")
		}
	}
	if active.Load() != 0 || time.Since(start) > time.Second {
		t.Fatal("TLS handshakes outlived the shared deadline")
	}
}

func TestProbeLeavesPlaybackConnectionPoolUsable(t *testing.T) {
	var dials atomic.Int32
	transport := probeFixture(t, func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/playback" {
			_, _ = io.WriteString(w, "video")
			return
		}
		w.WriteHeader(http.StatusNoContent)
	}, func(context.Context, string) error { dials.Add(1); return nil })
	client := &http.Client{Transport: transport, Timeout: time.Second}
	play := func() {
		t.Helper()
		response, err := client.Get("https://cp.cloudflare.com/playback")
		if err != nil {
			t.Fatal(err)
		}
		body, err := io.ReadAll(response.Body)
		response.Body.Close()
		if err != nil || string(body) != "video" {
			t.Fatal("playback fixture failed")
		}
	}
	play()
	if !shortProbe(t, transport, time.Second) {
		t.Fatal("healthy probe failed")
	}
	before := dials.Load()
	play()
	if dials.Load() != before {
		t.Fatal("probe drained playback's shared idle connection")
	}
}

func TestProbeMissingOrUnexpectedDialerFailsClosed(t *testing.T) {
	var custom atomic.Int32
	if shortProbe(t, &http.Transport{}, time.Second) {
		t.Fatal("missing private dialer used the default network")
	}
	transport := &http.Transport{DialContext: func(context.Context, string, string) (net.Conn, error) {
		custom.Add(1)
		return nil, errors.New("not a tunnel")
	}, DialTLSContext: func(context.Context, string, string) (net.Conn, error) {
		custom.Add(1)
		return nil, errors.New("unexpected TLS dialer")
	}}
	if shortProbe(t, transport, time.Second) || custom.Load() != 0 {
		t.Fatal("unexpected TLS dialer bypassed private transport assumptions")
	}
}

func TestProbeDoesNotVerifyReplacedTransport(t *testing.T) {
	started, release := make(chan struct{}, len(tunnelProbeChecks)), make(chan struct{})
	transport := probeFixture(t, func(w http.ResponseWriter, r *http.Request) {
		started <- struct{}{}
		select {
		case <-release:
			w.WriteHeader(http.StatusNoContent)
		case <-r.Context().Done():
		}
	}, nil)
	tunnel, _ := testProxy(t, nil)
	tunnel.transport.CloseIdleConnections()
	tunnel.transport = transport
	tunnel.packetLink = io.NopCloser(strings.NewReader(""))
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	done := make(chan bool, 1)
	go func() { done <- tunnel.probeContext(ctx) }()
	select {
	case <-started:
	case <-ctx.Done():
		t.Fatal("probe did not start")
	}
	tunnel.mu.Lock()
	tunnel.transport = privateTransport(nil)
	tunnel.mu.Unlock()
	close(release)
	select {
	case got := <-done:
		if got {
			t.Fatal("old transport verified a replacement session")
		}
	case <-ctx.Done():
		t.Fatal("probe did not finish")
	}
}

func TestProbeAlreadyCanceledDoesNotDial(t *testing.T) {
	var attempts atomic.Int32
	tunnel, _ := testProxy(t, func(context.Context, string, string) (net.Conn, error) {
		attempts.Add(1)
		return nil, errors.New("unexpected dial")
	})
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if tunnel.probeContext(ctx) || attempts.Load() != 0 {
		t.Fatal("canceled probe started network work")
	}
}
