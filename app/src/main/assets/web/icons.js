"use strict";

const CortexIcons = (() => {
  const paths = {
    sendUp: '<path d="M12 20V5m-6 6 6-6 6 6"/>',
    newChat: '<path d="M20.5 11.8a8.5 8.5 0 0 1-8.5 8.5H4l1.4-3.8A8.5 8.5 0 0 1 12 3.4c1 0 1.9.1 2.8.4M18 3v6m-3-3h6"/>',
    core: '<g fill="currentColor" stroke="none"><circle cx="12" cy="12" r="2.45"/><circle cx="5.5" cy="12" r="1.55"/><circle cx="18.5" cy="12" r="1.55"/><circle cx="12" cy="5.5" r="1.55"/><circle cx="12" cy="18.5" r="1.55"/><circle cx="7.4" cy="7.4" r="1.1"/><circle cx="16.6" cy="16.6" r="1.1"/><circle cx="7.4" cy="16.6" r="1.1"/><circle cx="16.6" cy="7.4" r="1.1"/></g>',
    copy: '<rect x="8" y="8" width="12" height="12" rx="2.5"/><path d="M16 4H6a2 2 0 0 0-2 2v10"/>',
    download: '<path d="M12 3v12m-5-5 5 5 5-5M4 16v3a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-3"/>',
    help: '<path d="M3 13v-2a9 9 0 0 1 18 0v2M8 14a2 2 0 0 0-2-2H5a2 2 0 0 0-2 2v2a2 2 0 0 0 2 2h1a2 2 0 0 0 2-2Zm8 0a2 2 0 0 1 2-2h1a2 2 0 0 1 2 2v2a2 2 0 0 1-2 2h-1a2 2 0 0 1-2-2Zm5 2v2a3 3 0 0 1-3 3h-5"/>',
    logout: '<path d="M10 4H5a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h5m5-13 5 5-5 5m-6-5h11"/>',
    image: '<rect x="3" y="4" width="18" height="16" rx="3"/><circle cx="8" cy="9" r="1.4"/><path d="m3 17 5-4 4 3 4-5 5 6"/>',
    pin: '<path d="m14 3 7 7-4 1-4 4-1 3-6-6 3-1 4-4zM8 16l-5 5"/>',
    alert: '<circle cx="12" cy="12" r="9"/><path d="M12 8v5m0 3h.01"/>',
    bookmark: '<path d="M6 4h12v17l-6-4-6 4z"/>',
    spark: '<path d="M12 2c1 6 4 9 10 10-6 1-9 4-10 10-1-6-4-9-10-10 6-1 9-4 10-10Z"/>',
    home: '<path d="m3 10 9-7 9 7v9a2 2 0 0 1-2 2h-4v-7H9v7H5a2 2 0 0 1-2-2z"/>',
    history: '<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/>',
    layers: '<path d="m12 3 9 5-9 5-9-5zm-9 9 9 5 9-5M3 16l9 5 9-5"/>',
    user: '<circle cx="12" cy="7.5" r="3.5"/><path d="M4 21v-2a4 4 0 0 1 4-4h8a4 4 0 0 1 4 4v2z"/>',
    settings: '<path d="M9.4 3h5.2l.5 2.6 2 .9 2.5-.9 2.6 4.5-2 1.8v2.2l2 1.8-2.6 4.5-2.5-.9-2 .9-.5 2.6H9.4l-.5-2.6-2-.9-2.5.9-2.6-4.5 2-1.8v-2.2l-2-1.8 2.6-4.5 2.5.9 2-.9z" transform="translate(1 0) scale(.92)"/><circle cx="12" cy="12" r="3"/>',
    wave: '<path d="M3 10v4m4-7v10m5-14v18m5-14v10m4-7v4"/>',
    focus: '<path d="M8 4H6a2 2 0 0 0-2 2v2m12-4h2a2 2 0 0 1 2 2v2M4 16v2a2 2 0 0 0 2 2h2m12-4v2a2 2 0 0 1-2 2h-2M8 12h8"/>',
    bell: '<path d="M18 8a6 6 0 0 0-12 0c0 6-3 7-3 9h18c0-2-3-3-3-9m-8 12a2.1 2.1 0 0 0 4 0"/>',
    send: '<path d="m22 2-7 20-4-9-9-4zm0 0L11 13"/>',
    telegram: '<path fill="currentColor" stroke="none" d="M21.9 3.9 18.7 20c-.2 1.1-.9 1.3-1.8.8l-5-3.8-2.4 2.4c-.3.3-.5.5-1 .5l.4-5.2 9.3-8.4c.4-.4-.1-.6-.6-.2L6.1 13.4 1.2 12c-1.1-.3-1.1-1.1.2-1.6l19-7.3c.9-.4 1.7.2 1.5.8Z"/>',
    folder: '<path d="M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"/>',
    note: '<path d="M11 4H5a2 2 0 0 0-2 2v13a2 2 0 0 0 2 2h13a2 2 0 0 0 2-2v-6M9 15l1-4L19 2l3 3-9 9z"/>',
    file: '<path d="M14 3H6a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V9zm0 0v6h6M8 13h8m-8 4h6"/>',
    search: '<circle cx="10.5" cy="10.5" r="6.5"/><path d="m16 16 5 5"/>',
    chevron: '<path d="m9 6 6 6-6 6"/>',
    back: '<path d="m15 5-7 7 7 7"/>',
    close: '<path d="m6 6 12 12M18 6 6 18"/>',
    check: '<path d="m5 12 4 4L19 6"/>',
    play: '<path d="M7 4v16l13-8z"/>',
    stop: '<rect x="6" y="6" width="12" height="12" rx="2"/>',
    dots: '<circle cx="12" cy="4.5" r="1" fill="currentColor"/><circle cx="12" cy="12" r="1" fill="currentColor"/><circle cx="12" cy="19.5" r="1" fill="currentColor"/>',
    bolt: '<path d="m13 2-9 12h7l-1 8 10-12h-7z"/>',
    message: '<path d="M21 11.5a8.5 8.5 0 0 1-8.5 8.5H4l1.5-4A8.5 8.5 0 1 1 21 11.5Z"/><path d="M8 12h.01m4 0h.01m4 0h.01"/>',
    mic: '<rect x="9" y="2" width="6" height="13" rx="3"/><path d="M5 10v2a7 7 0 0 0 14 0v-2M12 19v3m-3 0h6"/>',
    speaker: '<path d="M3 9h4l5-4v14l-5-4H3zm12 0a4 4 0 0 1 0 6m2-9a8 8 0 0 1 0 12"/>',
    memory: '<ellipse cx="12" cy="5" rx="8" ry="3"/><path d="M4 5v14c0 1.7 3.6 3 8 3s8-1.3 8-3V5M4 12c0 1.7 3.6 3 8 3s8-1.3 8-3"/>',
    shield: '<path d="M12 2 4 6v6c0 5 4 8.5 8 10 4-1.5 8-5 8-10V6z"/><path d="m8.5 12 2.5 2.5 4.5-5"/>',
    grid: '<rect x="4" y="4" width="6" height="6" rx="1.3"/><rect x="14" y="4" width="6" height="6" rx="1.3"/><rect x="4" y="14" width="6" height="6" rx="1.3"/><rect x="14" y="14" width="6" height="6" rx="1.3"/>',
    palette: '<path d="M12 3a9 9 0 1 0 0 18h1.3a1.7 1.7 0 0 0 1.3-2.8 1.8 1.8 0 0 1 1.4-3h2a3 3 0 0 0 3-3A9 9 0 0 0 12 3Z"/><path stroke-width="2.5" d="M7.5 10h.01M11 7h.01M16 8h.01M7.5 15h.01"/>',
    sliders: '<path d="M3 7h7m4 0h7M3 17h11m4 0h3"/><circle cx="12" cy="7" r="2"/><circle cx="16" cy="17" r="2"/>',
    plus: '<path d="M12 4v16M4 12h16"/>',
    trash: '<path d="M3 6h18M9 6V3h6v3M5 6l1 15h12l1-15M10 10v7m4-7v7"/>',
    info: '<circle cx="12" cy="12" r="9"/><path d="M12 11v6m0-10h.01"/>',
    globe: '<circle cx="12" cy="12" r="9"/><path d="M3 12h18m-9-9a14 14 0 0 0 0 18 14 14 0 0 0 0-18Z"/>',
    star: '<path d="m12 3 2.8 5.6 6.2.9-4.5 4.4 1.1 6.1-5.6-2.9-5.6 2.9 1.1-6.1L3 9.5l6.2-.9z"/>',
    heart: '<path d="M12 21 4 13a5 5 0 0 1 8-6 5 5 0 0 1 8 6z"/>',
    target: '<circle cx="11" cy="13" r="8"/><circle cx="11" cy="13" r="4"/><path d="m11 13 9-9m-4 0h4v4"/>',
    refresh: '<path d="M20 11a8 8 0 1 0-2 6m2-13v7h-7"/>',
    eye: '<path d="M2 12s3.6-6 10-6 10 6 10 6-3.6 6-10 6-10-6-10-6Z"/><circle cx="12" cy="12" r="2.6"/>',
    link: '<path d="m10 14 4-4m-4 7-2 2a4 4 0 0 1-6-6l4-4a4 4 0 0 1 6 0m2-2 2-2a4 4 0 0 1 6 6l-4 4a4 4 0 0 1-6 0"/>',
    signal: '<path fill="currentColor" stroke="none" d="M0 7h2v4H0zm3-2h2v6H3zm3-2h2v8H6zm3-2h2v10H9z"/><rect x="15" y="3" width="7" height="8" rx="1.1"/><path d="M17 1.5h3m-3 3h3v5h-3z" fill="currentColor"/>'
  };

  return (name, size = 20) => {
    const viewBox = name === "signal" ? "0 0 24 13" : "0 0 24 24";
    return '<svg aria-hidden="true" width="' + size + '" height="' + size + '" viewBox="' + viewBox +
      '" fill="none" stroke="currentColor" stroke-width="2.12" stroke-linecap="round" stroke-linejoin="round">' +
      (paths[name] || paths.info) + "</svg>";
  };
})();
