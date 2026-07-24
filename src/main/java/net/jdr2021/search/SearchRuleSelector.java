package net.jdr2021.search;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Applies the search-editor and "规则" checkbox selection semantics. */
public final class SearchRuleSelector {
    private SearchRuleSelector() {
    }

    public static List<SearchRule> select(List<SearchRule> configured,
                                          String editorValue,
                                          boolean includeConfigured) {
        List<SearchRule> available = configured == null
                ? Collections.<SearchRule>emptyList() : configured;
        String query = editorValue == null ? "" : editorValue;
        List<SearchRule> selected = new ArrayList<SearchRule>();
        if (query.trim().isEmpty()) {
            selected.addAll(available);
        } else {
            if (includeConfigured) {
                selected.addAll(available);
            }
            selected.add(SearchRule.custom(query));
        }
        return Collections.unmodifiableList(selected);
    }
}
