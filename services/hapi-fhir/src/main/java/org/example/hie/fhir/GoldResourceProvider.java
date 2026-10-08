package org.example.hie.fhir;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.DataFormatException;
import ca.uhn.fhir.parser.StrictErrorHandler;
import ca.uhn.fhir.rest.annotation.IdParam;
import ca.uhn.fhir.rest.annotation.Read;
import ca.uhn.fhir.rest.annotation.Search;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.IResourceProvider;
import ca.uhn.fhir.rest.server.exceptions.BaseServerResponseException;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import ca.uhn.fhir.rest.server.exceptions.UnprocessableEntityException;
import java.util.List;
import java.util.Map;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class GoldResourceProvider implements IResourceProvider {
    private static final Logger LOG = LoggerFactory.getLogger(GoldResourceProvider.class);
    private final FhirContext context;
    private final GoldStore store;
    private final String resourceType;
    private final String baseUrl;

    public GoldResourceProvider(FhirContext context, GoldStore store, String resourceType, String baseUrl) {
        this.context = context;
        this.store = store;
        this.resourceType = resourceType;
        this.baseUrl = baseUrl;
    }

    @Override
    public Class<? extends IBaseResource> getResourceType() {
        return context.getResourceDefinition(resourceType).getImplementingClass();
    }

    @Read
    public Resource read(@IdParam IdType id) {
        SearchParameters.requireId(id.getIdPart());
        List<String> results = find(SearchParameters.parse(resourceType, Map.of("_id", new String[] {id.getIdPart()})));
        if (results.isEmpty()) {
            throw new ResourceNotFoundException("No current resource with that logical ID.");
        }
        if (results.size() != 1) {
            throw BaseServerResponseException.newInstance(502, "Gold returned ambiguous resource identity.");
        }
        Resource resource = parse(results.getFirst());
        if (!resource.getIdElement().getIdPart().equals(id.getIdPart())) {
            throw BaseServerResponseException.newInstance(502, "Gold returned an inconsistent resource identity.");
        }
        return resource;
    }

    @Search(allowUnknownParams = true)
    public Bundle search(RequestDetails details) {
        SearchParameters parameters = SearchParameters.parse(resourceType, details.getParameters());
        List<String> results = find(parameters);
        boolean hasMore = results.size() > parameters.count();
        int nextOffset = parameters.offset() + parameters.count();
        if (hasMore && nextOffset > SearchParameters.MAX_OFFSET) {
            throw new UnprocessableEntityException("Search exceeds the supported paging window; narrow the filters.");
        }
        Bundle bundle = new Bundle().setType(Bundle.BundleType.SEARCHSET);
        bundle.addLink().setRelation("self").setUrl(baseUrl + "/" + resourceType + "?" + parameters.queryString(parameters.offset()));
        for (String json : results.subList(0, Math.min(results.size(), parameters.count()))) {
            Resource resource = parse(json);
            bundle.addEntry().setFullUrl(baseUrl + "/" + resourceType + "/" + resource.getIdElement().getIdPart())
                    .setResource(resource).getSearch().setMode(Bundle.SearchEntryMode.MATCH);
        }
        if (hasMore) {
            bundle.addLink().setRelation("next").setUrl(baseUrl + "/" + resourceType + "?" + parameters.queryString(nextOffset));
        }
        return bundle;
    }

    private List<String> find(SearchParameters parameters) {
        try {
            return store.search(resourceType, parameters);
        } catch (GoldStore.Unavailable e) {
            throw BaseServerResponseException.newInstance(503, e.getMessage());
        }
    }

    private Resource parse(String json) {
        try {
            IBaseResource parsed = context.newJsonParser().setParserErrorHandler(new StrictErrorHandler()).parseResource(json);
            if (parsed instanceof Resource resource && resource.fhirType().equals(resourceType)
                    && resource.hasId() && resource.getIdElement().getIdPart().matches("[A-Za-z0-9.-]{1,64}")) {
                return resource;
            }
        } catch (DataFormatException e) {
            LOG.error("Gold returned invalid FHIR JSON for type {}.", resourceType);
            throw BaseServerResponseException.newInstance(502, "Gold returned invalid FHIR JSON.");
        }
        LOG.error("Gold returned an inconsistent FHIR resource for type {}.", resourceType);
        throw BaseServerResponseException.newInstance(502, "Gold returned an inconsistent FHIR resource.");
    }
}
