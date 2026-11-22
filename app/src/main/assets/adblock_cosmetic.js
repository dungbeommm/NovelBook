(function () {
  "use strict";

  if (!document.documentElement || window.__tramTruyenCosmeticBlockerV1) return;
  window.__tramTruyenCosmeticBlockerV1 = true;

  var styleId = "tramtruyen-adblock-cosmetic-v1";
  var css = [
    "ins.adsbygoogle",
    'iframe[src*="doubleclick.net" i]',
    'iframe[src*="googlesyndication.com" i]',
    'iframe[src*="googleadservices.com" i]',
    "[data-ad]",
    "[data-ad-slot]",
    "[data-ad-unit]",
    "[data-advertisement]",
    "[id*=advertisement i]",
    "[class*=advertisement i]",
    "[id*=ad-banner i]",
    "[class*=ad-banner i]",
    "[id*=ad-container i]",
    "[class*=ad-container i]",
    "[class*=sponsored-content i]",
    "[id*=sponsored-content i]",
    ".ad-banner",
    "#ad-banner"
  ].join(",\n") + " { display: none !important; visibility: hidden !important; }";

  function installStyle() {
    var root = document.head || document.documentElement;
    if (!root) return;

    var style = document.getElementById(styleId);
    if (!style) {
      style = document.createElement("style");
      style.id = styleId;
      root.appendChild(style);
    }
    if (style.textContent !== css) style.textContent = css;
  }

  installStyle();

  if (window.MutationObserver) {
    var pending = false;
    new MutationObserver(function () {
      if (pending || document.getElementById(styleId)) return;
      pending = true;
      window.setTimeout(function () {
        pending = false;
        installStyle();
      }, 0);
    }).observe(document.documentElement, { childList: true, subtree: true });
  }
})();
