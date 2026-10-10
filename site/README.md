# Podium website

The landing page for Podium: a static site with no build step and no dependencies.

- `index.html`, `styles.css`, `app.js`: the page.
- `assets/covers/`: original album-cover artwork made for this page (fictional artists and
  titles), as WebP. Swap in art you hold the rights to by replacing these files and the `COVERS`
  list in `app.js`.
- `assets/img/`, `assets/video/`: real screenshots and screen recordings of Podium (test-tone
  library only). Images are WebP; each video has a `-poster.webp` frame that shows until it loads.
  `assets/img/device-silver.jpg` stays a JPEG for link previews (`og:image`).
- `assets/fonts/`: Archivo, Inter and Podium's display faces as WOFF2, under the SIL Open Font
  License (`assets/fonts/licenses/`).
  - Inter keeps every Latin letter and its variable weights.
  - Archivo is only ever drawn at weight 900 and width 125%, so it is a single instance of that.
  - The other faces only draw the word "Podium" in the typeface demo, so they hold just those
    letters. Change the demo word and you must rebuild them with more letters.
  - Dancing Script, Playfair Display and UnifrakturMaguntia have a Reserved Font Name. Under the
    OFL they may not be modified under their names, so they are only WOFF2-compressed, with their
    font data unchanged (OFL FAQ 2.2).

## How the page stays smooth on phones

- **The tour (`#tour`).** It lays itself out from its own stage, which is `100svh` tall. That
  height does not change when a phone's address bar shows or hides. Callouts are rebuilt only when
  the stage really changes size (a rotation, a resized window), and they keep their state when
  they are.
  - On phones held upright, the Podium shows through a window above a caption band, one part at a
    time.
  - On phones on their side, the window and the caption sit side by side.
  - On desktops, every part is labelled beside the device.
- **Animation.** One `requestAnimationFrame` loop runs only while something on screen moves, and
  styles are written only when they change. The covers keep one box size and move by transform
  alone, so they never trigger layout.
- **Touch screens.** The background smoke and grain hold still, the nav is solid instead of
  blurred, and reveals skip the blur.

## Preview

```bash
npx --yes http-server site -p 5178 -c-1
```

## Deploy

The production website is hosted on Vercel and deploys from the GitHub repository. The root
[`vercel.json`](../vercel.json) declares this dependency-free site as the deployment output, so
Vercel serves `site/` without an install or build step.

Files under `assets/` are cached by browsers for a week. When you change an asset, give it a new
file name rather than replacing it in place. The page itself, `styles.css` and `app.js`
revalidate on every visit.

To connect it for the first time:

1. In Vercel, import `GHOSTxASR/podium-music-player` from GitHub.
2. Leave **Root Directory** at the repository root. Vercel reads `vercel.json`, selects the
   **Other** framework and serves `site/` as the output directory.
3. Deploy `main` to production. Pushes to `main` then redeploy the site; pull requests receive
   preview deployments.
4. The production site is [podium-music-player.vercel.app](https://podium-music-player.vercel.app/).
   Add a custom domain in Vercel if wanted, then replace that URL in the root README and in the
   `og:` tags of `index.html`.
5. After verifying the Vercel production deployment, unpublish the old GitHub Pages deployment in
   the repository's **Settings → Pages** menu. Removing the workflow prevents future Pages builds,
   but it does not remove the already-published GitHub Pages site.

The download and source links intentionally stay on GitHub: the Download button uses
`releases/latest/download/podium.apk` and View on GitHub opens the repository. Publish each signed
`podium.apk` as a GitHub Release asset.
