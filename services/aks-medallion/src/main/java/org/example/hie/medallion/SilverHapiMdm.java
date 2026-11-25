package org.example.hie.medallion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnExpression("'${app.role}' == 'silver' and '${app.silver-hapi-enabled:false}' == 'true'")
class SilverHapiMdm {
    private static final String VERSION = "urn:hie:source-version";
    private static final String HASH = "urn:hie:source-hash";
    private final HapiVersionedClient hapi;
    private final DataSource dataSource;
    private final String identifierSystem;

    @Autowired
    SilverHapiMdm(HttpClient http, ObjectMapper json,
                  @Value("${app.hapi-silver-url:}") String url,
                  @Value("${app.hapi-silver-token:}") String token,
                  @Value("${app.patient-identifier-system:}") String identifierSystem) {
        this(new HapiVersionedClient(http, json, url, token),
                PostgresGoldStore.configuredDataSource(), identifierSystem);
    }

    SilverHapiMdm(HapiVersionedClient hapi, DataSource dataSource, String identifierSystem) {
        URI uri = URI.create(identifierSystem);
        if (!uri.isAbsolute()) {
            throw new IllegalArgumentException("Patient identifier system must be an absolute URI");
        }
        this.hapi = hapi;
        this.dataSource = dataSource;
        this.identifierSystem = identifierSystem;
    }

    JsonNode normalize(String source, JsonNode payload) {
        if (source == null || !source.matches("[A-Za-z0-9._:-]{1,128}") || !payload.isObject()) {
            throw new PermanentStageException("InvalidFhirIdentity", null);
        }
        Instant version = sourceVersion(payload);
        ObjectNode resource = ((ObjectNode) payload).deepCopy();
        if ("Patient".equals(resource.path("resourceType").asText())) {
            rewriteReferences(source, resource);
            return patient(source, resource, version);
        }
        if (!resource.path("resourceType").isTextual() || !resource.path("id").isTextual()) {
            throw new PermanentStageException("InvalidFhirIdentity", null);
        }
        int references = rewriteReferences(source, resource);
        if (references == 0) {
            throw new PermanentStageException("UnresolvedPatient", null);
        }
        return resource;
    }

    private JsonNode patient(String source, ObjectNode patient, Instant version) {
        String id = sourceId(source, patient.path("id").asText(""));
        String eid = identifier(patient);
        patient.put("id", id);
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement lock = connection.prepareStatement(
                    "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")) {
                lock.setString(1, "hie-mdm:" + id);
                lock.execute();
                hapi.write(patient, version);
                String goldenId = resolve(id);
                JsonNode golden = hapi.read(goldenId);
                if (!golden.isObject() || !"Patient".equals(golden.path("resourceType").asText())
                        || !goldenId.equals("Patient/" + golden.path("id").asText())
                        || !eid.equals(identifier(golden))
                        || !version.equals(OffsetDateTime.parse(
                                HapiVersionedClient.extension(golden, VERSION, "valueInstant")).toInstant())
                        || !HapiVersionedClient.hash(patient).equals(
                                HapiVersionedClient.extension(golden, HASH, "valueString"))) {
                    throw new IllegalStateException("MDM Golden Patient has not incorporated the source version");
                }
                ObjectNode result = ((ObjectNode) golden).deepCopy();
                result.put("id", goldPatientId(goldenId));
                ArrayNode extensions = result.withArray("extension");
                for (int i = extensions.size() - 1; i >= 0; i--) {
                    String url = extensions.get(i).path("url").asText();
                    if (VERSION.equals(url) || HASH.equals(url)) {
                        extensions.remove(i);
                    }
                    if (extensions.isEmpty()) {
                        result.remove("extension");
                    }
                }
                ObjectNode meta = result.withObject("/meta");
                meta.remove("versionId");
                meta.put("lastUpdated", version.toString());
                if (meta.path("tag").isArray()) {
                    ArrayNode tags = (ArrayNode) meta.path("tag");
                    for (int i = tags.size() - 1; i >= 0; i--) {
                        if ("https://hapifhir.org/NamingSystem/managing-mdm-system"
                                .equals(tags.get(i).path("system").asText())) {
                            tags.remove(i);
                        }
                        if (tags.isEmpty()) {
                            meta.remove("tag");
                        }
                    }
                }
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Silver MDM lock failed", exception);
        }
    }

    private int rewriteReferences(String source, JsonNode node) {
        int count = 0;
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            JsonNode reference = object.get("reference");
            if (reference != null && reference.isTextual()) {
                String value = reference.asText();
                if (value.startsWith("Patient/")) {
                    String sourceId = sourceId(source, value.substring("Patient/".length()));
                    JsonNode sourcePatient = hapi.read("Patient/" + sourceId);
                    String goldenId = resolve(sourceId);
                    JsonNode golden = hapi.read(goldenId);
                    if (!"Patient".equals(sourcePatient.path("resourceType").asText())
                            || !sourceId.equals(sourcePatient.path("id").asText())
                            || !"Patient".equals(golden.path("resourceType").asText())
                            || !goldenId.equals("Patient/" + golden.path("id").asText())
                            || !identifier(sourcePatient).equals(identifier(golden))) {
                        throw new PermanentStageException("ConflictingPatientIdentity", null);
                    }
                    object.put("reference", "Patient/" + goldPatientId(goldenId));
                    count++;
                } else if (!value.matches("[A-Z][A-Za-z0-9]{0,63}/[A-Za-z0-9.-]{1,64}")
                        || object.path("type").asText().endsWith("Patient")) {
                    throw new PermanentStageException("UnsupportedPatientReference", null);
                }
            } else if (object.path("type").asText().endsWith("Patient")) {
                throw new PermanentStageException("UnsupportedPatientReference", null);
            }
            var children = object.elements();
            while (children.hasNext()) {
                count += rewriteReferences(source, children.next());
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                count += rewriteReferences(source, child);
            }
        }
        return count;
    }

    private String resolve(String sourceId) {
        String match = null;
        for (int offset = 0; offset < 100000; offset += 100) {
            JsonNode page = hapi.read("$mdm-query-links?resourceId=Patient%2F" + sourceId
                    + "&_count=100&_offset=" + offset);
            boolean next = false;
            for (JsonNode parameter : page.path("parameter")) {
                if ("next".equals(parameter.path("name").asText())) {
                    next = true;
                } else if ("link".equals(parameter.path("name").asText())) {
                    String result = part(parameter, "matchResult");
                    if ("POSSIBLE_MATCH".equals(result) || "POSSIBLE_DUPLICATE".equals(result)) {
                        throw new PermanentStageException("AmbiguousPatient", null);
                    }
                    if ("MATCH".equals(result)) {
                        String candidate = part(parameter, "goldenResourceId");
                        if (!candidate.matches("Patient/[A-Za-z0-9.-]{1,64}") || match != null) {
                            throw new PermanentStageException("AmbiguousPatient", null);
                        }
                        match = candidate;
                    } else if (!"NO_MATCH".equals(result)) {
                        throw new IllegalStateException("Unexpected MDM match status");
                    }
                }
            }
            if (!next) {
                if (match == null) {
                    throw new IllegalStateException("MDM link not yet available");
                }
                verifyNoDuplicate(match);
                return match;
            }
        }
        throw new IllegalStateException("MDM link pagination limit exceeded");
    }

    private void verifyNoDuplicate(String goldenId) {
        for (int offset = 0; offset < 100000; offset += 100) {
            JsonNode page = hapi.read("$mdm-duplicate-golden-resources?_count=100&_offset=" + offset);
            boolean next = false;
            for (JsonNode parameter : page.path("parameter")) {
                if ("next".equals(parameter.path("name").asText())) {
                    next = true;
                } else if ("link".equals(parameter.path("name").asText())
                        && (goldenId.equals(part(parameter, "goldenResourceId"))
                        || goldenId.equals(part(parameter, "sourceResourceId")))) {
                    throw new PermanentStageException("AmbiguousPatient", null);
                }
            }
            if (!next) {
                return;
            }
        }
        throw new IllegalStateException("MDM duplicate pagination limit exceeded");
    }

    private static String part(JsonNode parameter, String name) {
        String value = null;
        for (JsonNode part : parameter.path("part")) {
            if (name.equals(part.path("name").asText())) {
                if (value != null || !part.path("valueString").isTextual()) {
                    throw new IllegalStateException("Invalid MDM link");
                }
                value = part.path("valueString").asText();
            }
        }
        if (value == null) {
            throw new IllegalStateException("Incomplete MDM link");
        }
        return value;
    }

    private String identifier(JsonNode patient) {
        String value = null;
        for (JsonNode identifier : patient.path("identifier")) {
            if (identifierSystem.equals(identifier.path("system").asText())) {
                if (value != null || !identifier.path("value").isTextual()
                        || identifier.path("value").asText().isBlank()) {
                    throw new PermanentStageException("InvalidPatientIdentifier", null);
                }
                value = identifier.path("value").asText();
            }
        }
        if (value == null) {
            throw new PermanentStageException("MissingPatientIdentifier", null);
        }
        return value;
    }

    private static String sourceId(String source, String id) {
        if (!id.matches("[A-Za-z0-9.-]{1,64}")) {
            throw new PermanentStageException("InvalidFhirIdentity", null);
        }
        return "s" + StorageSupport.sha256((source + "\n" + id)
                .getBytes(StandardCharsets.UTF_8)).substring(0, 63);
    }

    private static String goldPatientId(String goldenId) {
        return "g" + StorageSupport.sha256(goldenId.getBytes(StandardCharsets.UTF_8)).substring(0, 63);
    }

    private static Instant sourceVersion(JsonNode resource) {
        try {
            Instant time = OffsetDateTime.parse(resource.path("meta").path("lastUpdated").asText()).toInstant();
            if (!time.equals(time.truncatedTo(ChronoUnit.MICROS))) {
                throw new PermanentStageException("UnsupportedFhirVersionPrecision", null);
            }
            return time;
        } catch (DateTimeParseException exception) {
            throw new PermanentStageException("MissingFhirVersionTime", exception);
        }
    }
}
