# Frontend browser regressions

```sh
npm install
npx playwright install chromium
npm test
npm run test:appearance
npm run test:layout
```

For an existing system browser, set `CHROMIUM_PATH=/usr/bin/chromium`.
`PLAYWRIGHT_MODULE` can point to a shared Playwright module instead of the local installation.

Optional screenshots:

```sh
THEME_SCREENSHOTS=/tmp/opencode/theme-qa npm run test:appearance
LAYOUT_SCREENSHOTS=/tmp/opencode/album-layout npm run test:layout
```

The scripts start and close a local Vite server and an isolated browser. API calls
are mocked. They do not enqueue music, change rooms on the server, or deploy.

- `appearance.mjs` mounts the actual `App.vue` with socket connection stubbed.
  Checks previews vs saved state, cancel/Escape, focus restoration and Tab containment,
  invalid colors and imports, export, reload, system preference changes, cross-tab sync,
  cover extraction/fallback, all theme entry points, and 320/390/768/1200px bounds.
- `search-modal-layout.mjs` mounts the actual search modal with long text and checks
  sidebar/result width, unclipped controls, and search-toolbar bounds in five states,
  seven widths and both modes.
