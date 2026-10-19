package main

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"
)

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
		{Role: "system", Content: personalButlerInstruction},
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
