import html


def render_error(status, message):
    return ("<html><body><h1>%d</h1><p>%s</p></body></html>"
            % (status, html.escape(message)))


def render_results(hits):
    items = "".join("<li>%s</li>" % html.escape(hit["title"]) for hit in hits)
    return "<html><body><ul>%s</ul></body></html>" % items


def render_note(note):
    return ("<html><body><h1>%s</h1><p>%s</p></body></html>"
            % (html.escape(note["title"]), html.escape(note["body"])))
