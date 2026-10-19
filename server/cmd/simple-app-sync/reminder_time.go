package main

import (
	"fmt"
	"sort"
	"strconv"
	"strings"
	"time"
)

type clockTime struct{ hour, minute int }

func parseClockTime(value string) (clockTime, error) {
	parsed, err := time.Parse("15:04", value)
	if err != nil {
		return clockTime{}, err
	}
	return clockTime{hour: parsed.Hour(), minute: parsed.Minute()}, nil
}

func parseReminderDays(value string) ([]int, error) {
	seen := make(map[int]bool)
	for _, part := range strings.Split(value, ",") {
		days, err := strconv.Atoi(strings.TrimSpace(part))
		if err != nil || days < 0 || days > 30 {
			return nil, fmt.Errorf("LINE_DUE_REMINDER_DAYS must be comma-separated values from 0 to 30")
		}
		seen[days] = true
	}
	if len(seen) == 0 {
		return nil, fmt.Errorf("LINE_DUE_REMINDER_DAYS must not be empty")
	}
	result := make([]int, 0, len(seen))
	for days := range seen {
		result = append(result, days)
	}
	sort.Ints(result)
	return result, nil
}

func startOfDay(value time.Time) time.Time {
	return time.Date(value.Year(), value.Month(), value.Day(), 0, 0, 0, 0, value.Location())
}

func hasReached(now time.Time, target clockTime) bool {
	return now.Hour() > target.hour || (now.Hour() == target.hour && now.Minute() >= target.minute)
}

func containsInt(values []int, target int) bool {
	for _, value := range values {
		if value == target {
			return true
		}
	}
	return false
}
