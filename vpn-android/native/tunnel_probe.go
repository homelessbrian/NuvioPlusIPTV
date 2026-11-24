package main

import (
	"context"
	"io"
	"net"
	"net/http"
	"net/netip"
	"strings"
	"sync"
)

// Match the Full app alternatives without leaving the selected tunnel. A single
// blocked check must not prevent otherwise usable protected playback.
var tunnelProbeChecks = [...]string{
	"https://www.gstatic.com/generate_204",
	"https://cp.cloudflare.com/generate_204",
	"https://1.1.1.1/cdn-cgi/trace",
}

const tunnelProbeBodyLimit = 2048

func probeHTTPS(parent context.Context, transport *http.Transport) bool {
	if transport == nil || transport.DialContext == nil || transport.DialTLS != nil || transport.DialTLSContext != nil {
		return false
	}
	ctx, cancel := context.WithCancel(parent)
	// net/http detaches request cancellation while establishing reusable pooled
	// connections. Own this small pool and bind its existing private dialer to the
	// probe deadline as well, without canceling or draining playback connections.
	checks := transport.Clone()
	checks.Proxy = nil
	checks.DialTLS, checks.DialTLSContext = nil, nil
	checks.DisableKeepAlives = true
	checks.DialContext = func(parent context.Context, network, address string) (net.Conn, error) {
		dialCtx, dialCancel := context.WithCancel(parent)
		stop := context.AfterFunc(ctx, dialCancel)
		defer stop()
		defer dialCancel()
		if ctx.Err() != nil {
			dialCancel()
		}
		connection, err := transport.DialContext(dialCtx, network, address)
		if err != nil {
			if connection != nil {
				connection.Close()
			}
			return nil, err
		}
		if err = ctx.Err(); err != nil {
			connection.Close()
			return nil, err
		}
		// Connection setup (including TLS) also outlives request cancellation in
		// net/http. This connection belongs only to the probe, so close it when
		// the shared context ends, including a losing TLS handshake.
		return &probeConnection{Conn: connection, stop: context.AfterFunc(ctx, func() { connection.Close() })}, nil
	}
	var workers sync.WaitGroup
	defer func() {
		cancel()
		workers.Wait()
		checks.CloseIdleConnections()
	}()
	results := make(chan bool, len(tunnelProbeChecks))
	for _, address := range tunnelProbeChecks {
		workers.Add(1)
		go func() {
			defer workers.Done()
			results <- probeAnswer(ctx, checks, address)
		}()
	}
	for range tunnelProbeChecks {
		select {
		case <-ctx.Done():
			return false
		case answered := <-results:
			if answered && ctx.Err() == nil {
				return true
			}
		}
	}
	return false
}

type probeConnection struct {
	net.Conn
	stop func() bool
}

func (c *probeConnection) Close() error {
	c.stop()
	return c.Conn.Close()
}

func probeAnswer(ctx context.Context, transport *http.Transport, address string) bool {
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, address, nil)
	if err != nil || request.URL.Scheme != "https" {
		return false
	}
	// RoundTrip deliberately does not follow redirects. The supplied transport is
	// always the VPN netstack transport, including HTTPS in HTTP-only stream mode.
	response, err := transport.RoundTrip(request)
	if err != nil {
		return false
	}
	defer response.Body.Close()
	if strings.HasSuffix(request.URL.Path, "/generate_204") {
		return response.StatusCode == http.StatusNoContent
	}
	if response.StatusCode != http.StatusOK {
		return false
	}
	body, err := io.ReadAll(io.LimitReader(response.Body, tunnelProbeBodyLimit+1))
	if err != nil || len(body) > tunnelProbeBodyLimit {
		return false
	}
	for _, line := range strings.Split(string(body), "\n") {
		if value, found := strings.CutPrefix(strings.TrimSuffix(line, "\r"), "ip="); found {
			address, err := netip.ParseAddr(value)
			return err == nil && address.Zone() == "" && !address.IsUnspecified() && !address.IsMulticast()
		}
	}
	return false
}
