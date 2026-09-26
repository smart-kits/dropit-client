import { send } from './api.js';
import { t } from './i18n.js';

const MENUS = [
  { id: 'selection', title: t.menuSelection, contexts: ['selection'] },
  { id: 'link', title: t.menuLink, contexts: ['link'] },
  { id: 'image', title: t.menuImage, contexts: ['image'] },
  { id: 'page', title: t.menuPage, contexts: ['page'] },
];

chrome.runtime.onInstalled.addListener(() => {
  chrome.contextMenus.removeAll(() => MENUS.forEach((m) => chrome.contextMenus.create(m)));
});

chrome.contextMenus.onClicked.addListener((info) => {
  const payload = {
    selection: { kind: 'text', raw: info.selectionText },
    link: { kind: 'url', raw: info.linkUrl },
    image: { kind: 'url', raw: info.srcUrl },
    page: { kind: 'url', raw: info.pageUrl },
  }[info.menuItemId];
  if (payload?.raw) drop(payload);
});

/** Success is quiet, failure is loud — never pretend a send worked while offline */
export async function drop(payload) {
  try {
    const res = await send(payload);
    flash('✓', '#15803d', res.deduped ? t.alreadySent(res.seq) : t.sent(res.seq));
    return res;
  } catch (err) {
    flash('✗', '#b91c1c', err.message);
    chrome.notifications.create({
      type: 'basic', iconUrl: 'icon.png', title: t.failedTitle, message: err.message,
    });
    throw err;
  }
}

function flash(text, color, title) {
  chrome.action.setBadgeText({ text });
  chrome.action.setBadgeBackgroundColor({ color });
  chrome.action.setTitle({ title: `dropit: ${title}` });
  setTimeout(() => {
    chrome.action.setBadgeText({ text: '' });
    chrome.action.setTitle({ title: t.actionTitle });
  }, 2500);
}
