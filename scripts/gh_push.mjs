#!/usr/bin/env node
/**
 * 用 GitHub REST API 把本地提交推上去。
 *
 * 为什么需要它：这台机器上 `git push` 连 github.com:443 经常被 reset（Recv failure /
 * Empty reply），但 api.github.com 通。Python 的 urllib 在这个网络上 POST 会
 * SSL UNEXPECTED_EOF，Node 的 fetch 正常，所以用 Node 写。
 *
 * 用法：
 *   node scripts/gh_push.mjs               推当前分支所有未上传的提交（只支持快进）
 *   node scripts/gh_push.mjs --dry-run
 */
import { execFileSync } from "node:child_process";
import fs from "node:fs";
import { fileURLToPath } from "node:url";
import path from "node:path";

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const API = "https://api.github.com";
const dryRun = process.argv.includes("--dry-run");

function git(args, opts = {}) {
  return execFileSync("git", args, { cwd: ROOT, maxBuffer: 64 * 1024 * 1024, ...opts });
}
const gitText = (...args) => git(args, { encoding: "utf8" }).trim();
const gitBuf = (...args) => git(args);

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

const TOKEN = token();

async function gh(method, apiPath, body) {
  const res = await fetch(API + apiPath, {
    method,
    headers: {
      Authorization: `Bearer ${TOKEN}`,
      Accept: "application/vnd.github+json",
      "X-GitHub-Api-Version": "2022-11-28",
      "User-Agent": "lumenbook-gh-push",
      ...(body ? { "Content-Type": "application/json" } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
  });
  const text = await res.text();
  if (!res.ok) throw new Error(`GitHub API ${method} ${apiPath} -> ${res.status}\n${text.slice(0, 600)}`);
  return text ? JSON.parse(text) : {};
}

function repoSlug() {
  let url = gitText("remote", "get-url", "origin");
  if (url.startsWith("git@")) url = url.split(":", 2)[1];
  else url = url.split("github.com/")[1];
  return url.replace(/\.git$/, "").replace(/\/+$/, "");
}

const slug = repoSlug();
const branch = gitText("rev-parse", "--abbrev-ref", "HEAD");
const head = gitText("rev-parse", "HEAD");
console.log(`仓库 ${slug}  分支 ${branch}  本地 HEAD ${head.slice(0, 8)}`);

const ref = await gh("GET", `/repos/${slug}/git/ref/heads/${branch}`);
const remoteSha = ref.object.sha;
console.log(`远端 HEAD ${remoteSha.slice(0, 8)}`);

// GitHub 会把提交时间规范成 UTC，导致 API 建出来的 sha 和本地不一致（内容一样）。
// 所以维护一张 remoteSha -> localSha 的映射，下次就知道从哪个本地提交往后推。
const SYNC_FILE = path.join(ROOT, ".git", "lumenbook-sync.json");
let sync = {};
try {
  sync = JSON.parse(fs.readFileSync(SYNC_FILE, "utf8"));
} catch {
  sync = {};
}
const saveSync = () => fs.writeFileSync(SYNC_FILE, JSON.stringify(sync, null, 2), "utf8");

if (remoteSha === head) {
  console.log("已经是最新，不用推。");
  process.exit(0);
}

const remoteCommit = await gh("GET", `/repos/${slug}/git/commits/${remoteSha}`);
const localTree = gitText("show", "-s", "--format=%T", "HEAD");
if (remoteCommit.tree.sha === localTree) {
  sync[remoteSha] = head;
  saveSync();
  console.log("远端内容已经和本地一致（只是 sha 不同），已记录映射，不用重推。");
  process.exit(0);
}

const localBase = sync[remoteSha];
if (!localBase) {
  throw new Error(
    `不知道远端 ${remoteSha.slice(0, 8)} 对应哪个本地提交，无法安全地算差异。\n` +
      `先 git fetch/push 一次让两边对齐，或手动往 ${SYNC_FILE} 里写一条映射。`,
  );
}

const commits = gitText("rev-list", "--reverse", `${localBase}..${head}`).split("\n").filter(Boolean);
console.log(`要推 ${commits.length} 个提交（本地基准 ${localBase.slice(0, 8)}）`);

let baseTree = remoteCommit.tree.sha;
let parentSha = remoteSha;
let localParent = localBase;
let newSha = remoteSha;

for (const commit of commits) {
  const message = gitText("log", "-1", "--format=%B", commit);
  const subject = message.split("\n")[0];
  const changes = gitText("diff", "--name-status", "--no-renames", localParent, commit);

  const entries = [];
  for (const line of changes.split("\n")) {
    if (!line.trim()) continue;
    const [status, file] = line.split("\t");
    if (status.startsWith("D")) {
      entries.push({ path: file, mode: "100644", type: "blob", sha: null });
      continue;
    }
    const buf = gitBuf("show", `${commit}:${file}`);
    const mode = gitText("ls-tree", commit, "--", file).split(/\s+/)[0] || "100644";
    const blob = await gh("POST", `/repos/${slug}/git/blobs`, {
      content: buf.toString("base64"),
      encoding: "base64",
    });
    entries.push({ path: file, mode, type: "blob", sha: blob.sha });
  }

  if (dryRun) {
    console.log(`  [dry] ${commit.slice(0, 8)} ${subject}  (${entries.length} 个文件)`);
    localParent = commit;
    continue;
  }

  const tree = await gh("POST", `/repos/${slug}/git/trees`, { base_tree: baseTree, tree: entries });

  // 带上 author / committer 是为了尽量贴近本地提交；GitHub 会把时间规范成 UTC，
  // 所以 sha 通常还是不同，靠上面的映射表对齐。
  const meta = gitText("log", "-1", "--format=%an%n%ae%n%aI%n%cn%n%ce%n%cI", commit).split("\n");
  const created = await gh("POST", `/repos/${slug}/git/commits`, {
    message,
    tree: tree.sha,
    parents: [parentSha],
    author: { name: meta[0], email: meta[1], date: meta[2] },
    committer: { name: meta[3], email: meta[4], date: meta[5] },
  });
  newSha = created.sha;
  sync[newSha] = commit;
  saveSync();
  console.log(`  ✓ ${newSha.slice(0, 8)}  ${subject}  (${entries.length} 个文件)`);
  baseTree = tree.sha;
  parentSha = newSha;
  localParent = commit;
}

if (dryRun) process.exit(0);

await gh("PATCH", `/repos/${slug}/git/refs/heads/${branch}`, { sha: newSha, force: false });

// 远端 sha 本地不存在，但内容等于本地 HEAD；把 origin/<branch> 指到本地 HEAD，
// 这样 git status 不会一直显示 ahead，下次脚本也能从干净状态开始。
if (sync[newSha] === head || newSha === head) {
  git(["update-ref", `refs/remotes/origin/${branch}`, head]);
}
console.log(`✓ 已更新 ${branch} -> ${newSha.slice(0, 8)}`);
console.log(`  https://github.com/${slug}/commit/${newSha}`);
