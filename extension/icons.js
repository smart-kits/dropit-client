/** The popup's icons: 24-unit grid, 1.75 stroke, round caps, one colour (currentColor) */
const PATHS = {
  copy: '<rect x="9" y="9" width="11" height="11" rx="3"/><path d="M5 15V7a3 3 0 0 1 3-3h8"/>',
  link: '<path d="M10 14a4.5 4.5 0 0 0 6.4 0l3-3a4.5 4.5 0 0 0-6.4-6.4l-1 1"/><path d="M14 10a4.5 4.5 0 0 0-6.4 0l-3 3a4.5 4.5 0 0 0 6.4 6.4l1-1"/>',
  text: '<path d="M5 7h14M5 12h14M5 17h9"/>',
  image: '<rect x="3.5" y="4.5" width="17" height="15" rx="3"/><circle cx="9" cy="10" r="1.5"/><path d="M4 17l5-4.5 4 3.5 3-2.5 4.5 3.5"/>',
  file: '<path d="M14 3H8a3 3 0 0 0-3 3v12a3 3 0 0 0 3 3h8a3 3 0 0 0 3-3V8z"/><path d="M14 3v5h5"/>',
  check: '<path d="M5 12.5l4.5 4.5L19 7.5"/>',
  retry: '<path d="M20 12a8 8 0 1 1-2.6-5.9"/><path d="M20 4v5h-5"/>',
  alert: '<circle cx="12" cy="12" r="9"/><path d="M12 7.5v5.5M12 16.2v.1"/>',
  clip: '<path d="M21 12.5l-8.6 8.6a5.5 5.5 0 0 1-7.8-7.8l9-9a3.7 3.7 0 0 1 5.2 5.2l-9 9a1.8 1.8 0 0 1-2.6-2.6l8.3-8.3"/>',
  plus: '<path d="M12 5v14M5 12h14"/>',
  qr: '<rect x="4" y="4" width="6" height="6" rx="1"/><rect x="14" y="4" width="6" height="6" rx="1"/><rect x="4" y="14" width="6" height="6" rx="1"/><path d="M14 14h2.5v2.5M20 14v.1M14 20h2.5M20 17v3"/>',
  enter: '<path d="M4 12h12M11.5 6.5 17 12l-5.5 5.5M20 5v14"/>',
  chev: '<path d="M9 5l7 7-7 7"/>',
  phone: '<rect x="7" y="3" width="10" height="18" rx="2.5"/><path d="M11 17.5h2"/>',
  laptop: '<rect x="5" y="5" width="14" height="10" rx="2"/><path d="M3 19h18"/>',
  terminal: '<rect x="3.5" y="5" width="17" height="14" rx="3"/><path d="M8 10l3 2.5L8 15M13 15h3.5"/>',
  devices: '<rect x="2.5" y="4.5" width="13" height="9" rx="1.8"/><path d="M6 17.5h6M9 13.5v4"/><rect x="17.5" y="8" width="4" height="9.5" rx="1.2"/>',
  down: '<path d="M6 9l6 6 6-6"/>',
  back: '<path d="M15 5l-7 7 7 7"/>',
  sun: '<circle cx="12" cy="12" r="3.8"/><path d="M12 3v2.2M12 18.8V21M3 12h2.2M18.8 12H21M5.6 5.6l1.6 1.6M16.8 16.8l1.6 1.6M18.4 5.6l-1.6 1.6M7.2 16.8l-1.6 1.6"/>',
};

/** An <svg class="i"> for `name` */
export function icon(name) {
  const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  svg.setAttribute('viewBox', '0 0 24 24');
  svg.setAttribute('aria-hidden', 'true');
  svg.setAttribute('class', 'i');
  svg.innerHTML = PATHS[name];       // fixed strings above, never page or user text
  return svg;
}
