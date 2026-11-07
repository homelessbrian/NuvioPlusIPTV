package main

import (
	"context"
	"net"
	"time"
)

// tunnelDial bounds setup and races address families using only the private
// tunnel dialer. Neither failure nor cancellation can select the system network.
func tunnelDial(dial func(context.Context, string, string) (net.Conn, error), timeout, fallback time.Duration) func(context.Context, string, string) (net.Conn, error) {
	return func(parent context.Context, network, address string) (net.Conn, error) {
		ctx, cancel := context.WithTimeout(parent, timeout)
		defer cancel()
		if err := ctx.Err(); err != nil {
			return nil, err
		}
		if network != "tcp" {
			return dial(ctx, network, address)
		}
		type result struct {
			conn net.Conn
			err  error
		}
		results := make(chan result)
		start := func(family string) {
			go func() {
				conn, err := dial(ctx, family, address)
				select {
				case results <- result{conn, err}:
				case <-ctx.Done():
					if conn != nil {
						conn.Close()
					}
				}
			}()
		}
		start("tcp6")
		timer := time.NewTimer(fallback)
		defer timer.Stop()
		secondary := false
		failures := 0
		var firstErr error
		for {
			select {
			case <-ctx.Done():
				return nil, ctx.Err()
			case <-timer.C:
				if !secondary {
					secondary = true
					start("tcp4")
				}
			case r := <-results:
				if r.err == nil {
					if err := ctx.Err(); err != nil {
						r.conn.Close()
						return nil, err
					}
					return r.conn, nil
				}
				if r.conn != nil {
					r.conn.Close()
				}
				if err := ctx.Err(); err != nil {
					return nil, err
				}
				if firstErr == nil {
					firstErr = r.err
				}
				failures++
				if failures == 2 {
					return nil, firstErr
				}
				if !secondary {
					secondary = true
					start("tcp4")
				}
			}
		}
	}
}
