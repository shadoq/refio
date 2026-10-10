// Framework-free i18n helpers: message types, placeholder filling and plural forms.
// Kept apart from React so the rules are testable on their own.

export type Lang = "en" | "pl";

export const LANGS: Lang[] = ["en", "pl"];

export type Vars = Record<string, string | number>;

// A message is either plain text with {placeholders} or a function, for wording
// that depends on a number (plural forms) or needs other logic.
export type Message = string | ((vars: Vars) => string);

export type Messages = Record<string, Message>;

// The same keys as the English namespace, each carrying its own translation.
export type Translation<T extends Messages> = { [K in keyof T]: Message };

export function isLang(value: unknown): value is Lang {
  return typeof value === "string" && (LANGS as string[]).includes(value);
}

// "{count} models" + {count: 3} -> "3 models". An unknown placeholder stays visible
// so a missing variable shows up on screen instead of silently disappearing.
export function interpolate(text: string, vars: Vars = {}): string {
  return text.replace(/\{(\w+)\}/g, (match, name: string) =>
    name in vars ? String(vars[name]) : match,
  );
}

export function renderMessage(message: Message, vars: Vars = {}): string {
  return typeof message === "function" ? message(vars) : interpolate(message, vars);
}

const enRules = new Intl.PluralRules("en");
const plRules = new Intl.PluralRules("pl");

// English: 1 model, 2 models.
export function pluralEn(count: number, one: string, other: string): string {
  return enRules.select(count) === "one" ? one : other;
}

// Polish has three forms: 1 model, 2-4 modele, 5+ modeli (22 modele, 25 modeli).
// Fractions take the "few"-like genitive form ("1,5 modelu") - callers pass that
// as `other` when they need it, otherwise `many` is used.
export function pluralPl(
  count: number,
  one: string,
  few: string,
  many: string,
  other: string = many,
): string {
  switch (plRules.select(count)) {
    case "one":
      return one;
    case "few":
      return few;
    case "many":
      return many;
    default:
      return other;
  }
}
