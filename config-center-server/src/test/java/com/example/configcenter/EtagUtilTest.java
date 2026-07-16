package com.example.configcenter;

import com.example.configcenter.service.EtagUtil;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EtagUtilTest {

    @Test
    void fieldSerialization_isUnambiguousAndKeepsWeakFormat() {
        String splitOne = EtagUtil.weakEtagForFields(List.of("a", "bc"));
        String splitTwo = EtagUtil.weakEtagForFields(List.of("ab", "c"));
        String emptyThenNull = EtagUtil.weakEtagForFields(Arrays.asList("", null));
        String nullThenEmpty = EtagUtil.weakEtagForFields(Arrays.asList(null, ""));

        assertNotEquals(splitOne, splitTwo);
        assertNotEquals(emptyThenNull, nullThenEmpty);
        assertTrue(splitOne.startsWith("W/\""));
        assertTrue(splitOne.endsWith("\""));
    }
}
