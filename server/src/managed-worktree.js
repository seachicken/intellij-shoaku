import childProcess from 'node:child_process';
import { lstat } from 'node:fs/promises';
import { dirname, join, resolve } from 'node:path';

export function runWorktreeCommand(program, args, options) {
  return new Promise((resolve, reject) => {
    const child = childProcess.execFile(program, args, options, (error, stdout, stderr) => {
      if (error) reject(Object.assign(error, { stdout, stderr }));
      else resolve({ stdout, stderr });
    });
    // Codex appends piped stdin to its prompt; close it so initialization
    // cannot wait forever for input that the server will never send.
    child.stdin?.end();
  });
}
export const worktreeBootstrapPrompt = [
  'Initialize the Codex-managed linked worktree for a Shoaku Explorer session.',
  'Do not implement the user goal, modify source files, create commits, or change configuration.',
  'Run git status --short and git rev-parse --show-toplevel to verify that the linked checkout is usable. Do not write the Git index: Shoaku handles commits through its host Git bridge.',
  'If it succeeds, respond with exactly SHOAKU_WORKTREE_READY. If it fails, respond with SHOAKU_WORKTREE_BLOCKED and the concrete error.',
  'Do not request wider filesystem access, delete lock files, disable signing, or retry outside the sandbox.'
].join('\n');

export function parseWorktreeBootstrap(stdout) {
  let threadId;
  let finalAnswer;
  for (const line of stdout.split('\n').filter(Boolean)) {
    let event;
    try { event = JSON.parse(line); } catch { continue; }
    if (event.type === 'thread.started') threadId = event.thread_id;
    if (event.type === 'item.completed' && event.item?.type === 'agent_message') finalAnswer = event.item.text;
  }
  if (!threadId || finalAnswer?.trim() !== 'SHOAKU_WORKTREE_READY') {
    throw new Error(`Codex managed worktree initialization failed${threadId ? ` (thread ${threadId})` : ''}: ${finalAnswer || 'no successful initialization response'}`);
  }
  return threadId;
}

export async function createManagedWorktree(sourcePath, run = runWorktreeCommand) {
  const args = ['--enable', 'worktrees', '--ask-for-approval', 'never', '--sandbox', 'workspace-write', 'exec', '--worktree', '--json', '--cd', sourcePath, worktreeBootstrapPrompt];
  const { stdout } = await run('codex', args, { timeout: 120_000, maxBuffer: 4 * 1024 * 1024 });
  return parseWorktreeBootstrap(stdout);
}

// Only an explicit non-repository result selects direct execution. Permission,
// missing executable, and corrupt repository errors must remain visible.
export async function isGitWorkspace(sourcePath, run = runWorktreeCommand) {
  try {
    await run('git', ['-C', sourcePath, 'rev-parse', '--show-toplevel'], {
      timeout: 30_000, env: { ...process.env, LC_ALL: 'C' }
    });
    return true;
  } catch (error) {
    if (error.code === 128 && /not a git repository/i.test(error.stderr || '')) {
      // Corrupt Git metadata can produce the same error as a plain directory.
      // Never silently switch such a checkout to direct editing.
      for (let directory = resolve(sourcePath); ; directory = dirname(directory)) {
        const metadata = await lstat(join(directory, '.git')).catch(statError => {
          if (statError.code !== 'ENOENT') throw statError;
          return null;
        });
        if (metadata) throw error;
        if (dirname(directory) === directory) return false;
      }
    }
    throw error;
  }
}
