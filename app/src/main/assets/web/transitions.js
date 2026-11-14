"use strict";

const CortexMotion = (() => {
  const tau = Math.PI * 2;
  const clamp = value => Math.max(0, Math.min(1, value));
  const ease = value => 1 - Math.pow(1 - clamp(value), 3);
  const lerp = (from, to, at) => from + (to - from) * at;
  const smooth = (from, to, at) => {
    const t = clamp((at - from) / (to - from));
    return t * t * (3 - 2 * t);
  };

  function layer(app, duration, draw, extras) {
    const canvas = document.createElement("canvas");
    const width = app.clientWidth, height = app.clientHeight;
    if (!width || !height) return null;
    const scale = Math.min(devicePixelRatio || 1, 1.65);
    canvas.width = Math.round(width * scale);
    canvas.height = Math.round(height * scale);
    canvas.className = "transition-particles";
    canvas.setAttribute("aria-hidden", "true");
    const ctx = canvas.getContext("2d");
    if (!ctx) return null;
    ctx.scale(scale, scale);
    app.append(canvas);
    const extra = extras?.();
    let frame = 0, start;
    function clear() {
      cancelAnimationFrame(frame);
      canvas.remove();
      extra?.();
    }
    function update(now) {
      start ??= now;
      const time = clamp((now - start) / duration);
      ctx.clearRect(0, 0, width, height);
      draw(ctx, time, width, height);
      if (time < 1) frame = requestAnimationFrame(update);
      else clear();
    }
    frame = requestAnimationFrame(update);
    return clear;
  }

  function wave(app, origin, direction = 1) {
    const point = origin ? origin.y + origin.h / 2 : app.clientHeight;
    const anchor = origin ? (origin.x + origin.w / 2) / app.clientWidth : .5;
    return layer(app, 610, (ctx, p, w, h) => {
      const columns = Math.round(Math.min(32, Math.max(20, w / 12)));
      const center = lerp(point - h * .09, -h * .13, .8 * p + .2 * ease(p));
      const life = smooth(.02, .24, p) * (1 - smooth(.57, 1, p));
      ctx.fillStyle = "#fff";
      for (let row = -4; row <= 4; row++) {
        const depth = 1 - Math.abs(row) / 5;
        for (let col = 0; col < columns; col++) {
          const u = (col + .5) / columns;
          const ripple = Math.sin(((u - anchor) * direction * 1.39 - p * .76 + row * .055) * tau);
          const x = u * w + row * 2.1 * direction + ripple * 3;
          const y = center + row * (h * .023 + 2) + ripple * (13 + depth * 17) + (u - .5) * direction * 65;
          ctx.globalAlpha = life * depth * (.18 + .55 * (.5 + .5 * ripple));
          ctx.beginPath();
          ctx.arc(x, y, .62 + depth * (.58 + .62 * Math.abs(ripple)), 0, tau);
          ctx.fill();
        }
      }
    });
  }

  function core(app, from, to) {
    if (!from || !to || !from.w || !to.w) return null;
    const x = from.x + from.w / 2, y = from.y + from.h / 2;
    const tx = to.x + to.w / 2, ty = to.y + to.h / 2;
    const duration = 850;
    const dist = Math.hypot(tx - x, ty - y);
    const count = 140;
    const particles = Array.from({ length: count }, (_, i) => ({
      angle: i * 2.39996322973, orbit: 11 + (i * 17 % 51), radius: .58 + (i * 19 % 17) / 18,
      lane: i % 6, trail: (i * 13 % 25) / 100
    }));
    const glass = document.createElement("div");
    glass.className = "transition-core";
    glass.setAttribute("aria-hidden", "true");
    const mark = document.createElement("img");
    mark.src = "art/mark.svg";
    mark.alt = "";
    glass.append(mark);
    glass.style.left = `${x}px`;
    glass.style.top = `${y}px`;
    return layer(app, duration, (ctx, p, w) => {
      const launch = smooth(0, .20, p);
      const travel = smooth(.07, .93, p);
      const settle = smooth(.59, 1, p);
      const lift = Math.sin(Math.PI * travel);
      const cx = lerp(x, tx, travel) + lift * Math.min(39, dist * .15);
      const cy = lerp(y, ty, travel) - launch * (1 - travel) * 14;
      const strain = Math.sin(Math.PI * smooth(.11, .71, p));
      const size = lerp(.49 + launch * .34, .21, settle);
      const rotation = -12 * launch + 30 * strain - 18 * settle;
      glass.style.transform = `translate(-50%,-50%) translate3d(${cx - x}px,${cy - y}px,0) rotate(${rotation}deg) scale(${size + strain * .37},${size - strain * .26})`;
      glass.style.borderRadius = `${49 - 10 * strain}% ${51 + 10 * strain}% ${48 + 12 * strain}% ${52 - 10 * strain}%`;
      glass.style.opacity = String(1 - smooth(.72, 1, p));
      const sweep = Math.min(w * .43, 205);
      const brightness = Math.sin(Math.PI * p) ** .55;
      ctx.fillStyle = "#fff";
      for (const item of particles) {
        const angle = item.angle + launch * .86 + travel * 2.45 + item.lane * lift * .14;
        const spread = lerp(7 + item.orbit * .21, item.orbit * 1.65, launch) * (1 - settle * .9);
        const wing = Math.sin(angle + travel * 1.4) * lift * sweep * (.24 + item.trail);
        const px = cx + Math.cos(angle) * spread + wing;
        const py = cy + Math.sin(angle) * spread * (.61 + lift * .27) + (y - ty) * item.trail * lift;
        ctx.globalAlpha = brightness * (.28 + (5 - item.lane) * .10);
        ctx.beginPath();
        ctx.arc(px, py, item.radius * (1 + lift * .28 - settle * .40), 0, tau);
        ctx.fill();
      }
      for (let ring = 0; ring < 2; ring++) {
        const pulse = clamp((p - ring * .09) / .42);
        if (pulse <= 0 || pulse >= 1) continue;
        ctx.globalAlpha = Math.sin(pulse * Math.PI) * .24;
        ctx.strokeStyle = "#f3f3f3";
        ctx.lineWidth = 1.2 - pulse;
        ctx.beginPath();
        ctx.ellipse(x, y - pulse * 19, 17 + ease(pulse) * 90, 12 + ease(pulse) * 33, -.18, 0, tau);
        ctx.stroke();
      }
    }, () => {
      app.append(glass);
      return () => glass.remove();
    });
  }

  function page(surface, direction, tab) {
    const offset = tab ? 13 * direction : 0;
    return surface.animate([
      { opacity: .18, transform: `translate3d(${offset}px,12px,0) scale(.987,1.012)` },
      { opacity: .95, transform: `translate3d(${-.12 * offset}px,-2px,0) scale(1.003,.996)`, offset: .64 },
      { opacity: 1, transform: "none" }
    ], { duration: tab ? 390 : 345, easing: "cubic-bezier(.24,.72,.24,1)" });
  }

  const presses = new WeakMap();
  function press(control) {
    if (typeof control.animate !== "function" || control.disabled) return;
    presses.get(control)?.cancel();
    const transform = getComputedStyle(control).transform;
    const base = transform === "none" ? "" : transform + " ";
    presses.set(control, control.animate([
      { transform: base + "scale(.964,.94)" },
      { transform: base + "scale(1.016,.988)", offset: .53 },
      { transform }
    ], { duration: 320, easing: "cubic-bezier(.18,.82,.25,1)" }));
  }

  return { wave, core, page, press };
})();
