package de.mhus.hrafnagud.munin.net;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;

/**
 * A parsed {@code robots.txt}.
 *
 * <p>Implements the matching rules the Robots Exclusion Protocol
 * (RFC 9309) actually specifies, which differ from the naive reading in
 * three ways that matter:
 *
 * <ul>
 *   <li>the group selected is the one whose user-agent token matches ours,
 *       falling back to {@code *} — not the union of both;</li>
 *   <li>the <em>longest</em> matching rule wins, so a broad
 *       {@code Disallow: /} is overridden by a specific
 *       {@code Allow: /news/};</li>
 *   <li>on equal length, {@code Allow} wins.</li>
 * </ul>
 *
 * <p>An empty {@code Disallow:} is an explicit "everything is allowed", not
 * a rule matching every path — getting that backwards would lock us out of
 * the many sites that use it.
 */
public final class RobotsRules {

    /** Used when a host has no {@code robots.txt} or it could not be read. */
    public static final RobotsRules ALLOW_ALL = new RobotsRules(List.of());

    /** Used when {@code robots.txt} could not be fetched but the host answered 4xx/5xx deliberately. */
    public static final RobotsRules DENY_ALL =
            new RobotsRules(List.of(new Rule(false, "/", Pattern.compile(".*"))));

    private final List<Rule> rules;

    private RobotsRules(List<Rule> rules) {
        this.rules = rules;
    }

    private record Rule(boolean allow, String path, Pattern pattern) {
    }

    /**
     * Parses a {@code robots.txt} body for the given agent token.
     *
     * @param body       file contents
     * @param agentToken our product token, e.g. {@code hrafnagud}; matched
     *                   case-insensitively against the declared user agents
     */
    public static RobotsRules parse(String body, String agentToken) {
        Map<String, List<Rule>> groups = new LinkedHashMap<>();
        List<String> currentAgents = new ArrayList<>();
        boolean inGroupHeader = false;

        for (String rawLine : body.split("\\R")) {
            String line = stripComment(rawLine).trim();
            if (line.isEmpty()) {
                continue;
            }
            int colon = line.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String field = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();

            switch (field) {
                case "user-agent" -> {
                    // Consecutive user-agent lines address one group. A
                    // user-agent line after a rule starts a new group.
                    if (!inGroupHeader) {
                        currentAgents = new ArrayList<>();
                        inGroupHeader = true;
                    }
                    currentAgents.add(value.toLowerCase(Locale.ROOT));
                }
                case "allow", "disallow" -> {
                    inGroupHeader = false;
                    if (currentAgents.isEmpty()) {
                        continue;
                    }
                    // An empty value carries no path to match. For Disallow
                    // that is the idiomatic "nothing is forbidden"; for
                    // Allow it is meaningless. Skipping both is correct,
                    // since an absent rule means allowed anyway.
                    if (value.isEmpty()) {
                        continue;
                    }
                    boolean allow = "allow".equals(field);
                    Rule rule = new Rule(allow, value, toPattern(value));
                    for (String agent : currentAgents) {
                        groups.computeIfAbsent(agent, k -> new ArrayList<>()).add(rule);
                    }
                }
                default -> {
                    // Sitemap, Crawl-delay, Host and vendor extensions are
                    // not group members and must not end a group header.
                }
            }
        }

        String token = agentToken.toLowerCase(Locale.ROOT);
        List<Rule> selected = groups.get(token);
        if (selected == null) {
            selected = groups.entrySet().stream()
                    .filter(e -> !"*".equals(e.getKey()) && token.contains(e.getKey()))
                    .map(Map.Entry::getValue)
                    .findFirst()
                    .orElse(groups.get("*"));
        }
        return selected == null ? ALLOW_ALL : new RobotsRules(List.copyOf(selected));
    }

    /** Whether {@code path} (including query) may be fetched. */
    public boolean isAllowed(String path) {
        String candidate = StringUtils.isEmpty(path) ? "/" : path;
        Rule best = null;
        for (Rule rule : rules) {
            if (!rule.pattern().matcher(candidate).find()) {
                continue;
            }
            if (best == null
                    || rule.path().length() > best.path().length()
                    || (rule.path().length() == best.path().length() && rule.allow())) {
                best = rule;
            }
        }
        return best == null || best.allow();
    }

    /** Number of rules that applied to our agent. */
    public int ruleCount() {
        return rules.size();
    }

    private static String stripComment(String line) {
        int hash = line.indexOf('#');
        return hash < 0 ? line : line.substring(0, hash);
    }

    /**
     * Translates a robots path pattern into a regex anchored at the start of
     * the path. {@code *} matches any run of characters and a trailing
     * {@code $} anchors the end; everything else is literal.
     */
    private static Pattern toPattern(String value) {
        boolean anchorEnd = value.endsWith("$");
        String body = anchorEnd ? value.substring(0, value.length() - 1) : value;

        StringBuilder regex = new StringBuilder("^");
        StringBuilder literal = new StringBuilder();
        for (char c : body.toCharArray()) {
            if (c == '*') {
                if (literal.length() > 0) {
                    regex.append(Pattern.quote(literal.toString()));
                    literal.setLength(0);
                }
                regex.append(".*");
            } else {
                literal.append(c);
            }
        }
        if (literal.length() > 0) {
            regex.append(Pattern.quote(literal.toString()));
        }
        if (anchorEnd) {
            regex.append('$');
        }
        return Pattern.compile(regex.toString());
    }
}
