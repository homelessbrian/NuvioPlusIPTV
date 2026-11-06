package netstack

import (
	"context"
	"errors"
	"net"
	"net/netip"
	"sync"
	"sync/atomic"
	"syscall"
	"testing"
	"time"

	"golang.org/x/net/dns/dnsmessage"
	"gvisor.dev/gvisor/pkg/refs"
)

// These tests run in their own package so the process-wide reference checker
// cannot mistake another WireGuard test's live packets for leaked references.
func TestRejectedPacketReleasesCallerReference(t *testing.T) {
	for _, version := range []byte{0x45, 0xf0} {
		t.Run(map[byte]string{0x45: "invalid-ipv4", 0xf0: "unsupported-version"}[version], func(t *testing.T) {
			previous := refs.GetLeakMode()
			refs.SetLeakMode(refs.LeaksPanic)
			defer refs.SetLeakMode(previous)
			dev, _, err := CreateNetTUN([]netip.Addr{netip.MustParseAddr("10.1.2.3")}, nil, 1500)
			if err != nil {
				t.Fatal(err)
			}
			packet := make([]byte, 1500)
			packet[0] = version // Invalid length/checksum causes immediate rejection.
			_, err = dev.Write([][]byte{packet}, 0)
			if version == 0xf0 && !errors.Is(err, syscall.EAFNOSUPPORT) {
				t.Errorf("unsupported packet: %v", err)
			} else if version == 0x45 && err != nil {
				t.Errorf("discarded IPv4 packet: %v", err)
			}
			if err := dev.Close(); err != nil {
				t.Fatal(err)
			}
			refs.DoRepeatedLeakCheck()
		})
	}
}

func TestReadReleasesOutboundPacketView(t *testing.T) {
	previous := refs.GetLeakMode()
	refs.SetLeakMode(refs.LeaksPanic)
	defer refs.SetLeakMode(previous)
	dev, network, err := CreateNetTUN([]netip.Addr{netip.MustParseAddr("10.1.2.3")}, nil, 1500)
	if err != nil {
		t.Fatal(err)
	}
	conn, err := network.DialUDPAddrPort(netip.AddrPort{}, netip.MustParseAddrPort("10.1.2.4:9000"))
	if err != nil {
		dev.Close()
		t.Fatal(err)
	}
	done := make(chan error, 1)
	go func() { _, err := conn.Write([]byte("packet ownership")); done <- err }()
	bufs, sizes := [][]byte{make([]byte, 65535)}, make([]int, 1)
	if n, err := dev.Read(bufs, sizes, 0); err != nil || n != 1 || sizes[0] == 0 {
		t.Errorf("read packet: n=%d, size=%d, err=%v", n, sizes[0], err)
	}
	select {
	case err := <-done:
		if err != nil {
			t.Errorf("write packet: %v", err)
		}
	case <-time.After(time.Second):
		t.Error("packet writer did not finish")
	}
	conn.Close()
	dev.Close()
	refs.DoRepeatedLeakCheck()
}

func TestLookupCancellationClosesDroppedDNSRead(t *testing.T) {
	address := netip.MustParseAddr("10.1.2.3")
	dev, network, err := CreateNetTUN([]netip.Addr{address}, []netip.Addr{address}, 1500)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { dev.Close() })
	dns, err := network.ListenUDPAddrPort(netip.AddrPortFrom(address, 53))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { dns.Close() })
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	done := make(chan error, 1)
	go func() { _, err := network.LookupContextHost(ctx, "playback.test"); done <- err }()
	// Receive the actual DNS packet without responding; cancellation must
	// interrupt the socket read rather than waiting for its five-second deadline.
	if err := dns.SetReadDeadline(time.Now().Add(time.Second)); err != nil {
		t.Fatal(err)
	}
	if _, _, err := dns.ReadFrom(make([]byte, 512)); err != nil {
		t.Fatal(err)
	}
	cancel()
	select {
	case err := <-done:
		if !errors.Is(err, context.Canceled) {
			t.Fatalf("lookup cancellation: %v", err)
		}
	case <-time.After(time.Second):
		t.Fatal("canceled DNS lookup kept waiting for a response")
	}
	if err := dns.SetReadDeadline(time.Now().Add(50 * time.Millisecond)); err != nil {
		t.Fatal(err)
	}
	if _, _, err := dns.ReadFrom(make([]byte, 512)); err == nil {
		t.Fatal("canceled lookup sent another DNS query")
	}
}

type dnsTestServer struct {
	response string
	queries  atomic.Int32
	received chan struct{}
}

// These DNS servers live entirely in the private userspace network. No host
// resolver or provider account is needed, including for failure scenarios.
func dnsTestNetwork(t *testing.T, responses ...string) (*Net, []*dnsTestServer, int) {
	t.Helper()
	addresses := make([]netip.Addr, len(responses))
	for i := range responses {
		addresses[i] = netip.AddrFrom4([4]byte{10, 1, 2, byte(i + 3)})
	}
	dev, network, err := CreateNetTUN(addresses, addresses, 1280)
	if err != nil {
		t.Fatal(err)
	}
	servers := make([]*dnsTestServer, len(responses))
	var listeners []interface{ Close() error }
	var workers sync.WaitGroup
	t.Cleanup(func() {
		for _, listener := range listeners {
			listener.Close()
		}
		workers.Wait()
		dev.Close()
	})
	for i, response := range responses {
		server := &dnsTestServer{response: response, received: make(chan struct{}, 100)}
		servers[i] = server
		if response == "unbound" {
			continue // Local UDP port rejection exercises immediate transport errors.
		}
		listener, err := network.ListenUDPAddrPort(netip.AddrPortFrom(addresses[i], 53))
		if err != nil {
			t.Fatal(err)
		}
		listeners = append(listeners, listener)
		workers.Add(1)
		go func() {
			defer workers.Done()
			buf := make([]byte, 1500)
			for {
				n, peer, err := listener.ReadFrom(buf)
				if err != nil {
					return
				}
				var query dnsmessage.Message
				if query.Unpack(buf[:n]) != nil || len(query.Questions) != 1 {
					continue
				}
				server.queries.Add(1)
				server.received <- struct{}{}
				if server.response == "drop" {
					continue
				}
				answer := dnsmessage.Message{
					Header:    dnsmessage.Header{ID: query.ID, Response: true, Authoritative: true, RecursionAvailable: true},
					Questions: query.Questions,
				}
				switch server.response {
				case "answer":
					answer.Answers = []dnsmessage.Resource{{
						Header: dnsmessage.ResourceHeader{Name: query.Questions[0].Name, Type: dnsmessage.TypeA, Class: dnsmessage.ClassINET, TTL: 30},
						Body:   &dnsmessage.AResource{A: [4]byte{203, 0, 113, 1}},
					}}
				case "nxdomain":
					answer.RCode = dnsmessage.RCodeNameError
				case "servfail":
					answer.RCode = dnsmessage.RCodeServerFailure
				case "nodata":
					// A valid empty answer is final for this record type.
				}
				packet, err := answer.Pack()
				if err == nil {
					listener.WriteTo(packet, peer)
				}
			}
		}()
	}
	return network, servers, len(network.stack.RegisteredEndpoints())
}

func checkDNSClientSocketsClosed(t *testing.T, network *Net, listeners int) {
	t.Helper()
	if got := len(network.stack.RegisteredEndpoints()); got != listeners {
		t.Errorf("DNS client sockets remain registered after lookup: got %d endpoints, want %d listeners", got, listeners)
	}
}

func TestDNSFastPrimaryDoesNotQueryFallback(t *testing.T) {
	network, servers, listeners := dnsTestNetwork(t, "answer", "answer")
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	addresses, err := network.lookupContextHost(ctx, "playback.test", true, false)
	if err != nil || len(addresses) != 1 || addresses[0] != "203.0.113.1" {
		t.Fatalf("primary lookup: %v, %v", addresses, err)
	}
	checkDNSClientSocketsClosed(t, network, listeners)
	select {
	case <-servers[1].received:
		t.Fatal("fast primary unnecessarily queried fallback DNS")
	case <-time.After(300 * time.Millisecond):
	}
}

func TestDNSDroppedPrimaryUsesConfiguredFallback(t *testing.T) {
	network, servers, listeners := dnsTestNetwork(t, "drop", "answer")
	ctx, cancel := context.WithTimeout(context.Background(), 900*time.Millisecond)
	defer cancel()
	start := time.Now()
	addresses, err := network.lookupContextHost(ctx, "playback.test", true, false)
	if err != nil || len(addresses) != 1 || addresses[0] != "203.0.113.1" {
		t.Fatalf("working fallback was blocked by primary: %v, %v", addresses, err)
	}
	if elapsed := time.Since(start); elapsed >= time.Second {
		t.Fatalf("working fallback delayed lookup: %v", elapsed)
	}
	for i, server := range servers {
		if got := server.queries.Load(); got != 1 {
			t.Errorf("DNS server %d queries: got %d, want 1", i, got)
		}
	}
	checkDNSClientSocketsClosed(t, network, listeners)
	t.Logf("unresponsive primary / healthy fallback resolved in %v", time.Since(start))
}

func TestDNSTransportFailureStartsFallbackImmediately(t *testing.T) {
	network, _, listeners := dnsTestNetwork(t, "unbound", "answer")
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	start := time.Now()
	addresses, err := network.lookupContextHost(ctx, "playback.test", true, false)
	if err != nil || len(addresses) != 1 {
		t.Fatalf("transport error fallback: %v, %v", addresses, err)
	}
	if elapsed := time.Since(start); elapsed >= 200*time.Millisecond {
		t.Errorf("transport failure waited for fallback timer: %v", elapsed)
	}
	checkDNSClientSocketsClosed(t, network, listeners)
}

func TestDNSNegativeAnswerIsNotBypassed(t *testing.T) {
	for _, response := range []string{"nxdomain", "nodata"} {
		t.Run(response, func(t *testing.T) {
			network, servers, listeners := dnsTestNetwork(t, response, "answer")
			ctx, cancel := context.WithTimeout(context.Background(), time.Second)
			defer cancel()
			addresses, err := network.lookupContextHost(ctx, "playback.test", true, false)
			var dnsErr *net.DNSError
			if len(addresses) != 0 || !errors.As(err, &dnsErr) || !dnsErr.IsNotFound {
				t.Fatalf("negative DNS answer: %v, %v", addresses, err)
			}
			if servers[0].queries.Load() != 1 || servers[1].queries.Load() != 0 {
				t.Fatal("negative primary answer retried or queried a fallback")
			}
			checkDNSClientSocketsClosed(t, network, listeners)
		})
	}
}

func TestDNSFallbackNegativeCancelsOutstandingPrimary(t *testing.T) {
	network, servers, listeners := dnsTestNetwork(t, "drop", "nxdomain", "answer")
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	addresses, err := network.lookupContextHost(ctx, "playback.test", true, false)
	var dnsErr *net.DNSError
	if len(addresses) != 0 || !errors.As(err, &dnsErr) || !dnsErr.IsNotFound {
		t.Fatalf("negative fallback answer: %v, %v", addresses, err)
	}
	if servers[2].queries.Load() != 0 {
		t.Fatal("negative fallback answer queried another DNS server")
	}
	checkDNSClientSocketsClosed(t, network, listeners)
}

func TestDNSTransientErrorsKeepTwoAttemptsPerServer(t *testing.T) {
	network, servers, listeners := dnsTestNetwork(t, "servfail", "servfail")
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	addresses, err := network.lookupContextHost(ctx, "playback.test", true, false)
	var dnsErr *net.DNSError
	if len(addresses) != 0 || !errors.As(err, &dnsErr) || !dnsErr.IsTemporary {
		t.Fatalf("transient DNS errors did not fail closed: %v, %v", addresses, err)
	}
	for i, server := range servers {
		if got := server.queries.Load(); got != 2 {
			t.Errorf("DNS server %d attempts: got %d, want 2", i, got)
		}
	}
	checkDNSClientSocketsClosed(t, network, listeners)
}

func TestDNSCancellationStopsFallbackAndClosesClients(t *testing.T) {
	network, servers, listeners := dnsTestNetwork(t, "drop", "answer")
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	done := make(chan error, 1)
	go func() {
		_, err := network.lookupContextHost(ctx, "playback.test", true, false)
		done <- err
	}()
	select {
	case <-servers[0].received:
	case <-time.After(time.Second):
		t.Fatal("primary DNS query did not arrive")
	}
	cancel()
	select {
	case err := <-done:
		if !errors.Is(err, context.Canceled) {
			t.Fatalf("canceled DNS lookup: %v", err)
		}
	case <-time.After(time.Second):
		t.Fatal("canceled lookup left an outstanding DNS socket")
	}
	checkDNSClientSocketsClosed(t, network, listeners)
	select {
	case <-servers[1].received:
		t.Fatal("canceled lookup sent a fallback DNS query")
	case <-time.After(300 * time.Millisecond):
	}
	if servers[0].queries.Load() != 1 {
		t.Fatal("canceled lookup retried the primary")
	}
}

func TestDNSAllConfiguredServersUnavailableFailsClosed(t *testing.T) {
	network, servers, listeners := dnsTestNetwork(t, "drop", "drop")
	ctx, cancel := context.WithTimeout(context.Background(), 650*time.Millisecond)
	defer cancel()
	addresses, err := network.lookupContextHost(ctx, "playback.test", true, false)
	if len(addresses) != 0 || !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("unavailable VPN DNS did not fail closed: %v, %v", addresses, err)
	}
	for i, server := range servers {
		if got := server.queries.Load(); got != 1 {
			t.Errorf("DNS server %d attempts: got %d, want 1 before cancellation", i, got)
		}
	}
	checkDNSClientSocketsClosed(t, network, listeners)
}
