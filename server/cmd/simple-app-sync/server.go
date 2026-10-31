package main

import (
	"context"
	"database/sql"
	"log"
	"net/http"
)

type server struct {
	db            *sql.DB
	pairCode      string
	notifications *notificationHub
	mcp           *mcpService
	lineBot       *lineBotService
	gmail         *gmailService
}

func main() {
	cfg := loadConfig()
	if len(cfg.pairCode) < 16 {
		log.Fatal("PAIR_CODE must contain at least 16 characters")
	}
	db, err := openDatabase(cfg.database)
	if err != nil {
		log.Fatal(err)
	}
	defer db.Close()
	mcp, err := newMCPService(db, cfg.mcpBearerToken, cfg.reminderTimezone)
	if err != nil {
		log.Fatal(err)
	}
	s := &server{db: db, pairCode: cfg.pairCode, notifications: newNotificationHub(), mcp: mcp}
	lineBot, err := newLineBotService(lineBotConfig{
		accessToken: cfg.lineAccessToken, channelSecret: cfg.lineChannelSecret, allowedUserID: cfg.lineAllowedUserID,
		ollamaBaseURL: cfg.ollamaBaseURL, ollamaModel: cfg.ollamaModel, openAIAPIKey: cfg.openAIAPIKey, openAIModel: cfg.openAIModel,
	}, mcp)
	if err != nil {
		log.Fatal(err)
	}
	s.lineBot = lineBot
	gmail, err := newGmailService(gmailConfig{
		db: db, clientID: cfg.gmailClientID, clientSecret: cfg.gmailClientSecret, redirectURL: cfg.gmailRedirectURL,
		setupToken: cfg.gmailSetupToken, label: cfg.gmailLabel, sender: cfg.gmailSender, interval: cfg.gmailCheckEvery,
		notifications: s.notifications,
	})
	if err != nil {
		log.Fatal(err)
	}
	s.gmail = gmail
	reminders, err := newReminderService(db, reminderConfig{
		accessToken: cfg.lineAccessToken,
		userID:      cfg.lineUserID,
		timezone:    cfg.reminderTimezone,
		dailyTime:   cfg.dailySummaryTime,
		dueTime:     cfg.dueReminderTime,
		dueDays:     cfg.dueReminderDays,
		interval:    cfg.reminderCheckEvery,
	})
	if err != nil {
		log.Fatal(err)
	}
	if reminders.enabled() {
		go reminders.runLoop(context.Background())
		log.Printf("LINE reminders enabled for the configured personal account")
	} else {
		log.Printf("LINE reminders disabled; set both LINE_CHANNEL_ACCESS_TOKEN and LINE_USER_ID to enable them")
	}
	if mcp.enabled() {
		log.Printf("Personal Butler MCP server enabled at /mcp")
	} else {
		log.Printf("Personal Butler MCP server disabled; set MCP_BEARER_TOKEN to enable it")
	}
	if lineBot.enabled() {
		log.Printf("LINE Personal Butler chat enabled at /line/webhook")
	} else {
		log.Printf("LINE Personal Butler chat disabled; set LINE_CHANNEL_SECRET to enable it")
	}
	if gmail.enabled() {
		go gmail.runLoop(context.Background())
		log.Printf("eeClass Gmail importer enabled; connect it through /gmail/connect")
	} else {
		log.Printf("eeClass Gmail importer disabled; configure GMAIL_OAUTH_CLIENT_ID and related settings to enable it")
	}
	log.Printf("Simple App sync server listening on %s", cfg.listenAddr)
	log.Fatal(http.ListenAndServe(cfg.listenAddr, s.routes()))
}
