"use strict";

const CortexMotion = (() => {
  const easing = "cubic-bezier(.22,.77,.19,1)";
  const xy = box => [box.x + box.w / 2, box.y + box.h / 2];

  function play(app, from, to, chat, composerFrom, composerTo) {
    if (!from || !to || typeof app.animate !== "function") return null;
    const [x, y] = xy(from), [tx, ty] = xy(to);
    const dx = tx - x, dy = ty - y;
    const duration = chat ? 660 : 780;
    const layer = document.createElement("div");
    layer.className = "morph-fx";
    layer.setAttribute("aria-hidden", "true");
    layer.innerHTML = '<div class="morph-veil"></div><div class="morph-node"><i></i><i></i><i></i><b></b><img class="brand-mark" src="art/mark.svg" alt=""></div>';
    app.append(layer);
    const anims = [];
    const animate = (element, frames) => anims.push(element.animate(frames, { duration, easing, fill: "both" }));
    const node = layer.querySelector(".morph-node");
    node.style.cssText = `left:${x - 90}px;top:${y - 90}px`;
    const frame = (dx, dy, scale, rotate, opacity, offset) => ({
      transform: `translate3d(${dx}px,${dy}px,0) rotate(${rotate}deg) scale(${scale})`, opacity, offset
    });
    animate(node, chat ? [frame(0, 0, 1.16, 0, .78, 0), frame(0, 11, 1.29, -10, 1, .17),
      frame(dx * .7, dy * .65, .66, 15, .94, .55), frame(dx, dy, .18, 0, 0, 1)] :
      [frame(0, 0, .22, 0, .72, 0), frame(dx * .1, dy * .24, .61, -13, .9, .23),
        frame(dx, dy, 1.39, 10, 1, .67), frame(dx, dy, 1.4, 0, 0, 1)]);
    for (const [index, ring] of [...node.querySelectorAll("i")].entries()) {
      const angle = [-32, 52, -66][index];
      animate(ring, [
        { transform: `rotate(${angle + 47}deg) scaleY(.18)`, opacity: .22 },
        { transform: `rotate(${angle - 12}deg) scaleY(${.72 + index * .11})`, opacity: 1, offset: .49 },
        { transform: `rotate(${angle}deg) scaleY(.62)`, opacity: .34 }
      ]);
    }
    animate(layer.querySelector(".morph-veil"), [{ opacity: 0 }, { opacity: .35, offset: .36 }, { opacity: 0 }]);
    const spread = Math.min(app.clientWidth * .91, 455);
    for (const direction of [1, -1]) {
      const ribbon = document.createElement("i");
      ribbon.className = "morph-ribbon";
      ribbon.style.cssText = `width:${spread}px;left:${(chat ? x : tx) - spread / 2}px;top:${chat ? y : ty}px`;
      layer.append(ribbon);
      animate(ribbon, [
        { transform: `scaleX(.07) rotate(${direction * 28}deg)`, opacity: 0 },
        { transform: `translateY(${direction * 29}px) rotate(${direction * 10}deg)`, opacity: .75, offset: .43 },
        { transform: `translateY(${direction * 103}px) scaleX(1.16)`, opacity: 0 }
      ]);
    }
    if (chat && composerFrom && composerTo) {
      const edge = document.createElement("i");
      edge.className = "morph-edge";
      const px = composerFrom.x - composerTo.x, py = composerFrom.y - composerTo.y;
      edge.style.cssText = `left:${composerTo.x}px;top:${composerTo.y}px;width:${composerTo.w}px;height:${composerTo.h}px`;
      layer.append(edge);
      animate(edge, [
        { transform: `translate(${px}px,${py}px)`, opacity: .53 },
        { transform: `translate(${px / 2}px,${py / 2}px) scale(1.018,.92)`, opacity: .85, offset: .4 },
        { transform: "scale(1.01,1.08)", opacity: 0 }
      ]);
    }
    const clear = () => { for (const a of anims) a.cancel(); layer.remove(); };
    anims[0].finished.then(clear, () => {});
    return clear;
  }
  return { play };
})();
