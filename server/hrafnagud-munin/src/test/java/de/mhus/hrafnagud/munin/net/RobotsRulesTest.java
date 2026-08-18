package de.mhus.hrafnagud.munin.net;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The three rules that are easy to get backwards — group selection, longest
 * match, and the meaning of an empty {@code Disallow} — are each worth a
 * test, because getting any of them wrong either locks us out of most of the
 * web or ignores publishers who said no.
 */
class RobotsRulesTest {

    @Test
    void emptyDisallow_meansEverythingIsAllowed() {
        // The idiom for "no restrictions". Reading it as "matches every path"
        // would lock us out of a large share of sites.
        RobotsRules rules = RobotsRules.parse("""
                User-agent: *
                Disallow:
                """, "hrafnagud");
        assertThat(rules.isAllowed("/anything")).isTrue();
    }

    @Test
    void disallowRoot_blocksEverything() {
        RobotsRules rules = RobotsRules.parse("""
                User-agent: *
                Disallow: /
                """, "hrafnagud");
        assertThat(rules.isAllowed("/news/story")).isFalse();
    }

    @Test
    void longerRuleWins_soASpecificAllowOverridesABroadDisallow() {
        RobotsRules rules = RobotsRules.parse("""
                User-agent: *
                Disallow: /
                Allow: /news/
                """, "hrafnagud");
        assertThat(rules.isAllowed("/news/story")).isTrue();
        assertThat(rules.isAllowed("/private/x")).isFalse();
    }

    @Test
    void onEqualLength_allowWins() {
        RobotsRules rules = RobotsRules.parse("""
                User-agent: *
                Disallow: /x
                Allow: /x
                """, "hrafnagud");
        assertThat(rules.isAllowed("/x")).isTrue();
    }

    @Test
    void ourAgentGroupWins_overTheWildcardGroup() {
        // Not the union of both: a publisher who wrote a specific group for
        // us meant it to replace the general one.
        RobotsRules rules = RobotsRules.parse("""
                User-agent: *
                Disallow: /

                User-agent: hrafnagud
                Disallow: /admin
                """, "hrafnagud");
        assertThat(rules.isAllowed("/news/story")).isTrue();
        assertThat(rules.isAllowed("/admin/panel")).isFalse();
    }

    @Test
    void wildcardGroup_appliesWhenNoAgentGroupMatches() {
        RobotsRules rules = RobotsRules.parse("""
                User-agent: googlebot
                Disallow:

                User-agent: *
                Disallow: /secret
                """, "hrafnagud");
        assertThat(rules.isAllowed("/secret/x")).isFalse();
        assertThat(rules.isAllowed("/public")).isTrue();
    }

    @Test
    void consecutiveUserAgentLines_shareOneGroup() {
        RobotsRules rules = RobotsRules.parse("""
                User-agent: hrafnagud
                User-agent: someotherbot
                Disallow: /blocked
                """, "hrafnagud");
        assertThat(rules.isAllowed("/blocked/x")).isFalse();
    }

    @Test
    void pathWildcard_isHonoured() {
        RobotsRules rules = RobotsRules.parse("""
                User-agent: *
                Disallow: /*/private
                """, "hrafnagud");
        assertThat(rules.isAllowed("/section/private/x")).isFalse();
        assertThat(rules.isAllowed("/section/public")).isTrue();
    }

    @Test
    void endAnchor_restrictsToAnExactSuffix() {
        RobotsRules rules = RobotsRules.parse("""
                User-agent: *
                Disallow: /*.pdf$
                """, "hrafnagud");
        assertThat(rules.isAllowed("/docs/report.pdf")).isFalse();
        assertThat(rules.isAllowed("/docs/report.pdf.html")).isTrue();
    }

    @Test
    void commentsAndUnknownDirectives_areIgnoredWithoutEndingTheGroup() {
        // Crawl-delay and Sitemap sit inside groups in the wild; treating
        // them as group terminators would orphan the rules after them.
        RobotsRules rules = RobotsRules.parse("""
                # a comment
                User-agent: *
                Crawl-delay: 10
                Sitemap: https://example.com/sitemap.xml
                Disallow: /nope   # trailing comment
                """, "hrafnagud");
        assertThat(rules.isAllowed("/nope/x")).isFalse();
        assertThat(rules.ruleCount()).isEqualTo(1);
    }

    @Test
    void emptyDocument_allowsEverything() {
        assertThat(RobotsRules.parse("", "hrafnagud").isAllowed("/x")).isTrue();
    }

    @Test
    void rulesBeforeAnyUserAgentLine_areIgnored() {
        RobotsRules rules = RobotsRules.parse("""
                Disallow: /orphan
                User-agent: *
                Disallow: /real
                """, "hrafnagud");
        assertThat(rules.isAllowed("/orphan")).isTrue();
        assertThat(rules.isAllowed("/real")).isFalse();
    }

    @Test
    void allowAll_andDenyAll_behaveAsNamed() {
        assertThat(RobotsRules.ALLOW_ALL.isAllowed("/anything")).isTrue();
        assertThat(RobotsRules.DENY_ALL.isAllowed("/anything")).isFalse();
    }
}
