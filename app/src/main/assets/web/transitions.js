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

  function dock(control) {
    const icon = control?.querySelector(".nav-icon");
    if (!icon?.animate) return;
    icon.getAnimations().forEach(animation => animation.cancel());
    icon.animate([
      { transform: "translateY(2px) scale(.93)" },
      { transform: "translateY(-3px) scale(1.10)", offset: .65 },
      { transform: "translateY(-1px) scale(1.08)" }
    ], { duration: 285, easing: "cubic-bezier(.18,.76,.23,1)" });
  }

  function core(app, from, to) {
    if (!from || !to || !from.w || !to.w) return null;
    const x = from.x + from.w / 2, y = from.y + from.h / 2;
    const tx = to.x + to.w / 2, ty = to.y + to.h / 2;
    const duration = 580;
    const dist = Math.hypot(tx - x, ty - y);
    const count = 74;
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
    const distance = tab ? 17 * direction : 0;
    const rise = tab ? 5 : 10;
    return surface.animate([
      { opacity: .25, transform: `translate3d(${distance}px,${rise}px,0) scale(.99)` },
      { opacity: 1, transform: `translate3d(${-.7 * direction}px,0,0) scale(1.001)`, offset: .82 },
      { opacity: 1, transform: "none" }
    ], { duration: tab ? 345 : 285, easing: "cubic-bezier(.2,.74,.22,1)" });
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

  return { dock, core, page, press };
})();
