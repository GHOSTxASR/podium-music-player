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

  /* ---------- visibility helper ---------- */
  const visible = new WeakMap();
  const io = new IntersectionObserver((entries) => entries.forEach((e) => visible.set(e.target, e.isIntersecting)), { threshold: 0 });
  const watch = (el) => { visible.set(el, false); io.observe(el); };

  /* ---------- reveal on scroll ---------- */
  const revealIo = new IntersectionObserver((entries) => entries.forEach((e) => { if (e.isIntersecting) { e.target.classList.add("in"); revealIo.unobserve(e.target); } }), { threshold: 0.15, rootMargin: "0px 0px -6% 0px" });
  $$(".reveal").forEach((el) => revealIo.observe(el));

  /* ---------- glitter wordmarks: the cursor is the light ---------- */
  const glitters = $$(".glitter").map((el) => {
    const text = el.dataset.text || el.textContent;
    el.textContent = "";
    el.setAttribute("aria-label", text);
    ["g-base", "g-flakes-a", "g-flakes-b", "g-sheen"].forEach((c, i) => {
      const s = document.createElement("span"); s.className = c; s.textContent = text;
      if (i) s.setAttribute("aria-hidden", "true"); else s.setAttribute("aria-hidden", "true");
      el.appendChild(s);
    });
    watch(el);
    return el;
  });
  let pointer = null, lastMove = -1e9;
  addEventListener("pointermove", (e) => { if (e.pointerType === "mouse" || e.pointerType === "pen") { pointer = { x: e.clientX, y: e.clientY }; lastMove = performance.now(); } }, { passive: true });
  function lightGlitter(now) {
    const idle = now - lastMove > 2500 || !pointer;
    for (const el of glitters) {
      if (!visible.get(el)) continue;
      const r = el.getBoundingClientRect();
      let x, y;
      if (idle) {
        if (reduce) { x = r.width * 0.35; y = r.height * 0.4; }
        else { const t = now / 1000; x = r.width * (0.5 + 0.46 * Math.sin(t * 0.45)); y = r.height * (0.5 + 0.35 * Math.sin(t * 0.9 + 1)); }
      } else { x = pointer.x - r.left; y = pointer.y - r.top; }
      el.style.setProperty("--mx", x + "px");
      el.style.setProperty("--my", y + "px");
      // Flakes shift against each other as the light moves, so different ones catch it.
      el.style.setProperty("--px", (x * 0.18).toFixed(1) + "px");
      el.style.setProperty("--py", (y * 0.18).toFixed(1) + "px");
    }
  }

  /* ---------- covers ---------- */
  const COVERS = [
    ["neon_psalms", "Neon Psalms", "Velvet Static"], ["midnight_lilac", "Midnight Lilac", "Ava Moreau"], ["chrome_heart", "Chrome Heart", "Rico Vale"],
    ["slow_burn", "Slow Burn", "Kairo"], ["paper_planes", "Paper Planes in July", "The Lanterns"], ["overdrive", "Overdrive", "Nightfall 88"],
    ["cherry_static", "Cherry Static", "Mila Rae"], ["glasshouse", "Glasshouse", "North Atlas"], ["gold_rush", "Gold Rush", "Dezmond"],
    ["saint_monday", "Saint Monday", "Low Tide"], ["velour", "Velour", "June Avenue"], ["satellite_hearts", "Satellite Hearts", "Orbit Kids"],
    ["blue_hour", "Blue Hour", "Sol Marin"], ["wildflower", "Wildflower Tapes", "Hazel and Co."], ["mirrorball", "Mirrorball", "Disco Ghosts"],
    ["concrete_rose", "Concrete Rose", "Yung Atlas"], ["static_bloom", "Static Bloom", "Iris Kane"], ["eighty_eight", "88 MPH", "Vanta"],
  ].map(([f, title, artist]) => ({ src: `assets/covers/${f}.jpg`, title, artist }));
  const N = COVERS.length;
  const coverAt = (k) => COVERS[((k % N) + N) % N];

  /* ---------- the flow: big at the browser's edges, shrinking into the Podium, Cover Flow on its screen ---------- */
  const hero = $(".hero"), flow = $("#flow"), heroDevice = $("#heroDevice"), innerFlow = $("#innerFlow"), innerStage = $("#innerStage");
  const innerTitle = $("#innerTitle"), innerArtist = $("#innerArtist");
  watch(hero);
  const G = {}; // geometry
  const pools = { inner: new Map(), outer: new Map() };

  function makeCover(parent) {
    const el = document.createElement("div"); el.className = "cover";
    const shade = document.createElement("div"); shade.style.cssText = "position:absolute;inset:0;background:#000;pointer-events:none";
    el.appendChild(shade); el._shade = shade; el.style.left = "0"; el.style.top = "0";
    parent.appendChild(el); return el;
  }
  function getCover(pool, parent, key, k) {
    let el = pool.get(key);
    if (!el) { el = makeCover(parent); pool.set(key, el); }
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
    G.smax = clamp(G.hw * 0.19, 150, 330);                // the size it grows to at the edges
    G.grow = 7;                                           // slots to grow to full size
    G.x0 = G.dw * 0.5 - G.s0 * 0.25;                      // emerges from behind the device's edge
    // x(t) = x0 + ∫ spacing(t) dt, spacing growing with size: tight near the device, open at the edges.
    const step = 0.02, table = [G.x0]; let x = G.x0;
    for (let t = step; t <= 60; t += step) {
      const g = easeOut(Math.min(1, t / G.grow)), size = lerp(G.s0, G.smax, g), frac = lerp(0.26, 0.66, g);
      x += frac * size * step; table.push(x);
    }
    G.table = table; G.step = step;
  }
  const xAt = (t) => { const i = t / G.step, a = Math.floor(i), f = i - a; const T = G.table; return a + 1 < T.length ? lerp(T[a], T[a + 1], f) : T[T.length - 1]; };

  let pos = 0, from = 0, to = 0, t0 = 0;
  const STEP_EVERY = 2600, STEP_TIME = 1100, INNER_REACH = 2.6, OUTER_START = 2.2;

  function renderFlow() {
    for (const p of Object.values(pools)) p.forEach((el) => (el._used = false));
    const base = Math.floor(pos);
    // inner (on the screen)
    for (let k = base - 4; k <= base + 5; k++) {
      const d = k - pos, ad = Math.abs(d);
      if (ad > INNER_REACH + 0.5) continue;
      const el = getCover(pools.inner, innerStage, ((k % 12) + 12) % 12, k);
      const near = clamp(d, -1, 1);
      const x = ad <= 1 ? d * G.ic * 0.62 : Math.sign(d) * (G.ic * 0.62 + (ad - 1) * G.ic * 0.24);
      const s = 1 - 0.16 * Math.abs(near);
      el.style.width = el.style.height = G.ic + "px";
      el.style.transform = `translate3d(${G.icx + x - G.ic / 2}px, ${G.icy - G.ic / 2}px, 0) rotateY(${-near * 58}deg) scale(${s})`;
      el.style.zIndex = String(100 - Math.round(ad * 10));
      el._shade.style.opacity = (Math.min(0.5, ad * 0.12)).toFixed(3);
      el.style.opacity = String(clamp(INNER_REACH + 0.5 - ad, 0, 1));
    }
    // outer (across the page), mirrored on both sides
    const reach = G.hw / 2 + G.smax;
    for (let k = base - 40; k <= base + 40; k++) {
      const d = k - pos, ad = Math.abs(d);
      if (ad < OUTER_START) continue;
      const t = ad - OUTER_START, x = xAt(t);
      if (x - G.smax > reach) continue;
      const g = easeOut(Math.min(1, t / G.grow)), size = lerp(G.s0, G.smax, g), angle = lerp(62, 38, g);
      const el = getCover(pools.outer, flow, ((k % 90) + 90) % 90, k);
      el.style.width = el.style.height = size + "px";
      el.style.transform = `translate3d(${G.hw / 2 + Math.sign(d) * x - size / 2}px, ${-size / 2}px, 0) rotateY(${-Math.sign(d) * angle}deg)`;
      el.style.zIndex = String(500 - Math.round(ad * 10));
      el._shade.style.opacity = (lerp(0.55, 0.05, g)).toFixed(3);
      el.style.opacity = String(clamp((ad - OUTER_START) * 3, 0, 1));
    }
    for (const p of Object.values(pools)) p.forEach((el) => { if (!el._used) el.style.opacity = "0"; });
    const cur = coverAt(Math.round(pos));
    if (innerTitle._k !== Math.round(pos)) { innerTitle._k = Math.round(pos); innerTitle.textContent = cur.title; innerArtist.textContent = cur.artist; }
  }

  function tickFlow(now) {
    if (!visible.get(hero)) return;
    if (!reduce) {
      if (now - t0 >= STEP_EVERY) { from = to; to = from + 1; t0 = now; }
      const k = clamp((now - t0) / STEP_TIME, 0, 1);
      pos = lerp(from, to, easeInOut(k));
    }
    renderFlow();
  }

  /* ---------- the tour: one Podium, zoomed and labelled by scroll ---------- */
  const tour = $("#tour"), tourDevice = $("#tourDevice"), tourLines = $("#tourLines"), tourLabels = $("#tourLabels"), tourHead = $("#tourHead");
  const CALLOUTS = {
    A: [[1000, 150, "Glass display", "Four themes, from Glass to Bone."], [736, 240, "Focus lens", "The lit row follows every turn."], [960, 720, "Live previews", "Artwork for wherever you're headed."]],
    B: [[540, 1632, "Menu", "One step back, always."], [726, 1742, "Click wheel", "Turn it. Feel every detent."], [540, 1850, "Center button", "Press to choose."], [540, 2064, "Play, pause, skip", "Right where your thumb is."]],
  };
  const SHOW = { A: [0.22, 0.27, 0.32], B: [0.57, 0.61, 0.65, 0.69] };
  const WINDOW = { A: [0.2, 0.45], B: [0.55, 0.82] };
  let T = {}, groups = { A: [], B: [] };

  function tourStops() {
    const vw = innerWidth, vh = innerHeight, wide = vw >= 900;
    const at = (s, ax, ay, sx, sy) => ({ s, x: sx - s * ax, y: sy - s * ay });
    const full = wide ? at(0.84 * vh / 2272, 540, 1136, vw * 0.64, vh * 0.52) : at(0.7 * vh / 2272, 540, 1136, vw * 0.5, vh * 0.56);
    const A = wide ? at(0.8 * vh / 1080, 540, 520, vw * 0.33, vh * 0.52) : at(Math.min(vw * 0.94 / 1016, 0.6 * vh / 1080), 540, 470, vw * 0.5, vh * 0.34);
    const B = wide ? at(0.95 * vh / 1080, 540, 1850, vw * 0.3, vh * 0.5) : at(Math.min(vw * 0.94 / 1080, 0.55 * vh / 1080), 540, 1850, vw * 0.5, vh * 0.34);
    return { full, A, B, wide, vw, vh };
  }
  function buildCallouts() {
    T = tourStops();
    tourLines.setAttribute("viewBox", `0 0 ${T.vw} ${T.vh}`);
    tourLines.innerHTML = ""; tourLabels.innerHTML = ""; groups = { A: [], B: [] };
    const NS = "http://www.w3.org/2000/svg";
    for (const key of ["A", "B"]) {
      const stop = T[key], list = CALLOUTS[key];
      list.forEach(([ax, ay, title, desc], i) => {
        const px = stop.x + stop.s * ax, py = stop.y + stop.s * ay;
        let lx, ly;
        if (T.wide) { lx = T.vw * 0.62; ly = T.vh * (key === "A" ? 0.24 + i * 0.2 : 0.18 + i * 0.18); }
        else { lx = parseFloat(getComputedStyle(document.body).getPropertyValue("--gutter")) || 20; ly = T.vh * 0.66 + i * 64; }
        const g = document.createElementNS(NS, "g");
        const path = document.createElementNS(NS, "path");
        const ex = T.wide ? lx - 60 : px, ey = T.wide ? ly + 18 : ly - 10;
        path.setAttribute("d", T.wide ? `M ${px} ${py} L ${ex} ${ey} L ${lx - 12} ${ey}` : `M ${px} ${py} L ${ex} ${ey} L ${lx + 4} ${ey}`);
        const dot = document.createElementNS(NS, "circle"); dot.setAttribute("class", "dot"); dot.setAttribute("cx", px); dot.setAttribute("cy", py); dot.setAttribute("r", 7);
        const ping = document.createElementNS(NS, "circle"); ping.setAttribute("class", "ping"); ping.setAttribute("cx", px); ping.setAttribute("cy", py); ping.setAttribute("r", 7);
        g.append(path, ping, dot); tourLines.appendChild(g);
        const len = path.getTotalLength(); path.style.strokeDasharray = len; path.style.strokeDashoffset = len; dot.style.opacity = 0;
        const lab = document.createElement("div"); lab.className = "tour-label"; lab.style.left = lx + "px"; lab.style.top = (T.wide ? ly : ly - 2) + "px";
        lab.innerHTML = `<h3>${title}</h3><p>${desc}</p>`; tourLabels.appendChild(lab);
        groups[key].push({ g, path, dot, lab, len });
      });
    }
  }
  function setCallout(c, on) {
    if (c.on === on) return; c.on = on;
    c.g.classList.toggle("on", on); c.lab.classList.toggle("on", on);
    c.path.style.strokeDashoffset = on ? 0 : c.len; c.dot.style.opacity = on ? 1 : 0;
  }
  function renderTour() {
    const r = tour.getBoundingClientRect(), span = r.height - innerHeight;
    if (r.bottom < 0 || r.top > innerHeight) return;
    const p = clamp(-r.top / span, 0, 1);
    const seg = (a, b) => smooth(clamp((p - a) / (b - a), 0, 1));
    let cam;
    const mix = (s1, s2, k) => ({ s: lerp(s1.s, s2.s, k), x: lerp(s1.x, s2.x, k), y: lerp(s1.y, s2.y, k) });
    if (p < 0.2) cam = mix(T.full, T.A, seg(0.1, 0.2));
    else if (p < 0.55) cam = mix(T.A, T.B, seg(0.45, 0.55));
    else cam = mix(T.B, T.full, seg(0.82, 0.92));
    tourDevice.style.transform = `translate3d(${cam.x}px, ${cam.y}px, 0) scale(${cam.s})`;
    tourHead.classList.toggle("away", p > 0.1 && p < 0.9);
    for (const key of ["A", "B"]) {
      const [a, b] = WINDOW[key];
      groups[key].forEach((c, i) => setCallout(c, p >= SHOW[key][i] && p < b && p > a));
    }
  }

  /* ---------- finishes ---------- */
  const FINISHES = [["silver", "Silver", "#d6d8db"], ["steel", "Steel gray", "#5a5f66"], ["burg", "Burgundy", "#5c1d2b"], ["glacier", "Glacier blue", "#b4cfdf"], ["titan", "Your colour", "#b8a48c"], ["carbon", "Carbon", "#1a1a1a"], ["bone", "Bone", "#e9e4d8"]];
  const stack = $("#finishStack"), chips = $("#finishChips");
  let finish = 0, finishHold = 0;
  FINISHES.forEach(([f, name, c], i) => {
    const im = document.createElement("img"); im.src = `assets/img/finish-${f}.jpg`; im.alt = `Podium in ${name}`; im.loading = "lazy"; if (!i) im.classList.add("on"); stack.appendChild(im);
    const b = document.createElement("button"); b.className = "chip"; b.type = "button"; b.setAttribute("aria-pressed", String(!i));
    b.innerHTML = `<i style="background:${c}"></i>${name}`;
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
  function tickFinish(now) {
    if (reduce || !visible.get(stack) || now < finishHold) return;
    if (now >= finishNext) { if (finishNext) setFinish((finish + 1) % FINISHES.length); finishNext = now + 1800; }
  }

  /* ---------- typefaces ---------- */
  const FACES = [["Instrument Sans", 600, "Classic"], ["Inter", 600, "Clean"], ["Space Grotesk", 600, "Industrial"], ["JetBrains Mono", 600, "Mono"], ["Pixelify Sans", 500, "Pixel"],
    ["Playfair Display Italic", 600, "Elegant italic"], ["Courier Prime", 400, "Typewriter"], ["Caveat", 600, "Handwritten"], ["Dancing Script", 600, "Script"], ["Pacifico", 400, "Bubbly"],
    ["Grenze Gotisch", 600, "Gothic"], ["Great Vibes", 400, "Calligraphy, for lyrics"], ["UnifrakturMaguntia", 400, "Fraktur, for lyrics"]];
  const typeDemo = $("#typeDemo"), typeWord = $(".type-word", typeDemo), typeName = $(".type-name", typeDemo);
  let face = -1, faceNext = 0; watch(typeDemo);
  function tickType(now) {
    if (!visible.get(typeDemo) || now < faceNext) return;
    face = (face + 1) % FACES.length; faceNext = now + (reduce ? 2400 : 750);
    const [fam, w, name] = FACES[face];
    typeWord.style.fontFamily = `"${fam}", serif`; typeWord.style.fontWeight = w; typeName.textContent = name;
  }

  /* ---------- glitter panel ---------- */
  const panel = $("#glitterPanel"); let panelPointer = false; watch(panel);
  panel.addEventListener("pointermove", (e) => {
    const r = panel.getBoundingClientRect(), x = e.clientX - r.left, y = e.clientY - r.top;
    panelPointer = true;
    panel.style.setProperty("--lx", x + "px"); panel.style.setProperty("--ly", y + "px");
    panel.style.setProperty("--lpx", (x * 0.2).toFixed(1) + "px"); panel.style.setProperty("--lpy", (y * 0.2).toFixed(1) + "px");
  });
  panel.addEventListener("pointerleave", () => (panelPointer = false));
  function tickPanel(now) {
    if (panelPointer || reduce || !visible.get(panel)) return;
    const r = panel.getBoundingClientRect(), t = now / 1000;
    const x = r.width * (0.62 + 0.3 * Math.sin(t * 0.5)), y = r.height * (0.5 + 0.35 * Math.sin(t * 0.8));
    panel.style.setProperty("--lx", x + "px"); panel.style.setProperty("--ly", y + "px");
    panel.style.setProperty("--lpx", (x * 0.2).toFixed(1) + "px"); panel.style.setProperty("--lpy", (y * 0.2).toFixed(1) + "px");
  }

  /* ---------- videos: load and play only on screen ---------- */
  const vio = new IntersectionObserver((entries) => entries.forEach((e) => {
    const v = e.target;
    if (e.isIntersecting) { if (!v.src) v.src = v.dataset.src; if (!reduce) v.play().catch(() => {}); }
    else v.pause();
  }), { threshold: 0.2 });
  $$("video[data-src]").forEach((v) => vio.observe(v));

  /* ---------- loop ---------- */
  function onResize() { layout(); buildCallouts(); renderFlow(); renderTour(); }
  addEventListener("resize", onResize);
  addEventListener("scroll", renderTour, { passive: true });
  const ready = document.fonts && document.fonts.ready ? document.fonts.ready : Promise.resolve();
  const imgReady = heroDevice.querySelector("img").decode ? heroDevice.querySelector("img").decode().catch(() => {}) : Promise.resolve();
  Promise.all([ready, imgReady]).then(() => {
    onResize();
    t0 = performance.now();
    const frame = (now) => { lightGlitter(now); tickFlow(now); tickFinish(now); tickType(now); tickPanel(now); requestAnimationFrame(frame); };
    requestAnimationFrame(frame);
  });
})();
