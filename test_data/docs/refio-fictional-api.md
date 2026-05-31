# Zeta Retrieval Tuning API (fictional)

> This document is a **test fixture**. Everything in it is invented. It exists so
> the manual tests can prove that the model retrieved content from the indexed
> documentation corpus rather than from prior knowledge or from the source code.

## Overview

The Zeta Retrieval subsystem controls how candidate fragments are scored before
they are handed to the reranker. It is tuned through a small set of configuration
keys read at session start. None of these keys exist in the real Refio codebase;
they are here purely to act as retrieval needles.

## Configuration keys

The most important tuning knob is the similarity gate. When the gate is too low
the reranker is flooded with weak candidates; too high and recall collapses. The
recommended production value of the similarity gate is expressed by the key:

    refio.zeta_threshold = 0.73   # REFIO_DOC_NEEDLE{md_alpha_7Q}

A second knob, `refio.zeta_window`, bounds how many fragments enter the scoring
window. The window defaults to 24 fragments and should never exceed 64, because
the scoring pass is quadratic in window size.

## Iteration budget (intentional drift)

This document deliberately disagrees with the real code so that the
documentation-vs-code scoping test (T51) has something to detect. According to
this fixture, the Zeta loop runs with:

    maxIterations = 99            # REFIO_DOC_CONFLICT{docs_says_99}

The real `TurnLoopConfig` in the codebase uses different values. A model that
answers "99" while citing this document has correctly scoped its answer to the
documentation index; a model that answers with the real code value has either
ignored the `content_type=DOCUMENTATION` filter or read the source instead.

## Operational notes

Zeta tuning is hot-reloadable. Changing `refio.zeta_threshold` takes effect on
the next retrieval call without restarting the session. The subsystem logs a
single line `[ZetaTuning] gate=<value>` whenever the gate changes, which is the
canonical way to confirm a new value was picked up.
