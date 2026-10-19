package main

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"net/http"
	"strings"
	"time"
)

const protocolVersion = 1

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
		}
		accepted = append(accepted, op.OperationID)
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
	return len(op.Payload) > 0 && len(op.Payload) <= 64<<10 && json.Valid(op.Payload)
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
