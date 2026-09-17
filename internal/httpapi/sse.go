package httpapi

import (
	"encoding/json"
	"fmt"
	"net/http"
	"strings"
)

func wantsSSE(r *http.Request) bool {
	return strings.Contains(strings.ToLower(r.Header.Get("Accept")), "text/event-stream")
}

func startSSE(w http.ResponseWriter) (http.Flusher, func(event string, payload any) bool, bool) {
	flusher, ok := w.(http.Flusher)
	if !ok {
		return nil, nil, false
	}
	w.Header().Set("Content-Type", "text/event-stream")
	w.Header().Set("Cache-Control", "no-cache, no-transform")
	w.Header().Set("Connection", "keep-alive")
	w.Header().Set("X-Accel-Buffering", "no")
	w.WriteHeader(http.StatusOK)
	flusher.Flush()
	write := func(event string, payload any) bool {
		var b []byte
		switch v := payload.(type) {
		case json.RawMessage:
			b = v
		case []byte:
			b = v
		default:
			var err error
			b, err = json.Marshal(payload)
			if err != nil {
				return false
			}
		}
		if _, err := fmt.Fprintf(w, "event: %s\ndata: %s\n\n", event, b); err != nil {
			return false
		}
		flusher.Flush()
		return true
	}
	return flusher, write, true
}
