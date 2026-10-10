/* Podium site. No dependencies. */
(() => {
  const reduce = matchMedia("(prefers-reduced-motion: reduce)").matches;
  const $ = (s, r = document) => r.querySelector(s);
  const $$ = (s, r = document) => [...r.querySelectorAll(s)];
  const clamp = (v, a, b) => Math.min(b, Math.max(a, v));
  const lerp = (a, b, t) => a + (b - a) * t;
  const smooth = (t) => t * t * (3 - 2 * t);
  const easeInOut = (t) => (t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2);
  const easeOut = (t) => 1 - Math.pow(1 - t, 2);
  /** Write a style only when it changes: most frames change nothing, so most frames cost nothing. */
  const put = (el, prop, value) => { const k = "_" + prop; if (el[k] !== value) { el[k] = value; el.style[prop] = value; } };

  /* ---------- one animation loop that sleeps when nothing on screen moves ---------- */
  const tasks = new Set();
  let running = false;
  function wake() { if (!running) { running = true; requestAnimationFrame(frame); } }
  function frame(now) {
    let busy = false;
    for (const task of tasks) if (task(now)) busy = true;
    if (busy) requestAnimationFrame(frame); else running = false;
  }

  /* ---------- visibility helper ---------- */
  const visible = new WeakMap();
  const io = new IntersectionObserver((entries) => {
    entries.forEach((e) => visible.set(e.target, e.isIntersecting));
    wake();
  }, { threshold: 0 });
  const watch = (el) => { visible.set(el, false); io.observe(el); };

  /* ---------- reveal on scroll ---------- */
  const revealIo = new IntersectionObserver((entries) => entries.forEach((e) => { if (e.isIntersecting) { e.target.classList.add("in"); revealIo.unobserve(e.target); } }), { threshold: 0.15, rootMargin: "0px 0px -6% 0px" });
  $$(".reveal").forEach((el) => revealIo.observe(el));

  /* ---------- the menu on narrow screens ---------- */
  const navEl = $(".nav"), navToggle = $("#navToggle");
  const setMenu = (open) => { navEl.classList.toggle("open", open); navToggle.setAttribute("aria-expanded", String(open)); navToggle.setAttribute("aria-label", open ? "Close menu" : "Menu"); };
  navToggle.addEventListener("click", () => setMenu(!navEl.classList.contains("open")));
  $$(".nav-links a").forEach((a) => a.addEventListener("click", () => setMenu(false)));
  addEventListener("keydown", (e) => { if (e.key === "Escape" && navEl.classList.contains("open")) { setMenu(false); navToggle.focus(); } });
  document.addEventListener("click", (e) => { if (navEl.classList.contains("open") && !navEl.contains(e.target)) setMenu(false); });

  /* ---------- glitter wordmarks: the cursor is the light ---------- */
  const glitters = $$(".glitter").map((el) => {
    const text = el.dataset.text || el.textContent;
    el.textContent = "";
    el.setAttribute("role", "img");
    el.setAttribute("aria-label", text);
    ["g-base", "g-flakes-a", "g-flakes-b", "g-sheen"].forEach((c) => {
      const s = document.createElement("span"); s.className = c; s.textContent = text; s.setAttribute("aria-hidden", "true");
      el.appendChild(s);
    });
    watch(el);
    return el;
  });
  let pointer = null, lastMove = -1e9, lastLight = 0;
  addEventListener("pointermove", (e) => { if (e.pointerType === "mouse" || e.pointerType === "pen") { pointer = { x: e.clientX, y: e.clientY }; lastMove = performance.now(); wake(); } }, { passive: true });
  tasks.add(function lightGlitter(now) {
    const shown = glitters.filter((el) => visible.get(el));
    if (!shown.length) return false;
    const idle = now - lastMove > 2500 || !pointer;
    if (idle && reduce) {
      // A still light; set once.
      if (lastLight) return false;
    } else if (idle && now - lastLight < 33) return true; // the slow drift needs no more than 30 steps a second
    lastLight = now;
    const rects = shown.map((el) => el.getBoundingClientRect()); // read everything, then write
    shown.forEach((el, i) => {
      const r = rects[i];
      let x, y;
      if (idle) {
        if (reduce) { x = r.width * 0.35; y = r.height * 0.4; }
        else { const t = now / 1000; x = r.width * (0.5 + 0.46 * Math.sin(t * 0.45)); y = r.height * (0.5 + 0.35 * Math.sin(t * 0.9 + 1)); }
      } else { x = pointer.x - r.left; y = pointer.y - r.top; }
      el.style.setProperty("--mx", x.toFixed(1) + "px");
      el.style.setProperty("--my", y.toFixed(1) + "px");
      // Flakes shift against each other as the light moves, so different ones catch it.
      el.style.setProperty("--px", (x * 0.18).toFixed(1) + "px");
      el.style.setProperty("--py", (y * 0.18).toFixed(1) + "px");
    });
    return !(idle && reduce);
  });

  /* ---------- covers ---------- */
  const COVERS = [
    ["neon_psalms", "Neon Psalms", "Velvet Static"], ["midnight_lilac", "Midnight Lilac", "Ava Moreau"], ["chrome_heart", "Chrome Heart", "Rico Vale"],
    ["slow_burn", "Slow Burn", "Kairo"], ["paper_planes", "Paper Planes in July", "The Lanterns"], ["overdrive", "Overdrive", "Nightfall 88"],
    ["cherry_static", "Cherry Static", "Mila Rae"], ["glasshouse", "Glasshouse", "North Atlas"], ["gold_rush", "Gold Rush", "Dezmond"],
    ["saint_monday", "Saint Monday", "Low Tide"], ["velour", "Velour", "June Avenue"], ["satellite_hearts", "Satellite Hearts", "Orbit Kids"],
    ["blue_hour", "Blue Hour", "Sol Marin"], ["wildflower", "Wildflower Tapes", "Hazel and Co."], ["mirrorball", "Mirrorball", "Disco Ghosts"],
    ["concrete_rose", "Concrete Rose", "Yung Atlas"], ["static_bloom", "Static Bloom", "Iris Kane"], ["eighty_eight", "88 MPH", "Vanta"],
  ].map(([f, title, artist]) => ({ src: `assets/covers/${f}.webp`, title, artist }));
  const N = COVERS.length;
  const coverAt = (k) => COVERS[((k % N) + N) % N];

  /* ---------- the flow: big at the browser's edges, shrinking into the Podium, Cover Flow on its screen ---------- */
  const hero = $(".hero"), flow = $("#flow"), heroDevice = $("#heroDevice"), innerFlow = $("#innerFlow"), innerStage = $("#innerStage");
  const innerTitle = $("#innerTitle"), innerArtist = $("#innerArtist");
  watch(hero);
  const G = {}; // geometry
  const pools = { inner: new Map(), outer: new Map() };

  function makeCover(parent, size) {
    const el = document.createElement("div"); el.className = "cover";
    const shade = document.createElement("div"); shade.className = "cover-shade";
    el.appendChild(shade); el._shade = shade;
    el.style.width = el.style.height = size + "px";
    parent.appendChild(el); return el;
  }
  function getCover(pool, parent, key, k, size) {
    let el = pool.get(key);
    if (!el) { el = makeCover(parent, size); pool.set(key, el); }
    if (el._k !== k) { el._k = k; el.style.backgroundImage = `url("${coverAt(k).src}")`; }
    el._used = true; return el;
  }

  function layout() {
    const hr = hero.getBoundingClientRect(), dr = heroDevice.getBoundingClientRect();
    G.hw = hr.width; G.dw = dr.width; G.dh = dr.height; G.dTop = dr.top - hr.top;
    heroDevice.style.setProperty("--dw", G.dw + "px");
    // Inside the screen: Podium's own Cover Flow arrangement (LibraryBrowse.kt CoverFlowScreen).
    G.iw = innerFlow.clientWidth; G.ih = innerFlow.clientHeight;
    G.ic = G.iw * 0.503; G.icx = G.iw / 2; G.icy = G.ih * 0.522;
    // Outside: one continuous strip on the same line as the covers on the screen.
    G.y = G.dTop + G.dh * 0.3116;
    flow.style.top = G.y + "px";
    G.s0 = G.ic * 0.84;                                   // the size a neighbour has on the screen
    G.smax = clamp(G.hw * 0.19, G.hw < 600 ? 96 : 150, 330); // the size it grows to at the edges
    G.outer = getComputedStyle(flow).display !== "none";  // short landscape phones show only the screen's own flow
    G.grow = 7;                                           // slots to grow to full size
    G.x0 = G.dw * 0.5 - G.s0 * 0.25;                      // emerges from behind the device's edge
    // x(t) = x0 + ∫ spacing(t) dt, spacing growing with size: tight near the device, open at the edges.
    const step = 0.02, table = [G.x0]; let x = G.x0;
    for (let t = step; t <= 60; t += step) {
      const g = easeOut(Math.min(1, t / G.grow)), size = lerp(G.s0, G.smax, g), frac = lerp(0.26, 0.66, g);
      x += frac * size * step; table.push(x);
    }
    G.table = table; G.step = step;
    // Covers keep one box size and scale, so moving them never changes layout.
    pools.inner.forEach((el) => { el.style.width = el.style.height = G.ic + "px"; });
    pools.outer.forEach((el) => { el.style.width = el.style.height = G.smax + "px"; });
    lastPos = NaN;
  }
  const xAt = (t) => { const i = t / G.step, a = Math.floor(i), f = i - a; const T = G.table; return a + 1 < T.length ? lerp(T[a], T[a + 1], f) : T[T.length - 1]; };

  let pos = 0, from = 0, to = 0, t0 = 0, lastPos = NaN;
  const STEP_EVERY = 2600, STEP_TIME = 1100, INNER_REACH = 2.6, OUTER_START = 2.2;

  function renderFlow() {
    if (pos === lastPos || !G.table) return;
    lastPos = pos;
    for (const p of Object.values(pools)) p.forEach((el) => (el._used = false));
    const base = Math.floor(pos);
    // inner (on the screen)
    for (let k = base - 4; k <= base + 5; k++) {
      const d = k - pos, ad = Math.abs(d);
      if (ad > INNER_REACH + 0.5) continue;
      const el = getCover(pools.inner, innerStage, ((k % 12) + 12) % 12, k, G.ic);
      const near = clamp(d, -1, 1);
      const x = ad <= 1 ? d * G.ic * 0.62 : Math.sign(d) * (G.ic * 0.62 + (ad - 1) * G.ic * 0.24);
      const s = 1 - 0.16 * Math.abs(near);
      put(el, "transform", `translate3d(${(G.icx + x - G.ic / 2).toFixed(2)}px, ${(G.icy - G.ic / 2).toFixed(2)}px, 0) rotateY(${(-near * 58).toFixed(2)}deg) scale(${s.toFixed(4)})`);
      put(el, "zIndex", String(100 - Math.round(ad * 10)));
      put(el._shade, "opacity", Math.min(0.5, ad * 0.12).toFixed(3));
      put(el, "opacity", clamp(INNER_REACH + 0.5 - ad, 0, 1).toFixed(3));
    }
    // outer (across the page), mirrored on both sides
    const reach = G.hw / 2 + G.smax;
    for (let k = base - 40; G.outer && k <= base + 40; k++) {
      const d = k - pos, ad = Math.abs(d);
      if (ad < OUTER_START) continue;
      const t = ad - OUTER_START, x = xAt(t);
      if (x - G.smax > reach) continue;
      const g = easeOut(Math.min(1, t / G.grow)), size = lerp(G.s0, G.smax, g), angle = lerp(62, 38, g);
      const el = getCover(pools.outer, flow, ((k % 90) + 90) % 90, k, G.smax);
      put(el, "transform", `translate3d(${(G.hw / 2 + Math.sign(d) * x - G.smax / 2).toFixed(2)}px, ${(-G.smax / 2).toFixed(2)}px, 0) rotateY(${(-Math.sign(d) * angle).toFixed(2)}deg) scale(${(size / G.smax).toFixed(4)})`);
      put(el, "zIndex", String(500 - Math.round(ad * 10)));
      put(el._shade, "opacity", lerp(0.55, 0.05, g).toFixed(3));
      put(el, "opacity", clamp((ad - OUTER_START) * 3, 0, 1).toFixed(3));
    }
    for (const p of Object.values(pools)) p.forEach((el) => { if (!el._used) put(el, "opacity", "0"); });
    const cur = coverAt(Math.round(pos));
    if (innerTitle._k !== Math.round(pos)) { innerTitle._k = Math.round(pos); innerTitle.textContent = cur.title; innerArtist.textContent = cur.artist; }
  }

  tasks.add(function tickFlow(now) {
    if (!visible.get(hero) || !G.table) return false;
    if (reduce) { renderFlow(); return false; }
    if (now - t0 >= STEP_EVERY) { from = to; to = from + 1; t0 = now; }
    const k = clamp((now - t0) / STEP_TIME, 0, 1);
    pos = lerp(from, to, easeInOut(k));
    renderFlow();
    return true;
  });

  /* ---------- the tour: one Podium, zoomed and labelled by scroll ---------- */
  const tour = $("#tour"), stage = $("#tourStage"), tourDevice = $("#tourDevice"), tourLines = $("#tourLines"), tourLabels = $("#tourLabels");
  // Points on the 1080 x 2272 device picture, with what they are.
  const CALLOUTS = {
    A: [[1000, 150, "Glass display", "Four themes, from Glass to Bone."], [736, 240, "Focus lens", "The lit row follows every turn."], [960, 720, "Live previews", "Artwork for wherever you're headed."]],
    B: [[540, 1632, "Menu", "One step back, always."], [726, 1742, "Click wheel", "Turn it. Feel every detent."], [540, 1850, "Center button", "Press to choose."], [540, 2064, "Play, pause, skip", "Right where your thumb is."]],
  };
  // Scroll progress (0..1 through the section) at which each part is pointed out.
  const SHOW = { A: [0.22, 0.27, 0.32], B: [0.57, 0.61, 0.65, 0.69] };
  const WINDOW = { A: [0.2, 0.45], B: [0.55, 0.82] };
  /** Hysteresis: a callout changes only once the scroll is clearly past its edge, so tiny scrolls never make it flap. */
  const HY = 0.012;
  const within = (on, p, lo, hi) => (on ? p > lo - HY && p < hi + HY : p >= lo + HY && p < hi - HY);
  let T = null, groups = { A: [], B: [] }, tourDirty = true;

  /**
   * The stage's own size (CSS: 100svh), never the window's: a phone's address bar showing or hiding
   * resizes the window, not the stage, so nothing is rebuilt mid-scroll.
   *  - wide:  desktop. The Podium on one side, every part labelled beside it.
   *  - stack: a phone held upright. The Podium framed above a caption band; one part at a time.
   *  - side:  a phone on its side. The Podium on the left, the caption on the right; one at a time.
   */
  function tourStops() {
    const W = stage.clientWidth, H = stage.clientHeight;
    const mode = W >= 900 && H >= 560 ? "wide" : W > H ? "side" : "stack";
    const nav = navEl.offsetHeight || 60;
    const gutter = parseFloat(getComputedStyle(navEl).paddingLeft) || 20; // --gutter, resolved to px
    const at = (s, ax, ay, sx, sy) => ({ s, x: sx - s * ax, y: sy - s * ay });
    let full, A, B, win, cap;
    if (mode === "wide") {
      // Height decides the zoom on wide screens; on squarer ones (tablets on their side) width caps it,
      // so the device always leaves room for the labels beside it.
      const fitW = (0.56 * W - gutter) / 1080;
      full = at(0.84 * H / 2272, 540, 1136, W * 0.64, H * 0.52);
      A = at(Math.min(0.8 * H / 1080, fitW), 540, 520, W * 0.33, H * 0.52);
      B = at(Math.min(0.95 * H / 1080, fitW), 540, 1850, W * 0.3, H * 0.5);
    } else if (mode === "stack") {
      // The window the Podium shows through, above the caption band.
      const band = Math.round(clamp(H * 0.28, 168, 230));
      win = { x: 0, y: nav + 12, w: W, h: H - band - nav - 12 };
      cap = { x: gutter, y: H - band + 34, w: W - gutter * 2, h: band - 34 };
      const cx = W / 2, cy = win.y + win.h / 2;
      full = at(win.h * 0.94 / 2272, 540, 1136, cx, cy);
      A = at(Math.min(W * 0.98 / 1080, win.h * 0.96 / 980), 540, 520, cx, cy);
      B = at(Math.min(W * 0.98 / 1080, win.h * 0.96 / 760), 540, 1850, cx, cy);
    } else {
      const split = Math.round(W * 0.55);
      win = { x: 0, y: nav + 8, w: split, h: H - nav - 16 };
      cap = { x: split + 28, y: nav + 8, w: W - split - 28 - gutter, h: H - nav - 16 };
      const cx = split / 2, cy = win.y + win.h / 2;
      full = at(win.h * 0.94 / 2272, 540, 1136, cx, cy);
      A = at(Math.min(split * 0.96 / 1080, win.h * 0.96 / 980), 540, 520, cx, cy);
      B = at(Math.min(split * 0.96 / 1080, win.h * 0.96 / 760), 540, 1850, cx, cy);
    }
    return { mode, W, H, full, A, B, win, cap, gutter };
  }

  function buildCallouts() {
    const next = tourStops();
    if (T && next.W === T.W && next.H === T.H && next.mode === T.mode) return; // the same stage: keep everything as it is
    T = next;
    stage.dataset.mode = T.mode;
    if (T.cap) {
      stage.style.setProperty("--cap-x", T.cap.x + "px"); stage.style.setProperty("--cap-y", T.cap.y + "px");
      stage.style.setProperty("--cap-w", T.cap.w + "px"); stage.style.setProperty("--cap-h", T.cap.h + "px");
      stage.style.setProperty("--win-bottom", (T.win.y + T.win.h) + "px"); stage.style.setProperty("--win-right", (T.win.x + T.win.w) + "px");
    }
    const was = { A: groups.A.map((c) => c.on), B: groups.B.map((c) => c.on) };
    tourLines.setAttribute("viewBox", `0 0 ${T.W} ${T.H}`);
    tourLines.replaceChildren(); tourLabels.replaceChildren(); groups = { A: [], B: [] };
    const NS = "http://www.w3.org/2000/svg";
    for (const key of ["A", "B"]) {
      const stop = T[key], list = CALLOUTS[key];
      list.forEach(([ax, ay, title, desc], i) => {
        const px = stop.x + stop.s * ax, py = stop.y + stop.s * ay;
        let lx, ly, d;
        if (T.mode === "wide") {
          // Beside the device's right edge (its bezel included), never over it.
          lx = Math.max(T.W * 0.62, stop.x + (1080 + 30) * stop.s + 48); ly = T.H * (key === "A" ? 0.24 + i * 0.2 : 0.18 + i * 0.18);
          d = `M ${px} ${py} L ${lx - 60} ${ly + 18} L ${lx - 12} ${ly + 18}`;
        } else if (T.mode === "stack") {
          // Down from the part to just above the caption, then along to where its text starts.
          lx = T.cap.x; ly = T.cap.y;
          const ey = ly - 14;
          d = `M ${px} ${py} L ${px} ${ey} L ${lx + 2} ${ey}`;
        } else {
          lx = T.cap.x; ly = T.cap.y + T.cap.h * 0.36;
          d = `M ${px} ${py} L ${lx - 40} ${ly + 16} L ${lx - 10} ${ly + 16}`;
        }
        const g = document.createElementNS(NS, "g");
        // A dark halo under the line keeps it visible over the light wheel without an SVG filter.
        const halo = document.createElementNS(NS, "path"); halo.setAttribute("d", d); halo.setAttribute("class", "halo");
        const path = document.createElementNS(NS, "path"); path.setAttribute("d", d);
        const dot = document.createElementNS(NS, "circle"); dot.setAttribute("class", "dot"); dot.setAttribute("cx", px); dot.setAttribute("cy", py); dot.setAttribute("r", 7);
        const ping = document.createElementNS(NS, "circle"); ping.setAttribute("class", "ping"); ping.setAttribute("cx", px); ping.setAttribute("cy", py); ping.setAttribute("r", 7);
        g.append(halo, path, ping, dot); tourLines.appendChild(g);
        const len = Math.ceil(path.getTotalLength()) + 1;
        for (const l of [halo, path]) { l.style.strokeDasharray = len; l.style.strokeDashoffset = len; }
        const lab = document.createElement("div"); lab.className = "tour-label";
        if (T.mode === "wide") { lab.style.left = lx + "px"; lab.style.top = ly + "px"; lab.style.width = Math.min(420, T.W - lx - T.gutter) + "px"; }
        else { lab.style.left = lx + "px"; lab.style.top = ly + "px"; lab.style.width = T.cap.w + "px"; }
        const h = document.createElement("h3"); h.textContent = title;
        const p = document.createElement("p"); p.textContent = desc;
        lab.append(h, p); tourLabels.appendChild(lab);
        groups[key].push({ g, lines: [halo, path], dot, lab, len, on: undefined });
      });
    }
    // A rebuild (rotation, a resized window) shows straight away what was showing; nothing replays.
    stage.classList.add("instant");
    for (const key of ["A", "B"]) groups[key].forEach((c, i) => { if (was[key][i]) setCallout(c, true); });
    renderTour();
    requestAnimationFrame(() => requestAnimationFrame(() => stage.classList.remove("instant")));
  }

  function setCallout(c, on) {
    if (c.on === on) return;
    c.on = on;
    c.g.classList.toggle("on", on); c.lab.classList.toggle("on", on);
    for (const l of c.lines) l.style.strokeDashoffset = on ? 0 : c.len;
  }

  function renderTour() {
    if (!T) return;
    const r = tour.getBoundingClientRect(), span = r.height - T.H;
    if (r.bottom < 0 || r.top > innerHeight) return;
    const p = clamp(-r.top / span, 0, 1);
    const seg = (a, b) => smooth(clamp((p - a) / (b - a), 0, 1));
    const mix = (s1, s2, k) => ({ s: lerp(s1.s, s2.s, k), x: lerp(s1.x, s2.x, k), y: lerp(s1.y, s2.y, k) });
    let cam;
    if (p < 0.2) cam = mix(T.full, T.A, seg(0.1, 0.2));
    else if (p < 0.55) cam = mix(T.A, T.B, seg(0.45, 0.55));
    else cam = mix(T.B, T.full, seg(0.82, 0.92));
    put(tourDevice, "transform", `translate3d(${cam.x.toFixed(2)}px, ${cam.y.toFixed(2)}px, 0) scale(${cam.s.toFixed(5)})`);
    stage.classList.toggle("away", p > 0.1 && p < 0.9);
    for (const key of ["A", "B"]) {
      const [a, b] = WINDOW[key], list = groups[key];
      if (T.mode === "wide") {
        // Each part appears in turn and stays until the group's window ends.
        list.forEach((c, i) => setCallout(c, within(c.on, p, SHOW[key][i], b)));
      } else {
        // One part at a time: the group's window shared out evenly between its parts.
        const w = (b - a) / list.length;
        list.forEach((c, k) => setCallout(c, within(c.on, p, a + k * w, a + (k + 1) * w)));
      }
    }
  }
  tasks.add(function tickTour() {
    if (tourDirty) { tourDirty = false; renderTour(); }
    return false;
  });
  addEventListener("scroll", () => { tourDirty = true; wake(); }, { passive: true });

  /* ---------- finishes ---------- */
  const FINISHES = [["silver", "Silver", "#d6d8db"], ["steel", "Steel gray", "#5a5f66"], ["burg", "Burgundy", "#5c1d2b"], ["glacier", "Glacier blue", "#b4cfdf"], ["titan", "Your colour", "#b8a48c"], ["carbon", "Carbon", "#1a1a1a"], ["bone", "Bone", "#e9e4d8"]];
  const stack = $("#finishStack"), chips = $("#finishChips");
  let finish = 0, finishHold = 0;
  FINISHES.forEach(([f, name, c], i) => {
    const im = document.createElement("img"); im.src = `assets/img/finish-${f}.webp`; im.alt = `Podium in ${name}`; im.loading = "lazy"; im.decoding = "async";
    im.width = 720; im.height = 1514; if (!i) im.classList.add("on"); stack.appendChild(im);
    const b = document.createElement("button"); b.className = "chip"; b.type = "button"; b.setAttribute("aria-pressed", String(!i));
    const sw = document.createElement("i"); sw.style.background = c;
    b.append(sw, name);
    b.addEventListener("click", () => { setFinish(i); finishHold = performance.now() + 8000; });
    chips.appendChild(b);
  });
  function setFinish(i) {
    finish = i;
    $$("img", stack).forEach((im, k) => im.classList.toggle("on", k === i));
    $$(".chip", chips).forEach((b, k) => b.setAttribute("aria-pressed", String(k === i)));
  }
  watch(stack);
  let finishNext = 0;
  tasks.add(function tickFinish(now) {
    if (reduce || !visible.get(stack)) return false;
    if (now < finishHold) return true;
    if (now >= finishNext) { if (finishNext) setFinish((finish + 1) % FINISHES.length); finishNext = now + 1800; }
    return true;
  });

  /* ---------- typefaces ---------- */
  const FACES = [["Instrument Sans", 600, "Classic"], ["Inter", 600, "Clean"], ["Space Grotesk", 600, "Industrial"], ["JetBrains Mono", 600, "Mono"], ["Pixelify Sans", 500, "Pixel"],
    ["Playfair Display Italic", 600, "Elegant italic"], ["Courier Prime", 400, "Typewriter"], ["Caveat", 600, "Handwritten"], ["Dancing Script", 600, "Script"], ["Pacifico", 400, "Bubbly"],
    ["Grenze Gotisch", 600, "Gothic"], ["Great Vibes", 400, "Calligraphy, for lyrics"], ["UnifrakturMaguntia", 400, "Fraktur, for lyrics"]];
  const typeDemo = $("#typeDemo"), typeWord = $(".type-word", typeDemo), typeName = $(".type-name", typeDemo);
  let face = -1, faceNext = 0; watch(typeDemo);
  tasks.add(function tickType(now) {
    if (!visible.get(typeDemo)) return false;
    if (now >= faceNext) {
      face = (face + 1) % FACES.length; faceNext = now + (reduce ? 2400 : 750);
      const [fam, w, name] = FACES[face];
      typeWord.style.fontFamily = `"${fam}", serif`; typeWord.style.fontWeight = w; typeName.textContent = name;
    }
    return true;
  });

  /* ---------- glitter panel: a mouse, or a finger dragged across it ---------- */
  const panel = $("#glitterPanel"), photo = $("#glitterPhoto"); let panelPointer = false; watch(panel);
  const lightPanel = (x, y) => {
    panel.style.setProperty("--lx", x.toFixed(1) + "px"); panel.style.setProperty("--ly", y.toFixed(1) + "px");
    panel.style.setProperty("--lpx", (x * 0.2).toFixed(1) + "px"); panel.style.setProperty("--lpy", (y * 0.2).toFixed(1) + "px");
  };
  panel.addEventListener("pointermove", (e) => {
    if (e.pointerType === "touch" && !e.buttons && !e.pressure) return;
    const r = photo.getBoundingClientRect();
    panelPointer = true; lightPanel(e.clientX - r.left, e.clientY - r.top);
  });
  panel.addEventListener("pointerdown", (e) => { const r = photo.getBoundingClientRect(); panelPointer = true; lightPanel(e.clientX - r.left, e.clientY - r.top); });
  const release = () => { panelPointer = false; wake(); };
  panel.addEventListener("pointerleave", release); panel.addEventListener("pointercancel", release);
  panel.addEventListener("pointerup", (e) => { if (e.pointerType !== "mouse") release(); });
  let panelLast = 0;
  tasks.add(function tickPanel(now) {
    if (panelPointer || reduce || !visible.get(panel)) return false;
    if (now - panelLast < 33) return true;
    panelLast = now;
    const r = photo.getBoundingClientRect(), t = now / 1000;
    lightPanel(r.width * (0.62 + 0.3 * Math.sin(t * 0.5)), r.height * (0.5 + 0.35 * Math.sin(t * 0.8)));
    return true;
  });

  /* ---------- videos: load and play only on screen ---------- */
  const vio = new IntersectionObserver((entries) => entries.forEach((e) => {
    const v = e.target;
    if (e.isIntersecting) { if (!v.src) { v.preload = "auto"; v.src = v.dataset.src; } if (!reduce) v.play().catch(() => {}); }
    else v.pause();
  }), { threshold: 0.2, rootMargin: "200px 0px" });
  $$("video[data-src]").forEach((v) => vio.observe(v));

  /* ---------- sizes: rebuilt only when a box really changes size ---------- */
  let heroSize = "";
  const ro = new ResizeObserver(() => {
    const hs = hero.clientWidth + "x" + hero.clientHeight + "x" + heroDevice.clientWidth;
    if (hs !== heroSize) { heroSize = hs; layout(); renderFlow(); }
    buildCallouts();
    if (innerWidth >= 900 && navEl.classList.contains("open")) setMenu(false);
    wake();
  });
  const ready = document.fonts && document.fonts.ready ? document.fonts.ready : Promise.resolve();
  const heroImg = heroDevice.querySelector("img");
  const imgReady = heroImg.decode ? heroImg.decode().catch(() => {}) : Promise.resolve();
  // The tour needs neither fonts nor the hero picture: it lays itself out straight away.
  ro.observe(stage);
  Promise.all([ready, imgReady]).then(() => {
    [hero, heroDevice].forEach((el) => ro.observe(el));
    t0 = performance.now();
    wake();
  });
  // The rest of the covers, once the page has what it needs to show.
  addEventListener("load", () => {
    const warm = () => COVERS.forEach((c) => { const im = new Image(); im.decoding = "async"; im.src = c.src; });
    "requestIdleCallback" in window ? requestIdleCallback(warm, { timeout: 2000 }) : setTimeout(warm, 600);
  });
})();
