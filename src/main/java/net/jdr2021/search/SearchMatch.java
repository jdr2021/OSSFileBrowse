package net.jdr2021.search;

/** One matched sample and the remote file or archive entry that contains it. */
public final class SearchMatch {
    private final String ruleGroup;
    private final String ruleName;
    private final String sample;
    private final String sourceLink;
    private final String href;

    public SearchMatch(String ruleGroup,
                       String ruleName,
                       String sample,
                       String sourceLink,
                       String href) {
        this.ruleGroup = ruleGroup;
        this.ruleName = ruleName;
        this.sample = sample;
        this.sourceLink = sourceLink;
        this.href = href;
    }

    public String getRuleGroup() {
        return ruleGroup;
    }

    public String getRuleName() {
        return ruleName;
    }

    public String getSample() {
        return sample;
    }

    public String getSourceLink() {
        return sourceLink;
    }

    public String getHref() {
        return href;
    }
}
