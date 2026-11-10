"use strict";

const CortexWave = (() => {
  const vertex = `
    attribute vec2 grid;
    uniform mediump float time;
    uniform mediump float layer;
    uniform float aspect;
    uniform float amplitude;
    varying mediump vec3 pointOnRibbon;
    varying mediump vec3 surfaceNormal;
    varying mediump vec2 textureUV;

    vec3 ribbon(vec2 uv) {
      float s = uv.x;
      float angle = uv.y * 6.2831853;
      float drift = time * (.62 + layer * .037);
      float phase = layer * .82;
      float envelope = pow(max(.015, sin(s * 3.14159)), .55);
      float curl = sin(s * 7.9 - drift + phase) * .31
        + sin(s * 13.7 + drift * .54 + phase) * .10;
      float y = curl * amplitude + (layer - 1.) * .115;
      float z = sin(s * 9.1 + drift * .7 + phase) * .24 + layer * .16 - .13;
      float twist = sin(s * 6.4 - drift * .63 + phase) * 1.84 + .54;
      float width = (.235 - layer * .022) * envelope + .046;
      float thickness = (.079 - layer * .008) * envelope + .019;
      float side = cos(angle) * width;
      float depth = sin(angle) * thickness;
      float wing = .055 * sin(angle * 2. + s * 9.) * envelope;
      return vec3(
        (s - .5) * 3.24 + side * .18 * sin(twist),
        y + side * cos(twist) - depth * sin(twist) + wing,
        z + side * sin(twist) + depth * cos(twist)
      );
    }

    void main() {
      vec3 p = ribbon(grid);
      vec3 tangent = ribbon(grid + vec2(.001, 0.)) - p;
      vec3 around = ribbon(grid + vec2(0., .001)) - p;
      surfaceNormal = normalize(cross(tangent, around));
      pointOnRibbon = p;
      textureUV = grid;
      float perspective = 1.09 - p.z * .25;
      gl_Position = vec4(p.x * .73 / perspective, p.y * aspect * .96 / perspective, -.56 * p.z, 1.);
    }
  `;

  const fragment = `
    precision mediump float;
    uniform mediump float layer;
    uniform mediump float time;
    varying mediump vec3 pointOnRibbon;
    varying mediump vec3 surfaceNormal;
    varying mediump vec2 textureUV;

    void main() {
      float s = textureUV.x;
      vec3 n = normalize(surfaceNormal);
      vec3 camera = normalize(vec3(0., .03, 2.7) - pointOnRibbon);
      if (dot(n, camera) < 0.) n = -n;
      vec3 key = normalize(vec3(-.52, .78, .68));
      vec3 fill = normalize(vec3(.76, -.24, .66));
      float diffuse = max(0., dot(n, key));
      float diffuseFill = max(0., dot(n, fill));
      float facing = max(0., dot(n, camera));
      float rim = pow(1. - facing, 3.1);
      float gloss = pow(max(0., dot(n, normalize(key + camera))), 34.);
      float sheen = pow(max(0., dot(n, normalize(fill + camera))), 12.);
      float shift = sin(s * 7.5 + layer * .8 + time * .49) * .06;
      vec3 teal = vec3(.015, .78, .97);
      vec3 blue = vec3(.11, .27, .99);
      vec3 violet = vec3(.68, .15, .96);
      vec3 magenta = vec3(1., .23, .75);
      vec3 pigment = mix(teal, blue, smoothstep(.12, .57, s + shift));
      pigment = mix(pigment, violet, smoothstep(.42, .81, s + shift + layer * .041));
      pigment = mix(pigment, magenta, smoothstep(.76, .99, s + shift) * .65);
      float fibre = sin(textureUV.y * 113.097 + s * 20. + time * .24);
      float filaments = pow(max(0., fibre), 10.) * (.009 + rim * .028);
      vec3 silk = pigment * (.20 + diffuse * .62 + diffuseFill * .22 + filaments);
      silk += vec3(.64, .87, 1.) * gloss * .62;
      silk += vec3(.64, .44, .98) * sheen * .30;
      silk += (pigment * .57 + vec3(.13, .18, .28)) * rim;
      float edge = smoothstep(0., .095, s) * smoothstep(0., .095, 1. - s);
      float alpha = edge * mix(.84, .98, layer * .5);
      gl_FragColor = vec4(silk, alpha);
    }
  `;

  let canvas;
  let gl;
  let program;
  let uniforms;
  let indexCount;
  let host;
  let observer;
  let frame;
  let last = 0;
  let clock = 1.9;
  let active = false;
  let still = false;

  function compile(kind, source) {
    const shader = gl.createShader(kind);
    gl.shaderSource(shader, source);
    gl.compileShader(shader);
    if (!gl.getShaderParameter(shader, gl.COMPILE_STATUS)) {
      throw new Error(gl.getShaderInfoLog(shader));
    }
    return shader;
  }

  function init() {
    canvas = document.createElement("canvas");
    canvas.className = "ribbon-canvas";
    canvas.setAttribute("aria-hidden", "true");
    try {
      gl = canvas.getContext("webgl", {
        alpha: true, antialias: true, depth: true, premultipliedAlpha: false, powerPreference: "low-power"
      });
      if (!gl) return;
      const vs = compile(gl.VERTEX_SHADER, vertex);
      const fs = compile(gl.FRAGMENT_SHADER, fragment);
      program = gl.createProgram();
      gl.attachShader(program, vs);
      gl.attachShader(program, fs);
      gl.linkProgram(program);
      if (!gl.getProgramParameter(program, gl.LINK_STATUS)) throw new Error(gl.getProgramInfoLog(program));
      gl.deleteShader(vs);
      gl.deleteShader(fs);
      gl.useProgram(program);
      uniforms = Object.fromEntries(["time", "layer", "aspect", "amplitude"].map(name => [name, gl.getUniformLocation(program, name)]));
      const length = 156;
      const cross = 32;
      const vertices = [];
      const indices = [];
      for (let i = 0; i <= length; i++) {
        for (let j = 0; j <= cross; j++) {
          vertices.push(i / length, j / cross);
          if (i === length || j === cross) continue;
          const p = i * (cross + 1) + j;
          const next = p + cross + 1;
          indices.push(p, next, p + 1, p + 1, next, next + 1);
        }
      }
      const mesh = gl.createBuffer();
      gl.bindBuffer(gl.ARRAY_BUFFER, mesh);
      gl.bufferData(gl.ARRAY_BUFFER, new Float32Array(vertices), gl.STATIC_DRAW);
      const attribute = gl.getAttribLocation(program, "grid");
      gl.enableVertexAttribArray(attribute);
      gl.vertexAttribPointer(attribute, 2, gl.FLOAT, false, 0, 0);
      gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER, gl.createBuffer());
      gl.bufferData(gl.ELEMENT_ARRAY_BUFFER, new Uint16Array(indices), gl.STATIC_DRAW);
      indexCount = indices.length;
      gl.enable(gl.DEPTH_TEST);
      gl.enable(gl.BLEND);
      gl.blendFuncSeparate(gl.SRC_ALPHA, gl.ONE_MINUS_SRC_ALPHA, gl.ONE, gl.ONE_MINUS_SRC_ALPHA);
      gl.clearColor(0, 0, 0, 0);
      canvas.addEventListener("webglcontextlost", event => {
        event.preventDefault();
        stop();
        host?.classList.add("wave-fallback");
      });
      canvas.addEventListener("webglcontextrestored", () => {
        const previous = host;
        canvas.remove();
        canvas = null;
        gl = null;
        if (previous?.isConnected) mount(previous, still);
      });
    } catch {
      gl = null;
    }
  }

  function resize() {
    if (!host || !gl) return;
    const width = host.clientWidth;
    const height = host.clientHeight;
    if (!width || !height) return;
    const ratio = Math.min(devicePixelRatio || 1, 1.65, 1050 / width);
    canvas.width = Math.round(width * ratio);
    canvas.height = Math.round(height * ratio);
    gl.viewport(0, 0, canvas.width, canvas.height);
    draw();
  }

  function draw() {
    if (!gl || !canvas.height) return;
    gl.clear(gl.COLOR_BUFFER_BIT | gl.DEPTH_BUFFER_BIT);
    gl.uniform1f(uniforms.time, clock);
    gl.uniform1f(uniforms.aspect, Math.min(1.46, canvas.width / canvas.height));
    gl.uniform1f(uniforms.amplitude, host?.closest(".splash") ? .77 : 1.);
    for (let layer = 0; layer < 3; layer++) {
      gl.uniform1f(uniforms.layer, layer);
      gl.drawElements(gl.TRIANGLES, indexCount, gl.UNSIGNED_SHORT, 0);
    }
  }

  function loop(now) {
    if (!active || still || document.hidden) return;
    if (!last) last = now - 34;
    const elapsed = now - last;
    if (elapsed >= 31) {
      clock += Math.min(elapsed, 75) / 1000;
      last = now;
      draw();
    }
    frame = requestAnimationFrame(loop);
  }

  function stop() {
    active = false;
    cancelAnimationFrame(frame);
    observer?.disconnect();
    last = 0;
  }

  function mount(next, reduced = false) {
    stop();
    host = next;
    still = reduced;
    if (!host) return;
    if (!canvas) init();
    host.classList.toggle("wave-fallback", !gl);
    if (!gl) return;
    host.append(canvas);
    observer ??= new ResizeObserver(resize);
    observer.observe(host);
    active = true;
    resize();
    if (!still) frame = requestAnimationFrame(loop);
  }

  document.addEventListener("visibilitychange", () => {
    cancelAnimationFrame(frame);
    last = 0;
    if (!document.hidden && active && !still) frame = requestAnimationFrame(loop);
  });

  return { mount, stop };
})();
