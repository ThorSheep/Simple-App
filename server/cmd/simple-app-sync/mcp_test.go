package main

import (
	"bytes"
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"
)

func TestMCPListsAndReadsPersonalTasks(t *testing.T) {
	db, err := openDatabase(filepath.Join(t.TempDir(), "sync.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	if _, err := db.Exec(`INSERT INTO devices(id, name, token_hash, created_at) VALUES('phone', 'Phone', 'hash', 0)`); err != nil {
		t.Fatal(err)
	}
	insertReminderChange(t, db, "course", "course-1", `{"id":"course-1","title":"資料結構","archived":false}`)
	insertReminderChange(t, db, "academicItem", "item-1", `{"id":"item-1","title":"作業一","courseId":"course-1","kind":"作業","date":"2026-10-20","time":"23:59","done":false}`)
	service, err := newMCPService(db, strings.Repeat("a", 32), "Asia/Taipei")
	if err != nil {
		t.Fatal(err)
	}
	server := httptest.NewServer(service)
	defer server.Close()

	list := postMCP(t, server.URL, service.token, map[string]any{"jsonrpc": "2.0", "id": 1, "method": "tools/list", "params": map[string]any{}})
	result := list["result"].(map[string]any)
	if len(result["tools"].([]any)) != 4 {
		t.Fatalf("unexpected tools: %#v", result)
	}

	call := postMCP(t, server.URL, service.token, map[string]any{"jsonrpc": "2.0", "id": 2, "method": "tools/call", "params": map[string]any{"name": "get_open_tasks", "arguments": map[string]any{"course": "資料"}}})
	text := call["result"].(map[string]any)["content"].([]any)[0].(map[string]any)["text"].(string)
	if !strings.Contains(text, "作業一") || !strings.Contains(text, "資料結構") {
		t.Fatalf("unexpected tool response: %s", text)
	}
}

func TestMCPRejectsMissingToken(t *testing.T) {
	db, err := openDatabase(filepath.Join(t.TempDir(), "sync.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	service, err := newMCPService(db, strings.Repeat("a", 32), "Asia/Taipei")
	if err != nil {
		t.Fatal(err)
	}
	req := httptest.NewRequest(http.MethodPost, "/mcp", bytes.NewBufferString(`{"jsonrpc":"2.0","id":1,"method":"ping"}`))
	response := httptest.NewRecorder()
	service.ServeHTTP(response, req.WithContext(context.Background()))
	if response.Code != http.StatusUnauthorized {
		t.Fatalf("got %d", response.Code)
	}
}

func postMCP(t *testing.T, url, token string, body any) map[string]any {
	t.Helper()
	payload, err := json.Marshal(body)
	if err != nil {
		t.Fatal(err)
	}
	req, err := http.NewRequest(http.MethodPost, url, bytes.NewReader(payload))
	if err != nil {
		t.Fatal(err)
	}
	req.Header.Set("Authorization", "Bearer "+token)
	req.Header.Set("Content-Type", "application/json")
	response, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		t.Fatalf("got %s", response.Status)
	}
	var result map[string]any
	if err := json.NewDecoder(response.Body).Decode(&result); err != nil {
		t.Fatal(err)
	}
	return result
}
