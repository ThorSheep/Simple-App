package main

import (
	"database/sql"

	_ "modernc.org/sqlite"
)

func openDatabase(path string) (*sql.DB, error) {
	db, err := sql.Open("sqlite", path+"?_pragma=busy_timeout(5000)&_pragma=foreign_keys(1)")
	if err != nil {
		return nil, err
	}
	if err := db.Ping(); err != nil {
		db.Close()
		return nil, err
	}
	_, err = db.Exec(`
		CREATE TABLE IF NOT EXISTS devices (
			id TEXT PRIMARY KEY,
			name TEXT NOT NULL,
			token_hash TEXT NOT NULL UNIQUE,
			revoked INTEGER NOT NULL DEFAULT 0,
			created_at INTEGER NOT NULL
		);
		CREATE TABLE IF NOT EXISTS changes (
			seq INTEGER PRIMARY KEY AUTOINCREMENT,
			operation_id TEXT NOT NULL UNIQUE,
			entity_type TEXT NOT NULL,
			entity_id TEXT NOT NULL,
			revision TEXT NOT NULL,
			device_id TEXT NOT NULL,
			deleted INTEGER NOT NULL,
			payload BLOB NOT NULL,
			created_at INTEGER NOT NULL,
			FOREIGN KEY(device_id) REFERENCES devices(id)
		);
		CREATE INDEX IF NOT EXISTS changes_seq ON changes(seq);
		CREATE TABLE IF NOT EXISTS reminder_deliveries (
			id TEXT PRIMARY KEY,
			sent_at INTEGER NOT NULL
		);
		CREATE TABLE IF NOT EXISTS gmail_oauth_states (
			state TEXT PRIMARY KEY,
			expires_at INTEGER NOT NULL
		);
		CREATE TABLE IF NOT EXISTS gmail_oauth_tokens (
			id INTEGER PRIMARY KEY CHECK (id = 1),
			access_token TEXT NOT NULL,
			refresh_token TEXT NOT NULL,
			expires_at INTEGER NOT NULL,
			updated_at INTEGER NOT NULL
		);
		CREATE TABLE IF NOT EXISTS gmail_processed_messages (
			message_id TEXT PRIMARY KEY,
			processed_at INTEGER NOT NULL
		);
		CREATE INDEX IF NOT EXISTS gmail_processed_messages_processed_at ON gmail_processed_messages(processed_at);
	`)
	if err != nil {
		db.Close()
		return nil, err
	}
	return db, nil
}
