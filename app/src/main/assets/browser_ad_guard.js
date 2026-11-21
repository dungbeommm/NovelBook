(function () {
  "use strict";

  if (!document.documentElement || window.__tramTruyenOverlayGuardV1) return;
  window.__tramTruyenOverlayGuardV1 = true;

  var marker = /(?:^|[\s_-])(?:ad|ads|advert|advertisement|sponsor|promo)(?:$|[\s_-])/i;
  var candidates = [
    "iframe",
    "[data-ad]",
    "[data-advertisement]",
    "[id*=ad-banner i]",
    "[class*=ad-banner i]",
    "[id*=ad-overlay i]",
    "[class*=ad-overlay i]",
    "[id*=advertisement i]",
    "[class*=advertisement i]",
    "[id*=sponsor i]",
    "[class*=sponsor i]"
  ].join(",");

  function hasAdMarker(element) {
    if (element.hasAttribute("data-ad") || element.hasAttribute("data-advertisement")) return true;

    var id = element.id || "";
    var className = typeof element.className === "string" ? element.className : "";
    var label = [id, className, element.getAttribute("aria-label") || "", element.title || ""].join(" ");
    var src = element.getAttribute("src") || "";

    return marker.test(label) || /(?:doubleclick\.net|googlesyndication\.com|googleadservices\.com)/i.test(src);
  }

  function isFloating(element) {
    if (!window.getComputedStyle) return false;
    var position = window.getComputedStyle(element).position;
    return position === "fixed" || position === "absolute";
  }

  function removeIfOverlayAd(element) {
    if (!element || element.nodeType !== 1) return;
    if (element === document.documentElement || element === document.head || element === document.body) return;
    if (!hasAdMarker(element)) return;

    var outsideBody = element.parentNode === document.documentElement;
    if ((outsideBody || isFloating(element)) && element.parentNode) element.parentNode.removeChild(element);
  }

  function inspect(node) {
    if (!node || node.nodeType !== 1) return;
    removeIfOverlayAd(node);
    if (!node.querySelectorAll) return;
    Array.prototype.forEach.call(node.querySelectorAll(candidates), removeIfOverlayAd);
  }

  inspect(document.documentElement);

  if (window.MutationObserver) {
    new MutationObserver(function (records) {
      records.forEach(function (record) {
        Array.prototype.forEach.call(record.addedNodes, inspect);
      });
    }).observe(document, { childList: true, subtree: true });
  }
})();
