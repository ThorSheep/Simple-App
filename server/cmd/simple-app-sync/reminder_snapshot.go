package main

import (
	"context"
	"encoding/json"
)

// reminderSnapshot is the latest non-deleted state reconstructed from the
// append-only synchronization log. It is shared by reminders, MCP, and LINE.
type reminderSnapshot struct {
	courses  map[string]reminderCourse
	meetings []reminderMeeting
	items    []reminderItem
	settings *syncedLineReminderSettings
}

type syncedLineReminderSettings struct {
	Enabled         bool   `json:"enabled"`
	DailySummary    bool   `json:"dailySummary"`
	DailyTime       string `json:"dailyTime"`
	IncludeCourses  bool   `json:"includeCourses"`
	IncludeToday    bool   `json:"includeToday"`
	IncludeTomorrow bool   `json:"includeTomorrow"`
	DueTime         string `json:"dueTime"`
	DueDays         []int  `json:"dueDays"`
	AIProvider      string `json:"aiProvider"`
}

func (s syncedLineReminderSettings) aiProvider() string {
	if s.AIProvider == "openai" {
		return "openai"
	}
	return "ollama"
}

type reminderCourse struct {
	ID       string `json:"id"`
	Title    string `json:"title"`
	Archived bool   `json:"archived"`
}

type reminderMeeting struct {
	ID       string `json:"id"`
	CourseID string `json:"courseId"`
	Day      int    `json:"day"`
	Start    string `json:"start"`
	End      string `json:"end"`
	Room     string `json:"room"`
}

type reminderItem struct {
	ID               string  `json:"id"`
	Title            string  `json:"title"`
	CourseID         *string `json:"courseId"`
	Kind             string  `json:"kind"`
	Date             string  `json:"date"`
	Time             string  `json:"time"`
	PresentationDate string  `json:"presentationDate"`
	PresentationTime string  `json:"presentationTime"`
	Done             bool    `json:"done"`
}

func (s *reminderService) loadSnapshot(ctx context.Context) (reminderSnapshot, error) {
	rows, err := s.db.QueryContext(ctx, `SELECT entity_type, entity_id, deleted, payload
		FROM changes ORDER BY entity_type, entity_id, revision DESC, seq DESC`)
	if err != nil {
		return reminderSnapshot{}, err
	}
	defer rows.Close()
	snapshot := reminderSnapshot{courses: make(map[string]reminderCourse)}
	seen := make(map[string]bool)
	for rows.Next() {
		var entityType, entityID string
		var deleted int
		var payload []byte
		if err := rows.Scan(&entityType, &entityID, &deleted, &payload); err != nil {
			return reminderSnapshot{}, err
		}
		key := entityType + "\x00" + entityID
		if seen[key] {
			continue
		}
		seen[key] = true
		if deleted != 0 {
			continue
		}
		switch entityType {
		case "course":
			var course reminderCourse
			if json.Unmarshal(payload, &course) == nil && course.ID != "" && course.Title != "" {
				snapshot.courses[course.ID] = course
			}
		case "courseMeeting":
			var meeting reminderMeeting
			if json.Unmarshal(payload, &meeting) == nil && meeting.ID != "" && meeting.CourseID != "" && meeting.Day >= 1 && meeting.Day <= 7 {
				snapshot.meetings = append(snapshot.meetings, meeting)
			}
		case "academicItem":
			var item reminderItem
			if json.Unmarshal(payload, &item) == nil && item.ID != "" && item.Title != "" && !item.Done {
				snapshot.items = append(snapshot.items, item)
			}
		case "lineReminderSettings":
			var settings syncedLineReminderSettings
			if json.Unmarshal(payload, &settings) == nil {
				snapshot.settings = &settings
			}
		}
	}
	return snapshot, rows.Err()
}
