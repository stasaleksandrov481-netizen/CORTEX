"use strict";

const CortexWave = (() => {
  const duration = milliseconds => milliseconds * 2;
  const clamp = (value, low = 0, high = 1) => Math.max(low, Math.min(high, value));
  const smooth = value => {
    const t = clamp(value);
    return t * t * (3 - 2 * t);
  };
  const tau = Math.PI * 2;
  const spacings = [8.7, 10.2, 12.0, 14.5, 17];
  const colors = Array.from({ length: 14 }, (_, index) =>
    `rgba(${166 + index * 6},${179 + index * 5},${208 + index * 3},${((index + 1) / 16).toFixed(3)})`);

  let field, ctx, host, observer, grid, pulseGrid;
  let width = 0, height = 0, frame = 0, previous = 0, age = 0, clock = 1.7;
  let kind = "home", mode = "idle", quality = "balanced", still = false, running = false;
  let level = 0, energy = .55, chatMix = 0, textMix = 0, populated = false, ripple;
  let average = 16.7, slow = 0, adaptive = 0;

  function arrange() {
    const rank = quality === "full" ? 0 : quality === "simple" ? 3 : 1;
    const ambient = kind === "home" || kind === "chat" || kind === "splash";
    const gap = spacings[Math.min(4, rank + adaptive)] * (ambient ? 1 : .78);
    const columns = Math.max(1, Math.ceil(width / gap));
    const rows = Math.max(1, Math.ceil(height / gap));
    const left = (width - (columns - 1) * gap) / 2;
    const top = (height - (rows - 1) * gap) / 2;
    const x = Float32Array.from({ length: columns }, (_, column) => left + column * gap);
    const y = Float32Array.from({ length: rows }, (_, row) => top + row * gap);
    const crest = new Float32Array(columns);
    const drift = new Float32Array(columns);
    const grain = new Float32Array(rows * columns);
    const radial = new Float32Array(rows * columns);
    const px = new Float32Array(rows * columns), py = new Float32Array(rows * columns);
    const tones = new Int8Array(rows * columns), radii = new Float32Array(rows * columns);
    for (let row = 0, i = 0; row < rows; row++) {
      for (let column = 0; column < columns; column++, i++) {
        grain[i] = ((row * 41 + column * 17 + row * column * 7) % 53) / 53;
        radial[i] = Math.hypot(x[column] - width * .5, y[row] - height * .44);
      }
    }
    grid = { x, y, crest, drift, grain, radial, px, py, tones, radii };
    preparePulse();
  }

  function preparePulse() {
    if (!grid || !ripple) { pulseGrid = null; return; }
    const originX = ripple.x * width, originY = ripple.y * height;
    pulseGrid = new Float32Array(grid.tones.length);
    for (let row = 0, i = 0; row < grid.y.length; row++) {
      for (let column = 0; column < grid.x.length; column++, i++) {
        pulseGrid[i] = Math.hypot(grid.x[column] - originX, grid.y[row] - originY);
      }
    }
  }

  function paint(elapsed) {
    if (!grid || !width || !height) return;
    const launch = kind === "splash";
    const ambient = kind === "home" || kind === "chat";
    const blending = still ? 1 : 1 - Math.exp(-elapsed / 240);
    const targetEnergy = mode === "voice" ? .22 + level * .88 :
      mode === "speaking" ? .72 + Math.sin(clock * 7.2) * .16 : mode === "thinking" ? .79 :
      mode === "focus" ? .36 : .58;
    energy += (targetEnergy - energy) * blending;
    chatMix += ((kind === "chat" ? 1 : 0) - chatMix) * blending;
    textMix += ((populated ? 1 : 0) - textMix) * blending;

    const { x, y, crest, drift, grain, radial, tones, radii, px, py } = grid;
    const t = clock, short = Math.min(width, height);
    const bloom = ambient ? .89 - chatMix * .18 - textMix * .45 : 1;
    const entrance = still ? 1 : smooth(age / 1.3);
    const progress = still ? 1 : clamp(age / 3.05);
    const launchFront = short * (.06 + .88 * (1 - (1 - progress) ** 1.65));
    const launchRidge = Math.max(19, short * .135);
    const rippleAge = ripple ? clamp((clock - ripple.since) / .60) : 1;
    const rippleFront = Math.hypot(width, height) * (1 - (1 - rippleAge) ** 1.6);
    const rippleGain = (1 - rippleAge) * .83;
    const midline = ambient ? .55 - chatMix * .13 : .51;

    for (let column = 0; column < x.length; column++) {
      const u = x[column] / width;
      const flow = u * 6.35 - t * 1.18 + .51 * Math.sin(u * 3.6 + t * .6);
      crest[column] = midline + Math.sin(flow) * (.083 + energy * .10) +
        Math.sin(u * 12.1 + t * .68 + .8) * .036;
      drift[column] = Math.cos(u * 8.1 + t * 1.35);
    }
    for (let row = 0, i = 0; row < y.length; row++) {
      const v = y[row] / height;
      const rowDrift = Math.sin(v * 11.6 + t * 1.42);
      const edge = .28 + .72 * smooth(Math.min(v, 1 - v) * 5.2);
      for (let column = 0; column < x.length; column++, i++) {
        const g = grain[i];
        const d = v - crest[column];
        const primary = Math.max(0, 1 - Math.abs(d) * (ambient ? 4.3 : 4.8));
        const echo = Math.max(0, 1 - Math.abs(d + .228) * 6.3);
        const halo = Math.max(0, 1 - Math.abs(d - .23) * 5.1);
        let light = edge * (.082 + g * .10 +
          primary * primary * (.31 + .26 * energy) * (.83 + .17 * drift[column]) +
          echo * echo * .17 + halo * halo * .10) * bloom;
        let displacement = primary * (1.4 + energy * 3.9);
        if (launch) {
          const distance = radial[i];
          const ring = Math.max(0, 1 - Math.abs(distance - launchFront) / launchRidge);
          const echoRing = Math.max(0, 1 - Math.abs(distance - launchFront + short * .18) / (launchRidge * .9));
          const edgeGlow = Math.max(0, 1 - distance / (short * 1.36));
          light = edge * (.07 + .08 * g + .22 * edgeGlow + ring * ring * .47 + echoRing * .15) *
            (.37 + .63 * smooth(age / .46));
          displacement = (ring + echoRing * .5) * 5.5;
        } else if (pulseGrid && rippleGain > 0) {
          const wave = Math.max(0, 1 - Math.abs(pulseGrid[i] - rippleFront) / 53);
          light += wave * wave * rippleGain * .54;
          displacement += wave * rippleGain * 7;
        }
        if (!launch) light *= .57 + .43 * entrance;
        tones[i] = light < .065 ? -1 : Math.min(colors.length - 1, Math.floor(light * 15));
        radii[i] = .57 + light * 1.27;
        px[i] = x[column] + (rowDrift * .74 + g - .5) * displacement;
        py[i] = y[row] + drift[column] * displacement + (1 - entrance) * (12 + g * 15);
      }
    }

    ctx.clearRect(0, 0, width, height);
    if (ambient) {
      const shimmer = ctx.createLinearGradient(0, 0, width, height * .22);
      shimmer.addColorStop(0, "rgba(168,184,221,0)");
      shimmer.addColorStop(.27, "rgba(168,184,221,.38)");
      shimmer.addColorStop(.54, "rgba(228,237,255,.65)");
      shimmer.addColorStop(.78, "rgba(153,180,227,.28)");
      shimmer.addColorStop(1, "rgba(153,180,227,0)");
      ctx.strokeStyle = shimmer;
      for (let layer = 0; layer < 5; layer++) {
        ctx.globalAlpha = bloom * entrance * (.46 - layer * .071);
        ctx.lineWidth = layer === 0 ? 1.4 : .9;
        ctx.beginPath();
        for (let col = 0; col < x.length; col++) {
          const yAt = height * crest[col] + layer * (4.1 + 2.2 * drift[col]);
          if (col === 0) ctx.moveTo(x[col], yAt);
          else ctx.lineTo(x[col], yAt);
        }
        ctx.stroke();
      }
      ctx.globalAlpha = 1;
    }
    for (let band = 0; band < colors.length; band++) {
      ctx.beginPath();
      for (let i = 0; i < tones.length; i++) {
        if (tones[i] !== band) continue;
        ctx.moveTo(px[i] + radii[i], py[i]);
        ctx.arc(px[i], py[i], radii[i], 0, tau);
      }
      ctx.fillStyle = colors[band];
      ctx.fill();
    }
  }

  function resize() {
    if (!host || !ctx) return;
    const w = host.clientWidth, h = host.clientHeight;
    if (!w || !h) return;
    const ratio = Math.min(devicePixelRatio || 1, quality === "full" ? 1.6 : quality === "simple" ? 1 : 1.24,
      Math.sqrt(570_000 / (w * h)));
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
    const elapsed = previous ? Math.min(80, now - previous) : 16.7;
    previous = now;
    age += elapsed / 1000;
    clock += elapsed / duration(1000);
    level *= Math.exp(-elapsed / 410);
    average += (elapsed - average) * .06;
    slow = average > 25 ? slow + 1 : Math.max(0, slow - 2);
    if (slow > 38 && adaptive < 2) {
      adaptive++;
      slow = 0;
      arrange();
    }
    if (ripple && clock - ripple.since >= .60) {
      ripple = null;
      pulseGrid = null;
    }
    paint(elapsed);
    frame = requestAnimationFrame(tick);
  }

  function stop() {
    running = false;
    cancelAnimationFrame(frame);
    observer?.disconnect();
    previous = 0;
  }

  function restart() {
    age = 0;
    previous = 0;
    paint(16.7);
  }

  function mount(next, options) {
    const sameHost = running && host === next;
    const updatedQuality = quality !== options.quality;
    const updatedMotion = still !== options.still;
    kind = options.kind;
    quality = options.quality;
    still = options.still;
    mode = options.mode;
    populated = options.populated;
    if (!field) {
      field = document.createElement("canvas");
      field.className = "wave-canvas";
      field.setAttribute("aria-hidden", "true");
      ctx = field.getContext("2d", { alpha: true });
      if (typeof ResizeObserver !== "undefined") observer = new ResizeObserver(resize);
    }
    if (!ctx) return;
    if (sameHost) {
      if (updatedQuality) { grid = null; resize(); }
      if (updatedMotion) {
        cancelAnimationFrame(frame);
        previous = 0;
        if (!still) frame = requestAnimationFrame(tick);
      }
      if (still) paint(16.7);
      return;
    }
    stop();
    host = next;
    grid = ripple = null;
    age = level = 0;
    chatMix = kind === "chat" ? 1 : 0;
    textMix = populated ? 1 : 0;
    running = true;
    host.append(field);
    observer?.observe(host);
    resize();
    if (!still) frame = requestAnimationFrame(tick);
  }

  function state(next, value = 0) {
    const changed = mode !== next;
    mode = next;
    level = clamp(value);
    if (changed && still) paint(16.7);
  }

  function pulse(x, y) {
    if (!running || still || !width || !height) return;
    ripple = { x: clamp(x / width), y: clamp(y / height), since: clock };
    preparePulse();
  }

  function visibilityChanged() {
    cancelAnimationFrame(frame);
    previous = 0;
    if (!document.hidden && window.cortexAppVisible !== false && running && !still) frame = requestAnimationFrame(tick);
  }
  document.addEventListener("visibilitychange", visibilityChanged);
  addEventListener("cortex-app-visibility", visibilityChanged);

  return { duration, mount, stop, state, pulse, restart };
})();
