package net.jdr2021.utils;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;

public class RequestHeaderPolicyTest {
    @Test
    public void keepsLatestCaseInsensitiveHeaderAndFiltersControlledHeaders() {
        Map<String, String> input = new LinkedHashMap<>();
        input.put("Authorization", "first");
        input.put("authorization", "second");
        input.put("Host", "other.invalid");
        input.put("X-Api-Key", "secret");

        Map<String, String> result = RequestHeaderPolicy.sanitize(input);

        assertEquals(2, result.size());
        assertEquals("second", result.get("authorization"));
        assertEquals("secret", result.get("X-Api-Key"));
        assertFalse(result.containsKey("Host"));
    }

    @Test
    public void rejectsHeaderInjection() {
        Map<String, String> input = new LinkedHashMap<>();
        input.put("X-Test", "ok\r\nX-Injected: yes");
        assertThrows(IllegalArgumentException.class,
                () -> RequestHeaderPolicy.sanitize(input));
    }
}
