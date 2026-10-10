// @vitest-environment node
import { describe, it, expect } from "vitest";
import { interpolate, isLang, pluralPl, renderMessage } from "@/i18n/core";
import { en, pl } from "@/i18n/messages";

describe("pluralPl", () => {
  const models = (n: number) => `${n} ${pluralPl(n, "model", "modele", "modeli")}`;

  it("uses the three Polish forms, including the teens and compound numbers", () => {
    expect(models(1)).toBe("1 model");
    expect(models(3)).toBe("3 modele");
    expect(models(5)).toBe("5 modeli");
    expect(models(12)).toBe("12 modeli");
    expect(models(22)).toBe("22 modele");
    expect(models(25)).toBe("25 modeli");
  });
});

describe("interpolate", () => {
  it("fills placeholders and leaves an unknown one visible", () => {
    expect(interpolate("{count} models, {missing}", { count: 3 })).toBe("3 models, {missing}");
  });
});

describe("renderMessage", () => {
  it("calls a function message with the variables", () => {
    expect(renderMessage(({ n }) => `n=${n}`, { n: 2 })).toBe("n=2");
  });
});

describe("isLang", () => {
  it("accepts only supported languages, so a stale stored value falls back", () => {
    expect(isLang("pl")).toBe(true);
    expect(isLang("de")).toBe(false);
    expect(isLang(null)).toBe(false);
  });
});

// Types already force every English key into the Polish dictionary; this catches a
// key copied over but left untranslated as an empty string.
describe("dictionaries", () => {
  it("give every Polish message some text", () => {
    for (const [ns, messages] of Object.entries(pl)) {
      for (const [key, message] of Object.entries(messages)) {
        if (typeof message === "string") expect(message, `${ns}.${key}`).not.toBe("");
      }
      expect(Object.keys(messages).sort()).toEqual(
        Object.keys(en[ns as keyof typeof en]).sort(),
      );
    }
  });
});
