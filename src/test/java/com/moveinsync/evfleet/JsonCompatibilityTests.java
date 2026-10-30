package com.moveinsync.evfleet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moveinsync.evfleet.domain.Dataset;
import com.moveinsync.evfleet.domain.Plan;
import com.moveinsync.evfleet.validator.IndependentValidator;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Plans saved as JSON earlier must still load, validate and save back unchanged. */
class JsonCompatibilityTests {

    /** The saved 1,500-trip plan loads, passes the validator, and round-trips to identical JSON. */
    @Test
    void previouslyStoredScalePlanRemainsReadableAndValid() throws Exception {
        var mapper = new ObjectMapper();
        var savedPlan = Path.of("evidence/scale-plan.json").toFile();
        var dataset = mapper.readValue(Path.of("examples/scale-dataset.json").toFile(), Dataset.class);
        var plan = mapper.readValue(savedPlan, Plan.class);

        var report = new IndependentValidator().validate(dataset, plan);

        assertTrue(report.valid(), () -> "Stored plan violations: " + report.violations());
        assertEquals(dataset.trips().size(), plan.allAssignments().size());
        assertEquals(mapper.readTree(savedPlan), mapper.readTree(mapper.writeValueAsString(plan)));
    }
}
