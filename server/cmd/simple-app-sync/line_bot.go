package main

import (
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"strings"
	"time"
	"unicode"
)

const lineReplyEndpoint = "https://api.line.me/v2/bot/message/reply"

type lineBotConfig struct {
	accessToken   string
	channelSecret string
	allowedUserID string
	ollamaBaseURL string
	ollamaModel   string
	openAIAPIKey  string
	openAIModel   string
}

type lineChatProvider interface {
	answer(context.Context, string) (string, error)
}

type lineBotService struct {
	channelSecret string
	allowedUserID string
	tools         *mcpService
	ollama        lineChatProvider
	openAI        lineChatProvider
	replyMessage  func(context.Context, string, string) error
}

type lineWebhookRequest struct {
	Events []lineWebhookEvent `json:"events"`
}

type lineWebhookEvent struct {
	Type       string `json:"type"`
	ReplyToken string `json:"replyToken"`
	Source     struct {
		UserID string `json:"userId"`
	} `json:"source"`
	Message struct {
		Type string `json:"type"`
		Text string `json:"text"`
	} `json:"message"`
}

func newLineBotService(cfg lineBotConfig, tools *mcpService) (*lineBotService, error) {
	service := &lineBotService{tools: tools}
	if cfg.channelSecret == "" {
		return service, nil
	}
	if cfg.accessToken == "" || cfg.allowedUserID == "" {
		return nil, fmt.Errorf("LINE_CHANNEL_ACCESS_TOKEN and LINE_ALLOWED_USER_ID are required when LINE_CHANNEL_SECRET is set")
	}
	if tools == nil {
		return nil, fmt.Errorf("LINE chat requires the personal MCP tool service")
	}
	service.channelSecret = cfg.channelSecret
	service.allowedUserID = cfg.allowedUserID
	service.replyMessage = lineReplySender(cfg.accessToken)
	if cfg.ollamaModel != "" {
		ollama, err := newOllamaChatService(cfg.ollamaBaseURL, cfg.ollamaModel, tools)
		if err != nil {
			return nil, err
		}
		service.ollama = ollama
	}
	if cfg.openAIAPIKey != "" && cfg.openAIModel != "" {
		service.openAI = newOpenAIChatService(cfg.openAIAPIKey, cfg.openAIModel, tools)
	}
	return service, nil
}

func (s *lineBotService) enabled() bool {
	return s != nil && s.channelSecret != "" && s.replyMessage != nil && s.tools != nil
}

func (s *lineBotService) webhook(w http.ResponseWriter, r *http.Request) {
	body, err := io.ReadAll(r.Body)
	if err != nil {
		writeError(w, http.StatusBadRequest, "invalid_webhook")
		return
	}
	if !s.validSignature(r.Header.Get("X-Line-Signature"), body) {
		writeError(w, http.StatusUnauthorized, "invalid_line_signature")
		return
	}
	var request lineWebhookRequest
	if err := json.Unmarshal(body, &request); err != nil {
		writeError(w, http.StatusBadRequest, "invalid_webhook")
		return
	}
	for _, event := range request.Events {
		if event.Type != "message" || event.Message.Type != "text" || event.ReplyToken == "" || event.Source.UserID != s.allowedUserID {
			continue
		}
		event := event
		go s.replyToEvent(event)
	}
	w.WriteHeader(http.StatusOK)
}

func (s *lineBotService) replyToEvent(event lineWebhookEvent) {
	// LINE ends the webhook request as soon as it receives HTTP 200. AI work must
	// therefore not inherit the request context, or it will be cancelled mid-response.
	answerContext, cancelAnswer := context.WithTimeout(context.Background(), 50*time.Second)
	answer, answerErr := s.answer(answerContext, event.Message.Text)
	cancelAnswer()
	if answerErr != nil {
		log.Printf("LINE chat response failed: %v", answerErr)
		answer = "目前無法整理資料，請稍後再試。"
	}

	replyContext, cancelReply := context.WithTimeout(context.Background(), 8*time.Second)
	defer cancelReply()
	if err := s.replyMessage(replyContext, event.ReplyToken, truncateLineText(answer)); err != nil {
		log.Printf("LINE chat reply failed: %v", err)
	}
}

func (s *lineBotService) validSignature(signature string, body []byte) bool {
	received, err := base64.StdEncoding.DecodeString(signature)
	if err != nil {
		return false
	}
	mac := hmac.New(sha256.New, []byte(s.channelSecret))
	_, _ = mac.Write(body)
	return hmac.Equal(received, mac.Sum(nil))
}

func (s *lineBotService) answer(ctx context.Context, input string) (string, error) {
	command := compactLineText(input)
	switch command {
	case "功能", "說明", "help", "HELP":
		return "個人管家可以回覆：\n・今日行程\n・未完成作業\n・本週截止\n・摘要\n\n也可以直接用自然語言詢問。", nil
	case "今日行程", "今天行程":
		result, err := s.tools.callTool(ctx, "get_today_schedule", map[string]any{})
		return result.Text, err
	case "未完成作業", "未完成待辦", "未完成事項":
		result, err := s.tools.callTool(ctx, "get_open_tasks", map[string]any{})
		return result.Text, err
	case "本週截止", "近期截止":
		result, err := s.tools.callTool(ctx, "get_due_tasks", map[string]any{"days_ahead": float64(7)})
		return result.Text, err
	case "摘要", "我的摘要":
		result, err := s.tools.callTool(ctx, "get_task_summary", map[string]any{})
		return result.Text, err
	}
	provider, name, err := s.selectedProvider(ctx)
	if err != nil {
		return "目前無法讀取 AI 回覆模式，請稍後再試。", err
	}
	if provider == nil {
		return fmt.Sprintf("目前選擇「%s」，但伺服器尚未完成對應設定。你仍可使用固定指令：今日行程、未完成作業、本週截止、摘要。", name), nil
	}
	return provider.answer(ctx, input)
}

func (s *lineBotService) selectedProvider(ctx context.Context) (lineChatProvider, string, error) {
	snapshot, err := (&reminderService{db: s.tools.db}).loadSnapshot(ctx)
	if err != nil {
		return nil, "", err
	}
	provider := "ollama"
	if snapshot.settings != nil {
		provider = snapshot.settings.aiProvider()
	}
	if provider == "openai" {
		return s.openAI, "OpenAI API", nil
	}
	return s.ollama, "本機 Ollama", nil
}

func lineReplySender(accessToken string) func(context.Context, string, string) error {
	client := &http.Client{Timeout: 10 * time.Second}
	return func(ctx context.Context, replyToken, text string) error {
		body, err := json.Marshal(map[string]any{"replyToken": replyToken, "messages": []map[string]string{{"type": "text", "text": text}}})
		if err != nil {
			return err
		}
		request, err := http.NewRequestWithContext(ctx, http.MethodPost, lineReplyEndpoint, strings.NewReader(string(body)))
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
			return fmt.Errorf("LINE reply returned %s: %s", response.Status, strings.TrimSpace(string(message)))
		}
		return nil
	}
}

func compactLineText(value string) string {
	return strings.TrimSpace(strings.Map(func(r rune) rune {
		if unicode.IsSpace(r) {
			return -1
		}
		return r
	}, value))
}

func truncateLineText(value string) string {
	const limit = 4500
	if len([]rune(value)) <= limit {
		return value
	}
	return string([]rune(value)[:limit-1]) + "…"
}
