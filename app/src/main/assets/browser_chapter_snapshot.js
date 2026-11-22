(function captureChapter(expectedUrl, contentSelector) {
  if (location.href !== expectedUrl || document.readyState === 'loading' || !document.body) {
    return '';
  }

  function isHidden(element) {
    if (element.hasAttribute('hidden') || element.getAttribute('aria-hidden') === 'true') {
      return true;
    }
    const style = getComputedStyle(element);
    return style.display === 'none' || style.visibility === 'hidden' || style.visibility === 'collapse';
  }

  function isVisible(element) {
    for (let ancestor = element; ancestor; ancestor = ancestor.parentElement) {
      if (isHidden(ancestor)) return false;
    }
    return true;
  }

  if (contentSelector) {
    let roots;
    try {
      roots = Array.from(document.querySelectorAll(contentSelector));
    } catch (_) {
      // Jsoup can still handle selectors that are unavailable in the browser.
    }
    if (roots?.length && !roots.some(root => isVisible(root) && (root.innerText || '').trim())) {
      return '';
    }
  }

  if (!isVisible(document.body)) return '';

  // Keep the head and source data used by the existing parsers. Only prune the detached copy.
  const snapshot = document.documentElement.cloneNode(true);
  const elements = [[document.body, snapshot.querySelector('body')]];
  while (elements.length) {
    const [original, copy] = elements.pop();
    const children = Array.from(copy.children);
    for (let i = 0; i < children.length; i++) {
      const child = original.children[i];
      const childCopy = children[i];
      if (child.matches('script, style, meta, link, template')) continue;
      if (isHidden(child)) childCopy.remove();
      else elements.push([child, childCopy]);
    }
  }
  return snapshot.outerHTML;
})
