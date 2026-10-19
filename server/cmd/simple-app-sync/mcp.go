package main

import (
	"database/sql"
	"encoding/json"
	"fmt"
	"net/http"
	"strings"
	"time"
)

const mcpProtocolVersion = "2025-03-26"

// mcpService is deliberately read-only. The pairing credentials used by the
// Android app never grant access to this endpoint.
type mcpService struct {
	db       *sql.DB
	token    string
	location *time.Location
}

type mcpRequest struct {
	JSONRPC string          `json:"jsonrpc"`
	ID      json.RawMessage `json:"id"`
	Method  string          `json:"method"`
	Params  json.RawMessage `json:"params"`
}

type mcpToolDefinition struct {
	Name        string
	Description string
	InputSchema map[string]any
}

type mcpToolResult struct {
	Text string
	Data any
}

func newMCPService(db *sql.DB, token, timezone string) (*mcpService, error) {
	if token != "" && len(token) < 32 {
		return nil, fmt.Errorf("MCP_BEARER_TOKEN must contain at least 32 characters")
	}
	location, err := time.LoadLocation(timezone)
	if err != nil {
		return nil, fmt.Errorf("invalid REMINDER_TIMEZONE for MCP: %w", err)
	}
	return &mcpService{db: db, token: token, location: location}, nil
}

func (s *mcpService) enabled() bool { return s != nil && s.token != "" }

func (s *mcpService) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		w.Header().Set("Allow", http.MethodPost)
		writeError(w, http.StatusMethodNotAllowed, "method_not_allowed")
		return
	}
	if !s.authorized(r.Header.Get("Authorization")) {
		writeError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	var request mcpRequest
	if !decodeJSON(w, r, &request) {
		return
	}
	if request.JSONRPC != "2.0" || request.Method == "" {
		s.writeRPCError(w, request.ID, -32600, "Invalid Request")
		return
	}
	if request.Method == "notifications/initialized" || request.Method == "notifications/cancelled" {
		w.WriteHeader(http.StatusAccepted)
		return
	}
	var result any
	switch request.Method {
	case "initialize":
		result = map[string]any{
			"protocolVersion": mcpProtocolVersion,
			"capabilities":    map[string]any{"tools": map[string]any{"listChanged": false}},
			"serverInfo":      map[string]string{"name": "simple-app-personal-butler", "version": "5.0.0"},
			"instructions":    "This server only provides read-only personal task and schedule information.",
		}
	case "ping":
		result = map[string]any{}
	case "tools/list":
		tools := make([]map[string]any, 0, len(s.toolDefinitions()))
		for _, tool := range s.toolDefinitions() {
			tools = append(tools, map[string]any{
				"name": tool.Name, "description": tool.Description, "inputSchema": tool.InputSchema,
				"annotations": map[string]bool{"readOnlyHint": true, "destructiveHint": false},
			})
		}
		result = map[string]any{"tools": tools}
	case "tools/call":
		var params struct {
			Name      string          `json:"name"`
			Arguments json.RawMessage `json:"arguments"`
		}
		if err := json.Unmarshal(request.Params, &params); err != nil || params.Name == "" {
			s.writeRPCError(w, request.ID, -32602, "Invalid tool parameters")
			return
		}
		arguments := map[string]any{}
		if len(params.Arguments) > 0 && string(params.Arguments) != "null" {
			if err := json.Unmarshal(params.Arguments, &arguments); err != nil {
				s.writeRPCError(w, request.ID, -32602, "Tool arguments must be an object")
				return
			}
		}
		toolResult, err := s.callTool(r.Context(), params.Name, arguments)
		if err != nil {
			result = map[string]any{"content": []map[string]string{{"type": "text", "text": err.Error()}}, "isError": true}
		} else {
			result = map[string]any{
				"content":           []map[string]string{{"type": "text", "text": toolResult.Text}},
				"structuredContent": toolResult.Data,
			}
		}
	default:
		s.writeRPCError(w, request.ID, -32601, "Method not found")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"jsonrpc": "2.0", "id": json.RawMessage(request.ID), "result": result})
}

func (s *mcpService) authorized(value string) bool {
	parts := strings.Fields(value)
	return len(parts) == 2 && parts[0] == "Bearer" && parts[1] == s.token
}

func (s *mcpService) writeRPCError(w http.ResponseWriter, id json.RawMessage, code int, message string) {
	writeJSON(w, http.StatusOK, map[string]any{"jsonrpc": "2.0", "id": json.RawMessage(id), "error": map[string]any{"code": code, "message": message}})
}
