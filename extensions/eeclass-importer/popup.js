const byId = (id) => document.getElementById(id);
let scanned = [];
let ignored = {};
let mappings = {};

function setStatus(text, error = false) {
  const status = byId("status");
  status.textContent = text;
  status.classList.toggle("error", error);
}

function message(request) {
  return chrome.runtime.sendMessage(request).then((response) => {
    if (!response?.ok) throw new Error(response?.error || "擴充功能沒有回應");
    return response.value;
  });
}

function tabMessage(tabId, request) {
  return chrome.tabs.sendMessage(tabId, request);
}

function renderIgnored() {
  const root = byId("ignored");
  root.replaceChildren();
  const entries = Object.entries(ignored).sort((left, right) => right[1].ignoredAt - left[1].ignoredAt);
  if (entries.length === 0) {
    root.textContent = "沒有永久略過的作業。";
    return;
  }
  for (const [key, value] of entries) {
    const row = document.createElement("div");
    row.className = "ignored";
    const label = document.createElement("span");
    label.textContent = `${value.courseTitle ? `${value.courseTitle}｜` : ""}${value.title}`;
    const restore = document.createElement("button");
    restore.textContent = "恢復";
    restore.addEventListener("click", async () => {
      await message({ type: "restore", key });
      ignored = await message({ type: "ignored" });
      renderIgnored();
      renderAssignments();
    });
    row.append(label, restore);
    root.append(row);
  }
}

function renderAssignments() {
  const root = byId("assignments");
  root.replaceChildren();
  const visible = scanned.filter((assignment) => !ignored[assignment.key]);
  if (visible.length === 0) {
    root.textContent = "沒有可顯示的作業；可能都已永久略過。";
    return;
  }
  for (const assignment of visible) {
    const row = document.createElement("div");
    row.className = "assignment";
    const header = document.createElement("div");
    header.className = "row";
    const label = document.createElement("label");
    const checkbox = document.createElement("input");
    checkbox.type = "checkbox";
    checkbox.dataset.key = assignment.key;
    checkbox.checked = !assignment.submitted && assignment.timestamp > Date.now();
    const title = document.createElement("span");
    title.textContent = assignment.title;
    label.append(checkbox, title);
    const ignore = document.createElement("button");
    ignore.type = "button";
    ignore.textContent = "永久略過";
    ignore.addEventListener("click", async () => {
      await message({ type: "ignore", assignment: { key: assignment.key, title: assignment.title, courseTitle: byId("course-heading").textContent } });
      ignored = await message({ type: "ignored" });
      renderIgnored();
      renderAssignments();
    });
    header.append(label, ignore);
    const meta = document.createElement("div");
    meta.className = "meta";
    meta.textContent = `期限：${assignment.dueAt}${assignment.submitted ? "｜已繳交" : ""}`;
    row.append(header, meta);
    root.append(row);
  }
}

async function loadCourses() {
  const [values, savedMappings] = await Promise.all([message({ type: "courses" }), message({ type: "courseMappings" })]);
  mappings = savedMappings;
  const select = byId("course-map");
  select.replaceChildren(new Option("未關聯課程", ""));
  for (const course of values) select.add(new Option(course.title, course.id));
  const courseTitle = byId("course-heading").textContent;
  const saved = values.find((course) => course.id === mappings[courseTitle]);
  const exact = values.find((course) => course.title === courseTitle);
  if (saved || exact) select.value = (saved || exact).id;
}

async function pair() {
  const button = byId("pair");
  button.disabled = true;
  try {
    setStatus("正在配對…");
    const serverUrl = new URL(byId("server-url").value.trim());
    if (serverUrl.protocol !== "https:" || serverUrl.username || serverUrl.password || serverUrl.search || serverUrl.hash) throw new Error("同步伺服器必須是沒有路徑的 HTTPS 網址");
    const granted = await chrome.permissions.request({ origins: [`${serverUrl.origin}/*`] });
    if (!granted) throw new Error("需要允許擴充功能連線到你的同步伺服器才能匯入作業");
    const value = await message({ type: "pair", serverUrl: serverUrl.origin, pairCode: byId("pair-code").value });
    byId("pair-code").value = "";
    byId("pairing").hidden = true;
    setStatus(`已連接 ${value.serverUrl}`);
    await loadPage();
  } catch (error) { setStatus(error.message, true); } finally { button.disabled = false; }
}

async function importSelected() {
  const keys = new Set([...document.querySelectorAll("#assignments input:checked")].map((input) => input.dataset.key));
  const chosen = scanned.filter((assignment) => keys.has(assignment.key));
  try {
    const result = await message({ type: "import", assignments: chosen, courseId: byId("course-map").value });
    setStatus(`已新增 ${result.created} 筆、更新 ${result.updated} 筆作業；手機與平板同步後即可看到。`);
  } catch (error) { setStatus(error.message, true); }
}

async function loadPage() {
  const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
  if (!tab?.id || !tab.url?.startsWith("https://ncueeclass.ncu.edu.tw/course/homework")) {
    setStatus("請先開啟中央 ee-class 的課程「作業」清單頁。", true);
    return;
  }
  const result = await tabMessage(tab.id, { type: "scanAssignments" });
  if (result.error) throw new Error(result.error);
  scanned = result.assignments;
  byId("course-heading").textContent = result.courseTitle || "目前課程";
  ignored = await message({ type: "ignored" });
  await loadCourses();
  renderIgnored();
  renderAssignments();
  byId("import-panel").hidden = false;
  setStatus(`讀取到 ${scanned.length} 筆作業。`);
}

async function initialize() {
  byId("pair").addEventListener("click", pair);
  byId("import").addEventListener("click", importSelected);
  byId("course-map").addEventListener("change", async () => {
    try {
      mappings = await message({ type: "saveCourseMapping", courseTitle: byId("course-heading").textContent, courseId: byId("course-map").value });
    } catch (error) { setStatus(error.message, true); }
  });
  try {
    const state = await message({ type: "status" });
    byId("pairing").hidden = state.paired;
    if (state.paired) {
      setStatus(`已連接 ${state.serverUrl}`);
      await loadPage();
    } else setStatus("請先配對你的同步伺服器。");
  } catch (error) { setStatus(error.message, true); }
}

initialize();
