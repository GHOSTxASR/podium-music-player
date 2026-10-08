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

The production website is hosted on Vercel and deploys from the GitHub repository. The root
[`vercel.json`](../vercel.json) declares this dependency-free site as the deployment output, so
Vercel serves `site/` without an install or build step.

To connect it for the first time:

1. In Vercel, import `GHOSTxASR/podium-music-player` from GitHub.
2. Leave **Root Directory** at the repository root. Vercel reads `vercel.json`, selects the
   **Other** framework and serves `site/` as the output directory.
3. Deploy `main` to production. Pushes to `main` then redeploy the site; pull requests receive
   preview deployments.
4. The production site is [podium-music-player.vercel.app](https://podium-music-player.vercel.app/).
   Add a custom domain in Vercel if wanted, then replace that URL in the root README.
5. After verifying the Vercel production deployment, unpublish the old GitHub Pages deployment in
   the repository's **Settings → Pages** menu. Removing the workflow prevents future Pages builds,
   but it does not remove the already-published GitHub Pages site.

The download and source links intentionally stay on GitHub: the Download button uses
`releases/latest/download/podium.apk` and View on GitHub opens the repository. Publish each signed
`podium.apk` as a GitHub Release asset.
