#!/usr/bin/env node
/**
 * Minimal MCP-over-stdio client for the Compose Hot Reload MCP server
 * (`:desktopApp:hotMcpServer`). Harness verification instrument added by the
 * m1 feature kmp-desktop-harness; future UI features use it to drive the
 * desktop harness (правка -> reload -> скриншот/семантическое дерево/клики).
 *
 * Usage (from kmp/ or anywhere):
 *   node scripts/mcp-driver.mjs probe                 # initialize + tools/list
 *   node scripts/mcp-driver.mjs calls <plan.json>     # sequential tools/call per plan
 *   node scripts/mcp-driver.mjs flow                  # click/type_text/scroll demo against App()
 *   node scripts/mcp-driver.mjs reload-before         # wait connected + screenshot/tree "before"
 *   node scripts/mcp-driver.mjs reload-after          # await_reload + screenshot/tree "after"
 *
 * Plan format: [{"name":"take_screenshot","args":{...},"save":"/tmp/x.png",
 *                "label":"before"}, ...]
 * The app must be running (`./gradlew :desktopApp:hotRun --auto`); the MCP
 * server connects to it automatically (PID file -> orchestration port).
 * Results are printed as JSON (text truncated); screenshots are written by
 * the server itself via the `save_to` argument.
 */

import { spawn } from "node:child_process";
import { readFileSync, writeFileSync } from "node:fs";
import { createInterface } from "node:readline";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

const KMP_DIR = join(dirname(fileURLToPath(import.meta.url)), "..");
const JAVA_HOME = process.env.JAVA_HOME ?? "/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home";
const GRADLE_ARGS = ["--no-daemon", "--quiet", "--console=plain", ":desktopApp:hotMcpServer"];
const CALL_TIMEOUT_MS = 180_000;
const OVERALL_TIMEOUT_MS = 600_000;
const SHOT_DIR = "/tmp/gt-harness"; // скриншоты — в temp-каталог

const mode = process.argv[2];
if (!mode || !["probe", "calls", "flow", "reload-before", "reload-after"].includes(mode)) {
  console.error("usage: node mcp-driver.mjs probe | calls <plan.json> | flow | reload-before | reload-after");
  process.exit(2);
}

const child = spawn("./gradlew", GRADLE_ARGS, {
  cwd: KMP_DIR,
  env: { ...process.env, JAVA_HOME },
  stdio: ["pipe", "pipe", "pipe"],
  detached: true, // own process group so we can kill the whole tree (gradle -> javaexec)
});

const stderrChunks = [];
child.stderr.on("data", (d) => stderrChunks.push(d));

let nextId = 1;
const pending = new Map();

function send(msg) {
  child.stdin.write(JSON.stringify(msg) + "\n");
}

function request(method, params, timeoutMs) {
  const id = nextId++;
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      pending.delete(id);
      reject(new Error(`timeout waiting for ${method} response`));
    }, timeoutMs);
    pending.set(id, { resolve, timer });
    send({ jsonrpc: "2.0", id, method, params });
  });
}

const lines = createInterface({ input: child.stdout });
lines.on("line", (line) => {
  const trimmed = line.trim();
  if (!trimmed) return;
  let msg;
  try {
    msg = JSON.parse(trimmed);
  } catch {
    // Non-JSON noise on stdout (gradle banners etc.) — ignore.
    return;
  }
  if (msg.id != null && pending.has(msg.id)) {
    const { resolve, timer } = pending.get(msg.id);
    clearTimeout(timer);
    pending.delete(msg.id);
    resolve(msg);
  }
});

function killTree() {
  try {
    process.kill(-child.pid, "SIGTERM");
  } catch {
    /* already gone */
  }
}

const overall = setTimeout(() => {
  console.error("OVERALL TIMEOUT");
  killTree();
  process.exit(124);
}, OVERALL_TIMEOUT_MS);

function summarize(content) {
  if (!Array.isArray(content)) return content;
  return content.map((c) => {
    if (c.type === "image") {
      return { type: "image", mimeType: c.mimeType, dataBytes: c.data ? c.data.length : 0 };
    }
    if (c.type === "text" && typeof c.text === "string") {
      return { type: "text", text: c.text.length > 2000 ? c.text.slice(0, 2000) + `...[truncated ${c.text.length}]` : c.text };
    }
    return c;
  });
}

async function initialize() {
  const versions = ["2024-11-05", "2025-06-18", "2025-03-26"];
  let lastErr = null;
  for (const v of versions) {
    const res = await request(
      "initialize",
      {
        protocolVersion: v,
        capabilities: {},
        clientInfo: { name: "gt-harness-mcp-driver", version: "1.0.0" },
      },
      60_000,
    );
    if (res.error) {
      lastErr = res.error;
      continue; // try next protocol version
    }
    send({ jsonrpc: "2.0", method: "notifications/initialized" });
    return res.result;
  }
  throw new Error("initialize failed: " + JSON.stringify(lastErr));
}

async function callTool(name, args) {
  const res = await request("tools/call", { name, arguments: args ?? {} }, CALL_TIMEOUT_MS);
  if (res.error) return { jsonrpcError: res.error };
  return res.result ?? res;
}

const textOf = (r) => (r.content ?? []).filter((c) => c.type === "text").map((c) => c.text).join("");

/** The server discovers the app asynchronously — poll status before driving the UI. */
async function waitConnected() {
  for (let i = 0; i < 30; i++) {
    const txt = textOf(await callTool("status", {}));
    if (txt.includes('"connected":true')) return true;
    await new Promise((res) => setTimeout(res, 1000));
  }
  return false;
}

try {
  const init = await initialize();
  console.log("INITIALIZED: " + JSON.stringify(init.serverInfo ?? init));

  if (mode === "probe") {
    const res = await request("tools/list", {}, 60_000);
    const tools = (res.result?.tools ?? []).map((t) => ({ name: t.name, description: t.description, inputSchema: t.inputSchema }));
    console.log("TOOLS: " + JSON.stringify(tools, null, 1));
  } else if (mode === "calls") {
    const plan = JSON.parse(readFileSync(process.argv[3], "utf8"));
    const connected = await waitConnected();
    if (!connected) throw new Error("application did not connect to MCP server");
    for (const step of plan) {
      const result = await callTool(step.name, step.args);
      if (step.save) {
        const imgs = (result.content ?? []).filter((c) => c.type === "image" && c.data);
        if (imgs.length > 0) {
          writeFileSync(step.save, Buffer.from(imgs[0].data, "base64"));
          console.log(`STEP ${step.label ?? step.name}: image saved -> ${step.save}`);
        } else {
          console.log(`STEP ${step.label ?? step.name}: no image content to save; result=${JSON.stringify(summarize(result.content))}`);
        }
      } else {
        console.log(`STEP ${step.label ?? step.name}: ${JSON.stringify(summarize(result.content))}${result.isError ? " [isError]" : ""}`);
      }
      if (step.save && step.name === "get_semantic_tree") {
        writeFileSync(step.save, textOf(result));
      }
    }
  } else if (mode === "flow") {
    // Interactive-tools flow: tree -> click -> verify counter -> type_text -> scroll -> screenshot.
    if (!(await waitConnected())) throw new Error("application did not connect to MCP server");

    const treeText = async () => {
      for (let i = 0; i < 10; i++) {
        const r = await callTool("get_semantic_tree", {});
        try {
          return JSON.parse(textOf(r));
        } catch {
          await new Promise((res) => setTimeout(res, 1000)); // app not connected yet
        }
      }
      throw new Error("get_semantic_tree never returned a tree");
    };
    const findAll = (node, pred, acc = []) => {
      if (pred(node)) acc.push(node);
      for (const ch of node.children ?? []) findAll(ch, pred, acc);
      return acc;
    };

    const t0 = await treeText();
    const btn = findAll(t0, (n) => n.text === "Click me" && (n.actions ?? []).includes("onClick"));
    const editable = findAll(t0, (n) => (n.actions ?? []).includes("onClick") && (n.text === "Type here" || n.editableText != null));
    const scrollable = findAll(t0, (n) => (n.children ?? []).some((c) => (c.text ?? "").startsWith("Item 1")));
    console.log(`FLOW ids: button=${btn[0]?.id} editable=${editable[0]?.id} scrollable=${scrollable[0]?.id}`);
    if (!btn[0] || !editable[0] || !scrollable[0]) throw new Error("could not locate target nodes in semantic tree");

    const clickRes = await callTool("click", { nodeId: btn[0].id });
    console.log("FLOW click: " + JSON.stringify(summarize(clickRes.content)));

    await new Promise((r) => setTimeout(r, 500));
    const t1 = await treeText();
    const clicksLine = findAll(t1, (n) => /^Clicks: \d+$/.test(n.text ?? ""));
    console.log("FLOW click verify: " + (clicksLine[0]?.text ?? "NOT FOUND"));

    const typeRes = await callTool("type_text", { nodeId: editable[0].id, text: "Ficus elastica" });
    console.log("FLOW type_text: " + JSON.stringify(summarize(typeRes.content)));

    const scrollRes = await callTool("scroll", { nodeId: scrollable[0].id, deltaX: 0, deltaY: 500 });
    console.log("FLOW scroll: " + JSON.stringify(summarize(scrollRes.content)));

    await new Promise((r) => setTimeout(r, 500));
    const t2 = await treeText();
    const visibleItems = findAll(t2, (n) => /^Item \d+$/.test(n.text ?? "") && (n.bounds?.y ?? -1) >= 0).map((n) => n.text);
    const fieldText = findAll(t2, (n) => n.text === "Ficus elastica" || n.editableText === "Ficus elastica");
    console.log("FLOW scroll verify: visibleItems=" + JSON.stringify(visibleItems));
    console.log("FLOW type verify: fieldTextNodes=" + JSON.stringify(fieldText.map((n) => ({ id: n.id, text: n.text, editableText: n.editableText }))));

    const shot = await callTool("take_screenshot", { save_to: `${SHOT_DIR}/after-interactions.png` });
    console.log("FLOW screenshot: " + JSON.stringify(summarize(shot.content)));
    const logs = await callTool("get_logs", { limit: 40 });
    console.log("FLOW logs: " + JSON.stringify(summarize(logs.content)));
  } else if (mode === "reload-before" || mode === "reload-after") {
    // Hot-reload probe: run `reload-before`, edit a literal in App(), run `reload-after`.
    if (!(await waitConnected())) throw new Error("application did not connect to MCP server");

    if (mode === "reload-before") {
      const shot = await callTool("take_screenshot", { save_to: `${SHOT_DIR}/before-reload.png` });
      console.log("RELOAD before screenshot: " + JSON.stringify(summarize(shot.content)));
      const txt = textOf(await callTool("get_semantic_tree", {}));
      console.log("RELOAD tree has 'GreenThumb': " + txt.includes("GreenThumb"));
      const logs = await callTool("get_logs", { limit: 5 });
      console.log("RELOAD logs tail: " + JSON.stringify(summarize(logs.content)));
    } else {
      const rel = await callTool("await_reload", { timeout_seconds: 120 });
      console.log("RELOAD await_reload: " + JSON.stringify(summarize(rel.content)));
      const st = await callTool("status", {});
      console.log("RELOAD status: " + JSON.stringify(summarize(st.content)));
      const txt = textOf(await callTool("get_semantic_tree", {}));
      console.log("RELOAD tree has 'GreenThumb (reloaded)': " + txt.includes("GreenThumb (reloaded)"));
      const shot = await callTool("take_screenshot", { save_to: `${SHOT_DIR}/after-reload.png` });
      console.log("RELOAD after screenshot: " + JSON.stringify(summarize(shot.content)));
      const logs = await callTool("get_logs", { limit: 25 });
      console.log("RELOAD logs tail: " + JSON.stringify(summarize(logs.content)));
    }
  }
  clearTimeout(overall);
  killTree();
  child.stdin?.end();
  process.exit(0);
} catch (err) {
  console.error("DRIVER ERROR: " + (err?.stack ?? err));
  console.error("STDERR tail: " + Buffer.concat(stderrChunks).toString().slice(-3000));
  clearTimeout(overall);
  killTree();
  process.exit(1);
}
