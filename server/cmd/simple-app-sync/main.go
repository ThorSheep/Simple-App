package main

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"log"
	"net/http"
	"net/url"
	"os"
	"strings"
	"sync"
	"time"

	"github.com/gorilla/websocket"
	_ "modernc.org/sqlite"
)

const protocolVersion = 1

type config struct {
	listenAddr string
	database   string
	pairCode   string
}

type server struct {
	db            *sql.DB
	pairCode      string
	notifications *notificationHub
}

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

type pairRequest struct {
	PairCode string `json:"pairCode"`
	DeviceID string `json:"deviceId"`
	Name     string `json:"name"`
}

type pairResponse struct {
	Token string `json:"token"`
}

type operation struct {
	OperationID string          `json:"operationId"`
	EntityType  string          `json:"entityType"`
	EntityID    string          `json:"entityId"`
	Revision    string          `json:"revision"`
	DeviceID    string          `json:"deviceId"`
	Deleted     bool            `json:"deleted"`
	Payload     json.RawMessage `json:"payload"`
}

type syncRequest struct {
	ProtocolVersion int         `json:"protocolVersion"`
	DeviceID        string      `json:"deviceId"`
	Cursor          int64       `json:"cursor"`
	Operations      []operation `json:"operations"`
}

type change struct {
	Cursor int64 `json:"cursor"`
	operation
}

type syncResponse struct {
	AcceptedOperationIDs []string `json:"acceptedOperationIds"`
	Cursor               int64    `json:"cursor"`
	Changes              []change `json:"changes"`
}

func main() {
	cfg := config{
		listenAddr: value("LISTEN_ADDR", ":8080"),
		database:   value("DATABASE_PATH", "/data/sync.db"),
		pairCode:   os.Getenv("PAIR_CODE"),
	}
	if len(cfg.pairCode) < 16 {
		log.Fatal("PAIR_CODE must contain at least 16 characters")
	}
	db, err := openDatabase(cfg.database)
	if err != nil {
		log.Fatal(err)
	}
	defer db.Close()
	s := &server{db: db, pairCode: cfg.pairCode, notifications: newNotificationHub()}
	log.Printf("Simple App sync server listening on %s", cfg.listenAddr)
	log.Fatal(http.ListenAndServe(cfg.listenAddr, s.routes()))
}

func value(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}

func openDatabase(path string) (*sql.DB, error) {
	db, err := sql.Open("sqlite", path+"?_pragma=busy_timeout(5000)&_pragma=foreign_keys(1)")
	if err != nil {
		return nil, err
	}
	if err := db.Ping(); err != nil {
		db.Close()
		return nil, err
	}
	_, err = db.Exec(`
		CREATE TABLE IF NOT EXISTS devices (
			id TEXT PRIMARY KEY,
			name TEXT NOT NULL,
			token_hash TEXT NOT NULL UNIQUE,
			revoked INTEGER NOT NULL DEFAULT 0,
			created_at INTEGER NOT NULL
		);
		CREATE TABLE IF NOT EXISTS changes (
			seq INTEGER PRIMARY KEY AUTOINCREMENT,
			operation_id TEXT NOT NULL UNIQUE,
			entity_type TEXT NOT NULL,
			entity_id TEXT NOT NULL,
			revision TEXT NOT NULL,
			device_id TEXT NOT NULL,
			deleted INTEGER NOT NULL,
			payload BLOB NOT NULL,
			created_at INTEGER NOT NULL,
			FOREIGN KEY(device_id) REFERENCES devices(id)
		);
		CREATE INDEX IF NOT EXISTS changes_seq ON changes(seq);
	`)
	if err != nil {
		db.Close()
		return nil, err
	}
	return db, nil
}

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
	return limitBody(mux)
}

func (s *server) notificationsEndpoint(w http.ResponseWriter, r *http.Request) {
	deviceID, ok := s.authenticate(r.Context(), r.Header.Get("Authorization"))
	if !ok {
		writeError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	connection, err := websocketUpgrader.Upgrade(w, r, nil)
	if err != nil {
		return
	}
	s.notifications.add(connection, deviceID)
	defer s.notifications.remove(connection)
	for {
		if _, _, err := connection.ReadMessage(); err != nil {
			return
		}
	}
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

func (s *server) pair(w http.ResponseWriter, r *http.Request) {
	var request pairRequest
	if !decodeJSON(w, r, &request) {
		return
	}
	if request.PairCode != s.pairCode || !validID(request.DeviceID) || len(request.Name) == 0 || len(request.Name) > 100 {
		writeError(w, http.StatusUnauthorized, "invalid_pairing_request")
		return
	}
	token, err := newToken()
	if err != nil {
		writeError(w, http.StatusInternalServerError, "token_generation_failed")
		return
	}
	_, err = s.db.ExecContext(r.Context(), `INSERT INTO devices(id, name, token_hash, created_at)
		VALUES(?, ?, ?, ?)
		ON CONFLICT(id) DO UPDATE SET name = excluded.name, token_hash = excluded.token_hash, revoked = 0`,
		request.DeviceID, request.Name, tokenHash(token), time.Now().UnixMilli())
	if err != nil {
		writeError(w, http.StatusInternalServerError, "device_registration_failed")
		return
	}
	writeJSON(w, http.StatusCreated, pairResponse{Token: token})
}

func (s *server) sync(w http.ResponseWriter, r *http.Request) {
	deviceID, ok := s.authenticate(r.Context(), r.Header.Get("Authorization"))
	if !ok {
		writeError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	var request syncRequest
	if !decodeJSON(w, r, &request) {
		return
	}
	if request.ProtocolVersion != protocolVersion || request.DeviceID != deviceID || request.Cursor < 0 || len(request.Operations) > 1000 {
		writeError(w, http.StatusBadRequest, "invalid_sync_request")
		return
	}
	for _, op := range request.Operations {
		if !validOperation(op, deviceID) {
			writeError(w, http.StatusBadRequest, "invalid_operation")
			return
		}
	}
	tx, err := s.db.BeginTx(r.Context(), nil)
	if err != nil {
		writeError(w, http.StatusServiceUnavailable, "database_unavailable")
		return
	}
	defer tx.Rollback()
	accepted := make([]string, 0, len(request.Operations))
	changed := false
	for _, op := range request.Operations {
		result, err := tx.ExecContext(r.Context(), `INSERT INTO changes(operation_id, entity_type, entity_id, revision, device_id, deleted, payload, created_at)
			VALUES(?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT(operation_id) DO NOTHING`,
			op.OperationID, op.EntityType, op.EntityID, op.Revision, op.DeviceID, op.Deleted, []byte(op.Payload), time.Now().UnixMilli())
		if err != nil {
			writeError(w, http.StatusInternalServerError, "change_write_failed")
			return
		}
		if count, _ := result.RowsAffected(); count > 0 {
			changed = true
			accepted = append(accepted, op.OperationID)
		} else {
			accepted = append(accepted, op.OperationID)
		}
	}
	rows, err := tx.QueryContext(r.Context(), `SELECT seq, operation_id, entity_type, entity_id, revision, device_id, deleted, payload
		FROM changes WHERE seq > ? ORDER BY seq LIMIT 1000`, request.Cursor)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "change_read_failed")
		return
	}
	defer rows.Close()
	response := syncResponse{AcceptedOperationIDs: accepted, Cursor: request.Cursor, Changes: make([]change, 0)}
	for rows.Next() {
		var item change
		var deleted int
		if err := rows.Scan(&item.Cursor, &item.OperationID, &item.EntityType, &item.EntityID, &item.Revision, &item.DeviceID, &deleted, &item.Payload); err != nil {
			writeError(w, http.StatusInternalServerError, "change_read_failed")
			return
		}
		item.Deleted = deleted != 0
		response.Changes = append(response.Changes, item)
		response.Cursor = item.Cursor
	}
	if err := rows.Err(); err != nil {
		writeError(w, http.StatusInternalServerError, "change_read_failed")
		return
	}
	if err := tx.Commit(); err != nil {
		writeError(w, http.StatusServiceUnavailable, "database_unavailable")
		return
	}
	if changed {
		s.notifications.notifyOthers(deviceID)
	}
	writeJSON(w, http.StatusOK, response)
}

func (s *server) revoke(w http.ResponseWriter, r *http.Request) {
	deviceID, ok := s.authenticate(r.Context(), r.Header.Get("Authorization"))
	if !ok {
		writeError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	if _, err := s.db.ExecContext(r.Context(), "UPDATE devices SET revoked = 1 WHERE id = ?", deviceID); err != nil {
		writeError(w, http.StatusServiceUnavailable, "database_unavailable")
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (s *server) authenticate(ctx context.Context, authorization string) (string, bool) {
	parts := strings.Fields(authorization)
	if len(parts) != 2 || parts[0] != "Bearer" || len(parts[1]) < 32 {
		return "", false
	}
	var deviceID string
	err := s.db.QueryRowContext(ctx, "SELECT id FROM devices WHERE token_hash = ? AND revoked = 0", tokenHash(parts[1])).Scan(&deviceID)
	return deviceID, err == nil
}

func validID(value string) bool {
	return len(value) > 0 && len(value) <= 100
}

func validOperation(op operation, deviceID string) bool {
	if !validID(op.OperationID) || !validID(op.EntityType) || !validID(op.EntityID) || !validID(op.Revision) || op.DeviceID != deviceID {
		return false
	}
	if len(op.Payload) == 0 || len(op.Payload) > 64<<10 || !json.Valid(op.Payload) {
		return false
	}
	return true
}

func newToken() (string, error) {
	bytes := make([]byte, 32)
	if _, err := rand.Read(bytes); err != nil {
		return "", err
	}
	return hex.EncodeToString(bytes), nil
}

func tokenHash(token string) string {
	sum := sha256.Sum256([]byte(token))
	return hex.EncodeToString(sum[:])
}

func decodeJSON(w http.ResponseWriter, r *http.Request, target any) bool {
	decoder := json.NewDecoder(r.Body)
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(target); err != nil {
		status := http.StatusBadRequest
		var maxBytesError *http.MaxBytesError
		if errors.As(err, &maxBytesError) {
			status = http.StatusRequestEntityTooLarge
		}
		writeError(w, status, "invalid_json")
		return false
	}
	if err := decoder.Decode(&struct{}{}); err != io.EOF {
		writeError(w, http.StatusBadRequest, "invalid_json")
		return false
	}
	return true
}

func writeJSON(w http.ResponseWriter, status int, body any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	if err := json.NewEncoder(w).Encode(body); err != nil {
		log.Printf("writing response: %v", err)
	}
}

func writeError(w http.ResponseWriter, status int, code string) {
	writeJSON(w, status, map[string]string{"error": code})
}
