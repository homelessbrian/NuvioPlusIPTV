package main

// ReverseProxy otherwise allocates a new copy buffer for every ranged response.
// Retain at most 256 KiB across sessions; active responses never share a buffer.
const responseBufferBytes = 32 << 10

type responseBufferPool chan []byte

var playbackResponseBuffers = make(responseBufferPool, 8)

func (p responseBufferPool) Get() []byte {
	select {
	case buf := <-p:
		return buf
	default:
		return make([]byte, responseBufferBytes)
	}
}

func (p responseBufferPool) Put(buf []byte) {
	if cap(buf) != responseBufferBytes {
		return
	}
	select {
	case p <- buf[:responseBufferBytes]:
	default:
	}
}
