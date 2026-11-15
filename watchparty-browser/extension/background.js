// The toolbar button opens the room page (or brings it back).
const ROOM = 'room.html';

chrome.action.onClicked.addListener(async () => {
  const url = chrome.runtime.getURL(ROOM);
  const [tab] = await chrome.tabs.query({ url });
  if (tab) {
    await chrome.tabs.update(tab.id, { active: true });
    await chrome.windows.update(tab.windowId, { focused: true });
  } else {
    await chrome.tabs.create({ url });
  }
});
