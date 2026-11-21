(function () {
  if (window !== window.top || window.tramTruyenStvChapter) return;

  const contentSelector = '.contentbox[cid]';
  const chapterPath = /^\/truyen\/[\w-]+\/\d+\/[\w-]+\/([\w-]+)\/?$/;
  const breaks = new Set(['P', 'DIV', 'LI', 'BLOCKQUOTE', 'SECTION', 'ARTICLE', 'H1', 'H2', 'H3']);
  let snapshot;
  let previousLines;
  let currentKey;

  function hidden(element) {
    const style = getComputedStyle(element);
    return element.hidden || element.getAttribute('aria-hidden') === 'true' ||
      style.display === 'none' || ['hidden', 'collapse'].includes(style.visibility);
  }

  function visible(element) {
    for (let node = element; node; node = node.parentElement) {
      if (hidden(node)) return false;
    }
    return true;
  }

  function capture() {
    const id = chapterPath.exec(location.pathname)?.[1];
    const key = location.origin + location.pathname.replace(/\/$/, '');
    if (key !== currentKey) {
      if (snapshot) previousLines = snapshot.lines.join('\n');
      currentKey = key;
      snapshot = undefined;
    }
    if (!id) return { state: 'missing' };

    const heading = document.querySelector('#bookchapnameholder')?.textContent.trim() || '';
    const root = Array.from(document.querySelectorAll(contentSelector))
      .find(element => element.getAttribute('cid') === id && visible(element));
    if (!root || !/\p{L}/u.test(heading)) return { state: 'waiting' };
    if (Array.from(root.querySelectorAll('.spinner-border')).some(visible) ||
        /Nhấp vào để tải chương|Đang tải nội dung chương/i.test(root.textContent)) {
      return { state: 'waiting' };
    }

    function text(node) {
      if (node.nodeType === Node.TEXT_NODE) return node.nodeValue.replace(/[\t\r\n\u00a0 ]+/g, ' ');
      if (node.nodeType !== Node.ELEMENT_NODE || hidden(node) ||
          node.matches('script, style, template, noscript, button, nav, aside, iframe, rt, rp')) return '';
      if (node.tagName === 'BR') return '\n';
      const joined = Array.from(node.childNodes, text).join('');
      return breaks.has(node.tagName) ? '\n' + joined + '\n' : joined;
    }

    const lines = Array.from(root.childNodes, text).join('').split('\n').map(line => line.trim()).filter(Boolean);
    if (lines.join('').length < 40 || !lines.some(line => /\p{L}/u.test(line))) return { state: 'waiting' };
    if (lines.join('\n') === previousLines) return { state: 'waiting' };

    // Extend a chapter as paragraphs arrive. Keep the first text when a later render rewrites it.
    const priorLines = snapshot?.lines || [];
    let preserved = 0;
    for (const line of lines) if (line === priorLines[preserved]) preserved++;
    if (!snapshot || preserved === priorLines.length) snapshot = { lines, heading };
    previousLines = undefined;
    return { state: 'ready', id, snapshot };
  }

  function blocked() {
    return /just a moment|verify you are human|checking your browser/i.test(document.title) ||
      !!document.querySelector('#challenge-form, #cf-challenge-running, .cf-browser-verification');
  }

  const observer = new MutationObserver(() => { if (!blocked()) capture(); });
  observer.observe(document, { childList: true, characterData: true, subtree: true, attributes: true,
    attributeFilter: ['cid', 'class', 'style', 'hidden', 'aria-hidden'] });

  window.tramTruyenStvChapter = expectedUrl => {
    if (location.href !== expectedUrl) return JSON.stringify({ state: 'navigation' });
    if (blocked()) return JSON.stringify({ state: 'blocked' });
    const result = capture();
    if (result.state !== 'ready') return JSON.stringify({ state: result.state });

    const copy = document.implementation.createHTMLDocument('');
    function add(tag, id, value, parent = copy.body) {
      const element = copy.createElement(tag);
      if (id) element.id = id;
      element.textContent = value;
      parent.append(element);
      return element;
    }
    add('div', 'booknameholder', document.querySelector('#booknameholder')?.textContent || '');
    add('h1', 'bookchapnameholder', result.snapshot.heading);
    const content = add('div', null, '');
    content.className = 'contentbox';
    content.setAttribute('cid', result.id);
    for (const line of result.snapshot.lines) add('p', null, line, content);
    for (const id of ['navnexttop', 'navprevtop']) {
      const href = document.getElementById(id)?.getAttribute('href');
      if (href) add('a', id, '').setAttribute('href', href);
    }
    const cover = document.querySelector('meta[property="og:image"]')?.content;
    if (cover) {
      const meta = add('meta', null, '', copy.head);
      meta.setAttribute('property', 'og:image');
      meta.content = cover;
    }
    return JSON.stringify({ state: 'ready', html: copy.documentElement.outerHTML });
  };
})();
