/**
 * Translation by calling a model directly, without a brain in between.
 *
 * <p>A second {@link de.mhus.hrafnagud.hugin.translate.TranslationProvider}
 * beside the one that fires a Vancetope event. Which of them runs is a setting,
 * so both can be pointed at the same articles and compared afterwards out of
 * {@code enrichments} — each result records the model that produced it.
 *
 * <p>Uses langchain4j's Google-AI client and nothing else from it: no agent, no
 * tools, no memory. One prompt, a response schema, an answer.
 */
@NullMarked
package de.mhus.hrafnagud.hugin.gemini;

import org.jspecify.annotations.NullMarked;
