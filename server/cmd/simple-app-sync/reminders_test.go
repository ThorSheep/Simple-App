package main

import (
	"context"
	"database/sql"
	"encoding/json"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
	"time"
)

func TestReminderServiceSendsDailySummaryAndDueReminderOnce(t *testing.T) {
	db, err := openDatabase(filepath.Join(t.TempDir(), "sync.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	if _, err := db.Exec(`INSERT INTO devices(id, name, token_hash, created_at) VALUES('phone', 'Phone', 'hash', 0)`); err != nil {
		t.Fatal(err)
	}
	location, err := time.LoadLocation("Asia/Taipei")
	if err != nil {
		t.Fatal(err)
	}
	now := time.Date(2026, 10, 6, 10, 0, 0, 0, location)
	weekday := int(now.Weekday())
	if weekday == 0 {
		weekday = 7
	}
	insertReminderChange(t, db, "course", "course-1", `{"id":"course-1","title":"軟體工程","archived":false}`)
	insertReminderChange(t, db, "courseMeeting", "meeting-1", `{"id":"meeting-1","courseId":"course-1","day":`+strconv.Itoa(weekday)+`,"start":"09:00","end":"10:00","room":"A101"}`)
	insertReminderChange(t, db, "academicItem", "item-1", `{"id":"item-1","title":"期末報告","kind":"報告","date":"2026-10-06","time":"23:59","presentationDate":"","presentationTime":"","done":false}`)
	insertReminderChange(t, db, "lineReminderSettings", "personal", `{"enabled":true,"dailySummary":true,"dailyTime":"07:00","includeCourses":true,"includeToday":true,"includeTomorrow":true,"dueTime":"09:00","dueDays":[3,1,0]}`)

	service, err := newReminderService(db, reminderConfig{
		accessToken: "test-token", userID: "test-user", timezone: "Asia/Taipei", dailyTime: "07:00", dueTime: "09:00", dueDays: "3,1,0", interval: "5m",
	})
	if err != nil {
		t.Fatal(err)
	}
	var messages []string
	service.sendMessage = func(_ context.Context, message string) error {
		messages = append(messages, message)
		return nil
	}
	service.runOnce(context.Background(), now)
	if len(messages) != 2 {
		t.Fatalf("expected a daily summary and a due reminder, got %d: %#v", len(messages), messages)
	}
	if !strings.Contains(messages[0], "軟體工程") || !strings.Contains(messages[0], "期末報告") {
		t.Fatalf("daily summary is missing synced data: %q", messages[0])
	}
	if !strings.Contains(messages[1], "到期提醒") || !strings.Contains(messages[1], "期末報告") {
		t.Fatalf("unexpected due reminder: %q", messages[1])
	}
	service.runOnce(context.Background(), now.Add(10*time.Minute))
	if len(messages) != 2 {
		t.Fatalf("duplicate reminder was delivered: %#v", messages)
	}
}

func TestReminderServiceIsDisabledWithoutLINECredentials(t *testing.T) {
	db, err := openDatabase(filepath.Join(t.TempDir(), "sync.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	service, err := newReminderService(db, reminderConfig{})
	if err != nil {
		t.Fatal(err)
	}
	if service.enabled() {
		t.Fatal("reminders should be disabled when credentials are absent")
	}
}

func insertReminderChange(t *testing.T, db *sql.DB, entityType, entityID, payload string) {
	t.Helper()
	if !json.Valid([]byte(payload)) {
		t.Fatalf("invalid test payload: %s", payload)
	}
	_, err := db.Exec(`INSERT INTO changes(operation_id, entity_type, entity_id, revision, device_id, deleted, payload, created_at)
		VALUES(?, ?, ?, ?, ?, ?, ?, ?)`, "operation-"+entityType+"-"+entityID, entityType, entityID,
		"1770000000000-00000-phone", "phone", false, []byte(payload), 0)
	if err != nil {
		t.Fatal(err)
	}
}
