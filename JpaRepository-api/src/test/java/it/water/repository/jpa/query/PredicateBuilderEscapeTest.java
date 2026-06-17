/*
 * Copyright 2024 Aristide Cittadino
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package it.water.repository.jpa.query;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;

import java.lang.reflect.Method;

/**
 * Unit tests for the private {@code escapeLikeValue} method in {@link PredicateBuilder}.
 *
 * Because the method is private we access it via reflection. This keeps tests
 * focused on the escaping logic in isolation, without needing a database.
 *
 * Fix #21 coverage targets:
 *  - backslash escape (must be first to avoid double-escaping)
 *  - percent-sign escape
 *  - underscore escape
 *  - combined escaping
 *  - null input returns null
 *  - plain value (no special chars) passes through unchanged
 */
@ExtendWith(MockitoExtension.class)
class PredicateBuilderEscapeTest {

    @Mock
    private Root<Object> root;

    @Mock
    @SuppressWarnings("rawtypes")
    private CriteriaQuery criteriaQuery;

    @Mock
    private CriteriaBuilder criteriaBuilder;

    private Method escapeLikeValue;

    @BeforeEach
    void setUp() throws NoSuchMethodException {
        escapeLikeValue = PredicateBuilder.class.getDeclaredMethod("escapeLikeValue", String.class);
        escapeLikeValue.setAccessible(true);
    }

    @SuppressWarnings("unchecked")
    private String invoke(String input) throws Exception {
        PredicateBuilder<Object> builder = new PredicateBuilder<>(root, criteriaQuery, criteriaBuilder);
        return (String) escapeLikeValue.invoke(builder, input);
    }

    @Test
    void escapeLikeValue_nullInput_returnsNull() throws Exception {
        Assertions.assertNull(invoke(null));
    }

    @Test
    void escapeLikeValue_emptyString_returnsEmpty() throws Exception {
        Assertions.assertEquals("", invoke(""));
    }

    @Test
    void escapeLikeValue_noSpecialChars_returnsUnchanged() throws Exception {
        Assertions.assertEquals("hello", invoke("hello"));
    }

    @Test
    void escapeLikeValue_percentSign_isEscaped() throws Exception {
        // "a%b" must become "a\%b" so the LIKE treats % literally
        String result = invoke("a%b");
        Assertions.assertEquals("a\\%b", result);
    }

    @Test
    void escapeLikeValue_underscore_isEscaped() throws Exception {
        // "a_b" must become "a\_b"
        String result = invoke("a_b");
        Assertions.assertEquals("a\\_b", result);
    }

    @Test
    void escapeLikeValue_backslash_isEscapedFirst() throws Exception {
        // A raw backslash must become two backslashes.
        // This must happen before percent/underscore escaping to prevent
        // double-escaping: "a\" -> "a\\" (not "a\\\\").
        String result = invoke("a\\b");
        Assertions.assertEquals("a\\\\b", result);
    }

    @Test
    void escapeLikeValue_backslashThenPercent_bothEscapedCorrectly() throws Exception {
        // "a\%b": backslash escaped first -> "a\\%b", then % escaped -> "a\\\%b"
        String result = invoke("a\\%b");
        Assertions.assertEquals("a\\\\\\%b", result);
    }

    @Test
    void escapeLikeValue_backslashThenUnderscore_bothEscapedCorrectly() throws Exception {
        // "a\_b" -> "a\\\\_b"
        String result = invoke("a\\_b");
        Assertions.assertEquals("a\\\\\\_b", result);
    }

    @Test
    void escapeLikeValue_multiplePercentSigns_allEscaped() throws Exception {
        String result = invoke("%foo%");
        Assertions.assertEquals("\\%foo\\%", result);
    }

    @Test
    void escapeLikeValue_multipleUnderscores_allEscaped() throws Exception {
        String result = invoke("_a_b_");
        Assertions.assertEquals("\\_a\\_b\\_", result);
    }

    @Test
    void escapeLikeValue_mixedSpecialChars_allEscaped() throws Exception {
        // "10%_off" -> "10\%\_off"
        String result = invoke("10%_off");
        Assertions.assertEquals("10\\%\\_off", result);
    }

    @Test
    void escapeLikeValue_onlyBackslash_doubledCorrectly() throws Exception {
        String result = invoke("\\");
        Assertions.assertEquals("\\\\", result);
    }

    @Test
    void escapeLikeValue_onlyPercent_escapedCorrectly() throws Exception {
        String result = invoke("%");
        Assertions.assertEquals("\\%", result);
    }

    @Test
    void escapeLikeValue_onlyUnderscore_escapedCorrectly() throws Exception {
        String result = invoke("_");
        Assertions.assertEquals("\\_", result);
    }
}
