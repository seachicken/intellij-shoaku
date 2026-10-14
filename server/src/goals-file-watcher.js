import { watch } from 'node:fs/promises';
import { basename, dirname } from 'node:path';

// Watch the directory so atomic saves do not strand the watcher on an old inode.
export class GoalsFileWatcher {
  controller;

  async watch(filePath, onChange, onError) {
    this.controller?.abort();
    const controller = this.controller = new AbortController();
    if (!filePath) return;
    try {
      for await (const event of watch(dirname(filePath), { signal: controller.signal })) {
        if (controller.signal.aborted) break;
        if (event.filename != null && String(event.filename) !== basename(filePath)) continue;
        await onChange(filePath);
      }
    } catch (error) {
      if (error.name !== 'AbortError') onError(error);
    }
  }

  close() {
    this.controller?.abort();
  }
}
