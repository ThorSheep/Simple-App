const CONFIG_KEY = "syncConfig";
const IGNORED_KEY = "ignoredAssignments";
const COURSE_MAPPINGS_KEY = "courseMappings";
const PROTOCOL_VERSION = 1;

const getStore = (keys) => chrome.storage.local.get(keys);
const setStore = (value) => chrome.storage.local.set(value);

function normalizeServerUrl(value) {
  const url = new URL(value.trim());
  if (url.protocol !== "https:" || url.username || url.password || url.search || url.hash) throw new Error("同步伺服器必須是沒有路徑的 HTTPS 網址");
  return url.origin;
}

function browserDeviceId() {
  return crypto.randomUUID();
}

function revision(deviceId) {
  return `${String(Date.now()).padStart(13, "0")}-00000-${deviceId}`;
}

async function requestJson(url, options = {}) {
  const response = await fetch(url, options);
  const body = await response.text();
  let json = null;
  try { json = body ? JSON.parse(body) : null; } catch { /* Server errors can be plain text. */ }
  if (!response.ok) throw new Error(json?.error || `伺服器回應 ${response.status}`);
  return json;
}

async function requireConfig() {
  const { [CONFIG_KEY]: config } = await getStore(CONFIG_KEY);
  if (!config?.serverUrl || !config?.token || !config?.deviceId) throw new Error("請先完成同步伺服器配對");
  return config;
}

async function pair(serverUrlInput, pairCode) {
  const serverUrl = normalizeServerUrl(serverUrlInput);
  if (pairCode.trim().length < 16) throw new Error("配對碼至少需要 16 個字元");
  const permitted = await chrome.permissions.contains({ origins: [`${serverUrl}/*`] });
  if (!permitted) throw new Error("尚未允許連線到此同步伺服器");
  const previous = (await getStore(CONFIG_KEY))[CONFIG_KEY];
  const deviceId = previous?.serverUrl === serverUrl ? previous.deviceId : browserDeviceId();
  const response = await requestJson(`${serverUrl}/v1/pair`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ pairCode: pairCode.trim(), deviceId, name: "EEClass 作業匯入" })
  });
  await setStore({ [CONFIG_KEY]: { serverUrl, deviceId, token: response.token } });
  return { serverUrl, deviceId };
}

async function sync(config, operations = [], cursor = 0) {
  return requestJson(`${config.serverUrl}/v1/sync`, {
    method: "POST",
    headers: { "Content-Type": "application/json", Authorization: `Bearer ${config.token}` },
    body: JSON.stringify({ protocolVersion: PROTOCOL_VERSION, deviceId: config.deviceId, cursor, operations })
  });
}

function newestChanges(changes, entityType) {
  const result = new Map();
  for (const change of changes.filter((value) => value.entityType === entityType)) {
    const old = result.get(change.entityId);
    if (!old || String(change.revision) > String(old.revision)) result.set(change.entityId, change);
  }
  return result;
}

async function allChanges(config) {
  let cursor = 0;
  const result = [];
  for (let page = 0; page < 100; page += 1) {
    const response = await sync(config, [], cursor);
    const changes = response.changes || [];
    result.push(...changes);
    if (changes.length < 1000 || response.cursor <= cursor) return result;
    cursor = response.cursor;
  }
  throw new Error("伺服器變更資料過多，請先讓手機完成同步後再試一次");
}

async function courses() {
  const config = await requireConfig();
  const values = newestChanges(await allChanges(config), "course");
  return [...values.values()].filter((change) => !change.deleted).flatMap((change) => {
    try {
      const course = typeof change.payload === "string" ? JSON.parse(change.payload) : change.payload;
      return course.id && course.title && !course.archived ? [{ id: course.id, title: course.title }] : [];
    } catch { return []; }
  }).sort((left, right) => left.title.localeCompare(right.title, "zh-Hant"));
}

async function ignoredAssignments() {
  return (await getStore(IGNORED_KEY))[IGNORED_KEY] || {};
}

async function courseMappings() {
  return (await getStore(COURSE_MAPPINGS_KEY))[COURSE_MAPPINGS_KEY] || {};
}

async function saveCourseMapping(courseTitle, courseId) {
  const title = String(courseTitle || "").trim();
  if (!title || title === "目前課程") throw new Error("無法辨識目前 ee-class 課程名稱");
  const mappings = await courseMappings();
  if (courseId) mappings[title] = courseId;
  else delete mappings[title];
  await setStore({ [COURSE_MAPPINGS_KEY]: mappings });
  return mappings;
}

async function ignoreAssignment(assignment) {
  const ignored = await ignoredAssignments();
  ignored[assignment.key] = { title: assignment.title, courseTitle: assignment.courseTitle || "", ignoredAt: Date.now() };
  await setStore({ [IGNORED_KEY]: ignored });
}

async function restoreAssignment(key) {
  const ignored = await ignoredAssignments();
  delete ignored[key];
  await setStore({ [IGNORED_KEY]: ignored });
}

function importOperation(config, assignment, courseId) {
  const itemId = `eeclass-homework-${assignment.id}`;
  const payload = {
    id: itemId,
    title: assignment.title,
    courseId: courseId || null,
    kind: "作業",
    date: assignment.date,
    time: assignment.time,
    presentationDate: "",
    presentationTime: "",
    grouped: false,
    groupNote: "",
    note: `從中央 ee-class 匯入\n${assignment.href}`,
    done: false,
    important: true
  };
  return {
    operationId: crypto.randomUUID(), entityType: "academicItem", entityId: itemId,
    revision: revision(config.deviceId), deviceId: config.deviceId, deleted: false, payload
  };
}

async function importAssignments(assignments, courseId) {
  const config = await requireConfig();
  if (!Array.isArray(assignments) || assignments.length === 0) throw new Error("請先選擇至少一筆作業");
  if (assignments.some((assignment) => !assignment.id || !assignment.title || !assignment.date || !assignment.time || !assignment.href)) throw new Error("作業資料不完整，請重新讀取頁面");
  const existing = newestChanges(await allChanges(config), "academicItem");
  const existingIds = new Set([...existing.values()]
    .filter((change) => !change.deleted && change.entityId.startsWith("eeclass-homework-"))
    .map((change) => change.entityId));
  const response = await sync(config, assignments.map((assignment) => importOperation(config, assignment, courseId)));
  const accepted = response.acceptedOperationIds?.length || 0;
  const updated = assignments.filter((assignment) => existingIds.has(`eeclass-homework-${assignment.id}`)).length;
  return { created: Math.max(0, accepted - updated), updated };
}

chrome.runtime.onMessage.addListener((request, _sender, sendResponse) => {
  (async () => {
    switch (request?.type) {
      case "pair": return pair(request.serverUrl, request.pairCode);
      case "status": {
        const config = (await getStore(CONFIG_KEY))[CONFIG_KEY];
        return { paired: Boolean(config?.serverUrl), serverUrl: config?.serverUrl || "" };
      }
      case "courses": return courses();
      case "courseMappings": return courseMappings();
      case "saveCourseMapping": return saveCourseMapping(request.courseTitle, request.courseId);
      case "ignored": return ignoredAssignments();
      case "ignore": return ignoreAssignment(request.assignment);
      case "restore": return restoreAssignment(request.key);
      case "import": return importAssignments(request.assignments, request.courseId);
      default: throw new Error("未知的擴充功能操作");
    }
  })().then((value) => sendResponse({ ok: true, value }), (error) => sendResponse({ ok: false, error: error instanceof Error ? error.message : "操作失敗" }));
  return true;
});
