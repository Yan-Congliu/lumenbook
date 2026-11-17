#!/usr/bin/env node
/**
 * 用 GitHub REST API 发 Release（并上传 APK 资产）。
 *
 * 为什么不用 `gh release create`：这台机器上 github.com:443 常被 reset，
 * 只有 api.github.com（以及 uploads.github.com）通；`gh` 也不一定装了。
 *
 * 用法：
 *   node scripts/release_gh.mjs --version 0.6.3 \
 *        --apk dist/lumenbook-0.6.3-arm64-v8a.apk \
 *        --notes "本版改了什么…\n装不上怎么办…\n上次的数据会不会丢…"
 *   node scripts/release_gh.mjs --version 0.6.3 --notes-from docs/release-0.6.3.md
 *   node scripts/release_gh.mjs --version 0.6.3 --dry-run
 *
 * 已存在同名 tag 的 release 时会复用，缺 APK 就补传（可重复执行）。
 */
import { execFileSync } from "node:child_process";
import fs from "node:fs";
import { fileURLToPath } from "node:url";
import path from "node:path";

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const API = "https://api.github.com";
const UPLOADS = "https://uploads.github.com";

function arg(name, fallback = null) {
  const i = process.argv.indexOf(`--${name}`);
  return i >= 0 && process.argv[i + 1] ? process.argv[i + 1] : fallback;
}
const dryRun = process.argv.includes("--dry-run");

function git(args, opts = {}) {
  return execFileSync("git", args, { cwd: ROOT, maxBuffer: 64 * 1024 * 1024, ...opts });
}
const gitText = (...args) => git(args, { encoding: "utf8" }).trim();

function token() {
  const out = execFileSync("git", ["credential", "fill"], {
    cwd: ROOT,
    input: "protocol=https\nhost=github.com\n\n",
    encoding: "utf8",
  });
  const line = out.split("\n").find((l) => l.startsWith("password="));
  if (!line) throw new Error("拿不到 GitHub token（git credential fill 没有 password=）");
  return line.slice("password=".length).trim();
}

function repoSlug() {
  let url = gitText("remote", "get-url", "origin");
  if (url.startsWith("git@")) url = url.split(":", 2)[1];
  url = url.replace(/^https?:\/\/[^/]+\//, "").replace(/\.git$/, "");
  const [owner, name] = url.split("/");
  if (!owner || !name) throw new Error(`无法从 origin 解析仓库：${url}`);
  return `${owner}/${name}`;
}

const TOKEN = token();
const REPO = repoSlug();

async function gh(method, apiPath, body, base = API) {
  const res = await fetch(base + apiPath, {
    method,
    headers: {
      Authorization: `Bearer ${TOKEN}`,
      Accept: "application/vnd.github+json",
      "X-GitHub-Api-Version": "2022-11-28",
      "User-Agent": "lumenbook-release",
      ...(body ? { "Content-Type": "application/json" } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
  });
  const text = await res.text();
  if (!res.ok) throw new Error(`GitHub API ${method} ${apiPath} -> ${res.status}\n${text.slice(0, 800)}`);
  return text ? JSON.parse(text) : {};
}

async function upload(uploadUrlTemplate, file) {
  const name = path.basename(file);
  const size = fs.statSync(file).size;
  const url = uploadUrlTemplate.replace("{?name,label}", `?name=${encodeURIComponent(name)}`);
  const res = await fetch(url, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${TOKEN}`,
      Accept: "application/vnd.github+json",
      "X-GitHub-Api-Version": "2022-11-28",
      "User-Agent": "lumenbook-release",
      "Content-Type": "application/vnd.android.package-archive",
      "Content-Length": String(size),
    },
    body: fs.readFileSync(file),
  });
  const text = await res.text();
  if (!res.ok) throw new Error(`上传 APK 失败 ${res.status}\n${text.slice(0, 800)}`);
  return JSON.parse(text);
}

const version = arg("version");
if (!version) throw new Error("需要 --version（例如 0.6.3）");
const tag = arg("tag", `v${version}`);
const notesFile = arg("notes-from");
const apk = arg("apk");
const notes = notesFile
  ? fs.readFileSync(path.resolve(ROOT, notesFile), "utf8")
  : (arg("notes") ?? "").replaceAll("\\n", "\n");

if (!notes.trim()) throw new Error("需要 --notes 或 --notes-from（release notes 不能空）");

console.log(`仓库 ${REPO} / tag ${tag} / APK ${apk ?? "(不传)"}`);
if (apk && !fs.existsSync(path.resolve(ROOT, apk))) throw new Error(`APK 不存在：${apk}`);
if (dryRun) {
  console.log("\n[dry-run] notes:\n" + notes);
  process.exit(0);
}

// 1) 找/建 release
let release;
try {
  release = await gh("GET", `/repos/${REPO}/releases/tags/${tag}`);
  console.log(`已有 release：${release.name}`);
  if (notes.trim() && release.body !== notes) {
    release = await gh("PATCH", `/repos/${REPO}/releases/${release.id}`, { body: notes });
    console.log("已更新 release notes");
  }
} catch (e) {
  if (!String(e.message).includes("-> 404")) throw e;
  release = await gh("POST", `/repos/${REPO}/releases`, {
    tag_name: tag,
    target_commitish: "main",
    name: tag,
    body: notes,
    draft: false,
    prerelease: false,
  });
  console.log(`已创建 release：${release.html_url}`);
}

// 2) 传 APK（同名已存在就跳过）
if (apk) {
  const abs = path.resolve(ROOT, apk);
  const name = path.basename(abs);
  const assets = await gh("GET", `/repos/${REPO}/releases/${release.id}/assets`);
  if (assets.some((a) => a.name === name)) {
    console.log(`资产已存在，跳过上传：${name}`);
  } else {
    const uploaded = await upload(release.upload_url, abs);
    console.log(`已上传 ${name}（${(uploaded.size / 1024 / 1024).toFixed(1)} MB）`);
  }
}

console.log(`✓ ${release.html_url}`);
const fresh = await gh("GET", `/repos/${REPO}/releases/tags/${tag}`);
console.log(`  assets: ${fresh.assets.map((a) => a.name).join(", ") || "(无)"}`);
