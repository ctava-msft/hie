package org.example.hie.fhir;

import java.util.List;

public interface GoldStore {
    List<String> search(String resourceType, SearchParameters parameters);

    void checkReady();

    final class Unavailable extends RuntimeException {
        public Unavailable() {
            super("Gold Eventhouse is unavailable or returned an invalid response.");
        }
    }
}
