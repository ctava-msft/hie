package org.example.hie.fhir;

import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.TreeMap;

public record SearchParameters(Map<String, String> values, int count, int offset) {
    public static final int MAX_COUNT = 100;
    public static final int MAX_OFFSET = 10_000;
    public static final Map<String, Map<String, String>> SUPPORTED = Map.of(
            "Patient", Map.of("_id", "token", "identifier", "token"),
            "Encounter", Map.of("_id", "token", "patient", "reference", "status", "token"),
            "Condition", Map.of("_id", "token", "patient", "reference", "encounter", "reference",
                    "code", "token", "clinical-status", "token"),
            "Observation", clinicalParameters(),
            "ServiceRequest", clinicalParameters(),
            "DiagnosticReport", clinicalParameters(),
            "Provenance", Map.of("_id", "token"));
    private static final Set<String> CONTROLS = Set.of("_count", "_offset", "_format", "_pretty");

    public SearchParameters {
        values = Map.copyOf(values);
    }

    private static Map<String, String> clinicalParameters() {
        return Map.of("_id", "token", "patient", "reference", "encounter", "reference",
                "code", "token", "status", "token");
    }

    public static SearchParameters parse(String resourceType, Map<String, String[]> raw) {
        Map<String, String> values = new TreeMap<>();
        for (var entry : raw.entrySet()) {
            String name = entry.getKey();
            if (!SUPPORTED.get(resourceType).containsKey(name) && !CONTROLS.contains(name)) {
                throw new InvalidRequestException("Unsupported search parameter: " + name);
            }
            String[] supplied = entry.getValue();
            if (supplied == null || supplied.length != 1 || supplied[0] == null
                    || supplied[0].isBlank() || supplied[0].length() > 1024
                    || !supplied[0].equals(supplied[0].strip())
                    || supplied[0].contains(",") || supplied[0].contains("\\")) {
                throw new InvalidRequestException("Supply one nonempty, unmodified value for " + name);
            }
            String value = supplied[0];
            switch (name) {
                case "_id" -> requireId(value);
                case "patient" -> value = reference(value, "Patient");
                case "encounter" -> value = reference(value, "Encounter");
                case "status" -> {
                    if (!value.matches("[a-z][a-z-]{0,63}")) {
                        throw new InvalidRequestException("status requires one literal status code.");
                    }
                }
                case "identifier", "code", "clinical-status" -> Token.parse(value);
                default -> { }
            }
            values.put(name, value);
        }
        int count = integer(values.getOrDefault("_count", "50"), "_count", 1, MAX_COUNT);
        int offset = integer(values.getOrDefault("_offset", "0"), "_offset", 0, MAX_OFFSET);
        return new SearchParameters(values, count, offset);
    }

    public static void requireId(String value) {
        if (!value.matches("[A-Za-z0-9.-]{1,64}")) {
            throw new InvalidRequestException("Use a valid FHIR logical ID.");
        }
    }

    private static String reference(String value, String type) {
        String id = value.startsWith(type + "/") ? value.substring(type.length() + 1) : value;
        requireId(id);
        return type + "/" + id;
    }

    private static int integer(String value, String name, int min, int max) {
        if (!value.matches("[0-9]{1,9}")) {
            throw new InvalidRequestException(name + " must be an integer.");
        }
        int parsed = Integer.parseInt(value);
        if (parsed < min || parsed > max) {
            throw new InvalidRequestException(name + " must be between " + min + " and " + max + ".");
        }
        return parsed;
    }

    public String queryString(int pageOffset) {
        Map<String, String> query = new TreeMap<>(values);
        query.put("_count", Integer.toString(count));
        query.put("_offset", Integer.toString(pageOffset));
        StringJoiner result = new StringJoiner("&");
        query.forEach((key, value) -> result.add(encode(key) + "=" + encode(value)));
        return result.toString();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    public record Token(boolean hasSystem, String system, String value) {
        public static Token parse(String input) {
            String[] parts = input.split("\\|", -1);
            if (parts.length > 2 || parts[parts.length - 1].isEmpty()) {
                throw new InvalidRequestException("Tokens require code/value or system|code/value.");
            }
            return new Token(parts.length == 2, parts.length == 2 ? parts[0] : "", parts[parts.length - 1]);
        }
    }
}
