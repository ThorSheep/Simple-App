(() => {
  const compact = (value) => (value || "").replace(/\s+/g, " ").trim();

  function parseDateText(value, now = new Date()) {
    const match = compact(value).match(/^(?:(\d{4})-)?(\d{1,2})-(\d{1,2})\s+(\d{1,2}):(\d{2})$/);
    if (!match) return null;
    let year = match[1] ? Number(match[1]) : now.getFullYear();
    const month = Number(match[2]);
    const day = Number(match[3]);
    const hour = Number(match[4]);
    const minute = Number(match[5]);
    if (!match[1]) {
      const currentMonth = now.getMonth() + 1;
      if (month <= currentMonth - 6) year += 1;
      if (month >= currentMonth + 6) year -= 1;
    }
    const date = new Date(year, month - 1, day, hour, minute, 0, 0);
    if (date.getFullYear() !== year || date.getMonth() !== month - 1 || date.getDate() !== day) return null;
    return date;
  }

  function isoDeadline(value) {
    const date = parseDateText(value);
    if (!date) return { date: "", time: "", timestamp: 0 };
    const two = (part) => String(part).padStart(2, "0");
    return {
      date: `${date.getFullYear()}-${two(date.getMonth() + 1)}-${two(date.getDate())}`,
      time: `${two(date.getHours())}:${two(date.getMinutes())}`,
      timestamp: date.getTime()
    };
  }

  function columnIndex(headers, label) {
    return headers.findIndex((header) => compact(header).includes(label));
  }

  function wasSubmitted(row) {
    const signals = [...row.querySelectorAll("i, svg, .text-success")]
      .map((element) => `${element.className || ""} ${element.getAttribute("aria-label") || ""} ${element.getAttribute("title") || ""}`.toLowerCase());
    return signals.some((signal) => signal.includes("check") || signal.includes("success") || signal.includes("已繳"));
  }

  function courseTitle() {
    const crumb = document.querySelector(".breadcrumb a, nav[aria-label='breadcrumb'] a");
    return compact(crumb?.textContent) || compact(document.querySelector("h1")?.textContent);
  }

  function scanAssignments() {
    const table = [...document.querySelectorAll("table")].find((candidate) => {
      const headers = [...candidate.querySelectorAll("thead th")].map((header) => compact(header.textContent));
      return headers.some((header) => header.includes("名稱")) && headers.some((header) => header.includes("期限"));
    });
    if (!table) return { courseTitle: courseTitle(), assignments: [], error: "找不到 ee-class 作業清單。請先開啟課程的「作業」頁面。" };
    const headers = [...table.querySelectorAll("thead th")].map((header) => compact(header.textContent));
    const nameIndex = columnIndex(headers, "名稱");
    const openIndex = columnIndex(headers, "開放繳交");
    const dueIndex = columnIndex(headers, "期限");
    const assignments = [...table.querySelectorAll("tbody tr")].flatMap((row) => {
      const cells = [...row.querySelectorAll(":scope > td")];
      const link = row.querySelector("a[href*='/course/homework/']");
      if (!link || nameIndex < 0 || dueIndex < 0) return [];
      const href = new URL(link.getAttribute("href"), location.origin).toString();
      const id = href.match(/\/course\/homework\/(\d+)/)?.[1];
      const title = compact(link.getAttribute("title")) || compact(cells[nameIndex]?.textContent);
      const deadline = isoDeadline(cells[dueIndex]?.textContent);
      if (!id || !title || !deadline.date) return [];
      return [{
        key: `ncu-eeclass:${id}`,
        id,
        title,
        href,
        openAt: compact(cells[openIndex]?.textContent),
        dueAt: compact(cells[dueIndex]?.textContent),
        ...deadline,
        submitted: wasSubmitted(row)
      }];
    });
    return { courseTitle: courseTitle(), assignments };
  }

  const api = { compact, parseDateText, isoDeadline, scanAssignments };
  globalThis.EEClassParser = api;
  if (typeof module !== "undefined") module.exports = api;
})();
