/**
 * translation kit / scripts/translate-article.js
 *
 * @description Translates title and teaser via LightLlmService
 * @version     2.1.0
 * @timeout     120s
 */
// The whole chain in a few lines: the POST body arrives under
// `args.payload`, `vance.llm.callForJsonWithModel` is ScriptLightLlmApi →
// LightLlmService (so the recipe must be `internal: true`, model aliases
// and the setting cascade apply), and the returned object becomes the
// event response's `output`.
//
// One call for both fields: at realistic volume the recipe prompt
// dominates the token bill, so a second call would double the expensive
// half to save nothing.
//
// `...WithModel` rather than plain `callForJson` because the caller
// archives what comes back. Which model answered is not derivable from
// the recipe — `params.model` is an alias, and a fallback may have
// stepped in — so it has to travel with the answer or it is lost.
//
// IIFE because the executor takes the value of the last expression — a
// bare top-level `return` is a SyntaxError in GraalJS.

(function () {
    var payload = (args && args.payload) ? args.payload : {};
    var title = payload.title || "";
    var summary = payload.summary || "";
    var targetLang = payload.targetLang || "de";

    if (!title) {
        return { title: "", summary: "", model: null };
    }

    var answer = vance.llm.callForJsonWithModel(
        "article-translate",
        "Translate the title and teaser into " + targetLang + ".",
        { title: title, summary: summary, targetLang: targetLang });

    var result = answer.result || {};
    return {
        title: result.title || "",
        summary: result.summary || "",
        // May be null. A consumer must store that as unknown rather than
        // substituting the model it assumed.
        model: answer.model || null
    };
})();
