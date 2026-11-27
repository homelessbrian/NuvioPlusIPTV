package main

import (
	"context"
	"crypto/tls"
	"errors"
	"io"
	"net"
	"net/http"
	"net/http/httptrace"
	"net/netip"
	"slices"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
)

// Match the Full app alternatives without leaving the selected tunnel. A single
// blocked check must not prevent otherwise usable protected playback.
var tunnelProbeChecks = [...]string{
	"https://www.gstatic.com/generate_204",
	"https://cp.cloudflare.com/generate_204",
	"https://1.1.1.1/cdn-cgi/trace",
}

const tunnelProbeBodyLimit = 2048
const probeOK = "ok"

// Only fixed, non-identifying outcomes from the existing checks enter a report.
func probeHTTPS(parent context.Context, transport *http.Transport) string {
	if transport == nil || transport.DialContext == nil || transport.DialTLS != nil || transport.DialTLSContext != nil {
		return "unavailable"
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
	results := make(chan string, len(tunnelProbeChecks))
	for _, address := range tunnelProbeChecks {
		workers.Add(1)
		go func() {
			defer workers.Done()
			results <- probeAnswer(ctx, checks, address)
		}()
	}
	var failures []string
collect:
	for range tunnelProbeChecks {
		select {
		case <-ctx.Done():
			failures = append(failures, probeContextDetail(ctx.Err()))
			break collect
		case answered := <-results:
			if answered == probeOK {
				if ctx.Err() == nil {
					return probeOK
				}
				answered = probeContextDetail(ctx.Err())
			}
			failures = append(failures, answered)
		}
	}
	slices.Sort(failures)
	return strings.Join(slices.Compact(failures), "+")
}

type probeConnection struct {
	net.Conn
	stop func() bool
}

func (c *probeConnection) Close() error {
	c.stop()
	return c.Conn.Close()
}

func probeContextDetail(err error) string {
	if errors.Is(err, context.DeadlineExceeded) {
		return "timeout"
	}
	return "cancelled"
}

func probeAnswer(ctx context.Context, transport *http.Transport, address string) string {
	// The private dialer can return plain errors. Record whether TLS started or
	// completed to distinguish connection and handshake failures without error text.
	var tlsStarted, tlsCompleted atomic.Bool
	trace := &httptrace.ClientTrace{
		TLSHandshakeStart: func() { tlsStarted.Store(true) },
		TLSHandshakeDone:  func(_ tls.ConnectionState, err error) { tlsCompleted.Store(err == nil) },
	}
	ctx = httptrace.WithClientTrace(ctx, trace)
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, address, nil)
	if err != nil || request.URL.Scheme != "https" {
		return "unavailable"
	}
	// RoundTrip deliberately does not follow redirects. The supplied transport is
	// always the VPN netstack transport, including HTTPS in HTTP-only stream mode.
	response, err := transport.RoundTrip(request)
	if err != nil {
		var dnsErr *net.DNSError
		var netErr net.Error
		switch {
		case ctx.Err() != nil:
			return probeContextDetail(ctx.Err())
		case errors.As(err, &dnsErr):
			return "dns"
		case errors.As(err, &netErr) && netErr.Timeout():
			return "timeout"
		case !tlsStarted.Load():
			return "connect"
		case !tlsCompleted.Load():
			return "tls"
		default:
			return "io"
		}
	}
	defer response.Body.Close()
	if strings.HasSuffix(request.URL.Path, "/generate_204") {
		if response.StatusCode == http.StatusNoContent {
			return probeOK
		}
		return probeHTTPStatus(response.StatusCode)
	}
	if response.StatusCode != http.StatusOK {
		return probeHTTPStatus(response.StatusCode)
	}
	body, err := io.ReadAll(io.LimitReader(response.Body, tunnelProbeBodyLimit+1))
	if err != nil {
		if ctx.Err() != nil {
			return probeContextDetail(ctx.Err())
		}
		return "io"
	}
	if len(body) > tunnelProbeBodyLimit {
		return "invalid_body"
	}
	for _, line := range strings.Split(string(body), "\n") {
		if value, found := strings.CutPrefix(strings.TrimSuffix(line, "\r"), "ip="); found {
			address, err := netip.ParseAddr(value)
			if err == nil && address.Zone() == "" && !address.IsUnspecified() && !address.IsMulticast() {
				return probeOK
			}
			return "invalid_body"
		}
	}
	return "invalid_body"
}

func probeHTTPStatus(status int) string {
	if status >= 100 && status <= 999 {
		return "http_" + strconv.Itoa(status)
	}
	return "http_status"
}
