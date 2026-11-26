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

  window.SupportPortal = portal;
})();
