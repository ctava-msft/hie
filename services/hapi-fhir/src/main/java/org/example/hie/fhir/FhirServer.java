package org.example.hie.fhir;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.rest.api.EncodingEnum;
import ca.uhn.fhir.rest.server.HardcodedServerAddressStrategy;
import ca.uhn.fhir.rest.server.RestfulServer;
import com.azure.identity.WorkloadIdentityCredentialBuilder;
import com.microsoft.azure.kusto.data.Client;
import com.microsoft.azure.kusto.data.ClientFactory;
import com.microsoft.azure.kusto.data.auth.ConnectionStringBuilder;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.EnumSet;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.eclipse.jetty.ee10.servlet.FilterHolder;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.OperationOutcome;

public final class FhirServer {
    private FhirServer() { }

    public static void main(String[] args) throws Exception {
        String endpoint = required("GOLD_QUERY_URI");
        if (!endpoint.matches("https://[a-z0-9-]+(\\.[a-z0-9-]+)*\\.kusto\\.fabric\\.microsoft\\.com")) {
            throw new IllegalArgumentException("GOLD_QUERY_URI must be a Fabric Eventhouse HTTPS query endpoint.");
        }
        String database = required("GOLD_DATABASE");
        if (!database.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,127}")) {
            throw new IllegalArgumentException("GOLD_DATABASE must be a KQL database name or UUID.");
        }
        String baseUrl = required("FHIR_BASE_URL");
        URI base = URI.create(baseUrl);
        if (!"https".equals(base.getScheme()) || base.getHost() == null || base.getUserInfo() != null
                || base.getQuery() != null || base.getFragment() != null || !"/fhir".equals(base.getPath())) {
            throw new IllegalArgumentException("FHIR_BASE_URL must be the external HTTPS APIM URL ending in /fhir.");
        }
        for (String name : Set.of("AZURE_TENANT_ID", "AZURE_CLIENT_ID", "AZURE_FEDERATED_TOKEN_FILE")) {
            required(name);
        }
        var credential = new WorkloadIdentityCredentialBuilder().build();
        Client client = ClientFactory.createClient(ConnectionStringBuilder.createWithTokenCredential(endpoint, credential));
        Server server = create(8080, baseUrl, new KustoGoldStore(client, database));
        server.setStopAtShutdown(true);
        server.start();
        server.join();
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Required environment variable is missing: " + name);
        }
        return value;
    }

    static Server create(int port, String baseUrl, GoldStore store) {
        FhirContext context = FhirContext.forR4Cached();
        RestfulServer fhir = new RestfulServer(context);
        fhir.setDefaultResponseEncoding(EncodingEnum.JSON);
        fhir.setServerAddressStrategy(new HardcodedServerAddressStrategy(baseUrl));
        fhir.setServerConformanceProvider(new CapabilityProvider(fhir, baseUrl));
        fhir.registerInterceptor(new SearchResponseInterceptor());
        for (String type : SearchParameters.SUPPORTED.keySet()) {
            fhir.registerProvider(new GoldResourceProvider(context, store, type, baseUrl));
        }
        Server server = new Server(port);
        ServletContextHandler handler = new ServletContextHandler();
        handler.setContextPath("/");
        handler.addServlet(new ServletHolder(fhir), "/fhir/*");
        handler.addServlet(new ServletHolder(new HealthServlet(store)), "/health/*");
        handler.addFilter(new FilterHolder(new ReadOnlyFilter(context)), "/fhir/*", EnumSet.of(DispatcherType.REQUEST));
        server.setHandler(handler);
        return server;
    }

    public static final class SearchResponseInterceptor {
        @Hook(Pointcut.SERVER_OUTGOING_RESPONSE)
        public void outgoing(IBaseResource resource) {
            if (resource instanceof Bundle bundle && bundle.getType() == Bundle.BundleType.SEARCHSET) {
                // HAPI otherwise infers a total from this page, not the complete Gold result set.
                bundle.setTotalElement(null);
            }
        }
    }

    private static final class ReadOnlyFilter implements Filter {
        private final FhirContext context;

        ReadOnlyFilter(FhirContext context) {
            this.context = context;
        }

        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
                throws IOException, ServletException {
            HttpServletRequest http = (HttpServletRequest) request;
            HttpServletResponse output = (HttpServletResponse) response;
            output.setHeader("Cache-Control", "no-store");
            output.setHeader("X-Content-Type-Options", "nosniff");
            if (!"GET".equals(http.getMethod())) {
                output.setHeader("Allow", "GET");
                reject(output, 405, "Only GET read/search requests are supported.");
                return;
            }
            String accept = http.getHeader("Accept");
            String format = http.getParameter("_format");
            if ((format != null && !Set.of("json", "application/json", "application/fhir+json").contains(format))
                    || (format == null && !acceptsJson(accept))) {
                reject(output, 406, "Only FHIR JSON is supported.");
                return;
            }
            String pretty = http.getParameter("_pretty");
            if (pretty != null && !Set.of("true", "false").contains(pretty)) {
                reject(output, 400, "_pretty must be true or false.");
                return;
            }
            String path = http.getPathInfo();
            if (path != null && (path.equals("/metadata") || path.substring(1).contains("/"))) {
                if (http.getParameterMap().entrySet().stream().anyMatch(entry ->
                        !Set.of("_format", "_pretty").contains(entry.getKey()) || entry.getValue().length != 1)) {
                    reject(output, 400, "Read and metadata requests accept only _format and _pretty.");
                    return;
                }
            }
            chain.doFilter(new HttpServletRequestWrapper(http) {
                @Override
                public String getHeader(String name) {
                    return name.equalsIgnoreCase("Accept") ? "application/fhir+json" : super.getHeader(name);
                }

                @Override
                public Enumeration<String> getHeaders(String name) {
                    return name.equalsIgnoreCase("Accept")
                            ? Collections.enumeration(List.of("application/fhir+json")) : super.getHeaders(name);
                }
            }, response);
        }

        private boolean acceptsJson(String accept) {
            if (accept == null || accept.isBlank()) {
                return true;
            }
            int specificity = -1;
            double quality = 0;
            for (String item : accept.toLowerCase(Locale.ROOT).split(",")) {
                String[] parts = item.strip().split(";");
                int match = switch (parts[0].strip()) {
                    case "application/fhir+json", "application/json" -> 2;
                    case "application/*" -> 1;
                    case "*/*" -> 0;
                    default -> -1;
                };
                if (match < 0) {
                    continue;
                }
                double q = 1;
                for (int index = 1; index < parts.length; index++) {
                    String part = parts[index].strip();
                    if (part.startsWith("q=")) {
                        if (!part.substring(2).matches("(0(\\.[0-9]{0,3})?|1(\\.0{0,3})?)")) {
                            return false;
                        }
                        q = Double.parseDouble(part.substring(2));
                    }
                }
                if (match > specificity || (match == specificity && q > quality)) {
                    specificity = match;
                    quality = q;
                }
            }
            return quality > 0;
        }

        private void reject(HttpServletResponse response, int status, String message) throws IOException {
            OperationOutcome outcome = new OperationOutcome();
            outcome.addIssue().setSeverity(OperationOutcome.IssueSeverity.ERROR)
                    .setCode(OperationOutcome.IssueType.NOTSUPPORTED).setDiagnostics(message);
            response.setStatus(status);
            response.setContentType("application/fhir+json");
            response.getWriter().write(context.newJsonParser().encodeResourceToString(outcome));
        }
    }

    private static final class HealthServlet extends HttpServlet {
        private final GoldStore store;

        HealthServlet(GoldStore store) {
            this.store = store;
        }

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
            response.setContentType("application/json");
            response.setHeader("Cache-Control", "no-store");
            if ("/live".equals(request.getPathInfo())) {
                response.getWriter().write("{\"status\":\"live\"}");
            } else if ("/ready".equals(request.getPathInfo())) {
                try {
                    store.checkReady();
                    response.getWriter().write("{\"status\":\"ready\"}");
                } catch (GoldStore.Unavailable e) {
                    response.setStatus(503);
                    response.getWriter().write("{\"status\":\"unavailable\"}");
                }
            } else {
                response.setStatus(404);
                response.getWriter().write("{\"status\":\"not-found\"}");
            }
        }
    }
}
