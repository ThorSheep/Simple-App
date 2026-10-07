const assert = require("node:assert/strict");
const { isoDeadline } = require("../parser.js");

const currentYear = new Date().getFullYear();
assert.deepEqual(isoDeadline("10-10 00:00"), { date: `${currentYear}-10-10`, time: "00:00", timestamp: new Date(currentYear, 9, 10, 0, 0).getTime() });
assert.deepEqual(isoDeadline("2026-10-10 23:59"), { date: "2026-10-10", time: "23:59", timestamp: new Date(2026, 9, 10, 23, 59).getTime() });
assert.deepEqual(isoDeadline("日期未設定"), { date: "", time: "", timestamp: 0 });
console.log("parser tests passed");
