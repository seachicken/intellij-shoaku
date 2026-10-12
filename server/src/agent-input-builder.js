import child_process from 'node:child_process';
import { mkdtemp, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { promisify } from 'node:util';

const exec = promisify(child_process.exec);

export const inputType = {
  GOAL_HUMAN: 1,
  GOAL_EXPLORER: 2,
  LSP: 3
}

export default class AgentInputBuilder {
  constructor(rootPath, debounceMs, maxEvents = 20, {
    diffFun = async (a, b) => {
      const tmpDir = await mkdtemp(join(tmpdir(), 'shoaku-diff-'));
      const aPath = join(tmpDir, 'a.txt');
      const bPath = join(tmpDir, 'b.txt');
      try {
        await Promise.all([
          writeFile(aPath, JSON.stringify(a || {}, null, 2)),
          writeFile(bPath, JSON.stringify(b || {}, null, 2))
        ]);
        return await exec(`git diff --no-index --no-color -- ${aPath} ${bPath}`)
          .then(({ stdout }) => stdout)
          .catch((e) => e.stdout);
      } finally {
        await rm(tmpDir, { recursive: true, force: true });
      }
    },
  } = {}) {
    this.rootPath = rootPath;
    this.debounceMs = debounceMs;
    this.maxEvents = maxEvents;
    this.listeners = [];
    this.events = [];
    this.timer;
    this.prevOperation = new Map();
    this.diffFun = diffFun;
  }

  onAgentInput(listener) {
    this.listeners.push(listener);
  }

  ingest(event) {
    if (this.events.length > 0 && JSON.stringify(event) === JSON.stringify(this.events.at(-1))) {
      return;
    }

    if (this.events.length >= this.maxEvents) {
      this.events.shift();
    }
    this.events.push(event);

    clearTimeout(this.timer);
    this.timer = setTimeout(async () => {
      let lastOperation = new Map();
      for (const event of this.events.splice(0)) {
        switch (event.type) {
          case inputType.LSP:
            if (event.content.method === 'textDocument/didChange') {
              // ex. file:///project/root/Main.java -> Main.java
              const relativePath = event.content.params.textDocument.uri.slice(this.rootPath.length + 8);
              const line = event.content.params.contentChanges[0].range?.start.line ?? 0;
              lastOperation.set(event.type, `- Changed ${relativePath} around line ${line}`);
            }
            break;
          case inputType.GOAL_HUMAN:
          case inputType.GOAL_EXPLORER:
            lastOperation.set(event.type, event.content);
            break;
        }
      }

      const chunks = [];
      for (const [type, content] of lastOperation) {
        if (type === inputType.LSP) {
          chunks.push(`Driver operations:\n${content}`);
        } else {
          const diffResult = await this.diffFun(this.prevOperation.get(type), content);
          if (diffResult.trim()) chunks.push(`${type === inputType.GOAL_HUMAN ? 'Human' : 'Explorer'} goal change diff:\n${diffResult}`);
        }
        this.prevOperation.set(type, structuredClone(content));
      }
      if (chunks.length) for (const listener of this.listeners) listener(chunks.join('\n\n'));

    }, this.debounceMs);
  }
}

