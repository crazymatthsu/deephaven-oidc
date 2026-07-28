import { defineConfig } from 'vite';

// Mirrors the build shape of the reference @deephaven/js-plugin-auth-keycloak@0.2.0 bundle:
// a single unminified CommonJS file whose only require()s are modules the Deephaven web UI's
// plugin loader provides via its module shim. @azure/msal-browser is bundled IN (plugins must be
// self-contained; the server's CSP/offline posture cannot rely on a CDN).
//
// Deliberately NOT imported anywhere in this plugin: @deephaven/log — the published shim's
// interop for it broke the Keycloak plugin on current web UIs (see
// docs/auth-keycloak-js-plugin-fix.md); plain console logging sidesteps that entire bug class.
export default defineConfig({
  build: {
    lib: {
      entry: 'src/index.js',
      formats: ['cjs'],
      fileName: () => 'index.js',
    },
    outDir: 'dist',
    minify: false,
    rollupOptions: {
      external: ['react', '@deephaven/auth-plugins', '@deephaven/jsapi-components'],
    },
  },
});
