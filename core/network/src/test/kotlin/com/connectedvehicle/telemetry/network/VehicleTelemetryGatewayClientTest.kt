package com.connectedvehicle.telemetry.network

import com.connectedvehicle.telemetry.domain.model.RemoteActuationType
import com.connectedvehicle.telemetry.domain.model.RemoteVehicleCommand
import com.connectedvehicle.telemetry.domain.model.SignedVehicleCommandEnvelope
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class VehicleTelemetryGatewayClientTest {

    private fun sampleSignedEnvelope() = SignedVehicleCommandEnvelope(
        command = RemoteVehicleCommand(
            commandId = "cmd-net-101",
            vin = "TR-TOFAS-CONNECT-2026-001",
            actuationType = RemoteActuationType.LOCK_DOORS,
            issuedAtEpochMs = 1_760_000_000_000L,
            expiresAtEpochMs = 1_760_000_030_000L,
            nonceHex = "0011223344556677",
        ),
        signatureBase64 = "dGVzdC1lZDI1NTE5LXNpZ25hdHVyZQ==",
        publicKeyId = "key-ed25519-tcu-primary",
    )

    @Test
    fun `dispatchSignedCommand attaches Idempotency-Key and Ed25519 signature headers`() = runTest {
        var capturedIdempotencyKey: String? = null
        var capturedSignature: String? = null

        val mockEngine = MockEngine { request ->
            capturedIdempotencyKey = request.headers["Idempotency-Key"]
            capturedSignature = request.headers["X-TCU-Signature-Ed25519"]
            respond(
                content = """{"commandId":"cmd-net-101","status":"ACCEPTED","tcuCorrelationId":"tcu-ack-900"}""",
                status = HttpStatusCode.Accepted,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }

        val client = VehicleTelemetryGatewayClient(engine = mockEngine)
        val result = client.dispatchSignedCommand(sampleSignedEnvelope())

        assertTrue(result is GatewayNetworkResult.Success)
        assertEquals("cmd-net-101", capturedIdempotencyKey)
        assertEquals("dGVzdC1lZDI1NTE5LXNpZ25hdHVyZQ==", capturedSignature)
        assertEquals("tcu-ack-900", (result as GatewayNetworkResult.Success).data.tcuCorrelationId)
    }

    @Test
    fun `dispatchSignedCommand retries transient HTTP 503 cellular gateway errors`() = runTest {
        val attempts = AtomicInteger(0)
        val mockEngine = MockEngine {
            val current = attempts.incrementAndGet()
            if (current == 1) {
                respond(
                    content = """{"error":"TCU cellular relay timeout"}""",
                    status = HttpStatusCode.ServiceUnavailable,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            } else {
                respond(
                    content = """{"commandId":"cmd-net-101","status":"DELIVERED","tcuCorrelationId":"tcu-ack-retry"}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        }

        val client = VehicleTelemetryGatewayClient(engine = mockEngine)
        val result = client.dispatchSignedCommand(sampleSignedEnvelope())

        assertEquals(2, attempts.get())
        assertTrue(result is GatewayNetworkResult.Success)
        assertEquals("DELIVERED", (result as GatewayNetworkResult.Success).data.status)
    }
}
