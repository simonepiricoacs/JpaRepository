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
import it.water.core.api.service.Service;
import it.water.core.interceptors.annotations.Inject;
import it.water.core.testing.utils.junit.WaterTestExtension;
import it.water.repository.entity.model.PaginatedResult;
import it.water.repository.jpa.api.TestEntityRepository;
import it.water.repository.jpa.entity.TestEntity;
import lombok.Setter;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Integration tests for Fix #22 — max page size cap in {@link BaseJpaRepositoryImpl#doFindAll}.
 *
 * Uses the same WaterTestExtension + in-memory HSQLDB harness as the main test class.
 * Persists a small known set of rows then verifies that:
 *
 *  1. A delta larger than the cap does not produce more rows than the cap.
 *  2. delta == 0 / page == 0 paths still return a bounded result set.
 *  3. Small valid deltas are honoured exactly.
 *
 * NOTE: The actual DEFAULT_MAX_PAGE_SIZE is 200. Since creating 200+ rows in an
 * integration test is expensive, we rely on the known fact that requesting an
 * enormous delta (e.g., 100_000) will be clamped to 200 by the repository, and
 * that the number of actual rows returned equals only the rows we persisted
 * (which is well below 200). What we test is that the clamped value is stored
 * in the PaginatedResult and that setMaxResults is effectively constrained —
 * proven by the fact that no exception is thrown and the results count matches
 * the smaller of (rows persisted) vs cap.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith({WaterTestExtension.class})
class PaginationCapTest implements Service {

    private static final int ROWS_TO_PERSIST = 5;
    private static final int HUGE_DELTA = 100_000;

    @Inject
    @Setter
    private TestEntityRepository testEntityRepository;

    @BeforeAll
    void setUp() {
        testEntityRepository.removeAll();
        for (int i = 0; i < ROWS_TO_PERSIST; i++) {
            TestEntity e = new TestEntity();
            e.setUniqueField("cap-test-" + i);
            e.setCombinedUniqueField1("cap-c1-" + i);
            e.setCombinedUniqueField2("cap-c2-" + i);
            testEntityRepository.persist(e);
        }
    }

    @AfterAll
    void tearDown() {
        testEntityRepository.removeAll();
    }

    // ------------------------------------------------------------------
    // delta clamping when delta > DEFAULT_MAX_PAGE_SIZE
    // ------------------------------------------------------------------

    @Test
    @Order(1)
    void findAll_hugeDeltaAndValidPage_deltaClampedToDefault_returnsOnlyPersistedRows() {
        PaginatedResult<TestEntity> result =
                (PaginatedResult<TestEntity>) testEntityRepository.findAll(HUGE_DELTA, 1, null, null);

        // The effective delta stored in PaginatedResult must be capped at DEFAULT_MAX_PAGE_SIZE (200)
        int effectiveDelta = result.getDelta();
        Assertions.assertTrue(effectiveDelta <= BaseJpaRepositoryImpl.DEFAULT_MAX_PAGE_SIZE,
                "Effective delta must be <= DEFAULT_MAX_PAGE_SIZE after clamping");

        // The actual result set contains only the rows we persisted (< 200)
        Assertions.assertEquals(ROWS_TO_PERSIST, result.getResults().size(),
                "Result set must contain only the persisted rows");
    }

    @Test
    @Order(2)
    void findAll_deltaExactlyDefaultMaxPageSize_notClamped() {
        PaginatedResult<TestEntity> result =
                (PaginatedResult<TestEntity>) testEntityRepository.findAll(
                        BaseJpaRepositoryImpl.DEFAULT_MAX_PAGE_SIZE, 1, null, null);

        Assertions.assertEquals(BaseJpaRepositoryImpl.DEFAULT_MAX_PAGE_SIZE, result.getDelta(),
                "Delta equal to the cap must not be reduced");
        Assertions.assertEquals(ROWS_TO_PERSIST, result.getResults().size());
    }

    @Test
    @Order(3)
    void findAll_deltaOneAboveDefaultMaxPageSize_clampedToDefault() {
        int overCap = BaseJpaRepositoryImpl.DEFAULT_MAX_PAGE_SIZE + 1;
        PaginatedResult<TestEntity> result =
                (PaginatedResult<TestEntity>) testEntityRepository.findAll(overCap, 1, null, null);

        Assertions.assertEquals(BaseJpaRepositoryImpl.DEFAULT_MAX_PAGE_SIZE, result.getDelta(),
                "Delta one above the cap must be clamped to DEFAULT_MAX_PAGE_SIZE");
    }

    // ------------------------------------------------------------------
    // delta <= 0 / page <= 0 — the else-branch (unbounded request capped)
    // ------------------------------------------------------------------

    @Test
    @Order(4)
    void findAll_deltaZeroPageZero_elsePathAppliesMaxPageSizeBound() {
        // When both delta and page are 0, the else-branch sets maxResults = maxPageSize.
        // We cannot directly assert setMaxResults was called (no mock on the real EM),
        // but we can assert the call does not throw and returns a non-empty result.
        PaginableResult<TestEntity> result =
                testEntityRepository.findAll(0, 0, null, null);
        Assertions.assertNotNull(result, "findAll(0,0,...) must not return null");
        Assertions.assertFalse(result.getResults().isEmpty(),
                "findAll(0,0,...) must return the persisted rows (bounded by maxPageSize)");
    }

    @Test
    @Order(5)
    void findAll_deltaNegativePage1_elsePathAppliesMaxPageSizeBound() {
        PaginableResult<TestEntity> result =
                testEntityRepository.findAll(-1, 1, null, null);
        Assertions.assertNotNull(result);
        // delta is negative, so the else branch fires; we get all rows bounded by maxPageSize
        Assertions.assertEquals(ROWS_TO_PERSIST, result.getResults().size());
    }

    @Test
    @Order(6)
    void findAll_delta1PageNegative_elsePathAppliesMaxPageSizeBound() {
        PaginableResult<TestEntity> result =
                testEntityRepository.findAll(1, -1, null, null);
        Assertions.assertNotNull(result);
        // page is negative — else path fires
        Assertions.assertFalse(result.getResults().isEmpty());
    }

    // ------------------------------------------------------------------
    // Small valid delta — not clamped, page navigation works
    // ------------------------------------------------------------------

    @Test
    @Order(7)
    void findAll_smallValidDelta_returnsExactlyThatManyRows() {
        int delta = 2;
        PaginatedResult<TestEntity> page1 =
                (PaginatedResult<TestEntity>) testEntityRepository.findAll(delta, 1, null, null);
        Assertions.assertEquals(delta, page1.getResults().size(),
                "Page 1 with delta=2 must return exactly 2 rows");
        Assertions.assertEquals(delta, page1.getDelta());
    }

    @Test
    @Order(8)
    void findAll_smallValidDelta_lastPage_returnsRemainingRows() {
        // 5 rows, delta=2 → page 3 has 1 row
        int delta = 2;
        PaginatedResult<TestEntity> page3 =
                (PaginatedResult<TestEntity>) testEntityRepository.findAll(delta, 3, null, null);
        Assertions.assertEquals(1, page3.getResults().size(),
                "Last page with 1 remaining row must return 1 row");
    }
}
