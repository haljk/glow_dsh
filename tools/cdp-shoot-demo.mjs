// Drive the consultant's HTML demo in headless Chrome via CDP and screenshot each state.
//
// No automation package is installed, but Node 24 has fetch and WebSocket built in, so
// the DevTools protocol is enough: evaluate the demo's own state API, then capture the
// phone area.

import { spawn } from "node:child_process";
import { writeFileSync, mkdirSync } from "node:fs";
import { setTimeout as sleep } from "node:timers/promises";

const CHROME = "C:/Program Files/Google/Chrome/Application/chrome.exe";
const DEMO = "D:/Texts/00_个人资料/AI Native/20261121 个人多媒体工作台/【UIUX建议】glow-demo.html";
const OUT = "C:/glow-build/demo-shots";
const PORT = 9333;
const URL = "file:///" + encodeURI(DEMO).replace(/#/g, "%23");

mkdirSync(OUT, { recursive: true });

const chrome = spawn(CHROME, [
  "--headless=new",
  `--remote-debugging-port=${PORT}`,
  "--window-size=1500,1400",
  "--hide-scrollbars",
  "--force-device-scale-factor=2",
  "--allow-file-access-from-files",
  "--no-first-run",
  "--no-default-browser-check",
  "--disable-extensions",
  "--user-data-dir=" + OUT + "/profile",
  URL,
], { stdio: "ignore" });

async function targets() {
  for (let i = 0; i < 60; i++) {
    try {
      const res = await fetch(`http://127.0.0.1:${PORT}/json/list`);
      const list = await res.json();
      const page = list.find((t) => t.type === "page" && t.webSocketDebuggerUrl);
      if (page) return page;
    } catch { /* not up yet */ }
    await sleep(300);
  }
  throw new Error("chrome did not expose a page target");
}

const page = await targets();
const ws = new WebSocket(page.webSocketDebuggerUrl);
await new Promise((resolve, reject) => {
  ws.addEventListener("open", resolve, { once: true });
  ws.addEventListener("error", reject, { once: true });
});

let nextId = 1;
const pending = new Map();
ws.addEventListener("message", (event) => {
  const msg = JSON.parse(event.data);
  if (msg.id && pending.has(msg.id)) {
    const { resolve, reject } = pending.get(msg.id);
    pending.delete(msg.id);
    msg.error ? reject(new Error(msg.error.message)) : resolve(msg.result);
  }
});

function send(method, params = {}) {
  const id = nextId++;
  ws.send(JSON.stringify({ id, method, params }));
  return new Promise((resolve, reject) => pending.set(id, { resolve, reject }));
}

async function evaluate(expression) {
  const res = await send("Runtime.evaluate", { expression, awaitPromise: true, returnByValue: true });
  if (res.exceptionDetails) throw new Error(res.exceptionDetails.text + " " + (res.exceptionDetails.exception?.description ?? ""));
  return res.result?.value;
}

async function clipOf(selector) {
  return evaluate(`(() => {
    const el = document.querySelector(${JSON.stringify(selector)});
    if (!el) return null;
    const r = el.getBoundingClientRect();
    return { x: Math.max(0, r.x), y: Math.max(0, r.y), width: r.width, height: r.height };
  })()`);
}

async function shot(name, selector, { fullPage = false } = {}) {
  let params = { format: "png", captureBeyondViewport: true };
  if (!fullPage) {
    const clip = await clipOf(selector);
    if (!clip) { console.log(`  ! ${name}: selector ${selector} not found`); return; }
    params.clip = { ...clip, scale: 1 };
  }
  const { data } = await send("Page.captureScreenshot", params);
  writeFileSync(`${OUT}/${name}.png`, Buffer.from(data, "base64"));
  console.log(`  shot ${name}`);
}

await send("Page.enable");
await send("Runtime.enable");
await sleep(1500);

const steps = [
  ["18-ready", "location.reload()", "#phone", 1200],
  ["19-running", "S.prompts.text='雨后的旧书店，暖黄吊灯'; render(); A.start()", "#phone", 2000],
  ["20-running-later", "", "#phone", 3000],
  ["21-done", "", "#phone", 6000],
  ["22-gallery-sel", "A.go('gallery'); A.selStart(S.items[0].id)", "#phone", 900],
  ["23-refine", "A.back(); A.mode('refine')", "#phone", 900],
];

for (const [name, script, selector, wait] of steps) {
  try {
    await evaluate(script);
    await sleep(wait ?? 700);
    await shot(name, selector);
  } catch (error) {
    console.log(`  ! ${name}: ${error.message}`);
  }
}

// the notes panel beside the phone (wide viewport only)
try {
  await evaluate("location.reload()");
  await sleep(1500);
  await shot("00-panel", "aside.panel");
} catch (error) {
  console.log("  ! panel: " + error.message);
}

ws.close();
chrome.kill();
console.log("done");
