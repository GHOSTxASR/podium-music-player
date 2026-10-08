# Podium website

The landing page for Podium: a static site with no build step and no dependencies.

- `index.html`, `styles.css`, `app.js`: the page.
- `assets/covers/`: original album-cover artwork made for this page (fictional artists and
  titles). Swap in art you hold the rights to by replacing these files and the `COVERS` list in
  `app.js`.
- `assets/img/`, `assets/video/`: real screenshots and screen recordings of Podium (test-tone
  library only).
- `assets/fonts/`: Archivo, Inter and Podium's display faces, unmodified, under the SIL Open Font
  License (`assets/fonts/licenses/`).

## Preview

```bash
npx --yes http-server site -p 5178 -c-1
```

## Deploy

GitHub Pages serves a branch from its root or `/docs` only, so publishing `site/` needs a Pages
workflow (Actions: upload `site/` as the Pages artifact) or a copy to a `gh-pages` branch. The
Download button points at `releases/latest` of the GitHub repository: publish a signed release
APK there.
