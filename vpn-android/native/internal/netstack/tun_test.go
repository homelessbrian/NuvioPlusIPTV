package netstack

import (
	"context"
	"errors"
	"net/netip"
	"syscall"
	"testing"
	"time"

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
