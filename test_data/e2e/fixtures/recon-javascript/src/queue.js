import { formatDate, initials } from './format.js';

/** The statuses the queue panel lists; a ticket with any other status counts as open. */
const STATUS_ORDER = ['new', 'open', 'pending', 'resolved'];

function statusOf(ticket) {
  const status = String(ticket.status ?? 'open').toLowerCase();
  return STATUS_ORDER.includes(status) ? status : 'open';
}

export function countByStatus(tickets) {
  const counts = Object.fromEntries(STATUS_ORDER.map((status) => [status, 0]));
  for (const ticket of tickets) counts[statusOf(ticket)] += 1;
  return counts;
}

export function filterTickets(tickets, status) {
  return status ? tickets.filter((ticket) => statusOf(ticket) === status) : tickets;
}

/** The side panel: one line per status, built from text nodes only. */
export function renderQueueSummary(container, tickets) {
  if (!container) return;
  const counts = countByStatus(tickets);
  const rows = STATUS_ORDER.map((status) => {
    const row = document.createElement('li');
    row.dataset.status = status;
    row.textContent = `${status}: ${counts[status]}`;
    return row;
  });
  const avatar = document.createElement('span');
  avatar.className = 'avatar';
  avatar.title = tickets[0]?.assignee ?? '';
  avatar.textContent = initials(tickets[0]?.assignee);
  const refreshed = document.createElement('p');
  refreshed.className = 'refreshed';
  refreshed.textContent = `Last refresh ${formatDate(new Date())}`;
  container.replaceChildren(avatar, ...rows, refreshed);
}
