import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

// Java 側のサーバ（既定のポート）
const API_SERVER = 'http://127.0.0.1:7171';

export default defineConfig({
  plugins: [react()],
  // リバースプロキシの配下（/proxy/7171/ など）でも動くよう、資源は相対パスで参照する
  base: './',
  build: {
    // mvn package のときに jar の web/ 配下へ入るよう、target/classes に直接出力する
    outDir: '../target/classes/web',
    emptyOutDir: true,
  },
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: API_SERVER,
        changeOrigin: true,
        // サーバは自分以外の Origin を拒否するため、開発サーバ経由ではサーバ自身の Origin に置き換える
        headers: { origin: API_SERVER },
      },
    },
  },
  test: {
    environment: 'node',
  },
});
