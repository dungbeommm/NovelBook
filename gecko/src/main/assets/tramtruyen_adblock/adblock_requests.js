const bundledAdPatterns = fetch(browser.runtime.getURL("adblock_rules.json"))
  .then(response => response.json())
  .then(rules => rules.urls_union.map(pattern => pattern.trim().toLowerCase()).filter(Boolean))
  .catch(() => []);

browser.webRequest.onBeforeRequest.addListener(
  async request => {
    if (request.type === "main_frame") return {};
    const url = request.url.toLowerCase();
    const patterns = await bundledAdPatterns;
    return patterns.some(pattern => url.includes(pattern)) ? { cancel: true } : {};
  },
  { urls: ["http://*/*", "https://*/*"] },
  ["blocking"],
);
