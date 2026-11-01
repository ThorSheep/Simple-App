package main

import (
	"context"
	"crypto/sha256"
	"database/sql"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"html"
	"net/mail"
	"regexp"
	"strconv"
	"strings"
	"time"
)

type gmailPart struct {
	MimeType string `json:"mimeType"`
	Headers  []struct {
		Name  string `json:"name"`
		Value string `json:"value"`
	} `json:"headers"`
	Body struct {
		Data string `json:"data"`
	} `json:"body"`
	Parts []gmailPart `json:"parts"`
}

type eeClassAssignment struct {
	Course    string
	Title     string
	Date      string
	Time      string
	URL       string
	Submitted bool
}

type serverAcademicItem struct {
	ID               string  `json:"id"`
	Title            string  `json:"title"`
	CourseID         *string `json:"courseId"`
	Kind             string  `json:"kind"`
	Date             string  `json:"date"`
	Time             string  `json:"time"`
	PresentationDate string  `json:"presentationDate"`
	PresentationTime string  `json:"presentationTime"`
	Grouped          bool    `json:"grouped"`
	GroupNote        string  `json:"groupNote"`
	Note             string  `json:"note"`
	Done             bool    `json:"done"`
	Important        bool    `json:"important"`
}

func gmailHeader(part gmailPart, name string) string {
	for _, header := range part.Headers {
		if strings.EqualFold(header.Name, name) {
			return strings.TrimSpace(header.Value)
		}
	}
	return ""
}

func gmailFromMatches(raw, expected string) bool {
	address, err := mail.ParseAddress(raw)
	if err != nil {
		return false
	}
	return strings.EqualFold(address.Address, expected)
}

func gmailPartText(part gmailPart) string {
	plain, htmlText := "", ""
	var walk func(gmailPart)
	walk = func(current gmailPart) {
		if current.MimeType == "text/plain" && plain == "" {
			plain = decodeGmailBody(current.Body.Data)
		}
		if current.MimeType == "text/html" && htmlText == "" {
			htmlText = decodeGmailBody(current.Body.Data)
		}
		for _, child := range current.Parts {
			walk(child)
		}
	}
	walk(part)
	// Some ee-class messages include a short text/plain fallback plus the real
	// course and assignment fields only in text/html. Keep both alternatives so
	// a non-empty but incomplete plain part cannot hide the HTML table.
	parts := make([]string, 0, 2)
	if plain != "" {
		parts = append(parts, plain)
	}
	if htmlText != "" {
		parts = append(parts, plainHTML(htmlText))
	}
	return strings.Join(parts, "\n")
}

func decodeGmailBody(raw string) string {
	if raw == "" {
		return ""
	}
	decoded, err := base64.RawURLEncoding.DecodeString(raw)
	if err != nil {
		decoded, err = base64.URLEncoding.DecodeString(raw)
	}
	if err != nil {
		return ""
	}
	return string(decoded)
}

// ee-class completion notices are often HTML tables.  Preserve table-cell
// boundaries before stripping tags so labels such as "課程" and "作業" remain
// separate lines for the parser.
var htmlBreaks = regexp.MustCompile(`(?is)<(?:br\s*/?|/?(?:p|div|li|tr|td|th|h[1-6]))\s*>`)
var htmlTags = regexp.MustCompile(`(?is)<[^>]+>`)

func plainHTML(raw string) string {
	withLines := htmlBreaks.ReplaceAllString(raw, "\n")
	return html.UnescapeString(htmlTags.ReplaceAllString(withLines, ""))
}

func parseEEClassAssignment(subject, body string) (eeClassAssignment, bool) {
	subject = strings.TrimSpace(subject)
	course := labeledValue(body, "課程名稱")
	title := labeledValue(body, "作業名稱")
	if strings.Contains(subject, "已開放繳交") {
		if title == "" {
			title = quotedAssignment(subject)
		}
		if course == "" {
			course = parenthesizedCourse(subject)
		}
		date, clock := parseEEClassDateTime(labeledValue(body, "繳交期限"))
		return validAssignment(eeClassAssignment{Course: course, Title: title, Date: date, Time: clock, URL: firstURL(body)}), true
	}
	if strings.Contains(subject, "最後繳交期限提醒") {
		if title == "" {
			title = quotedAssignment(subject)
		}
		if course == "" {
			course = parenthesizedCourse(subject)
		}
		deadline := labeledValue(body, "最後期限")
		if deadline == "" {
			deadline = deadlineInReminder(body)
		}
		date, clock := parseEEClassDateTime(deadline)
		return validAssignment(eeClassAssignment{Course: course, Title: title, Date: date, Time: clock, URL: firstURL(body)}), true
	}
	if strings.Contains(subject, "繳交完成通知") {
		if course == "" {
			course = valueBelowHeading(body, "課程")
		}
		if title == "" {
			title = valueBelowHeading(body, "作業")
		}
		return validAssignment(eeClassAssignment{Course: course, Title: title, Submitted: true}), true
	}
	return eeClassAssignment{}, false
}

func validAssignment(value eeClassAssignment) eeClassAssignment {
	value.Course = strings.TrimSpace(value.Course)
	value.Title = strings.TrimSpace(value.Title)
	value.URL = strings.TrimSpace(value.URL)
	if value.Submitted || (value.Date != "" && value.Time != "") {
		return value
	}
	return eeClassAssignment{}
}

func labeledValue(body, label string) string {
	for _, line := range strings.Split(strings.ReplaceAll(body, "\r\n", "\n"), "\n") {
		line = strings.TrimSpace(line)
		if !strings.HasPrefix(line, label) {
			continue
		}
		rest := strings.TrimSpace(strings.TrimLeft(strings.TrimPrefix(line, label), " ：:"))
		if rest != "" {
			return rest
		}
	}
	return ""
}

func valueBelowHeading(body, heading string) string {
	lines := strings.Split(strings.ReplaceAll(body, "\r\n", "\n"), "\n")
	for index, line := range lines {
		if strings.Trim(strings.TrimSpace(line), "：:") != heading {
			continue
		}
		for _, next := range lines[index+1:] {
			if value := strings.TrimSpace(next); value != "" {
				return value
			}
		}
	}
	return ""
}

var quotedSubject = regexp.MustCompile(`「([^」]+)」`)
var finalParentheses = regexp.MustCompile(`\(([^()]+)\)\s*$`)
var dateTimePattern = regexp.MustCompile(`(\d{4}-\d{2}-\d{2})\s*(?:\(?\s*(\d{1,2}:\d{2})\s*\)?)?`)
var eeClassReminderDeadline = regexp.MustCompile(`請於\s*(\d{4}-\d{2}-\d{2})\s*\(?\s*(\d{1,2}:\d{2})\s*\)?`)
var linkPattern = regexp.MustCompile(`https?://[^\s<>"']+`)
var weekNumberPattern = regexp.MustCompile(`(?i)\bweek\s*0*(\d+)\b`)

func quotedAssignment(subject string) string {
	if found := quotedSubject.FindStringSubmatch(subject); len(found) == 2 {
		return found[1]
	}
	return ""
}

func parenthesizedCourse(subject string) string {
	if found := finalParentheses.FindStringSubmatch(subject); len(found) == 2 {
		return found[1]
	}
	return ""
}

func deadlineInReminder(body string) string {
	if found := eeClassReminderDeadline.FindStringSubmatch(body); len(found) == 3 {
		return found[1] + " " + found[2]
	}
	return ""
}

func parseEEClassDateTime(raw string) (string, string) {
	if found := dateTimePattern.FindStringSubmatch(raw); len(found) == 3 {
		clock := found[2]
		if len(clock) == 4 {
			clock = "0" + clock
		}
		if clock != "" {
			if _, err := time.Parse("2006-01-02 15:04", found[1]+" "+clock); err == nil {
				return found[1], clock
			}
		}
	}
	return "", ""
}

func firstURL(body string) string {
	value := linkPattern.FindString(body)
	return strings.TrimRight(value, ".,;。）」")
}

func eeClassItemID(course, title string) string {
	sum := sha256.Sum256([]byte(normalizeCourseTitle(course) + "\x00" + normalizeAssignmentTitle(title)))
	return "eeclass-email-" + hex.EncodeToString(sum[:])
}

// normalizeAssignmentTitle keeps matching strict enough for a single course,
// but treats ee-class's inconsistent Week 7 / Week07 / Week 07 spelling as
// one assignment name.
func normalizeAssignmentTitle(value string) string {
	value = weekNumberPattern.ReplaceAllStringFunc(value, func(match string) string {
		parts := weekNumberPattern.FindStringSubmatch(match)
		if len(parts) != 2 {
			return match
		}
		number, err := strconv.Atoi(parts[1])
		if err != nil {
			return match
		}
		return "week" + strconv.Itoa(number)
	})
	return normalizeCourseTitle(value)
}

func normalizeCourseTitle(value string) string {
	value = strings.ToLower(strings.TrimSpace(value))
	var result strings.Builder
	for _, runeValue := range value {
		if (runeValue >= 'a' && runeValue <= 'z') || (runeValue >= '0' && runeValue <= '9') || runeValue > 127 {
			result.WriteRune(runeValue)
		}
	}
	return result.String()
}

func (g *gmailService) applyAssignment(ctx context.Context, assignment eeClassAssignment) (bool, error) {
	if assignment.Course == "" || assignment.Title == "" {
		return false, nil
	}
	id := eeClassItemID(assignment.Course, assignment.Title)
	existing, previousRevision, deleted, found, err := g.existingItem(ctx, id)
	if err != nil {
		return false, err
	}
	if !found {
		// The browser extension existed before the Gmail importer.  When it has
		// already created the same assignment for the matched course, keep that
		// entity ID instead of creating a visually identical second task.
		matchedID, matchedItem, matchedRevision, matched, err := g.importedItemForAssignment(ctx, assignment, assignment.Submitted)
		if err != nil {
			return false, err
		}
		if matched {
			id, existing, previousRevision, found = matchedID, matchedItem, matchedRevision, true
		}
	}
	if deleted {
		// Deleting an imported task is an explicit user decision. A later email
		// notification must not silently recreate it.
		return false, nil
	}
	if assignment.Submitted {
		if !found || existing.Done {
			return false, nil
		}
		existing.Done = true
		return g.writeImportedItem(ctx, existing, previousRevision)
	}
	if assignment.Date < g.now().In(time.FixedZone("Asia/Taipei", 8*60*60)).Format("2006-01-02") {
		return false, nil
	}
	item := serverAcademicItem{ID: id, Title: assignment.Title, Kind: "作業", Date: assignment.Date, Time: assignment.Time, Note: importedNote(assignment), Important: true}
	if found {
		item.PresentationDate = existing.PresentationDate
		item.PresentationTime = existing.PresentationTime
		item.Grouped = existing.Grouped
		item.GroupNote = existing.GroupNote
		item.Done = existing.Done
		item.CourseID = existing.CourseID
		if existing.Note != "" {
			if strings.HasPrefix(existing.Note, "從中央 ee-class 電子郵件匯入") {
				// Repeated reminders must preserve the original import note and any
				// attached user note instead of rewriting the same completed task.
				item.Note = existing.Note
			} else {
				item.Note += "\n\n使用者備註：\n" + existing.Note
			}
		}
	}
	if item.CourseID == nil {
		item.CourseID = g.matchCourse(ctx, assignment.Course)
	}
	if found && sameImportedItem(existing, item) {
		return false, nil
	}
	return g.writeImportedItem(ctx, item, previousRevision)
}

func importedNote(assignment eeClassAssignment) string {
	value := "從中央 ee-class 電子郵件匯入\n課程：" + assignment.Course
	if assignment.URL != "" {
		value += "\n來源：" + assignment.URL
	}
	return value
}

func sameImportedItem(left, right serverAcademicItem) bool {
	return left.Title == right.Title && sameOptionalString(left.CourseID, right.CourseID) && left.Kind == right.Kind && left.Date == right.Date && left.Time == right.Time && left.PresentationDate == right.PresentationDate && left.PresentationTime == right.PresentationTime && left.Grouped == right.Grouped && left.GroupNote == right.GroupNote && left.Note == right.Note && left.Done == right.Done && left.Important == right.Important
}

func sameOptionalString(left, right *string) bool {
	if left == nil || right == nil {
		return left == right
	}
	return *left == *right
}

func (g *gmailService) existingItem(ctx context.Context, id string) (serverAcademicItem, string, bool, bool, error) {
	var raw, revision string
	var deleted int
	err := g.db.QueryRowContext(ctx, `SELECT payload, revision, deleted FROM changes WHERE entity_type = 'academicItem' AND entity_id = ? ORDER BY seq DESC LIMIT 1`, id).Scan(&raw, &revision, &deleted)
	if err == sql.ErrNoRows {
		return serverAcademicItem{}, "", false, false, nil
	}
	if err != nil {
		return serverAcademicItem{}, "", false, false, err
	}
	if deleted != 0 {
		return serverAcademicItem{}, revision, true, true, nil
	}
	var item serverAcademicItem
	if err := json.Unmarshal([]byte(raw), &item); err != nil {
		return serverAcademicItem{}, "", false, false, err
	}
	return item, revision, false, true, nil
}

// importedItemForAssignment finds an active task made by either eeClass import
// path. A matched App course and normalized assignment title are both required,
// so same-named work in different courses is never merged accidentally.
func (g *gmailService) importedItemForAssignment(ctx context.Context, assignment eeClassAssignment, includeDone bool) (string, serverAcademicItem, string, bool, error) {
	courseID := g.matchCourse(ctx, assignment.Course)
	if courseID == nil {
		return "", serverAcademicItem{}, "", false, nil
	}
	rows, err := g.db.QueryContext(ctx, `SELECT entity_id, payload, revision, deleted FROM changes WHERE entity_type = 'academicItem' ORDER BY seq DESC`)
	if err != nil {
		return "", serverAcademicItem{}, "", false, err
	}
	defer rows.Close()
	seen := map[string]bool{}
	for rows.Next() {
		var id, raw, revision string
		var deleted int
		if err := rows.Scan(&id, &raw, &revision, &deleted); err != nil {
			return "", serverAcademicItem{}, "", false, err
		}
		if seen[id] {
			continue
		}
		seen[id] = true
		if deleted != 0 || (!strings.HasPrefix(id, "eeclass-homework-") && !strings.HasPrefix(id, "eeclass-email-")) {
			continue
		}
		var item serverAcademicItem
		if err := json.Unmarshal([]byte(raw), &item); err != nil {
			continue
		}
		// A later deadline reminder for the same assignment must keep a task the
		// user already completed. Only a different due date is treated as a
		// possible new-term assignment with the same title.
		if (!includeDone && item.Done && item.Date != assignment.Date) || item.CourseID == nil || *item.CourseID != *courseID || normalizeAssignmentTitle(item.Title) != normalizeAssignmentTitle(assignment.Title) {
			continue
		}
		return id, item, revision, true, nil
	}
	if err := rows.Err(); err != nil {
		return "", serverAcademicItem{}, "", false, err
	}
	return "", serverAcademicItem{}, "", false, nil
}

func (g *gmailService) matchCourse(ctx context.Context, emailCourse string) *string {
	rows, err := g.db.QueryContext(ctx, `SELECT entity_id, payload, deleted FROM changes WHERE entity_type = 'course' ORDER BY seq DESC`)
	if err != nil {
		return nil
	}
	defer rows.Close()
	seen := map[string]bool{}
	emailNormalized := normalizeCourseTitle(emailCourse)
	bestID, bestScore := "", 0
	for rows.Next() {
		var id, raw string
		var deleted int
		if rows.Scan(&id, &raw, &deleted) != nil || seen[id] {
			continue
		}
		seen[id] = true
		if deleted != 0 {
			continue
		}
		var course struct {
			Title    string `json:"title"`
			Archived bool   `json:"archived"`
		}
		if json.Unmarshal([]byte(raw), &course) != nil || course.Archived {
			continue
		}
		candidate := normalizeCourseTitle(course.Title)
		score := 0
		switch {
		case candidate == emailNormalized:
			score = 3
		case len(candidate) >= 4 && strings.Contains(emailNormalized, candidate):
			score = 2
		case len(emailNormalized) >= 4 && strings.Contains(candidate, emailNormalized):
			score = 1
		}
		if score > bestScore {
			bestID, bestScore = id, score
		}
	}
	if bestID == "" {
		return nil
	}
	return &bestID
}

func (g *gmailService) writeImportedItem(ctx context.Context, item serverAcademicItem, previousRevision string) (bool, error) {
	payload, err := json.Marshal(item)
	if err != nil {
		return false, err
	}
	if len(payload) > 64<<10 {
		return false, fmt.Errorf("imported assignment payload is too large")
	}
	if err := g.ensureImporterDevice(ctx); err != nil {
		return false, err
	}
	revision := nextImporterRevision(previousRevision, g.now().UnixMilli())
	operationID, err := newToken()
	if err != nil {
		return false, err
	}
	result, err := g.db.ExecContext(ctx, `INSERT INTO changes(operation_id, entity_type, entity_id, revision, device_id, deleted, payload, created_at) VALUES(?, 'academicItem', ?, ?, ?, 0, ?, ?)`, operationID, item.ID, revision, gmailImporterDeviceID, payload, g.now().UnixMilli())
	if err != nil {
		return false, err
	}
	count, _ := result.RowsAffected()
	if count > 0 && g.notifications != nil {
		g.notifications.notifyOthers(gmailImporterDeviceID)
	}
	return count > 0, nil
}

func (g *gmailService) ensureImporterDevice(ctx context.Context) error {
	_, err := g.db.ExecContext(ctx, `INSERT INTO devices(id, name, token_hash, created_at) VALUES(?, ?, ?, ?) ON CONFLICT(id) DO NOTHING`, gmailImporterDeviceID, "eeClass Gmail 自動匯入", tokenHash("server:"+g.clientID), g.now().UnixMilli())
	return err
}

func nextImporterRevision(previous string, nowMillis int64) string {
	wall, logical := nowMillis, 0
	if len(previous) >= 19 {
		if priorWall, err := strconv.ParseInt(previous[:13], 10, 64); err == nil && priorWall >= wall {
			wall = priorWall
			if priorLogical, err := strconv.Atoi(previous[14:19]); err == nil {
				logical = priorLogical + 1
			}
		}
	}
	return fmt.Sprintf("%013d-%05d-%s", wall, logical, gmailImporterDeviceID)
}
