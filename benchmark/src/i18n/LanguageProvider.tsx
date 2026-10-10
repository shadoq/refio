import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from "react";
import { isLang, renderMessage, type Lang, type Vars } from "./core";
import { lookup, type MessageKey } from "./messages";

const STORAGE_KEY = "benchmark-lang";

interface LanguageState {
  lang: Lang;
  setLang: (lang: Lang) => void;
}

// Outside a provider (a component rendered on its own in a test) the app reads
// English and the switch does nothing.
const LanguageContext = createContext<LanguageState>({ lang: "en", setLang: () => {} });

function initialLang(): Lang {
  try {
    const stored = window.localStorage.getItem(STORAGE_KEY);
    if (isLang(stored)) return stored;
  } catch {
    // Storage blocked (private mode) - fall through to the browser language.
  }
  return navigator.language.toLowerCase().startsWith("pl") ? "pl" : "en";
}

export function LanguageProvider({ children }: { children: ReactNode }) {
  const [lang, setLang] = useState<Lang>(initialLang);

  useEffect(() => {
    document.documentElement.lang = lang;
    try {
      window.localStorage.setItem(STORAGE_KEY, lang);
    } catch {
      // Not remembered across visits, the page still works.
    }
  }, [lang]);

  const value = useMemo(() => ({ lang, setLang }), [lang]);
  return <LanguageContext.Provider value={value}>{children}</LanguageContext.Provider>;
}

export function useLang(): LanguageState {
  return useContext(LanguageContext);
}

export type Translate = (key: MessageKey, vars?: Vars) => string;

export function useT(): Translate {
  const { lang } = useLang();
  return useCallback((key, vars) => renderMessage(lookup(lang, key), vars), [lang]);
}
