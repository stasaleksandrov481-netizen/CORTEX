"use strict";

const CortexWave = (() => {
  const tau = Math.PI * 2;
  const duration = milliseconds => milliseconds * 2;
  const clamp = value => Math.max(0, Math.min(1, value));
  const smooth = value => {
    const t = clamp(value);
    return t * t * (3 - 2 * t);
  };

  let field, ctx, host, observer, grid, shimmer, pulseSource;
  let width = 0, height = 0, frame = 0, previous = 0, age = 0, clock = .92;
  let kind = "home", mode = "idle", quality = "balanced", still = false, running = false;
  let populated = false, energy = .42, level = 0, lastLevel = 0, amplitude = .42;
  let average = 16.7, slowFrames = 0, adaptive = 0, lastPaint = 0;

  function arrange() {
    const rank = Math.min(2, ["full", "balanced", "simple"].indexOf(quality) + adaptive);
    const rows = [30, 22, 14][Math.max(0, rank)];
    const spacing = [7, 9, 13][Math.max(0, rank)];
    const columns = Math.ceil(width * 1.46 / spacing);
    const count = rows * columns;
    const noise = Float32Array.from({length: count}, (_, i) => ((i * 73 + (i % 37) * 31) % 211) / 211);
    grid = { rows, columns, noise, x: new Float32Array(columns), y: new Float32Array(columns), slope: new Float32Array(columns) };
    shimmer = ctx.createLinearGradient(0, 0, width, 0);
    for (const [position, alpha] of [[0,0], [.21,.42], [.48,.82], [.70,.57], [.92,.17], [1,0]]) {
      shimmer.addColorStop(position, `rgba(255,255,255,${alpha})`);
    }
  }

  function paint(elapsed) {
    if (!grid) return;
    const blend = still ? 1 : 1 - Math.exp(-elapsed / 260);
    if (clock - lastLevel > .18) level *= Math.exp(-elapsed / 380);
    const target = mode === "voice" ? .27 + .69 * level
      : mode === "thinking" ? .68 : mode === "focus" ? .32 : .43;
    energy += (target - energy) * blend;
    amplitude += ((kind === "chat" ? .77 : 1) - amplitude) * blend;
    const { rows, columns, noise, x, y, slope } = grid;
    const scale = Math.min(height, width * 1.56);
    const alpha = (still ? 1 : .34 + .66 * smooth(age / .86)) * (populated ? .28 : 1);
    const horizon = height * (kind === "chat" ? .52 : .56);
    const pulseAge = pulseSource ? clamp((clock - pulseSource.time) / 1.05) : 1;
    const pulseDistance = Math.hypot(width, height) * .8 * pulseAge;
    const pulseGain = (1 - pulseAge) * (1 - pulseAge);
    const a = .68 * clock, b = .34 * clock;

    ctx.clearRect(0, 0, width, height);
    ctx.lineCap = "round";
    for (let row = 0; row < rows; row++) {
      const depth = row / (rows - 1);
      const lane = depth - .5;
      const perspective = 1.24 + lane * .51;
      const reach = .113 + energy * .083;
      const visibility = Math.sin(Math.PI * depth) ** .66 * alpha;
      let oldY = horizon;
      for (let column = 0; column < columns; column++) {
        const u = column / (columns - 1) - .5;
        const phase = u * 7.1 - a + depth * 1.72;
        const secondary = u * 11.7 + b + 1.6 - depth * 3.5;
        const swell = Math.sin(phase);
        const swell2 = Math.sin(secondary);
        const detail = Math.sin(u * 18.6 - a * 1.4 + depth * 5.9);
        const fold = (swell * reach + swell2 * .042 + detail * .013) * amplitude;
        const spread = lane * (.28 + .075 * swell2);
        const roll = u * (.14 + .03 * Math.cos(b));
        const drift = Math.cos(phase) * reach * .29 + .022 * Math.cos(secondary);
        x[column] = width * (.5 + u * perspective) + scale * (drift - lane * .12);
        let cy = horizon + scale * (fold + spread + roll);
        if (pulseSource && pulseAge < 1) {
          const distance = Math.hypot(x[column] - pulseSource.x, cy - pulseSource.y) - pulseDistance;
          const envelope = Math.max(0, 1 - Math.abs(distance) / 69);
          cy += Math.sin(distance * .093) * envelope * pulseGain * 11;
        }
        y[column] = cy;
        slope[column] = cy - oldY;
        oldY = cy;
      }
      const highlight = Math.exp(-((depth - .53 - .12 * Math.sin(b)) ** 2) / .024);
      if (row % 3 === 0) {
        ctx.strokeStyle = shimmer;
        ctx.globalAlpha = visibility * (.10 + .34 * highlight);
        ctx.lineWidth = .65 + .58 * highlight;
        ctx.beginPath();
        ctx.moveTo(x[0], y[0]);
        for (let column = 1; column < columns; column++) ctx.lineTo(x[column], y[column]);
        ctx.stroke();
      }
      ctx.fillStyle = "#fff";
      ctx.globalAlpha = visibility * (.14 + .31 * highlight);
      ctx.beginPath();
      for (let column = 1; column < columns; column++) {
        const px = x[column], py = y[column];
        if (px < -2 || px > width + 2) continue;
        const fleck = noise[row * columns + column];
        const specular = clamp(.72 + slope[column] * -.07);
        const size = (.31 + .62 * fleck + .49 * highlight) * (.62 + .5 * specular);
        ctx.moveTo(px + size, py);
        ctx.arc(px, py, size, 0, tau);
      }
      ctx.fill();
    }
    ctx.globalAlpha = 1;
  }

  function resize() {
    if (!host || !ctx) return;
    const w = host.clientWidth, h = host.clientHeight;
    if (!w || !h) return;
    const ratio = Math.min(devicePixelRatio || 1, quality === "full" ? 1.65 : quality === "simple" ? 1 : 1.3,
      Math.sqrt(580_000 / (w * h)));
    const pixelW = Math.max(1, Math.round(w * ratio)), pixelH = Math.max(1, Math.round(h * ratio));
    if (w === width && h === height && field.width === pixelW && field.height === pixelH && grid) return;
    width = w;
    height = h;
    field.width = pixelW;
    field.height = pixelH;
    ctx.setTransform(pixelW / w, 0, 0, pixelH / h, 0, 0);
    arrange();
    paint(16.7);
  }

  function tick(now) {
    if (!running || still || document.hidden || window.cortexAppVisible === false) return;
    const elapsed = previous ? Math.min(70, now - previous) : 16.7;
    previous = now;
    age += elapsed / 1000;
    clock += elapsed / 1000;
    average += (elapsed - average) * .06;
    slowFrames = average > 24 ? slowFrames + 1 : Math.max(0, slowFrames - 2);
    if (slowFrames > 38 && adaptive < 2) {
      adaptive++;
      slowFrames = 0;
      arrange();
    }
    if (pulseSource && clock - pulseSource.time >= 1.05) pulseSource = null;
    const interval = mode === "voice" || quality === "full" ? 0 : quality === "simple" ? 31 : 22;
    if (now - lastPaint >= interval) {
      paint(lastPaint ? Math.min(70, now - lastPaint) : elapsed);
      lastPaint = now;
    }
    frame = requestAnimationFrame(tick);
  }

  function stop() {
    running = false;
    cancelAnimationFrame(frame);
    observer?.disconnect();
    previous = lastPaint = 0;
  }

  function visibilityChanged() {
    cancelAnimationFrame(frame);
    previous = lastPaint = 0;
    if (!document.hidden && window.cortexAppVisible !== false && running && !still) frame = requestAnimationFrame(tick);
  }

  function mount(next, options) {
    const sameHost = running && host === next;
    const qualityChanged = quality !== options.quality;
    const motionChanged = still !== options.still;
    ({ kind, quality, still, mode, populated } = options);
    if (!field) {
      field = document.createElement("canvas");
      field.className = "wave-canvas";
      field.setAttribute("aria-hidden", "true");
      ctx = field.getContext("2d", { alpha: true });
      if (typeof ResizeObserver !== "undefined") observer = new ResizeObserver(resize);
    }
    if (!ctx) return;
    if (sameHost) {
      if (qualityChanged) { adaptive = slowFrames = 0; grid = null; resize(); }
      if (motionChanged) visibilityChanged();
      if (still) paint(16.7);
      return;
    }
    stop();
    host = next;
    grid = pulseSource = null;
    age = level = 0;
    amplitude = kind === "chat" ? .77 : 1;
    running = true;
    host.append(field);
    observer?.observe(host);
    resize();
    visibilityChanged();
  }

  function state(next, value = 0) {
    mode = next;
    level = clamp(value);
    lastLevel = clock;
    if (still) paint(16.7);
  }

  function pulse(x, y) {
    if (running && !still && grid) pulseSource = { x, y, time: clock };
  }

  document.addEventListener("visibilitychange", visibilityChanged);
  addEventListener("cortex-app-visibility", visibilityChanged);
  return { duration, mount, stop, state, pulse };
})();
