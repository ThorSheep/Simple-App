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

const linePushEndpoint = "https://api.line.me/v2/bot/message/push"

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
