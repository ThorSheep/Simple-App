package main

import (
	"fmt"
	"sort"
	"strings"
	"time"
)

func (s reminderSnapshot) dailySummary(now time.Time, settings *syncedLineReminderSettings) string {
	date := startOfDay(now)
	classes := make([]string, 0)
	weekday := int(date.Weekday())
	if weekday == 0 {
		weekday = 7
	}
	for _, meeting := range s.meetings {
		course, ok := s.courses[meeting.CourseID]
		if !ok || course.Archived || meeting.Day != weekday { // Go Sunday=0; app Monday=1.
			continue
		}
		room := meeting.Room
		if room == "" {
			room = "未填教室"
		}
		classes = append(classes, fmt.Sprintf("%s %s（%s）", meeting.Start, course.Title, room))
	}
	sort.Strings(classes)
	today := s.deadlinesOn(date)
	tomorrow := s.deadlinesOn(date.AddDate(0, 0, 1))
	lines := []string{fmt.Sprintf("個人管家｜%d/%d 今日提醒", date.Month(), date.Day())}
	if settings == nil || settings.IncludeCourses {
		if len(classes) == 0 {
			lines = append(lines, "課程：今天沒有課程")
		} else {
			lines = append(lines, "課程：\n"+strings.Join(classes, "\n"))
		}
	}
	if (settings == nil || settings.IncludeToday) && len(today) > 0 {
		lines = append(lines, "今日到期：\n"+joinDeadlines(today))
	}
	if (settings == nil || settings.IncludeTomorrow) && len(tomorrow) > 0 {
		lines = append(lines, "明日到期：\n"+joinDeadlines(tomorrow))
	}
	if (settings == nil || settings.IncludeToday || settings.IncludeTomorrow) && len(today) == 0 && len(tomorrow) == 0 {
		lines = append(lines, "待辦：今天與明天沒有到期事項")
	}
	return strings.Join(lines, "\n")
}

type reminderDeadline struct {
	id     string
	title  string
	kind   string
	date   time.Time
	time   string
	course string
}

func (s reminderSnapshot) deadlines() []reminderDeadline {
	result := make([]reminderDeadline, 0)
	for _, item := range s.items {
		course := "生活待辦"
		if item.CourseID != nil {
			course = "未關聯課程"
			if value, ok := s.courses[*item.CourseID]; ok {
				course = value.Title
			}
		}
		appendDeadline := func(id, label, rawDate, rawTime string) {
			if rawDate == "" {
				return
			}
			date, err := time.ParseInLocation("2006-01-02", rawDate, time.Local)
			if err == nil {
				result = append(result, reminderDeadline{id: id, title: item.Title, kind: label, date: date, time: rawTime, course: course})
			}
		}
		appendDeadline(item.ID+":date", item.Kind, item.Date, item.Time)
		if item.PresentationDate != "" && (item.PresentationDate != item.Date || item.PresentationTime != item.Time) {
			appendDeadline(item.ID+":presentation", "報告", item.PresentationDate, item.PresentationTime)
		}
	}
	sort.Slice(result, func(i, j int) bool {
		return result[i].date.Before(result[j].date) || (result[i].date.Equal(result[j].date) && result[i].title < result[j].title)
	})
	return result
}

func (s reminderSnapshot) deadlinesOn(day time.Time) []reminderDeadline {
	result := make([]reminderDeadline, 0)
	for _, deadline := range s.deadlines() {
		if deadline.date.Format("2006-01-02") == day.Format("2006-01-02") {
			result = append(result, deadline)
		}
	}
	return result
}

func (d reminderDeadline) message(days int) string {
	when := "今天"
	if days > 0 {
		when = fmt.Sprintf("%d 天後", days)
	}
	timeText := ""
	if d.time != "" {
		timeText = " " + d.time
	}
	return fmt.Sprintf("個人管家｜到期提醒\n%s %s%s\n課程：%s\n%s\n%s", when, d.kind, timeText, d.course, d.title, d.date.Format("2006/01/02"))
}

func joinDeadlines(items []reminderDeadline) string {
	parts := make([]string, 0, len(items))
	for _, item := range items {
		timeText := ""
		if item.time != "" {
			timeText = " " + item.time
		}
		parts = append(parts, "【"+item.course+"】"+item.kind+"："+item.title+timeText)
	}
	return strings.Join(parts, "\n")
}
