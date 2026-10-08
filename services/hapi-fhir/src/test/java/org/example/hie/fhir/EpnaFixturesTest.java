package org.example.hie.fhir;

import static org.junit.jupiter.api.Assertions.*;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.StrictErrorHandler;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import org.hl7.fhir.common.hapi.validation.validator.FhirInstanceValidator;
import org.hl7.fhir.r4.model.Bundle;
import org.junit.jupiter.api.Test;

class EpnaFixturesTest {
    @Test
    void everyGeneratedBundlePassesOfflineCoreR4Validation() throws Exception {
        FhirContext context = FhirContext.forR4Cached();
        var module = new FhirInstanceValidator(context);
        module.setNoTerminologyChecks(true);
        var validator = context.newValidator().registerValidatorModule(module);
        Set<String> resourceTypes = new HashSet<>();
        Set<String> identities = new HashSet<>();
        int updates = 0;
        int bundles = 0;
        try (var paths = Files.list(Path.of(System.getProperty("epna.samples")))) {
            for (Path path : paths.filter(file -> file.toString().endsWith(".bundle.json")).sorted().toList()) {
                Bundle bundle = context.newJsonParser().setParserErrorHandler(new StrictErrorHandler())
                        .parseResource(Bundle.class, Files.readString(path));
                var validation = validator.validateWithResult(bundle);
                assertTrue(validation.isSuccessful(), () -> path.getFileName() + ": " + validation.getMessages());
                assertEquals(Bundle.BundleType.COLLECTION, bundle.getType());
                for (var entry : bundle.getEntry()) {
                    var resource = entry.getResource();
                    assertTrue(resource.getIdElement().getIdPart().startsWith("epna-"));
                    assertFalse(resource.getMeta().hasProfile(), "The POC must not claim unvalidated US Core conformance.");
                    resourceTypes.add(resource.fhirType());
                    identities.add(resource.fhirType() + "/" + resource.getIdElement().getIdPart());
                    updates++;
                }
                bundles++;
            }
        }
        assertEquals(5, bundles);
        assertEquals(31, updates);
        assertEquals(22, identities.size());
        assertEquals(SearchParameters.SUPPORTED.keySet(), resourceTypes);
    }
}
