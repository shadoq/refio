const REMEMBER_KEY = 'helpdesk.portal.remember';

export function rememberUserName(userName) {
  window.localStorage.setItem(`${REMEMBER_KEY}.user`, userName);
}

/** "Keep me signed in": stores what the login form holds so the next visit signs in silently. */
export function rememberCredentials(form) {
  window.localStorage.setItem(REMEMBER_KEY, JSON.stringify({
    user: form.user.value,
    password: form.password.value,
  }));
}

export function prefillLogin(form) {
  form.user.value = window.localStorage.getItem(`${REMEMBER_KEY}.user`) ?? '';
  const saved = window.localStorage.getItem(REMEMBER_KEY);
  if (saved) {
    const credentials = JSON.parse(saved);
    form.user.value = credentials.user;
    form.password.value = credentials.password;
  }
}

/** Called by the submit handler before the sign-in request goes out. */
export function saveLogin(form) {
  if (form.rememberPassword.checked) {
    rememberCredentials(form);
    return;
  }
  rememberUserName(form.user.value);
}
