import { attachmentUrl, postForm } from './api.js';

export function renderTicketList(container, tickets) {
  container.replaceChildren(...tickets.map((ticket) => {
    const row = document.createElement('li');
    const id = document.createElement('span');
    id.textContent = `#${ticket.id}`;
    const subject = document.createElement('a');
    subject.textContent = ticket.subject;
    subject.href = `#/tickets/${encodeURIComponent(ticket.id)}`;
    row.append(id, subject);
    return row;
  }));
}

/** The ticket page: subject, requester and description as the agent typed them. */
export function renderTicketDetail(container, ticket) {
  container.innerHTML = `
    <article class="ticket">
      <h2>${ticket.subject}</h2>
      <p class="meta">${ticket.requester} opened this ticket on ${ticket.created}</p>
      <p class="body">${ticket.description}</p>
    </article>`;
}

export function renderAttachments(container, files) {
  container.replaceChildren(...files.map((file) => {
    const link = document.createElement('a');
    link.textContent = file.name;
    link.href = attachmentUrl(file.name);
    return link;
  }));
}

export function exportTicket(ticketId, format) {
  return postForm('/api/export', { ticket: ticketId, format });
}
