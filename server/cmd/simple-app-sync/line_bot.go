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
	"net/url"
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
}

type lineBotService struct {
	channelSecret string
	allowedUserID string
	tools         *mcpService
	ollama        *ollamaChatService
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
		ctx, cancel := context.WithTimeout(r.Context(), 50*time.Second)
		answer, answerErr := s.answer(ctx, event.Message.Text)
		cancel()
		if answerErr != nil {
			log.Printf("LINE chat response failed: %v", answerErr)
			answer = "目前無法整理資料，請稍後再試。"
		}
		if err := s.replyMessage(r.Context(), event.ReplyToken, truncateLineText(answer)); err != nil {
			log.Printf("LINE chat reply failed: %v", err)
		}
	}
	w.WriteHeader(http.StatusOK)
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
	if s.ollama == nil {
		return "我目前可處理固定指令：今日行程、未完成作業、本週截止、摘要。AI 對話尚未啟用。", nil
	}
	return s.ollama.answer(ctx, input)
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

type ollamaChatService struct {
	baseURL string
	model   string
	tools   *mcpService
	client  *http.Client
}

type ollamaMessage struct {
	Role      string           `json:"role"`
	Content   string           `json:"content"`
	ToolCalls []ollamaToolCall `json:"tool_calls,omitempty"`
	ToolName  string           `json:"tool_name,omitempty"`
}

type ollamaToolCall struct {
	Function struct {
		Name      string          `json:"name"`
		Arguments json.RawMessage `json:"arguments"`
	} `json:"function"`
}

func newOllamaChatService(baseURL, model string, tools *mcpService) (*ollamaChatService, error) {
	parsed, err := url.Parse(strings.TrimSpace(baseURL))
	if err != nil || (parsed.Scheme != "http" && parsed.Scheme != "https") || parsed.Host == "" || parsed.RawQuery != "" || parsed.Fragment != "" {
		return nil, fmt.Errorf("OLLAMA_BASE_URL must be a valid HTTP URL without a query or fragment")
	}
	if tools == nil {
		return nil, fmt.Errorf("Ollama chat requires MCP tools")
	}
	return &ollamaChatService{baseURL: strings.TrimRight(parsed.String(), "/"), model: strings.TrimSpace(model), tools: tools, client: &http.Client{Timeout: 45 * time.Second}}, nil
}

func (s *ollamaChatService) answer(ctx context.Context, question string) (string, error) {
	messages := []ollamaMessage{
		{Role: "system", Content: "你是使用者的個人管家。回答一律使用繁體中文。只能根據工具回傳的個人資料回答；資料不足時要坦白說明。不可聲稱修改、建立或刪除任何資料。回答請簡潔。"},
		{Role: "user", Content: question},
	}
	for step := 0; step < 3; step++ {
		response, err := s.chat(ctx, messages)
		if err != nil {
			return "", err
		}
		if len(response.Message.ToolCalls) == 0 {
			if strings.TrimSpace(response.Message.Content) == "" {
				return "", fmt.Errorf("Ollama did not return a reply")
			}
			return response.Message.Content, nil
		}
		messages = append(messages, response.Message)
		for _, call := range response.Message.ToolCalls {
			arguments := map[string]any{}
			if len(call.Function.Arguments) > 0 && string(call.Function.Arguments) != "null" {
				if err := json.Unmarshal(call.Function.Arguments, &arguments); err != nil {
					messages = append(messages, ollamaMessage{Role: "tool", ToolName: call.Function.Name, Content: "工具參數格式不正確"})
					continue
				}
			}
			result, err := s.tools.callTool(ctx, call.Function.Name, arguments)
			if err != nil {
				messages = append(messages, ollamaMessage{Role: "tool", ToolName: call.Function.Name, Content: err.Error()})
			} else {
				messages = append(messages, ollamaMessage{Role: "tool", ToolName: call.Function.Name, Content: result.Text})
			}
		}
	}
	return "", fmt.Errorf("Ollama exceeded the allowed number of tool calls")
}

func (s *ollamaChatService) chat(ctx context.Context, messages []ollamaMessage) (struct {
	Message ollamaMessage `json:"message"`
}, error) {
	tools := make([]map[string]any, 0, len(s.tools.toolDefinitions()))
	for _, tool := range s.tools.toolDefinitions() {
		tools = append(tools, map[string]any{"type": "function", "function": map[string]any{"name": tool.Name, "description": tool.Description, "parameters": tool.InputSchema}})
	}
	body, err := json.Marshal(map[string]any{"model": s.model, "stream": false, "messages": messages, "tools": tools})
	if err != nil {
		return struct {
			Message ollamaMessage `json:"message"`
		}{}, err
	}
	request, err := http.NewRequestWithContext(ctx, http.MethodPost, s.baseURL+"/api/chat", strings.NewReader(string(body)))
	if err != nil {
		return struct {
			Message ollamaMessage `json:"message"`
		}{}, err
	}
	request.Header.Set("Content-Type", "application/json")
	response, err := s.client.Do(request)
	if err != nil {
		return struct {
			Message ollamaMessage `json:"message"`
		}{}, err
	}
	defer response.Body.Close()
	if response.StatusCode < 200 || response.StatusCode >= 300 {
		message, _ := io.ReadAll(io.LimitReader(response.Body, 2048))
		return struct {
			Message ollamaMessage `json:"message"`
		}{}, fmt.Errorf("Ollama returned %s: %s", response.Status, strings.TrimSpace(string(message)))
	}
	var result struct {
		Message ollamaMessage `json:"message"`
	}
	if err := json.NewDecoder(response.Body).Decode(&result); err != nil {
		return result, err
	}
	return result, nil
}
