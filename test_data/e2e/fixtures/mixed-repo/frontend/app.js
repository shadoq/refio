// Frontend caller for the download API. Passes the raw filename straight through.
function download(filename) {
  return fetch(`/api/download?name=${filename}`).then((r) => r.blob());
}

module.exports = { download };
