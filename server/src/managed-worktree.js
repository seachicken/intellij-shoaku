import childProcess from 'node:child_process';

export function runWorktreeCommand(program, args, options) {
  return new Promise((resolve, reject) => {
    const child = childProcess.execFile(program, args, options, (error, stdout, stderr) => {
      if (error) reject(error);
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
