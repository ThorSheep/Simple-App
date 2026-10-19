package main

import (
	"bytes"
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"
)

func TestLineBotRepliesToFixedCommandOnlyForAllowedUser(t *testing.T) {
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
	bot, err := newLineBotService(lineBotConfig{accessToken: "token", channelSecret: "secret", allowedUserID: "U-person"}, tools)
	if err != nil {
		t.Fatal(err)
	}
	var replies []string
	bot.replyMessage = func(_ context.Context, _ string, text string) error { replies = append(replies, text); return nil }

	body := []byte(`{"events":[{"type":"message","replyToken":"reply","source":{"userId":"U-person"},"message":{"type":"text","text":"未完成作業"}}]}`)
	req := httptest.NewRequest(http.MethodPost, "/line/webhook", bytes.NewReader(body))
	req.Header.Set("X-Line-Signature", lineSignature("secret", body))
	response := httptest.NewRecorder()
	bot.webhook(response, req)
	if response.Code != http.StatusOK || len(replies) != 1 || !strings.Contains(replies[0], "期中作業") {
		t.Fatalf("unexpected reply: %d %#v", response.Code, replies)
	}

	blocked := []byte(`{"events":[{"type":"message","replyToken":"reply","source":{"userId":"U-other"},"message":{"type":"text","text":"未完成作業"}}]}`)
	req = httptest.NewRequest(http.MethodPost, "/line/webhook", bytes.NewReader(blocked))
	req.Header.Set("X-Line-Signature", lineSignature("secret", blocked))
	bot.webhook(httptest.NewRecorder(), req)
	if len(replies) != 1 {
		t.Fatalf("unexpected reply for blocked user: %#v", replies)
	}
}

func TestLineBotRejectsInvalidSignature(t *testing.T) {
	bot := &lineBotService{channelSecret: "secret"}
	req := httptest.NewRequest(http.MethodPost, "/line/webhook", strings.NewReader(`{"events":[]}`))
	req.Header.Set("X-Line-Signature", "not-a-signature")
	response := httptest.NewRecorder()
	bot.webhook(response, req)
	if response.Code != http.StatusUnauthorized {
		t.Fatalf("got %d", response.Code)
	}
}

func lineSignature(secret string, body []byte) string {
	mac := hmac.New(sha256.New, []byte(secret))
	_, _ = mac.Write(body)
	return base64.StdEncoding.EncodeToString(mac.Sum(nil))
}
