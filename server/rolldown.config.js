import { defineConfig } from 'rolldown';

export default defineConfig({
  platform: 'node',
  input: 'src/index.js',
  output: {
    file: 'dist/shoaku-server.js',
  }
});
