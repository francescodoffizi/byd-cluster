package com.byd.clusternavbridge.route

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

class RouteFetcherTest {

    @Before
    fun setUp() {
        RouteFetcher.clearRoute()
        RouteFetcher.clearAllAuthoritativeRoutes()
    }

    @Test
    fun stripsSummaryPrefixesAndBlocksSymbolicFavorites() {
        assertEquals("", RouteFetcher.extractTargetQuery("Viaggio verso Lavoro", "Tra 300 m svolta a destra"))
        assertEquals("", RouteFetcher.extractTargetQuery("Viaggio verso Casa", "E80 A90"))
        assertEquals("", RouteFetcher.extractTargetQuery("Trip to Work", "Turn right onto Main St"))
        assertEquals("", RouteFetcher.extractTargetQuery("Verso Ufficio", "Via Cristoforo Colombo"))
        assertEquals("", RouteFetcher.extractTargetQuery("Posizione salvata", "Uscita 32 Pisana"))

        // Real street/city addresses must have their summary prefix stripped cleanly
        assertEquals("Via del Corso 12, Roma", RouteFetcher.extractTargetQuery("Viaggio verso Via del Corso 12, Roma"))
        assertEquals("Aeroporto di Fiumicino", RouteFetcher.extractTargetQuery("Verso Aeroporto di Fiumicino"))
        assertEquals("Piazza del Duomo, Milano", RouteFetcher.extractTargetQuery("Trip to Piazza del Duomo, Milano"))
    }

    @Test
    fun neverFabricatesRouteFromNextTurnInstructionWhenDestinationMissing() {
        // HERE WeGo case: destination is empty, instruction has highway / exit banner
        assertEquals("", RouteFetcher.extractTargetQuery("", "Esci a destra verso Uscita 32 Pisana"))
        assertEquals("", RouteFetcher.extractTargetQuery("", "E80 A90"))
        assertEquals("", RouteFetcher.extractTargetQuery("Navigazione HERE WeGo attiva", "Svolta a destra su Via Aurelia"))
    }

    @Test
    fun parsesRemainingDistanceAndEnforcesPhysicalTriangleInequality() {
        assertEquals(34_000.0, RouteFetcher.parseRemainingDistanceMeters("32 min · 34 km"), 0.1)
        assertEquals(85_400.0, RouteFetcher.parseRemainingDistanceMeters("1 h 12 min • 85,4 km"), 0.1)
        assertEquals(850.0, RouteFetcher.parseRemainingDistanceMeters("2 min · 850 m"), 0.1)

        // For a 34 km remaining trip:
        // - Nearby 1.2 km homonym POI is rejected (< 6.8 km)
        assertFalse(RouteFetcher.isCandidateDistancePlausible(1_200.0, 34_000.0))
        // - Distant 480 km homonym in another city is rejected (> 39.1 km)
        assertFalse(RouteFetcher.isCandidateDistancePlausible(480_000.0, 34_000.0))
        // - Plausible 24.5 km straight-line target (~31-35 km by road) is accepted
        assertTrue(RouteFetcher.isCandidateDistancePlausible(24_500.0, 34_000.0))
    }

    @Test
    fun decompressesAuthoritativeGzipGeoJsonAndStoresPerPackage() {
        val sampleGeoJson = """{"type":"FeatureCollection","features":[{"type":"Feature","properties":{},"geometry":{"type":"LineString","coordinates":[[12.49223,41.89021],[12.49511,41.89345],[12.50102,41.90012]]}}]}"""
        val baos = ByteArrayOutputStream()
        GZIPOutputStream(baos).use { it.write(sampleGeoJson.toByteArray(Charsets.UTF_8)) }

        val decompressed = RouteFetcher.decompressGzipGeoJson(baos.toByteArray())
        assertEquals(sampleGeoJson, decompressed)

        RouteFetcher.setAuthoritativeRoute("com.iternio.abrpapp", decompressed!!, 1450.0, nowMs = 1000L)
        val stored = RouteFetcher.getAuthoritativeRoute("com.iternio.abrpapp")
        assertNotNull(stored)
        assertEquals(sampleGeoJson, stored?.geoJson)
        assertNull(RouteFetcher.getAuthoritativeRoute("com.here.app.maps"))

        RouteFetcher.clearAuthoritativeRoute("com.iternio.abrpapp")
        assertNull(RouteFetcher.getAuthoritativeRoute("com.iternio.abrpapp"))
    }
}
