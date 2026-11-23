package com.example.configcenter;

import com.example.configcenter.service.EtagUtil;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class EtagUtilTest {

    @Test
    void fieldSerialization_isUnambiguousAndKeepsWeakFormat() {
        String splitOne = EtagUtil.weakEtagForFields(List.of("a", "bc"));
        String splitTwo = EtagUtil.weakEtagForFields(List.of("ab", "c"));
        String emptyThenNull = EtagUtil.weakEtagForFields(Arrays.asList("", null));
        String nullThenEmpty = EtagUtil.weakEtagForFields(Arrays.asList(null, ""));

        assertNotEquals(splitOne, splitTwo);
        assertNotEquals(emptyThenNull, nullThenEmpty);
        assertEquals("W/\"0e161aa9baccea99ec3fd09974a583e60ba9e9b842cecd9d952203f94ce1c891\"", splitOne);
    }
}
