(() => {
  let retry;

  function connect() {
    clearTimeout(retry);
    try {
      const port = browser.runtime.connectNative('stvChapter');
      port.onMessage.addListener(message => {
        if (message?.type !== 'read' || !Number.isSafeInteger(message.id)) return;
        const result = JSON.parse(window.tramTruyenStvChapter(message.url));
        port.postMessage({ id: message.id, ...result });
      });
      port.onDisconnect.addListener(() => { retry = setTimeout(connect, 500); });
    } catch (_) {
      retry = setTimeout(connect, 500);
    }
  }

  connect();
})();
