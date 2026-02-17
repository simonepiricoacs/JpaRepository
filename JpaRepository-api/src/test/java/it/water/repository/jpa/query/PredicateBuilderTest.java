package it.water.repository.jpa.query;

import it.water.core.api.repository.query.operands.FieldValueOperand;
import it.water.core.api.repository.query.operations.EqualTo;
import it.water.repository.jpa.entity.TestEntity;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Root;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

class PredicateBuilderTest {

    private Root<TestEntity> root;
    private CriteriaQuery<?> cq;
    private CriteriaBuilder cb;
    private PredicateBuilder<TestEntity> predicateBuilder;
    private Path<Object> path;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        root = Mockito.mock(Root.class);
        cq = Mockito.mock(CriteriaQuery.class);
        cb = Mockito.mock(CriteriaBuilder.class);
        path = Mockito.mock(Path.class);
        predicateBuilder = new PredicateBuilder<>(root, cq, cb);

        Mockito.when(root.get(anyString())).thenReturn(path);
    }

    @Test
    void testConvertBasicTypes() {
        // Test String
        testConversion(String.class, "test", "test");
        // Test Integer
        testConversion(Integer.class, "10", 10);
        testConversion(int.class, "10", 10);
        // Test Long
        testConversion(Long.class, "100", 100L);
        testConversion(long.class, "100", 100L);
        // Test Boolean
        testConversion(Boolean.class, "true", true);
        testConversion(boolean.class, "false", false);
        // Test Double
        testConversion(Double.class, "10.5", 10.5);
        testConversion(double.class, "10.5", 10.5);
        // Test Float
        testConversion(Float.class, "10.5", 10.5f);
        testConversion(float.class, "10.5", 10.5f);
    }

    @Test
    void testConvertDateTypes() {
        long now = System.currentTimeMillis();
        Date date = new Date(now);

        // java.util.Date
        testConversion(Date.class, date, now);
        // java.sql.Date
        java.sql.Date sqlDate = new java.sql.Date(now);
        testConversion(java.sql.Date.class, sqlDate, now);
        // java.sql.Timestamp
        java.sql.Timestamp timestamp = new java.sql.Timestamp(now);
        testConversion(java.sql.Timestamp.class, timestamp, now);
        // java.time.Instant
        Instant instant = Instant.ofEpochMilli(now);
        testConversion(Instant.class, instant, now);
        // java.time.LocalDateTime
        LocalDateTime ldt = LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
        testConversion(LocalDateTime.class, ldt, now);
        // java.time.LocalDate
        LocalDate ld = ldt.toLocalDate();
        long ldMillis = ld.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        testConversion(LocalDate.class, ld, ldMillis);
    }

    @Test
    void testConvertEnumTypes() {
        // Enum instance
        testConversion(TestEnum.class, TestEnum.VAL1, TestEnum.VAL1);
        // Enum String
        testConversion(TestEnum.class, "VAL2", TestEnum.VAL2);
        // Enum Number (Ordinal)
        testConversion(TestEnum.class, 0, TestEnum.VAL1);
        testConversion(TestEnum.class, 1L, TestEnum.VAL2);

        Assertions.assertThrows(IllegalArgumentException.class, () -> testConversion(TestEnum.class, "INVALID", null));
    }

    @Test
    void testNullBranches() {
        // value == null || type == null
        PredicateBuilder<TestEntity> builder = new PredicateBuilder<>(root, cq, cb);

        EqualTo equalTo = new EqualTo();
        equalTo.defineOperands(new it.water.core.api.repository.query.operands.FieldNameOperand("field"),
                new FieldValueOperand(null));

        Mockito.when(path.getJavaType()).thenReturn(null);
        predicateBuilder.buildPredicate(equalTo);
        Mockito.verify(cb).isNull(any());
    }

    @Test
    void testUnsupportedTypes() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> testConversion(Object.class, "someValue", null));
    }

    @Test
    void testUnsupportedDateTypes() {
        // OffsetDateTime is Temporal (isDateType=true) but not handled in
        // convertToEpochMillis
        Mockito.when(path.getJavaType()).thenAnswer(invocation -> java.time.OffsetDateTime.class);
        EqualTo equalTo = new EqualTo();
        equalTo.defineOperands(new it.water.core.api.repository.query.operands.FieldNameOperand("field"),
                new FieldValueOperand(java.time.OffsetDateTime.now()));

        Assertions.assertThrows(IllegalArgumentException.class, () -> predicateBuilder.buildPredicate(equalTo));
    }

    @Test
    void testEnumEdgeCases() {
        // Null enum type
        Assertions.assertEquals(java.util.Optional.empty(), PredicateBuilder.toEnum(null, TestEnum.VAL1));
        // Null object
        Assertions.assertEquals(java.util.Optional.empty(), PredicateBuilder.toEnum(TestEnum.class, null));
        // Not an enum class
        Assertions.assertEquals(java.util.Optional.empty(), PredicateBuilder.toEnum((Class) String.class, "test"));
        // Invalid ordinal (too large)
        Assertions.assertEquals(java.util.Optional.empty(), PredicateBuilder.toEnum(TestEnum.class, 10));
        // Invalid ordinal (negative)
        Assertions.assertEquals(java.util.Optional.empty(), PredicateBuilder.toEnum(TestEnum.class, -1));
    }

    private void testConversion(Class<?> type, Object value, Object expected) {
        Mockito.when(path.getJavaType()).thenAnswer(invocation -> type);

        EqualTo equalTo = new EqualTo();
        equalTo.defineOperands(new it.water.core.api.repository.query.operands.FieldNameOperand("field"),
                new FieldValueOperand(value));

        predicateBuilder.buildPredicate(equalTo);

        if (expected == null) {
            Mockito.verify(cb).isNull(any());
        } else {
            Mockito.verify(cb).equal(any(), Mockito.eq(expected));
        }

        // Reset mock for next call
        Mockito.reset(cb);
    }

    enum TestEnum {
        VAL1, VAL2
    }
}
