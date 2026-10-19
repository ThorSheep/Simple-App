package main

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"
)

const openAIResponsesEndpoint = "https://api.openai.com/v1/responses"

const personalButlerInstruction = "你是使用者的個人管家。回答一律使用繁體中文。只能根據工具回傳的個人資料回答；資料不足時要坦白說明。不可聲稱修改、建立或刪除任何資料。回答請簡潔。"

// openAIChatService keeps credentials on the server and gives the model only
// the same read-only tools exposed through the Personal Butler MCP server.
type openAIChatService struct {
	apiKey   string
	model    string
	tools    *mcpService
	endpoint string
	client   *http.Client
}

type openAIResponse struct {
	Output []json.RawMessage `json:"output"`
}

type openAIFunctionCall struct {
	CallID    string
	Name      string
	Arguments string
}

func newOpenAIChatService(apiKey, model string, tools *mcpService) *openAIChatService {
	return &openAIChatService{
		apiKey: strings.TrimSpace(apiKey), model: strings.TrimSpace(model), tools: tools,
		endpoint: openAIResponsesEndpoint, client: &http.Client{Timeout: 45 * time.Second},
	}
}

func (s *openAIChatService) answer(ctx context.Context, question string) (string, error) {
	input := []any{map[string]any{"role": "user", "content": question}}
	for step := 0; step < 3; step++ {
		response, err := s.createResponse(ctx, input)
		if err != nil {
			return "", err
		}
		calls, text, err := openAIResponseParts(response.Output)
		if err != nil {
			return "", err
		}
		if len(calls) == 0 {
			if strings.TrimSpace(text) == "" {
				return "", fmt.Errorf("OpenAI did not return a reply")
			}
			return text, nil
		}

		for _, output := range response.Output {
			input = append(input, output)
		}
		for _, call := range calls {
			arguments := map[string]any{}
			resultText := ""
			if err := json.Unmarshal([]byte(call.Arguments), &arguments); err != nil {
				resultText = "工具參數格式不正確"
			} else if result, err := s.tools.callTool(ctx, call.Name, arguments); err != nil {
				resultText = err.Error()
			} else {
				resultText = result.Text
			}
			input = append(input, map[string]any{"type": "function_call_output", "call_id": call.CallID, "output": resultText})
		}
	}
	return "", fmt.Errorf("OpenAI exceeded the allowed number of tool calls")
}

func (s *openAIChatService) createResponse(ctx context.Context, input []any) (openAIResponse, error) {
	tools := make([]map[string]any, 0, len(s.tools.toolDefinitions()))
	for _, tool := range s.tools.toolDefinitions() {
		tools = append(tools, map[string]any{"type": "function", "name": tool.Name, "description": tool.Description, "parameters": tool.InputSchema})
	}
	body, err := json.Marshal(map[string]any{
		"model": s.model, "instructions": personalButlerInstruction, "input": input, "tools": tools, "store": false,
	})
	if err != nil {
		return openAIResponse{}, err
	}
	request, err := http.NewRequestWithContext(ctx, http.MethodPost, s.endpoint, strings.NewReader(string(body)))
	if err != nil {
		return openAIResponse{}, err
	}
	request.Header.Set("Authorization", "Bearer "+s.apiKey)
	request.Header.Set("Content-Type", "application/json")
	response, err := s.client.Do(request)
	if err != nil {
		return openAIResponse{}, err
	}
	defer response.Body.Close()
	if response.StatusCode < 200 || response.StatusCode >= 300 {
		message, _ := io.ReadAll(io.LimitReader(response.Body, 2048))
		return openAIResponse{}, fmt.Errorf("OpenAI returned %s: %s", response.Status, strings.TrimSpace(string(message)))
	}
	var result openAIResponse
	if err := json.NewDecoder(response.Body).Decode(&result); err != nil {
		return openAIResponse{}, err
	}
	return result, nil
}

func openAIResponseParts(output []json.RawMessage) ([]openAIFunctionCall, string, error) {
	calls := make([]openAIFunctionCall, 0)
	text := make([]string, 0)
	for _, raw := range output {
		var item struct {
			Type      string `json:"type"`
			CallID    string `json:"call_id"`
			Name      string `json:"name"`
			Arguments string `json:"arguments"`
			Content   []struct {
				Type string `json:"type"`
				Text string `json:"text"`
			} `json:"content"`
		}
		if err := json.Unmarshal(raw, &item); err != nil {
			return nil, "", err
		}
		if item.Type == "function_call" {
			if item.CallID == "" || item.Name == "" || item.Arguments == "" {
				return nil, "", fmt.Errorf("OpenAI returned an incomplete function call")
			}
			calls = append(calls, openAIFunctionCall{CallID: item.CallID, Name: item.Name, Arguments: item.Arguments})
		}
		if item.Type == "message" {
			for _, content := range item.Content {
				if content.Type == "output_text" && strings.TrimSpace(content.Text) != "" {
					text = append(text, content.Text)
				}
			}
		}
	}
	return calls, strings.Join(text, "\n"), nil
}
