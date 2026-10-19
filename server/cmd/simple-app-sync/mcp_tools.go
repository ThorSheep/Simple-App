package main

import (
	"context"
	"fmt"
	"sort"
	"strings"
	"time"
)

func (s *mcpService) toolDefinitions() []mcpToolDefinition {
	return []mcpToolDefinition{
		{Name: "get_open_tasks", Description: "取得未完成的作業、報告、考試與生活待辦。可依課程名稱篩選。", InputSchema: taskFilterSchema(false)},
		{Name: "get_due_tasks", Description: "取得從今天起指定天數內到期的未完成事項。", InputSchema: taskFilterSchema(true)},
		{Name: "get_today_schedule", Description: "取得今天的課程與今天到期事項。", InputSchema: map[string]any{"type": "object", "properties": map[string]any{}, "additionalProperties": false}},
		{Name: "get_task_summary", Description: "取得未完成事項與近期到期事項的精簡摘要。", InputSchema: map[string]any{"type": "object", "properties": map[string]any{}, "additionalProperties": false}},
	}
}

func taskFilterSchema(includeDays bool) map[string]any {
	properties := map[string]any{
		"course": map[string]any{"type": "string", "description": "選填；以課程名稱篩選。"},
		"limit":  map[string]any{"type": "integer", "minimum": 1, "maximum": 50, "description": "最多回傳筆數，預設 20。"},
	}
	if includeDays {
		properties["days_ahead"] = map[string]any{"type": "integer", "minimum": 0, "maximum": 30, "description": "從今天起的天數，預設 7。"}
	}
	return map[string]any{"type": "object", "properties": properties, "additionalProperties": false}
}

func (s *mcpService) callTool(ctx context.Context, name string, arguments map[string]any) (mcpToolResult, error) {
	snapshot, err := (&reminderService{db: s.db}).loadSnapshot(ctx)
	if err != nil {
		return mcpToolResult{}, fmt.Errorf("無法讀取同步資料")
	}
	switch name {
	case "get_open_tasks":
		return s.openTasks(snapshot, stringArgument(arguments, "course"), integerArgument(arguments, "limit", 20, 1, 50))
	case "get_due_tasks":
		return s.dueTasks(snapshot, stringArgument(arguments, "course"), integerArgument(arguments, "days_ahead", 7, 0, 30), integerArgument(arguments, "limit", 20, 1, 50))
	case "get_today_schedule":
		return s.todaySchedule(snapshot), nil
	case "get_task_summary":
		return s.taskSummary(snapshot), nil
	default:
		return mcpToolResult{}, fmt.Errorf("未知工具：%s", name)
	}
}

func stringArgument(arguments map[string]any, key string) string {
	value, _ := arguments[key].(string)
	return strings.TrimSpace(value)
}

func integerArgument(arguments map[string]any, key string, fallback, minimum, maximum int) int {
	value, ok := arguments[key].(float64)
	if !ok || value != float64(int(value)) || int(value) < minimum || int(value) > maximum {
		return fallback
	}
	return int(value)
}

type mcpTask struct {
	Title  string `json:"title"`
	Kind   string `json:"kind"`
	Course string `json:"course"`
	Date   string `json:"date,omitempty"`
	Time   string `json:"time,omitempty"`
}

func (s *mcpService) openTasks(snapshot reminderSnapshot, course string, limit int) (mcpToolResult, error) {
	rows := make([]mcpTask, 0)
	for _, item := range snapshot.items {
		row := mcpTask{Title: item.Title, Kind: item.Kind, Course: snapshot.courseName(item.CourseID), Date: item.Date, Time: item.Time}
		if course == "" || containsText(row.Course, course) {
			rows = append(rows, row)
		}
	}
	sort.Slice(rows, func(i, j int) bool {
		left, right := rows[i].Date, rows[j].Date
		if left == "" {
			left = "9999-12-31"
		}
		if right == "" {
			right = "9999-12-31"
		}
		return left < right || (left == right && rows[i].Title < rows[j].Title)
	})
	if len(rows) > limit {
		rows = rows[:limit]
	}
	if len(rows) == 0 {
		return mcpToolResult{Text: "沒有符合條件的未完成事項。", Data: map[string]any{"tasks": rows}}, nil
	}
	return mcpToolResult{Text: "未完成事項：\n" + renderTasks(rows), Data: map[string]any{"tasks": rows}}, nil
}

func (s *mcpService) dueTasks(snapshot reminderSnapshot, course string, daysAhead, limit int) (mcpToolResult, error) {
	start := startOfDay(time.Now().In(s.location))
	end := start.AddDate(0, 0, daysAhead)
	rows := make([]mcpTask, 0)
	for _, deadline := range snapshot.deadlines() {
		if deadline.date.Before(start) || deadline.date.After(end) || (course != "" && !containsText(deadline.course, course)) {
			continue
		}
		rows = append(rows, mcpTask{Title: deadline.title, Kind: deadline.kind, Course: deadline.course, Date: deadline.date.In(s.location).Format("2006-01-02"), Time: deadline.time})
	}
	if len(rows) > limit {
		rows = rows[:limit]
	}
	if len(rows) == 0 {
		return mcpToolResult{Text: fmt.Sprintf("從今天起 %d 天內沒有符合條件的到期事項。", daysAhead), Data: map[string]any{"tasks": rows, "daysAhead": daysAhead}}, nil
	}
	return mcpToolResult{Text: fmt.Sprintf("近期到期事項（%d 天內）：\n%s", daysAhead, renderTasks(rows)), Data: map[string]any{"tasks": rows, "daysAhead": daysAhead}}, nil
}

func (s *mcpService) todaySchedule(snapshot reminderSnapshot) mcpToolResult {
	now := time.Now().In(s.location)
	day := startOfDay(now)
	weekday := int(day.Weekday())
	if weekday == 0 {
		weekday = 7
	}
	classes := make([]string, 0)
	for _, meeting := range snapshot.meetings {
		course, ok := snapshot.courses[meeting.CourseID]
		if ok && !course.Archived && meeting.Day == weekday {
			room := meeting.Room
			if room == "" {
				room = "未填教室"
			}
			classes = append(classes, fmt.Sprintf("%s %s（%s）", meeting.Start, course.Title, room))
		}
	}
	sort.Strings(classes)
	tasks := make([]mcpTask, 0)
	for _, deadline := range snapshot.deadlinesOn(day) {
		tasks = append(tasks, mcpTask{Title: deadline.title, Kind: deadline.kind, Course: deadline.course, Date: day.Format("2006-01-02"), Time: deadline.time})
	}
	lines := []string{fmt.Sprintf("%s 今日行程", day.Format("2006/01/02"))}
	if len(classes) == 0 {
		lines = append(lines, "課程：今天沒有課程")
	} else {
		lines = append(lines, "課程：\n"+strings.Join(classes, "\n"))
	}
	if len(tasks) == 0 {
		lines = append(lines, "到期事項：今天沒有到期事項")
	} else {
		lines = append(lines, "到期事項：\n"+renderTasks(tasks))
	}
	return mcpToolResult{Text: strings.Join(lines, "\n"), Data: map[string]any{"date": day.Format("2006-01-02"), "classes": classes, "tasks": tasks}}
}

func (s *mcpService) taskSummary(snapshot reminderSnapshot) mcpToolResult {
	open, _ := s.openTasks(snapshot, "", 8)
	due, _ := s.dueTasks(snapshot, "", 7, 8)
	return mcpToolResult{Text: "個人管家摘要\n\n" + open.Text + "\n\n" + due.Text, Data: map[string]any{"openTasks": open.Data, "dueTasks": due.Data}}
}

func (s reminderSnapshot) courseName(courseID *string) string {
	if courseID == nil {
		return "生活待辦"
	}
	if course, ok := s.courses[*courseID]; ok {
		return course.Title
	}
	return "未關聯課程"
}

func containsText(value, query string) bool {
	return strings.Contains(strings.ToLower(value), strings.ToLower(query))
}

func renderTasks(tasks []mcpTask) string {
	lines := make([]string, 0, len(tasks))
	for _, task := range tasks {
		when := task.Date
		if task.Time != "" {
			when += " " + task.Time
		}
		if when == "" {
			when = "未設定日期"
		}
		lines = append(lines, fmt.Sprintf("【%s】%s：%s（%s）", task.Course, task.Kind, task.Title, when))
	}
	return strings.Join(lines, "\n")
}
