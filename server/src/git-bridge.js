import { readFile, writeFile, lstat } from 'node:fs/promises';
import { join, posix } from 'node:path';
import childProcess from 'node:child_process';
import { promisify } from 'node:util';
const execFile = promisify(childProcess.execFile);
const git = async (workspace, ...args) => (await execFile('git', ['-C', workspace, ...args], { timeout: 30000 })).stdout.trim();

export function validateCommitRequest(request) {
  if (!request || !/^[A-Za-z0-9_-]{1,100}$/.test(request.id || '') || request.action !== 'commit') throw new Error('Invalid Git request');
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
    // A persisted pending response prevents silently repeating a partially
    // completed operation after a server crash. A new request ID is required.
    await writeFile(responseFile, JSON.stringify({ id: request.id, status: 'pending', parent }));
    await git(workspace, '--literal-pathspecs', 'add', '--', ...request.files);
    await git(workspace, '--literal-pathspecs', 'commit', '--only', '-m', request.message, '--', ...request.files);
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
