import childProcess from 'node:child_process';
import { promisify } from 'node:util';
import { readdir, readFile, writeFile, mkdir, lstat } from 'node:fs/promises';
import { join } from 'node:path';

const execFile = promisify(childProcess.execFile);
const git = async (workspace, ...args) => (await execFile('git', ['-C', workspace, ...args], {
  timeout: 30000, maxBuffer: 4 * 1024 * 1024
})).stdout.trim();
const marker = '<!-- shoaku:repository-commits -->';
const snapshotPath = workspace => join(workspace, '.shoaku', 'repositories.json');
const code = text => {
  const length = [...text.matchAll(/`+/g)].reduce((max, match) => Math.max(max, match[0].length + 1), 3);
  const fence = '`'.repeat(length);
  return `${fence}text\n${text}\n${fence}`;
};

// Deliberately inspect immediate child repositories only; do not crawl source,
// dependencies, nested submodules, or symlinks outside the workspace.
export async function recordWorkspaceRepositories(workspace) {
  const repositories = [];
  for (const entry of (await readdir(workspace, { withFileTypes: true })).sort((a, b) => a.name.localeCompare(b.name))) {
    if (!entry.isDirectory() || entry.name.startsWith('.')) continue;
    const directory = join(workspace, entry.name);
    const metadata = await lstat(join(directory, '.git')).catch(error => {
      if (error.code !== 'ENOENT') throw error;
      return null;
    });
    if (!metadata) continue;
    let base;
    try { base = await git(directory, 'rev-parse', '--verify', '--quiet', 'HEAD'); }
    catch (error) { if (error.code !== 1) throw error; base = null; }
    repositories.push({ path: entry.name, base });
  }
  await mkdir(join(workspace, '.shoaku'), { recursive: true });
  await writeFile(snapshotPath(workspace), JSON.stringify({ kind: 'direct', capturedAt: new Date().toISOString(), repositories }, null, 2));
  return repositories;
}

export async function inspectWorkspaceReport(workspace) {
  const reportPath = join(workspace, '.shoaku', 'RESULTS.md');
  const previous = await readFile(reportPath, 'utf8').catch(error => {
    if (error.code !== 'ENOENT') throw error;
    return '';
  });
  const report = previous.split(marker)[0].trimEnd();
  if (!report.trim()) return { ready: false, detail: 'Write the implementation report in .shoaku/RESULTS.md.' };
  const { repositories, capturedAt } = JSON.parse(await readFile(snapshotPath(workspace), 'utf8'));
  const sections = [marker, '', '## Repository commits', '',
    `Observed since the recorded baseline (${capturedAt}); commits may include work by other sessions or people. Automatic commits are disabled.`, ''];
  if (!repositories.length) sections.push('No immediate child Git repositories were found at session start.', '');
  for (const repository of repositories) {
    // Snapshot data is persisted under the user workspace; never allow paths or
    // revisions from it to escape the workspace or become Git options.
    if (!repository.path || /[/\\\0]/.test(repository.path) || ['.', '..'].includes(repository.path) ||
        (repository.base !== null && !/^[a-f0-9]{40,64}$/.test(repository.base))) {
      throw new Error('Invalid workspace repository snapshot');
    }
    const directory = join(workspace, repository.path);
    sections.push('### Repository', '', code(repository.path), '');
    try {
      const info = await lstat(directory);
      if (!info.isDirectory() || info.isSymbolicLink()) throw new Error('Repository directory is unavailable');
      let head;
      try { head = await git(directory, 'rev-parse', '--verify', '--quiet', 'HEAD'); }
      catch (error) { if (error.code !== 1) throw error; head = null; }
      if (repository.base) await git(directory, 'merge-base', '--is-ancestor', repository.base, head || 'HEAD');
      const hashes = head ? (await git(directory, 'rev-list', '--reverse', repository.base ? `${repository.base}..${head}` : head, '--')).split('\n').filter(Boolean) : [];
      sections.push(hashes.length ? `${hashes.length} commits since start.` : 'No commits since start.', '');
      for (const hash of hashes) {
        sections.push(`#### ${hash}`, '', code(await git(directory, 'show', '--no-ext-diff', '--no-color', '--no-renames', '--format=%B', '--stat', hash, '--')), '');
      }
      const changes = await git(directory, 'status', '--short', '--untracked-files=normal');
      sections.push(changes ? 'Uncommitted changes (not included in the commits above):' : 'No uncommitted changes.', '');
      if (changes) sections.push(code(changes), '');
    } catch (error) {
      // A removed repository or rewritten history must not hide other repos or
      // silently substitute a new baseline and claim there were no commits.
      sections.push('Could not inspect this repository. Its history may have changed or it may be unavailable.', '', code(error.message), '');
    }
  }
  const next = `${report}\n\n${sections.join('\n')}`;
  if (next !== previous) await writeFile(reportPath, next);
  return { ready: true, detail: 'Implementation report and repository commit summaries are ready.' };
}
