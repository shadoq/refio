// Turns a title into a url-safe slug, optionally cut to a maximum length.
function slugify(title, maxLength) {
  const slug = title
    .toLowerCase()
    .normalize("NFD")
    .replace(/[̀-ͯ]/g, "")
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-+|-+$/g, "");
  if (maxLength === undefined) return slug;
  return slug.slice(0, maxLength).replace(/-+$/g, "");
}

module.exports = { slugify };
