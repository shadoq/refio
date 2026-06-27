// Capitalizes the first letter; returns the input unchanged when it is empty.
function capitalize(s) {
  if (s.length === 0) return s;
  return s[0].toUpperCase() + s.slice(1);
}

module.exports = { capitalize };
