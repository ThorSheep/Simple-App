package main

import (
	"context"
	"database/sql"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"sort"
	"strconv"
	"strings"
	"time"
)

const linePushEndpoint = "https://api.line.me/v2/bot/message/push"

type reminderConfig struct {
	accessToken string
	userID      string
	timezone    string
	dailyTime   string
	dueTime     string
	dueDays     string
	interval    string
}

type reminderService struct {
	db          *sql.DB
	location    *time.Location
	dailyAt     clockTime
	dueAt       clockTime
	dueDays     []int
	interval    time.Duration
	sendMessage func(context.Context, string) error
}

type clockTime struct{ hour, minute int }

func newReminderService(db *sql.DB, cfg reminderConfig) (*reminderService, error) {
	if cfg.accessToken == "" && cfg.userID == "" {
		return &reminderService{db: db}, nil
	}
	if cfg.accessToken == "" || cfg.userID == "" {
		return nil, fmt.Errorf("LINE_CHANNEL_ACCESS_TOKEN and LINE_USER_ID must either both be set or both be empty")
	}
	location, err := time.LoadLocation(cfg.timezone)
	if err != nil {
		return nil, fmt.Errorf("invalid REMINDER_TIMEZONE: %w", err)
	}
	dailyAt, err := parseClockTime(cfg.dailyTime)
	if err != nil {
		return nil, fmt.Errorf("invalid LINE_DAILY_SUMMARY_TIME: %w", err)
	}
	dueAt, err := parseClockTime(cfg.dueTime)
	if err != nil {
		return nil, fmt.Errorf("invalid LINE_DUE_REMINDER_TIME: %w", err)
	}
	dueDays, err := parseReminderDays(cfg.dueDays)
	if err != nil {
		return nil, err
	}
	interval, err := time.ParseDuration(cfg.interval)
	if err != nil || interval < time.Minute {
		return nil, fmt.Errorf("REMINDER_CHECK_INTERVAL must be at least one minute")
	}
	service := &reminderService{db: db, location: location, dailyAt: dailyAt, dueAt: dueAt, dueDays: dueDays, interval: interval}
	service.sendMessage = linePushSender(cfg.accessToken, cfg.userID)
	return service, nil
}

func (s *reminderService) enabled() bool { return s != nil && s.sendMessage != nil }

func (s *reminderService) runLoop(ctx context.Context) {
	s.runOnce(ctx, time.Now())
	ticker := time.NewTicker(s.interval)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case now := <-ticker.C:
			s.runOnce(ctx, now)
		}
	}
}

func (s *reminderService) runOnce(ctx context.Context, now time.Time) {
	if !s.enabled() {
		return
	}
	localNow := now.In(s.location)
	snapshot, err := s.loadSnapshot(ctx)
	if err != nil {
		fmt.Printf("LINE reminder snapshot failed: %v\n", err)
		return
	}
	dailyAt, dueAt, dueDays := s.dailyAt, s.dueAt, s.dueDays
	settings := snapshot.settings
	if settings == nil {
		fmt.Printf("LINE reminders waiting for enabled App settings to sync\n")
		return
	}
	if !settings.Enabled {
		fmt.Printf("LINE reminders are disabled in App settings\n")
		return
	}
	if settings != nil {
		if value, err := parseClockTime(settings.DailyTime); err == nil {
			dailyAt = value
		}
		if value, err := parseClockTime(settings.DueTime); err == nil {
			dueAt = value
		}
		if len(settings.DueDays) > 0 {
			dueDays = settings.DueDays
		}
	}
	if settings != nil && !settings.DailySummary {
		dailyAt = clockTime{hour: 24}
	}
	if hasReached(localNow, dailyAt) {
		dayKey := localNow.Format("2006-01-02")
		s.deliver(ctx, "daily:"+dayKey, snapshot.dailySummary(localNow, settings), "daily summary")
	}
	if hasReached(localNow, dueAt) {
		for _, deadline := range snapshot.deadlines() {
			days := int(deadline.date.Sub(startOfDay(localNow)).Hours() / 24)
			if !containsInt(dueDays, days) {
				continue
			}
			id := fmt.Sprintf("due:%s:%s:%d", deadline.id, deadline.date.Format("2006-01-02"), days)
			s.deliver(ctx, id, deadline.message(days), "due reminder")
		}
	}
}

func (s *reminderService) deliver(ctx context.Context, id, text, kind string) {
	var exists int
	err := s.db.QueryRowContext(ctx, "SELECT 1 FROM reminder_deliveries WHERE id = ?", id).Scan(&exists)
	if err == nil {
		return
	}
	if err != sql.ErrNoRows {
		fmt.Printf("LINE %s delivery lookup failed: %v\n", kind, err)
		return
	}
	if err := s.sendMessage(ctx, text); err != nil {
		fmt.Printf("LINE %s delivery failed: %v\n", kind, err)
		return
	}
	if _, err := s.db.ExecContext(ctx, "INSERT INTO reminder_deliveries(id, sent_at) VALUES(?, ?)", id, time.Now().UnixMilli()); err != nil {
		fmt.Printf("LINE %s delivery record failed: %v\n", kind, err)
	}
}

type reminderSnapshot struct {
	courses  map[string]reminderCourse
	meetings []reminderMeeting
	items    []reminderItem
	settings *syncedLineReminderSettings
}

type syncedLineReminderSettings struct {
	Enabled         bool   `json:"enabled"`
	DailySummary    bool   `json:"dailySummary"`
	DailyTime       string `json:"dailyTime"`
	IncludeCourses  bool   `json:"includeCourses"`
	IncludeToday    bool   `json:"includeToday"`
	IncludeTomorrow bool   `json:"includeTomorrow"`
	DueTime         string `json:"dueTime"`
	DueDays         []int  `json:"dueDays"`
}

type reminderCourse struct {
	ID       string `json:"id"`
	Title    string `json:"title"`
	Archived bool   `json:"archived"`
}

type reminderMeeting struct {
	ID       string `json:"id"`
	CourseID string `json:"courseId"`
	Day      int    `json:"day"`
	Start    string `json:"start"`
	End      string `json:"end"`
	Room     string `json:"room"`
}

type reminderItem struct {
	ID               string `json:"id"`
	Title            string `json:"title"`
	Kind             string `json:"kind"`
	Date             string `json:"date"`
	Time             string `json:"time"`
	PresentationDate string `json:"presentationDate"`
	PresentationTime string `json:"presentationTime"`
	Done             bool   `json:"done"`
}

func (s *reminderService) loadSnapshot(ctx context.Context) (reminderSnapshot, error) {
	rows, err := s.db.QueryContext(ctx, `SELECT entity_type, entity_id, deleted, payload
		FROM changes ORDER BY entity_type, entity_id, revision DESC, seq DESC`)
	if err != nil {
		return reminderSnapshot{}, err
	}
	defer rows.Close()
	snapshot := reminderSnapshot{courses: make(map[string]reminderCourse)}
	seen := make(map[string]bool)
	for rows.Next() {
		var entityType, entityID string
		var deleted int
		var payload []byte
		if err := rows.Scan(&entityType, &entityID, &deleted, &payload); err != nil {
			return reminderSnapshot{}, err
		}
		key := entityType + "\x00" + entityID
		if seen[key] {
			continue
		}
		seen[key] = true
		if deleted != 0 {
			continue
		}
		switch entityType {
		case "course":
			var course reminderCourse
			if json.Unmarshal(payload, &course) == nil && course.ID != "" && course.Title != "" {
				snapshot.courses[course.ID] = course
			}
		case "courseMeeting":
			var meeting reminderMeeting
			if json.Unmarshal(payload, &meeting) == nil && meeting.ID != "" && meeting.CourseID != "" && meeting.Day >= 1 && meeting.Day <= 7 {
				snapshot.meetings = append(snapshot.meetings, meeting)
			}
		case "academicItem":
			var item reminderItem
			if json.Unmarshal(payload, &item) == nil && item.ID != "" && item.Title != "" && !item.Done {
				snapshot.items = append(snapshot.items, item)
			}
		case "lineReminderSettings":
			var settings syncedLineReminderSettings
			if json.Unmarshal(payload, &settings) == nil {
				snapshot.settings = &settings
			}
		}
	}
	return snapshot, rows.Err()
}

func (s reminderSnapshot) dailySummary(now time.Time, settings *syncedLineReminderSettings) string {
	date := startOfDay(now)
	classes := make([]string, 0)
	weekday := int(date.Weekday())
	if weekday == 0 {
		weekday = 7
	}
	for _, meeting := range s.meetings {
		course, ok := s.courses[meeting.CourseID]
		if !ok || course.Archived || meeting.Day != weekday { // Go Sunday=0; app Monday=1.
			continue
		}
		room := meeting.Room
		if room == "" {
			room = "未填教室"
		}
		classes = append(classes, fmt.Sprintf("%s %s（%s）", meeting.Start, course.Title, room))
	}
	sort.Strings(classes)
	today := s.deadlinesOn(date)
	tomorrow := s.deadlinesOn(date.AddDate(0, 0, 1))
	lines := []string{fmt.Sprintf("個人管家｜%d/%d 今日提醒", date.Month(), date.Day())}
	if settings == nil || settings.IncludeCourses {
		if len(classes) == 0 {
			lines = append(lines, "課程：今天沒有課程")
		} else {
			lines = append(lines, "課程："+strings.Join(classes, "、"))
		}
	}
	if (settings == nil || settings.IncludeToday) && len(today) > 0 {
		lines = append(lines, "今日到期："+joinDeadlines(today))
	}
	if (settings == nil || settings.IncludeTomorrow) && len(tomorrow) > 0 {
		lines = append(lines, "明日到期："+joinDeadlines(tomorrow))
	}
	if (settings == nil || settings.IncludeToday || settings.IncludeTomorrow) && len(today) == 0 && len(tomorrow) == 0 {
		lines = append(lines, "待辦：今天與明天沒有到期事項")
	}
	return strings.Join(lines, "\n")
}

type reminderDeadline struct {
	id    string
	title string
	kind  string
	date  time.Time
	time  string
}

func (s reminderSnapshot) deadlines() []reminderDeadline {
	result := make([]reminderDeadline, 0)
	for _, item := range s.items {
		appendDeadline := func(id, label, rawDate, rawTime string) {
			if rawDate == "" {
				return
			}
			date, err := time.ParseInLocation("2006-01-02", rawDate, time.Local)
			if err == nil {
				result = append(result, reminderDeadline{id: id, title: item.Title, kind: label, date: date, time: rawTime})
			}
		}
		appendDeadline(item.ID+":date", item.Kind, item.Date, item.Time)
		if item.PresentationDate != "" && (item.PresentationDate != item.Date || item.PresentationTime != item.Time) {
			appendDeadline(item.ID+":presentation", "報告", item.PresentationDate, item.PresentationTime)
		}
	}
	sort.Slice(result, func(i, j int) bool {
		return result[i].date.Before(result[j].date) || (result[i].date.Equal(result[j].date) && result[i].title < result[j].title)
	})
	return result
}

func (s reminderSnapshot) deadlinesOn(day time.Time) []reminderDeadline {
	result := make([]reminderDeadline, 0)
	for _, deadline := range s.deadlines() {
		if deadline.date.Format("2006-01-02") == day.Format("2006-01-02") {
			result = append(result, deadline)
		}
	}
	return result
}

func (d reminderDeadline) message(days int) string {
	when := "今天"
	if days > 0 {
		when = fmt.Sprintf("%d 天後", days)
	}
	timeText := ""
	if d.time != "" {
		timeText = " " + d.time
	}
	return fmt.Sprintf("個人管家｜到期提醒\n%s %s%s %s\n%s", when, d.kind, timeText, d.title, d.date.Format("2006/01/02"))
}

func joinDeadlines(items []reminderDeadline) string {
	parts := make([]string, 0, len(items))
	for _, item := range items {
		timeText := ""
		if item.time != "" {
			timeText = " " + item.time
		}
		parts = append(parts, item.kind+"："+item.title+timeText)
	}
	return strings.Join(parts, "、")
}

func linePushSender(accessToken, userID string) func(context.Context, string) error {
	client := &http.Client{Timeout: 10 * time.Second}
	return func(ctx context.Context, text string) error {
		body, err := json.Marshal(map[string]any{"to": userID, "messages": []map[string]string{{"type": "text", "text": text}}})
		if err != nil {
			return err
		}
		request, err := http.NewRequestWithContext(ctx, http.MethodPost, linePushEndpoint, strings.NewReader(string(body)))
		if err != nil {
			return err
		}
		request.Header.Set("Authorization", "Bearer "+accessToken)
		request.Header.Set("Content-Type", "application/json")
		response, err := client.Do(request)
		if err != nil {
			return err
		}
		defer response.Body.Close()
		if response.StatusCode < 200 || response.StatusCode >= 300 {
			message, _ := io.ReadAll(io.LimitReader(response.Body, 2048))
			return fmt.Errorf("LINE push returned %s: %s", response.Status, strings.TrimSpace(string(message)))
		}
		return nil
	}
}

func parseClockTime(value string) (clockTime, error) {
	parsed, err := time.Parse("15:04", value)
	if err != nil {
		return clockTime{}, err
	}
	return clockTime{hour: parsed.Hour(), minute: parsed.Minute()}, nil
}

func parseReminderDays(value string) ([]int, error) {
	seen := make(map[int]bool)
	for _, part := range strings.Split(value, ",") {
		days, err := strconv.Atoi(strings.TrimSpace(part))
		if err != nil || days < 0 || days > 30 {
			return nil, fmt.Errorf("LINE_DUE_REMINDER_DAYS must be comma-separated values from 0 to 30")
		}
		seen[days] = true
	}
	if len(seen) == 0 {
		return nil, fmt.Errorf("LINE_DUE_REMINDER_DAYS must not be empty")
	}
	result := make([]int, 0, len(seen))
	for days := range seen {
		result = append(result, days)
	}
	sort.Ints(result)
	return result, nil
}

func startOfDay(value time.Time) time.Time {
	return time.Date(value.Year(), value.Month(), value.Day(), 0, 0, 0, 0, value.Location())
}

func hasReached(now time.Time, target clockTime) bool {
	return now.Hour() > target.hour || (now.Hour() == target.hour && now.Minute() >= target.minute)
}

func containsInt(values []int, target int) bool {
	for _, value := range values {
		if value == target {
			return true
		}
	}
	return false
}
