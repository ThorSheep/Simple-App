package main

import (
	"context"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestParseEEClassAssignmentNotifications(t *testing.T) {
	opened, recognized := parseEEClassAssignment("[作業]「Week 07 作業繳交區」已開放繳交 (學習、合作與創造 Learning Collaboration and Creativity)", `
課程名稱: 學習、合作與創造 Learning Collaboration and Creativity
作業名稱: Week 07 作業繳交區
開始時間: 2026-10-14 00:00
繳交期限: 2026-10-18 22:00
進入作業 https://ncueeclass.ncu.edu.tw/course/homework/72961
`)
	if !recognized || opened.Submitted || opened.Title != "Week 07 作業繳交區" || opened.Date != "2026-10-18" || opened.Time != "22:00" || opened.URL == "" {
		t.Fatalf("opened notification was parsed incorrectly: %#v", opened)
	}

	reminder, recognized := parseEEClassAssignment("[作業]「Week 07 作業繳交區」最後繳交期限提醒 (學習、合作與創造 Learning Collaboration and Creativity)", `
提醒您，請於 2026-10-18 (22:00) 前完成作業繳交
課程名稱: 學習、合作與創造 Learning Collaboration and Creativity
作業名稱: Week 07 作業繳交區
最後期限: 2026-10-18 22:00
`)
	if !recognized || reminder.Submitted || reminder.Date != "2026-10-18" || reminder.Time != "22:00" {
		t.Fatalf("deadline reminder was parsed incorrectly: %#v", reminder)
	}

	submitted, recognized := parseEEClassAssignment("[作業繳交] 繳交完成通知 (Week7 作業繳交區)", `
陳尚洋 繳交一份作業
課程
軟體工程 Software Engineering

作業
Week7 作業繳交區
`)
	if !recognized || !submitted.Submitted || submitted.Course != "軟體工程 Software Engineering" || submitted.Title != "Week7 作業繳交區" {
		t.Fatalf("submission notification was parsed incorrectly: %#v", submitted)
	}
}

func TestGmailImporterCreatesImportantTaskAndMarksSubmissionDone(t *testing.T) {
	db, err := openDatabase(filepath.Join(t.TempDir(), "sync.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	now := time.Date(2026, 10, 1, 8, 0, 0, 0, time.FixedZone("Asia/Taipei", 8*60*60))
	service := &gmailService{db: db, clientID: "test-client", notifications: newNotificationHub(), now: func() time.Time { return now }}
	assignment := eeClassAssignment{Course: "軟體工程 Software Engineering", Title: "Week 8 作業", Date: "2026-10-20", Time: "23:59", URL: "https://ncueeclass.ncu.edu.tw/course/homework/1"}
	changed, err := service.applyAssignment(context.Background(), assignment)
	if err != nil || !changed {
		t.Fatalf("assignment was not imported: changed=%v err=%v", changed, err)
	}
	item, _, deleted, found, err := service.existingItem(context.Background(), eeClassItemID(assignment.Course, assignment.Title))
	if err != nil || !found || deleted || !item.Important || item.Done || item.Kind != "作業" {
		t.Fatalf("unexpected imported item: %#v found=%v deleted=%v err=%v", item, found, deleted, err)
	}
	now = now.Add(time.Minute)
	changed, err = service.applyAssignment(context.Background(), eeClassAssignment{Course: assignment.Course, Title: assignment.Title, Submitted: true})
	if err != nil || !changed {
		t.Fatalf("submission was not applied: changed=%v err=%v", changed, err)
	}
	item, _, _, _, err = service.existingItem(context.Background(), item.ID)
	if err != nil || !item.Done {
		t.Fatalf("submitted item should be marked done: %#v err=%v", item, err)
	}
}

func TestGmailFromMustMatchExactMailbox(t *testing.T) {
	if !gmailFromMatches("國立中央大學 「新 ee-class 系統」 <eeclass@ncu.edu.tw>", "eeclass@ncu.edu.tw") {
		t.Fatal("expected matching sender")
	}
	if gmailFromMatches("eeclass@ncu.edu.tw <attacker@example.com>", "eeclass@ncu.edu.tw") {
		t.Fatal("display name must not bypass sender check")
	}
}

func TestGmailConnectRequiresSetupTokenAndCreatesOAuthState(t *testing.T) {
	db, err := openDatabase(filepath.Join(t.TempDir(), "sync.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	setupToken := strings.Repeat("a", 32)
	service, err := newGmailService(gmailConfig{
		db: db, clientID: "client-id", clientSecret: "client-secret", redirectURL: "https://sync.example.com/gmail/oauth/callback",
		setupToken: setupToken, label: "eeClass", sender: "eeclass@ncu.edu.tw", interval: "15m", notifications: newNotificationHub(),
	})
	if err != nil {
		t.Fatal(err)
	}
	denied := httptest.NewRecorder()
	service.connect(denied, httptest.NewRequest(http.MethodGet, "/gmail/connect", nil))
	if denied.Code != http.StatusNotFound {
		t.Fatalf("missing setup token returned %d", denied.Code)
	}
	allowed := httptest.NewRecorder()
	service.connect(allowed, httptest.NewRequest(http.MethodGet, "/gmail/connect?token="+setupToken, nil))
	if allowed.Code != http.StatusFound {
		t.Fatalf("authorized connect returned %d: %s", allowed.Code, allowed.Body.String())
	}
	location := allowed.Header().Get("Location")
	if !strings.HasPrefix(location, "https://accounts.google.com/o/oauth2/v2/auth?") || !strings.Contains(location, "gmail.readonly") {
		t.Fatalf("unexpected OAuth redirect: %s", location)
	}
	var states int
	if err := db.QueryRow("SELECT count(*) FROM gmail_oauth_states").Scan(&states); err != nil || states != 1 {
		t.Fatalf("OAuth state was not stored: states=%d err=%v", states, err)
	}
}
