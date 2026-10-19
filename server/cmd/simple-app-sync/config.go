package main

import "os"

// config contains process-level values loaded once when the server starts.
// Runtime user preferences remain in the synced database instead.
type config struct {
	listenAddr         string
	database           string
	pairCode           string
	lineAccessToken    string
	lineUserID         string
	lineChannelSecret  string
	lineAllowedUserID  string
	reminderTimezone   string
	dailySummaryTime   string
	dueReminderTime    string
	dueReminderDays    string
	reminderCheckEvery string
	mcpBearerToken     string
	ollamaBaseURL      string
	ollamaModel        string
	openAIAPIKey       string
	openAIModel        string
}

func loadConfig() config {
	return config{
		listenAddr:         value("LISTEN_ADDR", ":8080"),
		database:           value("DATABASE_PATH", "/data/sync.db"),
		pairCode:           os.Getenv("PAIR_CODE"),
		lineAccessToken:    os.Getenv("LINE_CHANNEL_ACCESS_TOKEN"),
		lineUserID:         os.Getenv("LINE_USER_ID"),
		lineChannelSecret:  os.Getenv("LINE_CHANNEL_SECRET"),
		lineAllowedUserID:  value("LINE_ALLOWED_USER_ID", os.Getenv("LINE_USER_ID")),
		reminderTimezone:   value("REMINDER_TIMEZONE", "Asia/Taipei"),
		dailySummaryTime:   value("LINE_DAILY_SUMMARY_TIME", "07:00"),
		dueReminderTime:    value("LINE_DUE_REMINDER_TIME", "09:00"),
		dueReminderDays:    value("LINE_DUE_REMINDER_DAYS", "3,1,0"),
		reminderCheckEvery: value("REMINDER_CHECK_INTERVAL", "5m"),
		mcpBearerToken:     os.Getenv("MCP_BEARER_TOKEN"),
		ollamaBaseURL:      value("OLLAMA_BASE_URL", "http://host.docker.internal:11434"),
		ollamaModel:        os.Getenv("OLLAMA_MODEL"),
		openAIAPIKey:       os.Getenv("OPENAI_API_KEY"),
		openAIModel:        os.Getenv("OPENAI_MODEL"),
	}
}

func value(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}
