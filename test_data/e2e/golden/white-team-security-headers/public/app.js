// Shared front-end code of the support portal. Every page loads it through
// `<script src="/static/app.js" defer>` and says which page it is through
// `<body data-page="...">`, so no page needs an inline <script> block or an on...=
// attribute (both would be blocked by the Content-Security-Policy).
// Shared front-end code of the support portal.
(function () {
  "use strict";

  var portal = {};

  // Marks the first occurrence of `term` inside `element` with <mark>.
  portal.mark = function (element, term) {
    var text = element.textContent;
    var index = text.toLowerCase().indexOf(term.toLowerCase());
    if (index < 0) {
      return;
    }
    var mark = document.createElement("mark");
    mark.textContent = text.slice(index, index + term.length);
    element.textContent = text.slice(0, index);
    element.appendChild(mark);
    element.appendChild(document.createTextNode(text.slice(index + term.length)));
  };

  // Dashboard: fills the notes box.
  portal.loadNotes = function () {
    var box = document.getElementById("notes");
    if (!box) {
      return;
    }
    box.textContent = "Open tickets: 7. Escalated: 1.";
    portal.mark(box, "Escalated");
  };

  function focusField(id) {
    var field = document.getElementById(id);
    if (field) {
      field.focus();
    }
  }

  function start() {
    var page = document.body.getAttribute("data-page");
    if (page === "search") {
      focusField("q");
    } else if (page === "login") {
      focusField("username");
    } else if (page === "dashboard") {
      var button = document.getElementById("refresh-notes");
      if (button) {
        button.addEventListener("click", portal.loadNotes);
      }
      portal.loadNotes();
    }
  }

  window.SupportPortal = portal;

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", start);
  } else {
    start();
  }
})();
