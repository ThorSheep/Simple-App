package main

import (
	"net/http"
	"net/url"
	"sync"
	"time"

	"github.com/gorilla/websocket"
)

// notificationHub only carries a wake-up signal. Clients still fetch changes
// through /v1/sync, so the existing cursor and conflict rules remain central.
type notificationHub struct {
	mu      sync.Mutex
	clients map[*websocket.Conn]string
}

var websocketUpgrader = websocket.Upgrader{
	CheckOrigin: func(r *http.Request) bool {
		// Native Android clients do not send an Origin header. If one is present,
		// only accept the server's own origin.
		origin := r.Header.Get("Origin")
		if origin == "" {
			return true
		}
		parsed, err := url.Parse(origin)
		return err == nil && parsed.Scheme == "https" && parsed.Host == r.Host
	},
}

func newNotificationHub() *notificationHub {
	return &notificationHub{clients: make(map[*websocket.Conn]string)}
}

func (h *notificationHub) add(connection *websocket.Conn, deviceID string) {
	h.mu.Lock()
	h.clients[connection] = deviceID
	h.mu.Unlock()
}

func (h *notificationHub) remove(connection *websocket.Conn) {
	h.mu.Lock()
	delete(h.clients, connection)
	h.mu.Unlock()
	_ = connection.Close()
}

func (h *notificationHub) notifyOthers(senderID string) {
	h.mu.Lock()
	defer h.mu.Unlock()
	for connection, deviceID := range h.clients {
		if deviceID == senderID {
			continue
		}
		_ = connection.SetWriteDeadline(time.Now().Add(5 * time.Second))
		if err := connection.WriteJSON(map[string]string{"type": "changes_available"}); err != nil {
			delete(h.clients, connection)
			_ = connection.Close()
		}
	}
}
