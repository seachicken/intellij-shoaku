import { readFile, writeFile, lstat } from 'node:fs/promises';
import { join, posix } from 'node:path';
import childProcess from 'node:child_process';
import { promisify } from 'node:util';
const execFile = promisify(childProcess.execFile);
const git = async (workspace, ...args) => (await execFile('git', ['-C', workspace, ...args], { timeout: 30000 })).stdout.trim();

export function validateCommitRequest(request) {
  if (!request || !/^[A-Za-z0-9_-]{1,100}$/.test(request.id || '') || !['commit', 'fixup'].includes(request.action)) throw new Error('Invalid Git request');
  if (request.action === 'fixup' && !/^[a-f0-9]{40}$/.test(request.target || '')) throw new Error('Fixup requires a full target commit hash');
  if (typeof request.message !== 'string' || !request.message.trim() || request.message.length > 10000) throw new Error('Commit message is required');
  if (!Array.isArray(request.files) || !request.files.length || request.files.length > 500) throw new Error('Explicit commit files are required');
  for (const file of request.files) {
    if (typeof file !== 'string' || !file || file === '.' || posix.isAbsolute(file) || file.includes('\\') || file.includes('\0') || posix.normalize(file) !== file || file.split('/').some(part => ['..', '.git', '.shoaku', '.codex', '.agents'].includes(part))) {
      throw new Error('Commit files must be relative source-file paths, excluding Git and agent metadata');
    }
  }
}

export async function ensureExplorerBranch(workspace, branch) {
  let current;
  try { current = await git(workspace, 'symbolic-ref', '--quiet', '--short', 'HEAD'); } catch (error) { if (error.code !== 1) throw error; }
  if (current === branch) return;
  if (current) throw new Error(`Explorer is on unexpected branch ${current}`);
  await git(workspace, 'switch', '-c', branch);
}

export async function processGitRequest(workspace, branch) {
  const requestFile = join(workspace, '.shoaku', 'git-request.json');
  const responseFile = join(workspace, '.shoaku', 'git-response.json');
  const requestText = await readFile(requestFile, 'utf8').catch(error => { if (error.code !== 'ENOENT') throw error; return null; });
  if (!requestText) return null;
  let request;
  try { request = JSON.parse(requestText); } catch { return null; } // tolerate an in-flight writer
  const previous = await readFile(responseFile, 'utf8').then(JSON.parse).catch(error => { if (error.code !== 'ENOENT') throw error; return null; });
  if (previous?.id === request.id) return null; // persisted acknowledgement prevents duplicate commits
  let response;
  try {
    validateCommitRequest(request);
    const current = await git(workspace, 'symbolic-ref', '--quiet', '--short', 'HEAD');
    if (current !== branch) throw new Error('Refusing to commit outside this session branch');
    for (const file of request.files) {
      const info = await lstat(join(workspace, file)).catch(error => { if (error.code !== 'ENOENT') throw error; return null; });
      if (info?.isDirectory()) throw new Error('List individual files instead of directories');
    }
    const parent = await git(workspace, 'rev-parse', 'HEAD');
    let base;
    if (request.action === 'fixup') {
      base = (await readFile(join(workspace, '.shoaku', 'base-revision'), 'utf8')).trim();
      if (!/^[a-f0-9]{40}$/.test(base)) throw new Error('Invalid session base revision');
      await git(workspace, 'merge-base', '--is-ancestor', base, parent);
      const commits = (await git(workspace, 'rev-list', `${base}..HEAD`)).split('\n');
      if (!commits.includes(request.target)) throw new Error('Fixup target must belong to this session after its base revision');
      if (await git(workspace, 'rev-list', '--merges', `${base}..HEAD`)) throw new Error('Cannot autosquash a session with merge commits');
      // Rebase must never carry unrelated staged or unstaged edits along.
      const changed = new Set((await git(workspace, 'diff', '--name-only', '-z', 'HEAD')).split('\0').filter(Boolean));
      if ([...changed].some(file => !request.files.includes(file))) throw new Error('Commit unrelated changes before requesting a fixup');
    }
    // A persisted pending response prevents silently repeating a partially
    // completed operation after a server crash. A new request ID is required.
    await writeFile(responseFile, JSON.stringify({ id: request.id, status: 'pending', parent }));
    await git(workspace, '--literal-pathspecs', 'add', '--', ...request.files);
    await git(workspace, '--literal-pathspecs', 'commit', '--only', ...(request.action === 'fixup' ? [`--fixup=${request.target}`] : ['-m', request.message]), '--', ...request.files);
    if (request.action === 'fixup') {
      try {
        await execFile('git', ['-C', workspace, 'rebase', '--interactive', '--autosquash', '--no-update-refs', '--no-autostash', base], {
          timeout: 30000,
          env: { ...process.env, GIT_SEQUENCE_EDITOR: 'true', GIT_EDITOR: 'true' }
        });
      } catch (error) {
        await git(workspace, 'rebase', '--abort').catch(() => {});
        throw new Error(`Autosquash failed; the fixup commit is preserved. Inspect the branch before retrying: ${error.message}`);
      }
    }
    response = { id: request.id, status: 'complete', parent, hash: await git(workspace, 'rev-parse', 'HEAD') };
  } catch (error) {
    response = { id: request?.id || null, status: 'failed', error: error.message };
  }
  await writeFile(responseFile, JSON.stringify(response, null, 2));
  return response;
}

export function watchGitRequests(workspace, branch, onResult) {
  let busy = false;
  const timer = setInterval(async () => {
    if (busy) return;
    busy = true;
    try {
      const result = await processGitRequest(workspace, branch);
      if (result) await onResult(result);
    } catch (error) { await onResult({ status: 'failed', error: error.message }); }
    finally { busy = false; }
  }, 1000);
  timer.unref();
  return () => clearInterval(timer);
}
