package org.example.hie.fhir;

import ca.uhn.fhir.rest.annotation.Metadata;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.RestfulServer;
import ca.uhn.fhir.rest.server.provider.ServerCapabilityStatementProvider;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Date;
import java.util.TreeMap;
import org.hl7.fhir.r4.model.CapabilityStatement;
import org.hl7.fhir.r4.model.Enumerations;

public final class CapabilityProvider extends ServerCapabilityStatementProvider {
    private final String baseUrl;

    public CapabilityProvider(RestfulServer server, String baseUrl) {
        super(server);
        this.baseUrl = baseUrl;
    }

    @Override
    @Metadata
    public CapabilityStatement getServerConformance(HttpServletRequest request, RequestDetails details) {
        CapabilityStatement statement = new CapabilityStatement();
        statement.setStatus(Enumerations.PublicationStatus.ACTIVE);
        statement.setExperimental(true);
        statement.setDate(new Date());
        statement.setKind(CapabilityStatement.CapabilityStatementKind.INSTANCE);
        statement.setFhirVersion(Enumerations.FHIRVersion._4_0_1);
        statement.addFormat("application/fhir+json");
        statement.getSoftware().setName("HIE Gold HAPI FHIR").setVersion("0.1.0");
        statement.getImplementation().setUrl(baseUrl).setDescription(
                "Read-only R4 virtualization over Gold Eventhouse. No JPA repository, writes, history, "
                + "subscriptions, includes, chained searches or terminology operations. "
                + "Single exact search values only. _count=1..100; _offset=0..10000. "
                + "Paging is ordered by logical ID over live data, not a snapshot; totals are omitted.");
        var rest = statement.addRest().setMode(CapabilityStatement.RestfulCapabilityMode.SERVER);
        rest.getSecurity().addService().addCoding()
                .setSystem("http://terminology.hl7.org/CodeSystem/restful-security-service").setCode("OAuth");
        rest.getSecurity().setDescription("External access requires an Entra Fhir.Read application role at API Management.");
        new TreeMap<>(SearchParameters.SUPPORTED).forEach((type, parameters) -> {
            var resource = rest.addResource().setType(type)
                    .setVersioning(CapabilityStatement.ResourceVersionPolicy.NOVERSION)
                    .setReadHistory(false).setUpdateCreate(false).setConditionalCreate(false);
            resource.addInteraction().setCode(CapabilityStatement.TypeRestfulInteraction.READ);
            resource.addInteraction().setCode(CapabilityStatement.TypeRestfulInteraction.SEARCHTYPE);
            new TreeMap<>(parameters).forEach((name, parameterType) -> resource.addSearchParam()
                    .setName(name).setType(Enumerations.SearchParamType.fromCode(parameterType))
                    .setDocumentation("One exact value; no modifiers, chains, comma lists or repeated parameters."));
        });
        return statement;
    }
}
