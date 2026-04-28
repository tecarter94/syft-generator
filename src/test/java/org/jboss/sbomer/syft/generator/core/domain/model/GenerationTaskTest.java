package org.jboss.sbomer.syft.generator.core.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.jboss.sbomer.events.common.GenerationRequestSpec;
import org.jboss.sbomer.events.common.Target;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link GenerationTask} constructor validation.
 * 
 * <p>These tests verify that the GenerationTask record validates all required fields
 * in its constructor, following the fail-fast principle.</p>
 */
class GenerationTaskTest {

    @Test
    void testValidConstruction() {
        // Given
        String generationId = "test-gen-123";
        GenerationRequestSpec spec = new GenerationRequestSpec();
        Target target = new Target();
        target.setIdentifier("quay.io/test/image:latest");
        spec.setTarget(target);
        String traceParent = "00-trace-span-01";

        // When
        GenerationTask task = new GenerationTask(generationId, spec, traceParent);

        // Then
        assertNotNull(task);
        assertEquals(generationId, task.generationId());
        assertEquals(spec, task.spec());
        assertEquals(null, task.memoryOverride());
        assertEquals(traceParent, task.traceParent());
    }

    @Test
    void testValidConstructionWithMemoryOverride() {
        // Given
        String generationId = "test-gen-123";
        GenerationRequestSpec spec = new GenerationRequestSpec();
        Target target = new Target();
        target.setIdentifier("quay.io/test/image:latest");
        spec.setTarget(target);
        String memoryOverride = "2Gi";
        String traceParent = "00-trace-span-01";

        // When
        GenerationTask task = new GenerationTask(generationId, spec, memoryOverride, traceParent);

        // Then
        assertNotNull(task);
        assertEquals(generationId, task.generationId());
        assertEquals(spec, task.spec());
        assertEquals(memoryOverride, task.memoryOverride());
        assertEquals(traceParent, task.traceParent());
    }

    @Test
    void testNullGenerationIdThrowsException() {
        // Given
        GenerationRequestSpec spec = new GenerationRequestSpec();
        Target target = new Target();
        target.setIdentifier("quay.io/test/image:latest");
        spec.setTarget(target);

        // When/Then
        NullPointerException exception = assertThrows(
                NullPointerException.class,
                () -> new GenerationTask(null, spec, "trace"));

        assertEquals("generationId cannot be null", exception.getMessage());
    }

    @Test
    void testNullSpecThrowsException() {
        // Given
        String generationId = "test-gen-123";

        // When/Then
        NullPointerException exception = assertThrows(
                NullPointerException.class,
                () -> new GenerationTask(generationId, null, "trace"));

        assertEquals("spec cannot be null", exception.getMessage());
    }

    @Test
    void testNullTargetThrowsException() {
        // Given
        String generationId = "test-gen-123";
        GenerationRequestSpec spec = new GenerationRequestSpec();
        // spec.target is null

        // When/Then
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new GenerationTask(generationId, spec, "trace"));

        assertEquals("spec.target cannot be null", exception.getMessage());
    }

    @Test
    void testNullIdentifierThrowsException() {
        // Given
        String generationId = "test-gen-123";
        GenerationRequestSpec spec = new GenerationRequestSpec();
        Target target = new Target();
        // target.identifier is null
        spec.setTarget(target);

        // When/Then
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new GenerationTask(generationId, spec, "trace"));

        assertEquals("spec.target.identifier cannot be null or empty", exception.getMessage());
    }

    @Test
    void testEmptyIdentifierThrowsException() {
        // Given
        String generationId = "test-gen-123";
        GenerationRequestSpec spec = new GenerationRequestSpec();
        Target target = new Target();
        target.setIdentifier("   "); // whitespace only
        spec.setTarget(target);

        // When/Then
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new GenerationTask(generationId, spec, "trace"));

        assertEquals("spec.target.identifier cannot be null or empty", exception.getMessage());
    }

    @Test
    void testInvalidMemoryFormatThrowsException() {
        // Given
        String generationId = "test-gen-123";
        GenerationRequestSpec spec = new GenerationRequestSpec();
        Target target = new Target();
        target.setIdentifier("quay.io/test/image:latest");
        spec.setTarget(target);
        String invalidMemory = "invalid-format";

        // When/Then
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new GenerationTask(generationId, spec, invalidMemory, "trace"));

        assertEquals(
                "Invalid memory format: invalid-format. Expected format: <number>[Ki|Mi|Gi|Ti|Pi|Ei]",
                exception.getMessage());
    }

    @Test
    void testValidMemoryFormats() {
        // Given
        String generationId = "test-gen-123";
        GenerationRequestSpec spec = new GenerationRequestSpec();
        Target target = new Target();
        target.setIdentifier("quay.io/test/image:latest");
        spec.setTarget(target);

        // Test various valid memory formats
        String[] validFormats = { "2Gi", "4096Mi", "1024Ki", "1Ti", "500M", "1.5Gi", "2048" };

        for (String memoryFormat : validFormats) {
            // When
            GenerationTask task = new GenerationTask(generationId, spec, memoryFormat, "trace");

            // Then
            assertNotNull(task);
            assertEquals(memoryFormat, task.memoryOverride());
        }
    }

    @Test
    void testEmptyMemoryOverrideIsAllowed() {
        // Given
        String generationId = "test-gen-123";
        GenerationRequestSpec spec = new GenerationRequestSpec();
        Target target = new Target();
        target.setIdentifier("quay.io/test/image:latest");
        spec.setTarget(target);

        // When - empty string should be allowed (treated as no override)
        GenerationTask task = new GenerationTask(generationId, spec, "", "trace");

        // Then
        assertNotNull(task);
        assertEquals("", task.memoryOverride());
    }

    @Test
    void testNullMemoryOverrideIsAllowed() {
        // Given
        String generationId = "test-gen-123";
        GenerationRequestSpec spec = new GenerationRequestSpec();
        Target target = new Target();
        target.setIdentifier("quay.io/test/image:latest");
        spec.setTarget(target);

        // When - null memory override should be allowed
        GenerationTask task = new GenerationTask(generationId, spec, null, "trace");

        // Then
        assertNotNull(task);
        assertEquals(null, task.memoryOverride());
    }

    @Test
    void testNullTraceParentIsAllowed() {
        // Given
        String generationId = "test-gen-123";
        GenerationRequestSpec spec = new GenerationRequestSpec();
        Target target = new Target();
        target.setIdentifier("quay.io/test/image:latest");
        spec.setTarget(target);

        // When - null traceParent should be allowed
        GenerationTask task = new GenerationTask(generationId, spec, null);

        // Then
        assertNotNull(task);
        assertEquals(null, task.traceParent());
    }
}

