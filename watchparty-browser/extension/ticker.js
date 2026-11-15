// A once-a-second tick from a worker: the room page usually sits in a
// background tab, where Chrome throttles a page's own timers (to once a
// minute after five minutes); messages from a worker aren't throttled.
setInterval(() => postMessage('tick'), 1000);
