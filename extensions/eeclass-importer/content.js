chrome.runtime.onMessage.addListener((request, _sender, sendResponse) => {
  if (request?.type !== "scanAssignments") return;
  try {
    sendResponse(EEClassParser.scanAssignments());
  } catch (error) {
    sendResponse({ courseTitle: "", assignments: [], error: error instanceof Error ? error.message : "讀取作業頁失敗" });
  }
  return true;
});
