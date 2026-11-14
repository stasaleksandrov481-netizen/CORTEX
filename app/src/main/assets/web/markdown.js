"use strict";

const CortexMarkdown = (() => {
  const parser = window.markdownit({ html: false, linkify: true });
  const escape = parser.utils.escapeHtml;

  function webUrl(value) {
    if (typeof value !== "string" || value.length > 2048) return "";
    try {
      const url = new URL(value);
      return ["https:", "http:"].includes(url.protocol) && url.hostname &&
        !url.username && !url.password ? url.href : "";
    } catch { return ""; }
  }

  parser.validateLink = value => Boolean(webUrl(value));
  parser.renderer.rules.link_open = (tokens, index, options, env, renderer) => {
    tokens[index].attrSet("target", "_blank");
    tokens[index].attrSet("rel", "noopener noreferrer");
    return renderer.renderToken(tokens, index, options);
  };
  parser.renderer.rules.image = (tokens, index, options, env, renderer) => {
    const token = tokens[index];
    const href = webUrl(token.attrGet("src"));
    const alt = renderer.renderInlineAsText(token.children || [], options, env) || "Открыть изображение";
    return href ? '<a href="' + escape(href) + '" target="_blank" rel="noopener noreferrer">' +
      escape(alt) + "</a>" : escape(alt);
  };

  function codeBlock(token) {
    const info = token.info.trim().split(/\s+/u)[0];
    const language = /^[a-z0-9_.+-]{1,24}$/i.test(info) ? info : "Код";
    return '<figure class="code-block"><figcaption><span>' + escape(language) + '</span>' +
      '<button type="button" class="code-copy" data-action="copy-code" aria-label="Копировать код">' +
      CortexIcons("copy", 17) + '<span>Копировать</span></button></figcaption>' +
      '<pre tabindex="0"><code>' + escape(token.content) + "</code></pre></figure>\n";
  }
  parser.renderer.rules.fence = (tokens, index) => codeBlock(tokens[index]);
  parser.renderer.rules.code_block = (tokens, index) => codeBlock(tokens[index]);
  parser.renderer.rules.table_open = () => '<div class="table-scroll" tabindex="0" role="region" aria-label="Таблица"><table>\n';
  parser.renderer.rules.table_close = () => "</table></div>\n";

  parser.core.ruler.after("inline", "cortex_task_list", state => {
    for (let i = 2; i < state.tokens.length; i++) {
      const inline = state.tokens[i], item = state.tokens[i - 2];
      if (inline.type !== "inline" || state.tokens[i - 1].type !== "paragraph_open" || item.type !== "list_item_open") continue;
      const first = inline.children?.[0];
      const marker = first?.type === "text" && /^\[([ x])]\s+/i.exec(first.content);
      if (!marker) continue;
      item.attrJoin("class", "task-item");
      first.content = first.content.slice(marker[0].length);
      const check = new state.Token("cortex_task", "", 0);
      check.meta = { checked: marker[1] !== " " };
      inline.children.unshift(check);
    }
  });
  parser.renderer.rules.cortex_task = (tokens, index) => tokens[index].meta.checked
    ? '<span class="task-check" role="img" aria-label="Выполнено">' + CortexIcons("check", 13) + "</span>"
    : '<span class="task-check" role="img" aria-label="В процессе"></span>';

  const callouts = { NOTE: ["Заметка", "info"], TIP: ["Совет", "spark"], IMPORTANT: ["Важно", "alert"] };
  parser.core.ruler.after("inline", "cortex_callout", state => {
    for (let index = 0; index + 2 < state.tokens.length; index++) {
      const [quote, paragraph, inline] = state.tokens.slice(index, index + 3);
      if (quote.type !== "blockquote_open" || paragraph.type !== "paragraph_open" || inline.type !== "inline") continue;
      const first = inline.children?.[0];
      const mark = first?.type === "text" && /^\[!(NOTE|TIP|IMPORTANT)\]\s*$/u.exec(first.content);
      if (!mark) continue;
      quote.meta = { callout: mark[1] };
      quote.attrJoin("class", "cortex-callout");
      inline.children.shift();
      if (["softbreak", "hardbreak"].includes(inline.children[0]?.type)) inline.children.shift();
    }
  });
  parser.renderer.rules.blockquote_open = (tokens, index, options, env, renderer) => {
    const token = tokens[index];
    const open = renderer.renderToken(tokens, index, options);
    const kind = token.meta?.callout;
    if (!kind || !Object.hasOwn(callouts, kind)) return open;
    const [title, icon] = callouts[kind];
    return open + '<div class="callout-title">' + CortexIcons(icon, 16) + "<strong>" + title + "</strong></div>\n";
  };

  const render = value => parser.render(String(value ?? ""));

  return { render, webUrl };
})();
