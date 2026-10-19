package main

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"
)

func TestOpenAIChatUsesReadOnlyPersonalTool(t *testing.T) {
	db, err := openDatabase(filepath.Join(t.TempDir(), "sync.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	if _, err := db.Exec(`INSERT INTO devices(id, name, token_hash, created_at) VALUES('phone', 'Phone', 'hash', 0)`); err != nil {
		t.Fatal(err)
	}
	insertReminderChange(t, db, "academicItem", "item-1", `{"id":"item-1","title":"期中作業","courseId":null,"kind":"作業","date":"2026-10-20","time":"23:59","done":false}`)
	tools, err := newMCPService(db, "", "Asia/Taipei")
	if err != nil {
		t.Fatal(err)
	}

	requests := 0
	api := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if got := r.Header.Get("Authorization"); got != "Bearer test-key" {
			t.Fatalf("unexpected authorization header: %q", got)
		}
		requests++
		if requests == 1 {
			writeJSON(w, http.StatusOK, map[string]any{"output": []map[string]any{{"type": "function_call", "call_id": "call-1", "name": "get_open_tasks", "arguments": `{}`}}})
			return
		}
		var request struct {
			Input []json.RawMessage `json:"input"`
		}
		if err := json.NewDecoder(r.Body).Decode(&request); err != nil {
			t.Fatal(err)
		}
		joined := strings.Join(func() []string {
			values := make([]string, 0, len(request.Input))
			for _, value := range request.Input {
				values = append(values, string(value))
			}
			return values
		}(), "\n")
		if !strings.Contains(joined, "期中作業") || !strings.Contains(joined, "function_call_output") {
			t.Fatalf("tool output was not returned to OpenAI: %s", joined)
		}
		writeJSON(w, http.StatusOK, map[string]any{"output": []map[string]any{{"type": "message", "content": []map[string]string{{"type": "output_text", "text": "你有一項未完成作業：期中作業。"}}}}})
	}))
	defer api.Close()

	chat := newOpenAIChatService("test-key", "test-model", tools)
	chat.endpoint = api.URL
	answer, err := chat.answer(context.Background(), "我還有什麼作業？")
	if err != nil {
		t.Fatal(err)
	}
	if requests != 2 || !strings.Contains(answer, "期中作業") {
		t.Fatalf("unexpected response: requests=%d answer=%q", requests, answer)
	}
}
