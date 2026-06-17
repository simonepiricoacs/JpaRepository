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
package it.water.repository.jpa;

import it.water.core.api.model.PaginableResult;
import it.water.core.api.repository.query.operands.FieldValueOperand;
import it.water.core.api.repository.query.operations.Like;
import it.water.core.api.service.Service;
import it.water.core.interceptors.annotations.Inject;
import it.water.core.testing.utils.junit.WaterTestExtension;
import it.water.repository.jpa.api.TestEntityRepository;
import it.water.repository.jpa.entity.TestEntity;
import lombok.Setter;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Integration tests for Fix #21 — LIKE wildcard escaping.
 *
 * Persists rows whose {@code uniqueField} contains SQL wildcard characters
 * ('%', '_', '\') and verifies that a LIKE query built via QueryBuilder
 * matches only the intended literal row, not rows that would match if the
 * value were treated as a wildcard pattern.
 *
 * Uses the same WaterTestExtension + in-memory HSQLDB harness as
 * {@link JpaRepositoryTest}.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith({WaterTestExtension.class})
class LikeWildcardEscapingTest implements Service {

    @Inject
    @Setter
    private TestEntityRepository testEntityRepository;

    /** IDs set during the setup test; used by subsequent assertion tests. */
    private long idLiteralPercent;
    private long idLiteralUnderscore;
    private long idLiteralBackslash;
    private long idWouldMatchUnescaped;

    /**
     * Order 0 — seeds the DB with rows containing SQL wildcard characters.
     * Runs first because WaterTestExtension injects before each @Test, but
     * BeforeAll callbacks may run before injection; using an @Order(0) test
     * guarantees injection is available.
     */
    @Test
    @Order(0)
    void setupLikeEscapeTestData() {
        // Clean any leftover state from other tests that share the same EM/schema
        testEntityRepository.removeAll();

        // Row whose uniqueField contains a literal '%'
        TestEntity e1 = makeEntity("price:50%off", "like-escape", "col2-1");
        testEntityRepository.persist(e1);
        idLiteralPercent = e1.getId();

        // Row whose uniqueField contains a literal '_'
        TestEntity e2 = makeEntity("code_X", "like-escape", "col2-2");
        testEntityRepository.persist(e2);
        idLiteralUnderscore = e2.getId();

        // Row that WOULD match "price:50%off" as a wildcard (matches price:50<anything>OFF)
        TestEntity e3 = makeEntity("price:50XXXOFF", "like-escape", "col2-3");
        testEntityRepository.persist(e3);
        idWouldMatchUnescaped = e3.getId();

        // Row that WOULD match "code_X" as a wildcard (code<any char>X)
        TestEntity e4 = makeEntity("codeZX", "like-escape", "col2-4");
        testEntityRepository.persist(e4);

        // Row whose uniqueField contains a literal backslash (Java: "path\\file" → actual: path\file)
        TestEntity e5 = makeEntity("path\\file", "like-escape", "col2-5");
        testEntityRepository.persist(e5);
        idLiteralBackslash = e5.getId();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private TestEntity makeEntity(String uniqueField, String combined1, String combined2) {
        TestEntity e = new TestEntity();
        e.setUniqueField(uniqueField);
        e.setCombinedUniqueField1(combined1 + "-" + uniqueField);
        e.setCombinedUniqueField2(combined2);
        return e;
    }

    private PaginableResult<TestEntity> likeQuery(String value) {
        Like likeOperation = new Like();
        likeOperation.defineOperands(
                testEntityRepository.getQueryBuilderInstance().field("uniqueField"),
                new FieldValueOperand(value));
        return testEntityRepository.findAll(100, 1, likeOperation, null);
    }

    // ------------------------------------------------------------------
    // Tests
    // ------------------------------------------------------------------

    @Test
    @Order(1)
    void likeWithPercentValue_matchesOnlyLiteralRow_notWildcardRow() {
        // Searching for the literal string "price:50%off" must return exactly
        // the row with that field value and must NOT return "price:50XXXOFF".
        PaginableResult<TestEntity> result = likeQuery("price:50%off");

        Assertions.assertEquals(1, result.getResults().size(),
                "LIKE with '%' in value must match only the literal row");
        TestEntity found = result.getResults().iterator().next();
        Assertions.assertEquals(idLiteralPercent, found.getId(),
                "The matched row must be the one with the literal '%' in uniqueField");
    }

    @Test
    @Order(2)
    void likeWithUnderscoreValue_matchesOnlyLiteralRow_notWildcardRow() {
        // Searching for "code_X" must return only the row with that exact value
        // and must NOT return "codeZX".
        PaginableResult<TestEntity> result = likeQuery("code_X");

        Assertions.assertEquals(1, result.getResults().size(),
                "LIKE with '_' in value must match only the literal row");
        TestEntity found = result.getResults().iterator().next();
        Assertions.assertEquals(idLiteralUnderscore, found.getId(),
                "The matched row must be the one with the literal '_' in uniqueField");
    }

    @Test
    @Order(3)
    void likeWithBackslashValue_matchesOnlyLiteralRow() {
        PaginableResult<TestEntity> result = likeQuery("path\\file");

        Assertions.assertEquals(1, result.getResults().size(),
                "LIKE with '\\' in value must match only the literal row");
        TestEntity found = result.getResults().iterator().next();
        Assertions.assertEquals(idLiteralBackslash, found.getId(),
                "The matched row must be the one with the literal backslash in uniqueField");
    }

    @Test
    @Order(4)
    void likeWithPlainValue_noSpecialChars_matchesNormally() {
        // A LIKE on a plain value with no wildcards must still match the row.
        PaginableResult<TestEntity> result = likeQuery("price:50%off");
        Assertions.assertFalse(result.getResults().isEmpty(),
                "Plain LIKE must return results when a matching row exists");
    }

    @Test
    @Order(5)
    void likeWouldMatchUnescapedCannotBeFoundByWildcardSearch() {
        // "price:50XXXOFF" should NOT appear when we search for the literal "price:50%off"
        PaginableResult<TestEntity> result = likeQuery("price:50%off");
        long matchedId = result.getResults().iterator().next().getId();
        Assertions.assertNotEquals(idWouldMatchUnescaped, matchedId,
                "Row that would match as wildcard must not appear in escaped LIKE result");
    }
}
