package main

import (
	"io"
	"net/http"
	"net/http/httputil"
	"strings"
	"sync"
	"testing"
)

type responseBenchmarkTransport struct{ payload string }

func (t responseBenchmarkTransport) RoundTrip(*http.Request) (*http.Response, error) {
	return &http.Response{StatusCode: http.StatusOK, Header: make(http.Header),
		ContentLength: int64(len(t.payload)), Body: io.NopCloser(strings.NewReader(t.payload))}, nil
}

type responseBenchmarkWriter struct{ header http.Header }

func (w responseBenchmarkWriter) Header() http.Header       { return w.header }
func (responseBenchmarkWriter) WriteHeader(int)             {}
func (responseBenchmarkWriter) Write(p []byte) (int, error) { return len(p), nil }
func (responseBenchmarkWriter) Flush()                      {}

func BenchmarkProxyResponseCopy(b *testing.B) {
	for _, pooled := range []bool{false, true} {
		name := "unpooled"
		if pooled {
			name = "bounded-pool"
		}
		b.Run(name, func(b *testing.B) {
			proxy := &httputil.ReverseProxy{Director: func(*http.Request) {}, FlushInterval: -1,
				Transport: responseBenchmarkTransport{strings.Repeat("x", 128<<10)}}
			if pooled {
				proxy.BufferPool = make(responseBufferPool, 8)
			}
			request, _ := http.NewRequest(http.MethodGet, "http://fixture.invalid/stream", nil)
			writer := responseBenchmarkWriter{make(http.Header)}
			b.ReportAllocs()
			b.ResetTimer()
			for i := 0; i < b.N; i++ {
				proxy.ServeHTTP(writer, request)
			}
		})
	}
}

func TestResponseBuffersHaveBoundedRetention(t *testing.T) {
	p := make(responseBufferPool, 2)
	first, second, third := p.Get(), p.Get(), p.Get()
	if &first[0] == &second[0] || &second[0] == &third[0] {
		t.Fatal("active responses share a copy buffer")
	}
	p.Put(first[:1])
	p.Put(second)
	p.Put(third)
	p.Put(make([]byte, responseBufferBytes*8))
	if len(p) != 2 {
		t.Fatalf("retained %d buffers, want bounded capacity 2", len(p))
	}
	reused := p.Get()
	if len(reused) != responseBufferBytes || &reused[0] != &first[0] {
		t.Fatal("buffer not reused at its full fixed size")
	}
}

func TestConcurrentResponseBuffersRemainIndependentlyOwned(t *testing.T) {
	p := make(responseBufferPool, 8)
	var workers sync.WaitGroup
	for id := 1; id <= 32; id++ {
		workers.Add(1)
		go func(id byte) {
			defer workers.Done()
			for i := 0; i < 20; i++ {
				buf := p.Get()
				for j := range buf {
					buf[j] = id
				}
				for _, got := range buf {
					if got != id {
						t.Error("another response changed an owned buffer")
						break
					}
				}
				p.Put(buf)
			}
		}(byte(id))
	}
	workers.Wait()
}
