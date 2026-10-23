import childProcess from 'node:child_process';
import { promisify } from 'node:util';
import { readFile, writeFile, mkdir, stat } from 'node:fs/promises';
import { join } from 'node:path';
import { inspectReviewMap } from './review-map.js';

const execFile = promisify(childProcess.execFile);
const git = async (workspace, ...args) => (await execFile('git', ['-C', workspace, ...args], { maxBuffer: 4 * 1024 * 1024, timeout: 30000 })).stdout;

export async function recordReviewBase(workspace, { existing = false } = {}) {
  if (existing) {
    const directory = await stat(workspace).catch(error => {
      if (error.code !== 'ENOENT') throw error;
      return null;
    });
    if (!directory?.isDirectory()) {
      const error = new Error(`Session workspace no longer exists: ${workspace}. Restore it before resuming this goal.`);
      error.code = 'SHOAKU_WORKSPACE_MISSING';
      throw error;
    }
  }
  const file = join(workspace, '.shoaku', 'base-revision');
  try { return (await readFile(file, 'utf8')).trim(); } catch (error) { if (error.code !== 'ENOENT') throw error; }
  // Older sessions already have a dedicated branch. Its oldest reflog entry
  // records branch creation; do not use today's HEAD as the starting revision.
  const revision = existing
    ? (await git(workspace, 'reflog', 'show', '--format=%H', 'HEAD')).trim().split('\n').at(-1)
    : (await git(workspace, 'rev-parse', 'HEAD')).trim();
  if (!/^[a-f0-9]{40,64}$/.test(revision || '')) throw new Error('Cannot establish session starting revision');
  await mkdir(join(workspace, '.shoaku'), { recursive: true });
  await writeFile(file, revision + '\n', { flag: 'wx' });
  await refreshCommitGuide(workspace);
  return revision;
}

export async function inspectExistingCommitReview(workspace) {
  await recordReviewBase(workspace, { existing: true });
  return inspectCommitReview(workspace);
}

async function sessionCommits(workspace) {
  const base = (await readFile(join(workspace, '.shoaku', 'base-revision'), 'utf8')).trim();
  if (!/^[a-f0-9]{40,64}$/.test(base)) throw new Error('Invalid session starting revision');
  await git(workspace, 'merge-base', '--is-ancestor', base, 'HEAD');
  const log = await git(workspace, 'log', '--reverse', '--format=%H%x09%s', `${base}..HEAD`);
  const commits = log.trim().split('\n').filter(Boolean).map(line => {
    const [hash, ...subject] = line.split('\t');
    return { hash, subject: subject.join('\t') };
  });
  return { base, commits };
}

// Serialize index refreshes and read Git inside that queue so an older review
// inspection cannot overwrite an index published after a fixup.
const guideRefreshes = new Map();
export async function refreshCommitGuide(workspace) {
  const refresh = (guideRefreshes.get(workspace) || Promise.resolve()).catch(() => {}).then(async () => {
    const { base, commits } = await sessionCommits(workspace);
    const guide = [
      '# Implementation commits', '',
      `Starting revision: \`${base}\``, '',
      'Current session commit order. Explanations may still be in progress; completion is checked separately.', '',
      '| Commit | Purpose | Explanation |', '| --- | --- | --- |',
      ...commits.map(commit => `| \`${commit.hash.slice(0, 12)}\` | ${commit.subject.replaceAll('|', '\\|')} | [Read](commits/${commit.hash}.md) |`), '',
      '[Design](RESULTS.md)', ''
    ].join('\n');
    const path = join(workspace, '.shoaku', 'COMMITS.md');
    const previous = await readFile(path, 'utf8').catch(error => { if (error.code !== 'ENOENT') throw error; return null; });
    if (previous !== guide) await writeFile(path, guide);
  });
  guideRefreshes.set(workspace, refresh);
  try { await refresh; }
  finally { if (guideRefreshes.get(workspace) === refresh) guideRefreshes.delete(workspace); }
}

export async function inspectCommitReview(workspace) {
  const { commits } = await sessionCommits(workspace);
  const changed = (await git(workspace, 'diff', '--name-only', '-z', 'HEAD')).split('\0').filter(Boolean);
  const added = (await git(workspace, 'ls-files', '--others', '--exclude-standard', '-z')).split('\0').filter(Boolean);
  const uncommitted = [...new Set([...changed, ...added])].filter(path => !path.startsWith('.shoaku/'));
  const missingDocs = [];
  const invalidMaps = [];
  for (const commit of commits) {
    try {
      const doc = await readFile(join(workspace, '.shoaku', 'commits', `${commit.hash}.md`), 'utf8');
      if (!doc.includes(commit.hash)) missingDocs.push(commit.hash);
    } catch (error) { if (error.code !== 'ENOENT') throw error; missingDocs.push(commit.hash); }
    try { await inspectReviewMap(workspace, commit, git); }
    catch (error) { invalidMaps.push({ hash: commit.hash, error: error.code === 'ENOENT' ? 'Design-to-code map is missing' : error.message }); }
  }
  const results = await readFile(join(workspace, '.shoaku', 'RESULTS.md'), 'utf8').catch(error => {
    if (error.code !== 'ENOENT') throw error;
    return '';
  });
  const ready = uncommitted.length === 0 && missingDocs.length === 0 && invalidMaps.length === 0 && results.trim().length > 0;
  const detail = uncommitted.length ? `${uncommitted.length} implementation files remain uncommitted.`
    : !results.trim() ? 'The design report is missing.'
    : missingDocs.length ? `${missingDocs.length} commit explanations are missing or do not identify the actual commit.`
    : invalidMaps.length ? `${invalidMaps.length} commits need valid design-to-code links: ${invalidMaps[0].error}`
    : commits.length ? `${commits.length} implementation commits are ready for review.` : 'No implementation changes; the design report is available.';
  await refreshCommitGuide(workspace);
  return { ready, detail, commits, uncommitted, missingDocs, invalidMaps };
}
