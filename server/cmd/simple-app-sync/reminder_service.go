package main

import (
	"context"
	"database/sql"
	"fmt"
	"time"
)

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
	if value, err := parseClockTime(settings.DailyTime); err == nil {
		dailyAt = value
	}
	if value, err := parseClockTime(settings.DueTime); err == nil {
		dueAt = value
	}
	if len(settings.DueDays) > 0 {
		dueDays = settings.DueDays
	}
	if !settings.DailySummary {
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
