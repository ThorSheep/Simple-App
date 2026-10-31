package main

import (
	"context"
	"crypto/subtle"
	"database/sql"
	"encoding/json"
	"fmt"
	"html"
	"io"
	"log"
	"net/http"
	"net/url"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
)

const gmailImporterDeviceID = "server-eeclass-importer"

// gmailService imports only the explicitly configured Gmail label.  It never
// stores an email body; after a message is parsed, only its Gmail message ID is
// retained for de-duplication.
type gmailService struct {
	db            *sql.DB
	clientID      string
	clientSecret  string
	redirectURL   string
	setupToken    string
	label         string
	sender        string
	interval      time.Duration
	notifications *notificationHub
	httpClient    *http.Client
	now           func() time.Time
	importMu      sync.Mutex
}

type gmailConfig struct {
	db            *sql.DB
	clientID      string
	clientSecret  string
	redirectURL   string
	setupToken    string
	label         string
	sender        string
	interval      string
	notifications *notificationHub
}

func newGmailService(cfg gmailConfig) (*gmailService, error) {
	values := []string{cfg.clientID, cfg.clientSecret, cfg.redirectURL, cfg.setupToken}
	enabled := values[0] != ""
	for _, value := range values {
		if (value != "") != enabled {
			return nil, fmt.Errorf("Gmail importer requires GMAIL_OAUTH_CLIENT_ID, GMAIL_OAUTH_CLIENT_SECRET, GMAIL_OAUTH_REDIRECT_URL, and GMAIL_OAUTH_SETUP_TOKEN together")
		}
	}
	service := &gmailService{db: cfg.db, clientID: cfg.clientID, clientSecret: cfg.clientSecret, redirectURL: cfg.redirectURL, setupToken: cfg.setupToken, label: cfg.label, sender: strings.ToLower(cfg.sender), notifications: cfg.notifications, httpClient: &http.Client{Timeout: 20 * time.Second}, now: time.Now}
	if !enabled {
		return service, nil
	}
	if len(cfg.setupToken) < 32 {
		return nil, fmt.Errorf("GMAIL_OAUTH_SETUP_TOKEN must contain at least 32 characters")
	}
	redirect, err := url.Parse(cfg.redirectURL)
	if err != nil || redirect.Scheme != "https" || redirect.Host == "" || redirect.User != nil || redirect.RawQuery != "" || redirect.Fragment != "" {
		return nil, fmt.Errorf("GMAIL_OAUTH_REDIRECT_URL must be an HTTPS URL without query parameters")
	}
	if service.label == "" || service.sender == "" {
		return nil, fmt.Errorf("GMAIL_LABEL and GMAIL_SENDER must not be empty")
	}
	service.interval, err = time.ParseDuration(cfg.interval)
	if err != nil || service.interval < time.Minute {
		return nil, fmt.Errorf("GMAIL_CHECK_INTERVAL must be at least 1m")
	}
	return service, nil
}

func (g *gmailService) enabled() bool { return g != nil && g.clientID != "" }

func (g *gmailService) runLoop(ctx context.Context) {
	if g.hasTokens(ctx) {
		g.importMessages(ctx)
	} else {
		log.Printf("eeClass Gmail importer is waiting for OAuth authorization")
		g.importMessages(ctx)
	}
	ticker := time.NewTicker(g.interval)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			g.importMessages(ctx)
		}
	}
}

func (g *gmailService) connect(w http.ResponseWriter, r *http.Request) {
	if !secureEqual(r.URL.Query().Get("token"), g.setupToken) {
		writeError(w, http.StatusNotFound, "not_found")
		return
	}
	state, err := newToken()
	if err != nil {
		writeError(w, http.StatusInternalServerError, "token_generation_failed")
		return
	}
	if _, err := g.db.ExecContext(r.Context(), "DELETE FROM gmail_oauth_states WHERE expires_at < ?", g.now().UnixMilli()); err != nil {
		writeError(w, http.StatusServiceUnavailable, "database_unavailable")
		return
	}
	if _, err := g.db.ExecContext(r.Context(), "INSERT INTO gmail_oauth_states(state, expires_at) VALUES(?, ?)", state, g.now().Add(10*time.Minute).UnixMilli()); err != nil {
		writeError(w, http.StatusServiceUnavailable, "database_unavailable")
		return
	}
	query := url.Values{
		"client_id":     {g.clientID},
		"redirect_uri":  {g.redirectURL},
		"response_type": {"code"},
		"scope":         {"https://www.googleapis.com/auth/gmail.readonly"},
		"access_type":   {"offline"},
		"prompt":        {"consent"},
		"state":         {state},
	}
	http.Redirect(w, r, "https://accounts.google.com/o/oauth2/v2/auth?"+query.Encode(), http.StatusFound)
}

func (g *gmailService) callback(w http.ResponseWriter, r *http.Request) {
	state := r.URL.Query().Get("state")
	if !g.consumeState(r.Context(), state) {
		writeError(w, http.StatusBadRequest, "invalid_oauth_state")
		return
	}
	if r.URL.Query().Get("error") != "" || r.URL.Query().Get("code") == "" {
		writeHTML(w, http.StatusBadRequest, "Gmail 授權未完成", "你取消了 Google 授權，尚未讀取任何 Gmail 信件。請關閉本頁後重新執行連線網址。")
		return
	}
	token, err := g.exchangeCode(r.Context(), r.URL.Query().Get("code"))
	if err != nil || token.RefreshToken == "" {
		log.Printf("Gmail OAuth authorization failed: %v", err)
		writeHTML(w, http.StatusBadGateway, "Gmail 授權失敗", "Google 未回傳可用的長期授權。請關閉本頁後重新執行連線網址。")
		return
	}
	if err := g.saveTokens(r.Context(), token); err != nil {
		writeError(w, http.StatusServiceUnavailable, "database_unavailable")
		return
	}
	go g.importMessages(context.Background())
	writeHTML(w, http.StatusOK, "Gmail 已連線", "Simple App 已取得 eeClass 標籤的唯讀授權，現在會開始匯入可辨識的作業通知。你可以安全地關閉本頁。")
}

func writeHTML(w http.ResponseWriter, status int, title, body string) {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.WriteHeader(status)
	_, _ = fmt.Fprintf(w, "<!doctype html><html lang=\"zh-Hant\"><meta charset=\"utf-8\"><title>%s</title><body><h1>%s</h1><p>%s</p></body></html>", title, title, body)
}

func secureEqual(left, right string) bool {
	if len(left) != len(right) || left == "" {
		return false
	}
	return subtle.ConstantTimeCompare([]byte(left), []byte(right)) == 1
}

type gmailImportStatus struct {
	StartedAt          int64
	FinishedAt         int64
	ListedMessages     int
	NewMessages        int
	AllowedMessages    int
	RecognizedMessages int
	ChangedItems       int
	SkippedMessages    int
	LastError          string
}

func (g *gmailService) authorizedSetup(r *http.Request) bool {
	return secureEqual(r.URL.Query().Get("token"), g.setupToken)
}

// status is a small self-hosted operator page. It never shows email subjects,
// bodies, or OAuth credentials.
func (g *gmailService) status(w http.ResponseWriter, r *http.Request) {
	if !g.authorizedSetup(r) {
		writeError(w, http.StatusNotFound, "not_found")
		return
	}
	state := g.loadStatus(r.Context())
	connected := g.hasTokens(r.Context())
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.WriteHeader(http.StatusOK)
	lastScan := "尚未完成掃描"
	if state.FinishedAt > 0 {
		lastScan = time.UnixMilli(state.FinishedAt).In(time.FixedZone("Asia/Taipei", 8*60*60)).Format("2006-01-02 15:04:05 MST")
	}
	errorText := "無"
	if state.LastError != "" {
		errorText = html.EscapeString(state.LastError)
	}
	_, _ = fmt.Fprintf(w, `<!doctype html><html lang="zh-Hant"><meta charset="utf-8"><title>eeClass Gmail 匯入狀態</title><body><h1>eeClass Gmail 匯入狀態</h1><p>OAuth 授權：%s</p><p>最後掃描：%s</p><ul><li>符合「eeClass」標籤與寄件者搜尋的信件：%d</li><li>本次新信：%d</li><li>通過寄件者驗證：%d</li><li>辨識為作業通知：%d</li><li>建立或更新待辦：%d</li><li>略過：%d</li></ul><p>最後錯誤：%s</p><form method="post" action="/gmail/rescan?token=%s"><button type="submit">重新掃描 eeClass 標籤</button></form><p>本頁不會顯示信件內容或 OAuth 憑證。</p></body></html>`, boolText(connected, "已連線", "尚未授權"), lastScan, state.ListedMessages, state.NewMessages, state.AllowedMessages, state.RecognizedMessages, state.ChangedItems, state.SkippedMessages, errorText, html.EscapeString(r.URL.Query().Get("token")))
}

func boolText(value bool, yes, no string) string {
	if value {
		return yes
	}
	return no
}

func (g *gmailService) rescan(w http.ResponseWriter, r *http.Request) {
	if !g.authorizedSetup(r) {
		writeError(w, http.StatusNotFound, "not_found")
		return
	}
	if _, err := g.db.ExecContext(r.Context(), "DELETE FROM gmail_processed_messages"); err != nil {
		writeError(w, http.StatusServiceUnavailable, "database_unavailable")
		return
	}
	go g.importMessages(context.Background())
	http.Redirect(w, r, "/gmail/status?token="+url.QueryEscape(r.URL.Query().Get("token")), http.StatusSeeOther)
}

func (g *gmailService) loadStatus(ctx context.Context) gmailImportStatus {
	var status gmailImportStatus
	_ = g.db.QueryRowContext(ctx, `SELECT started_at, finished_at, listed_messages, new_messages, allowed_messages, recognized_messages, changed_items, skipped_messages, last_error FROM gmail_import_status WHERE id = 1`).Scan(
		&status.StartedAt, &status.FinishedAt, &status.ListedMessages, &status.NewMessages, &status.AllowedMessages, &status.RecognizedMessages, &status.ChangedItems, &status.SkippedMessages, &status.LastError)
	return status
}

func (g *gmailService) saveStatus(ctx context.Context, status gmailImportStatus) {
	_, _ = g.db.ExecContext(ctx, `INSERT INTO gmail_import_status(id, started_at, finished_at, listed_messages, new_messages, allowed_messages, recognized_messages, changed_items, skipped_messages, last_error) VALUES(1, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT(id) DO UPDATE SET started_at = excluded.started_at, finished_at = excluded.finished_at, listed_messages = excluded.listed_messages, new_messages = excluded.new_messages, allowed_messages = excluded.allowed_messages, recognized_messages = excluded.recognized_messages, changed_items = excluded.changed_items, skipped_messages = excluded.skipped_messages, last_error = excluded.last_error`, status.StartedAt, status.FinishedAt, status.ListedMessages, status.NewMessages, status.AllowedMessages, status.RecognizedMessages, status.ChangedItems, status.SkippedMessages, status.LastError)
}

func (g *gmailService) consumeState(ctx context.Context, state string) bool {
	if state == "" {
		return false
	}
	result, err := g.db.ExecContext(ctx, "DELETE FROM gmail_oauth_states WHERE state = ? AND expires_at >= ?", state, g.now().UnixMilli())
	if err != nil {
		return false
	}
	count, _ := result.RowsAffected()
	return count == 1
}

type gmailToken struct {
	AccessToken  string `json:"access_token"`
	RefreshToken string `json:"refresh_token"`
	ExpiresIn    int64  `json:"expires_in"`
	Error        string `json:"error"`
}

func (g *gmailService) exchangeCode(ctx context.Context, code string) (gmailToken, error) {
	form := url.Values{"code": {code}, "client_id": {g.clientID}, "client_secret": {g.clientSecret}, "redirect_uri": {g.redirectURL}, "grant_type": {"authorization_code"}}
	return g.requestToken(ctx, form)
}

func (g *gmailService) refreshToken(ctx context.Context, refresh string) (gmailToken, error) {
	form := url.Values{"refresh_token": {refresh}, "client_id": {g.clientID}, "client_secret": {g.clientSecret}, "grant_type": {"refresh_token"}}
	token, err := g.requestToken(ctx, form)
	if token.RefreshToken == "" {
		token.RefreshToken = refresh
	}
	return token, err
}

func (g *gmailService) requestToken(ctx context.Context, form url.Values) (gmailToken, error) {
	request, err := http.NewRequestWithContext(ctx, http.MethodPost, "https://oauth2.googleapis.com/token", strings.NewReader(form.Encode()))
	if err != nil {
		return gmailToken{}, err
	}
	request.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	response, err := g.httpClient.Do(request)
	if err != nil {
		return gmailToken{}, err
	}
	defer response.Body.Close()
	var token gmailToken
	if err := json.NewDecoder(io.LimitReader(response.Body, 1<<20)).Decode(&token); err != nil {
		return gmailToken{}, err
	}
	if response.StatusCode != http.StatusOK || token.AccessToken == "" || token.ExpiresIn <= 0 {
		if token.Error == "" {
			token.Error = response.Status
		}
		return gmailToken{}, fmt.Errorf("Google token request failed: %s", token.Error)
	}
	return token, nil
}

func (g *gmailService) saveTokens(ctx context.Context, token gmailToken) error {
	_, err := g.db.ExecContext(ctx, `INSERT INTO gmail_oauth_tokens(id, access_token, refresh_token, expires_at, updated_at)
		VALUES(1, ?, ?, ?, ?) ON CONFLICT(id) DO UPDATE SET access_token = excluded.access_token, refresh_token = excluded.refresh_token, expires_at = excluded.expires_at, updated_at = excluded.updated_at`,
		token.AccessToken, token.RefreshToken, g.now().Add(time.Duration(token.ExpiresIn)*time.Second).UnixMilli(), g.now().UnixMilli())
	return err
}

func (g *gmailService) hasTokens(ctx context.Context) bool {
	var count int
	return g.db.QueryRowContext(ctx, "SELECT count(*) FROM gmail_oauth_tokens WHERE id = 1").Scan(&count) == nil && count == 1
}

func (g *gmailService) accessToken(ctx context.Context, forceRefresh bool) (string, error) {
	var access, refresh string
	var expires int64
	if err := g.db.QueryRowContext(ctx, "SELECT access_token, refresh_token, expires_at FROM gmail_oauth_tokens WHERE id = 1").Scan(&access, &refresh, &expires); err != nil {
		return "", err
	}
	if !forceRefresh && expires > g.now().Add(time.Minute).UnixMilli() {
		return access, nil
	}
	token, err := g.refreshToken(ctx, refresh)
	if err != nil {
		return "", err
	}
	if err := g.saveTokens(ctx, token); err != nil {
		return "", err
	}
	return token.AccessToken, nil
}

func (g *gmailService) authorizedGet(ctx context.Context, endpoint string) (*http.Response, error) {
	for attempt := 0; attempt < 2; attempt++ {
		token, err := g.accessToken(ctx, attempt > 0)
		if err != nil {
			return nil, err
		}
		request, err := http.NewRequestWithContext(ctx, http.MethodGet, endpoint, nil)
		if err != nil {
			return nil, err
		}
		request.Header.Set("Authorization", "Bearer "+token)
		response, err := g.httpClient.Do(request)
		if err != nil || response.StatusCode != http.StatusUnauthorized || attempt == 1 {
			return response, err
		}
		response.Body.Close()
	}
	return nil, fmt.Errorf("unreachable Gmail request")
}

type gmailMessageList struct {
	Messages []struct {
		ID string `json:"id"`
	} `json:"messages"`
	NextPageToken string `json:"nextPageToken"`
}

type gmailMessage struct {
	ID           string    `json:"id"`
	InternalDate string    `json:"internalDate"`
	Payload      gmailPart `json:"payload"`
}

func (g *gmailService) importMessages(ctx context.Context) {
	g.importMu.Lock()
	defer g.importMu.Unlock()
	status := gmailImportStatus{StartedAt: g.now().UnixMilli()}
	defer func() {
		status.FinishedAt = g.now().UnixMilli()
		g.saveStatus(ctx, status)
	}()
	if !g.hasTokens(ctx) {
		status.LastError = "尚未完成 Gmail OAuth 授權"
		return
	}
	labelID, err := g.labelID(ctx)
	if err != nil {
		status.LastError = "讀取 Gmail 標籤失敗：" + err.Error()
		log.Printf("eeClass Gmail import skipped: %v", err)
		return
	}
	ids, err := g.listMessageIDs(ctx, labelID)
	if err != nil {
		status.LastError = "列出 Gmail 信件失敗：" + err.Error()
		log.Printf("eeClass Gmail import skipped: %v", err)
		return
	}
	status.ListedMessages = len(ids)
	messages := make([]gmailMessage, 0, len(ids))
	for _, id := range ids {
		if g.wasProcessed(ctx, id) {
			continue
		}
		message, err := g.message(ctx, id)
		if err != nil {
			status.LastError = "讀取一封 Gmail 信件失敗：" + err.Error()
			log.Printf("eeClass Gmail message %s skipped: %v", id, err)
			continue
		}
		messages = append(messages, message)
	}
	sort.Slice(messages, func(i, j int) bool { return gmailMessageTime(messages[i]) < gmailMessageTime(messages[j]) })
	status.NewMessages = len(messages)
	for _, message := range messages {
		if !gmailFromMatches(gmailHeader(message.Payload, "From"), g.sender) {
			status.SkippedMessages++
			g.markProcessed(ctx, message.ID)
			continue
		}
		status.AllowedMessages++
		assignment, recognized := parseEEClassAssignment(gmailHeader(message.Payload, "Subject"), gmailPartText(message.Payload))
		if recognized {
			status.RecognizedMessages++
			changed, err := g.applyAssignment(ctx, assignment)
			if err != nil {
				status.LastError = "寫入作業待辦失敗：" + err.Error()
				status.SkippedMessages++
				log.Printf("eeClass Gmail message %s could not be imported: %v", message.ID, err)
				continue
			}
			if changed {
				status.ChangedItems++
			} else {
				status.SkippedMessages++
			}
		} else {
			status.SkippedMessages++
		}
		g.markProcessed(ctx, message.ID)
	}
	if status.NewMessages > 0 || status.ChangedItems > 0 || status.LastError != "" {
		log.Printf("eeClass Gmail importer scan: label=%d new=%d allowed=%d recognized=%d changed=%d skipped=%d", status.ListedMessages, status.NewMessages, status.AllowedMessages, status.RecognizedMessages, status.ChangedItems, status.SkippedMessages)
	}
}

func gmailMessageTime(message gmailMessage) int64 {
	value, _ := strconv.ParseInt(message.InternalDate, 10, 64)
	return value
}

func (g *gmailService) labelID(ctx context.Context) (string, error) {
	response, err := g.authorizedGet(ctx, "https://gmail.googleapis.com/gmail/v1/users/me/labels")
	if err != nil {
		return "", err
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		return "", fmt.Errorf("Gmail labels request returned %s", response.Status)
	}
	var body struct {
		Labels []struct{ ID, Name string } `json:"labels"`
	}
	if err := json.NewDecoder(io.LimitReader(response.Body, 2<<20)).Decode(&body); err != nil {
		return "", err
	}
	for _, label := range body.Labels {
		if label.Name == g.label {
			return label.ID, nil
		}
	}
	return "", fmt.Errorf("Gmail label %q was not found", g.label)
}

func (g *gmailService) listMessageIDs(ctx context.Context, labelID string) ([]string, error) {
	values := url.Values{"labelIds": {labelID}, "q": {"from:" + g.sender}, "maxResults": {"100"}}
	result := make([]string, 0, 100)
	for page := 0; page < 5; page++ {
		response, err := g.authorizedGet(ctx, "https://gmail.googleapis.com/gmail/v1/users/me/messages?"+values.Encode())
		if err != nil {
			return nil, err
		}
		if response.StatusCode != http.StatusOK {
			response.Body.Close()
			return nil, fmt.Errorf("Gmail messages request returned %s", response.Status)
		}
		var body gmailMessageList
		err = json.NewDecoder(io.LimitReader(response.Body, 2<<20)).Decode(&body)
		response.Body.Close()
		if err != nil {
			return nil, err
		}
		for _, message := range body.Messages {
			if message.ID != "" {
				result = append(result, message.ID)
			}
		}
		if body.NextPageToken == "" {
			break
		}
		values.Set("pageToken", body.NextPageToken)
	}
	return result, nil
}

func (g *gmailService) message(ctx context.Context, id string) (gmailMessage, error) {
	response, err := g.authorizedGet(ctx, "https://gmail.googleapis.com/gmail/v1/users/me/messages/"+url.PathEscape(id)+"?format=full")
	if err != nil {
		return gmailMessage{}, err
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		return gmailMessage{}, fmt.Errorf("Gmail message request returned %s", response.Status)
	}
	var message gmailMessage
	if err := json.NewDecoder(io.LimitReader(response.Body, 5<<20)).Decode(&message); err != nil {
		return gmailMessage{}, err
	}
	if message.ID == "" {
		return gmailMessage{}, fmt.Errorf("Gmail returned an empty message ID")
	}
	return message, nil
}

func (g *gmailService) wasProcessed(ctx context.Context, id string) bool {
	var count int
	return g.db.QueryRowContext(ctx, "SELECT count(*) FROM gmail_processed_messages WHERE message_id = ?", id).Scan(&count) == nil && count == 1
}

func (g *gmailService) markProcessed(ctx context.Context, id string) {
	_, _ = g.db.ExecContext(ctx, "INSERT OR IGNORE INTO gmail_processed_messages(message_id, processed_at) VALUES(?, ?)", id, g.now().UnixMilli())
}
