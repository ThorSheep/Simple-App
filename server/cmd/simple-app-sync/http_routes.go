package main

import "net/http"

func (s *server) routes() http.Handler {
	if s.notifications == nil {
		s.notifications = newNotificationHub()
	}
	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", s.health)
	mux.HandleFunc("POST /v1/pair", s.pair)
	mux.HandleFunc("POST /v1/sync", s.sync)
	mux.HandleFunc("GET /v1/notifications", s.notificationsEndpoint)
	mux.HandleFunc("POST /v1/devices/revoke", s.revoke)
	if s.mcp != nil && s.mcp.enabled() {
		mux.Handle("/mcp", s.mcp)
	}
	if s.lineBot != nil && s.lineBot.enabled() {
		mux.HandleFunc("POST /line/webhook", s.lineBot.webhook)
	}
	if s.gmail != nil && s.gmail.enabled() {
		mux.HandleFunc("GET /gmail/connect", s.gmail.connect)
		mux.HandleFunc("GET /gmail/oauth/callback", s.gmail.callback)
		mux.HandleFunc("GET /gmail/status", s.gmail.status)
		mux.HandleFunc("POST /gmail/rescan", s.gmail.rescan)
	}
	return limitBody(mux)
}

func limitBody(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		r.Body = http.MaxBytesReader(w, r.Body, 5<<20)
		next.ServeHTTP(w, r)
	})
}

func (s *server) health(w http.ResponseWriter, r *http.Request) {
	if err := s.db.PingContext(r.Context()); err != nil {
		writeError(w, http.StatusServiceUnavailable, "database_unavailable")
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
}
