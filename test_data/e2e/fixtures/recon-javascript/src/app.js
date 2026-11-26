import { loadConfig } from './config.js';
import { startAutoRefresh } from './refresh.js';
import { renderQueueSummary } from './queue.js';
import { searchTickets } from './api.js';
import { renderTicketDetail, renderTicketList } from './tickets.js';
import { prefillLogin, saveLogin } from './account.js';

const views = {
  home(container) {
    renderTicketList(container, []);
  },
  async tickets(container, params) {
    const result = await searchTickets(params.get('q') ?? '');
    renderTicketList(container, result.tickets);
    renderQueueSummary(container.ownerDocument.querySelector('aside.queue'), result.tickets);
  },
  ticket(container, params) {
    renderTicketDetail(container, {
      id: params.get('id'),
      subject: window.history.state?.subject ?? 'Ticket',
      requester: loadConfig().user?.name ?? 'you',
      created: new Date().toDateString(),
      description: window.history.state?.description ?? '',
    });
  },
};

export function navigate(hash) {
  const [path, query] = hash.replace(/^#\/?/, '').split('?');
  const [route, ...rest] = path.split('/');
  const params = new URLSearchParams(query ?? '');
  const container = window.document.querySelector('main');
  const view = views[route ?? 'home'];
  if (view) view(container, new URLSearchParams([['id', rest[0] ?? ''], ...params]));
}

export function start(doc = window.document) {
  const config = loadConfig(doc);
  doc.documentElement.lang = config.locale;
  const form = doc.querySelector('form.login');
  if (form) {
    prefillLogin(form);
    form.addEventListener('submit', () => saveLogin(form));
  }
  window.addEventListener('hashchange', () => navigate(window.location.hash));
  navigate(window.location.hash);
  // Since 2.1.0 the list picks up changes made by other agents without a reload.
  startAutoRefresh(doc, () => navigate(window.location.hash), config.refreshSeconds);
}
