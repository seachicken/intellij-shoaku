import { readdir, rename } from 'node:fs/promises';
import { join } from 'node:path';

export async function explorerGoalsPath(workspace) {
  const directory = join(workspace, '.shoaku');
  const path = join(directory, 'GOALS.md');
  const names = await readdir(directory);
  // Inspect actual casing so this also migrates case-insensitive filesystems.
  if (!names.includes('GOALS.md') && names.includes('goals.md')) {
    await rename(join(directory, 'goals.md'), path);
  }
  return path;
}
