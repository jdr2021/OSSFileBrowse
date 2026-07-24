package net.jdr2021.search;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.regex.PatternSyntaxException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SearchRuleLoaderTest {
    @Test
    public void loadsEnabledBundledRules() throws Exception {
        SearchRuleLoader.Result loaded = SearchRuleLoader.loadDefault();

        assertTrue(loaded.getRules().size() >= 30);
        assertTrue(loaded.getWarnings().toString(),
                loaded.getWarnings().isEmpty());
        assertTrue(hasRule(loaded.getRules(), "JSON Web Token"));
        assertTrue(hasRule(loaded.getRules(), "All URL"));
        assertFalse(hasRule(loaded.getRules(), "Request URI"));
    }

    @Test
    public void appliesEditorAndCheckboxSemantics() {
        List<SearchRule> configured = Arrays.asList(
                SearchRule.configured("Rules", "Rule A",
                        "secret", "", "{0}"));

        List<SearchRule> emptyEditor =
                SearchRuleSelector.select(configured, "", false);
        assertEquals(1, emptyEditor.size());
        assertEquals("Rule A", emptyEditor.get(0).getName());

        List<SearchRule> customOnly =
                SearchRuleSelector.select(configured, "domain\\.test", false);
        assertEquals(1, customOnly.size());
        assertTrue(customOnly.get(0).isCustom());

        List<SearchRule> exactWhitespace =
                SearchRuleSelector.select(configured, " secret ", false);
        assertEquals(" secret ",
                exactWhitespace.get(0).getPrimary().pattern());

        List<SearchRule> combined =
                SearchRuleSelector.select(configured, "10\\.0\\.0\\.1", true);
        assertEquals(2, combined.size());
        assertEquals("Rule A", combined.get(0).getName());
        assertTrue(combined.get(1).isCustom());
    }

    @Test(expected = PatternSyntaxException.class)
    public void validatesCustomJavaRegex() {
        SearchRuleSelector.select(null, "([", false);
    }

    private static boolean hasRule(List<SearchRule> rules, String name) {
        for (SearchRule rule : rules) {
            if (name.equals(rule.getName())) {
                return true;
            }
        }
        return false;
    }
}
