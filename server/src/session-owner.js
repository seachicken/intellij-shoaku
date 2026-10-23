import { createServer, createConnection } from 'node:net';
import { mkdir, rmdir, unlink } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { join } from 'node:path';
import { tmpdir } from 'node:os';

// The listening socket is the lease: the OS releases it when a window exits.
export class SessionOwner {
  leases = new Map();

  async claim(sessionPath) {
    if (this.leases.has(sessionPath)) return;
    const key = createHash('sha256').update(sessionPath).digest('hex').slice(0, 32);
    const path = join(tmpdir(), `shoaku-owner-${key}.sock`);
    const server = createServer(socket => socket.end());
    const listen = () => new Promise((resolve, reject) => {
      const failed = error => { server.off('listening', ready); reject(error); };
      const ready = () => { server.off('error', failed); resolve(); };
      server.once('error', failed);
      server.once('listening', ready);
      server.listen(path);
    });
    try {
      await listen();
    } catch (error) {
      if (error.code !== 'EADDRINUSE') throw error;
      // Only one process may remove a crashed owner's stale socket.
      const recovery = `${path}.recovery`;
      await mkdir(recovery).catch(error => {
        if (error.code === 'EEXIST') throw new Error('Session ownership is being recovered. Please retry.');
        throw error;
      });
      try {
        const alive = await new Promise((resolve, reject) => {
          const socket = createConnection(path);
          socket.once('connect', () => { socket.destroy(); resolve(true); });
          socket.once('error', error => {
            if (['ECONNREFUSED', 'ENOENT'].includes(error.code)) resolve(false);
            else reject(error);
          });
        });
        if (alive) throw new Error('This session is running in another IntelliJ window. Open its details in that window.');
        await unlink(path).catch(error => { if (error.code !== 'ENOENT') throw error; });
        await listen();
      } finally {
        await rmdir(recovery);
      }
    }
    this.leases.set(sessionPath, server);
  }

  async close() {
    await Promise.all([...this.leases.values()].map(server => new Promise(resolve => server.close(resolve))));
    this.leases.clear();
  }
}
