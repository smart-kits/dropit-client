import { send } from './api.js';

const MENUS = [
  { id: 'selection', title: '把选中的字投进 dropit', contexts: ['selection'] },
  { id: 'link', title: '把这个链接投进 dropit', contexts: ['link'] },
  { id: 'image', title: '把图片地址投进 dropit', contexts: ['image'] },
  { id: 'page', title: '把这个页面投进 dropit', contexts: ['page'] },
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
    flash('✓', '#15803d', res.deduped ? `已投过了 #${res.seq}` : `已投递 #${res.seq}`);
    return res;
  } catch (err) {
    flash('✗', '#b91c1c', err.message);
    chrome.notifications.create({
      type: 'basic', iconUrl: 'icon.png', title: 'dropit 投递失败', message: err.message,
    });
    throw err;
  }
}

function flash(text, color, title) {
  chrome.action.setBadgeText({ text });
  chrome.action.setBadgeBackgroundColor({ color });
  chrome.action.setTitle({ title: `dropit：${title}` });
  setTimeout(() => {
    chrome.action.setBadgeText({ text: '' });
    chrome.action.setTitle({ title: '投进 dropit' });
  }, 2500);
}
