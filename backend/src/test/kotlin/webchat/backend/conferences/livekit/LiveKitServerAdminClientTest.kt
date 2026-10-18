package webchat.backend.conferences.livekit

import com.fasterxml.jackson.databind.ObjectMapper
import io.livekit.server.RoomService
import io.livekit.server.RoomServiceClient
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import livekit.LivekitModels.DataPacket
import livekit.LivekitModels.ParticipantInfo
import livekit.LivekitModels.Room
import livekit.LivekitModels.TrackInfo
import livekit.LivekitModels.TrackSource
import livekit.LivekitRoom.CreateRoomRequest
import livekit.LivekitRoom.DeleteRoomRequest
import livekit.LivekitRoom.ListParticipantsRequest
import livekit.LivekitRoom.ListParticipantsResponse
import livekit.LivekitRoom.ListRoomsRequest
import livekit.LivekitRoom.ListRoomsResponse
import livekit.LivekitRoom.MuteRoomTrackRequest
import livekit.LivekitRoom.MuteRoomTrackResponse
import livekit.LivekitRoom.SendDataRequest
import livekit.LivekitRoom.UpdateParticipantRequest
import okhttp3.MediaType
import okhttp3.ResponseBody
import okio.Timeout
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.jdbc.core.JdbcTemplate
import retrofit2.Call
import retrofit2.Response
import webchat.backend.conferences.ConferenceMetrics
import webchat.backend.conferences.domain.model.PublicationSource
import webchat.backend.conferences.domain.model.PublishGrants
import webchat.backend.conferences.domain.port.ConferenceMediaUnavailableException
import webchat.backend.conferences.domain.port.LiveKitParticipant
import java.io.IOException
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * Unit mirror of the T012 adapter contract (livekit-integration.md §4/§7):
 * the Twirp wire shapes (`CreateRoom` parameters, the full
 * `UpdateParticipant` permission SET, the `SendData` broadcast packet,
 * the adapter-side track-sid resolution of `MutePublishedTrack`), the
 * SET-semantics folds (a `not_found` target is a success no-op, never
 * «unavailable») and the retry policy (transient failures only, backoff,
 * exhaustion → [ConferenceMediaUnavailableException], misconfiguration
 * fails fast). The media plane itself (connection, grants on a live
 * room, webhook signature) is exercised by the IT set T021+ against the
 * Testcontainers LiveKit — media is never connected here (§7).
 */
class LiveKitServerAdminClientTest {
    private val roomService = RecordingRoomService()

    private val meterRegistry = SimpleMeterRegistry()

    private val adapter =
        LiveKitServerAdminClient(
            // retryBackoff ZERO: the backoff curve itself is a constant of
            // the adapter, sleeping in a unit test would only slow it down
            objectMapper = ObjectMapper(),
            client = RoomServiceClient(roomService, "devkey", "secret"),
            retryBackoff = Duration.ZERO,
            maxAttempts = 3,
            metrics = ConferenceMetrics(meterRegistry, Mockito.mock(JdbcTemplate::class.java)),
        )

    /**
     * T067 (FR-018/SC-009): every logical Twirp call times one
     * `webchat_conference_livekit_api_seconds{method}` sample — retries
     * and backoff included in the single sample of the logical call.
     */
    @Test
    fun everyTwirpCallTimesTheLiveKitApiTimer() {
        roomService.stub("deleteRoom", success(Room.getDefaultInstance()))
        roomService.stub("listRooms", success(ListRoomsResponse.getDefaultInstance()))

        adapter.deleteRoom("c:1")
        adapter.listRoomNames()
        adapter.deleteRoom("c:1")

        val deleteTimer =
            meterRegistry
                .find("webchat_conference_livekit_api_seconds")
                .tag("method", "DeleteRoom")
                .timer()
        val listTimer =
            meterRegistry
                .find("webchat_conference_livekit_api_seconds")
                .tag("method", "ListRooms")
                .timer()
        assertThat(deleteTimer).isNotNull
        assertThat(deleteTimer!!.count()).isEqualTo(2L)
        assertThat(listTimer).isNotNull
        assertThat(listTimer!!.count()).isEqualTo(1L)
        assertThat(deleteTimer.max(TimeUnit.SECONDS)).isNotNegative()
    }

    @Test
    fun createRoomSendsContractParameters() {
        roomService.stub("createRoom", success(Room.getDefaultInstance()))

        adapter.createRoom(
            name = "c:0b6b1b0a-0000-4000-8000-000000000001",
            emptyTimeout = Duration.ofSeconds(60),
            departureTimeout = Duration.ofSeconds(45),
            maxParticipants = 16,
        )

        val request = roomService.requestOf<CreateRoomRequest>("createRoom")
        assertThat(request.name).isEqualTo("c:0b6b1b0a-0000-4000-8000-000000000001")
        assertThat(request.emptyTimeout).isEqualTo(60)
        assertThat(request.departureTimeout).isEqualTo(45)
        assertThat(request.maxParticipants).isEqualTo(16)
        // the admin token is minted in the adapter's memory (§3) — a
        // Bearer JWT per call, never issued to any client
        assertThat(roomService.authHeaderOf("createRoom")).startsWith("Bearer ")
    }

    @Test
    fun createRoomFloorsSubSecondTimersToOneSecond() {
        roomService.stub("createRoom", success(Room.getDefaultInstance()))

        adapter.createRoom(
            name = "c:1",
            emptyTimeout = Duration.ofMillis(500),
            departureTimeout = Duration.ofSeconds(60),
            maxParticipants = 2,
        )

        assertThat(roomService.requestOf<CreateRoomRequest>("createRoom").emptyTimeout).isEqualTo(1)
    }

    @Test
    fun deleteRoomFoldsAbsentTargetIntoIdempotentNoOp() {
        roomService.stub("deleteRoom", error<Void>(404, NOT_FOUND_JSON))

        assertThatCode { adapter.deleteRoom("c:1") }.doesNotThrowAnyException()

        // not_found is terminal — the no-op is NOT retried
        assertThat(roomService.times("deleteRoom")).isEqualTo(1)
    }

    @Test
    fun deleteRoomRecognizesTwirpNotFoundCodeUnderAQuirkedHttpStatus() {
        roomService.stub("deleteRoom", error<Void>(400, NOT_FOUND_JSON))

        assertThatCode { adapter.deleteRoom("c:1") }.doesNotThrowAnyException()

        assertThat(roomService.times("deleteRoom")).isEqualTo(1)
    }

    @Test
    fun listRoomNamesMapsTheRoomNameSet() {
        roomService.stub(
            "listRooms",
            success(
                ListRoomsResponse
                    .newBuilder()
                    .addRooms(Room.newBuilder().setName("c:1"))
                    .addRooms(Room.newBuilder().setName("c:2"))
                    .build(),
            ),
        )

        assertThat(adapter.listRoomNames()).containsExactlyInAnyOrder("c:1", "c:2")
    }

    @Test
    fun listParticipantsProjectsIdentityAndMetadata() {
        roomService.stub(
            "listParticipants",
            success(
                ListParticipantsResponse
                    .newBuilder()
                    .addParticipants(
                        ParticipantInfo
                            .newBuilder()
                            .setIdentity("u:1:a")
                            .setMetadata("""{"role":"moderator","guest":false}"""),
                    ).addParticipants(ParticipantInfo.newBuilder().setIdentity("g:b"))
                    .build(),
            ),
        )

        assertThat(adapter.listParticipants("c:1")).containsExactly(
            LiveKitParticipant("u:1:a", """{"role":"moderator","guest":false}"""),
            // proto3 keeps unset metadata as "" — the port maps it to null
            LiveKitParticipant("g:b", null),
        )
    }

    @Test
    fun removeParticipantFoldsAbsentIdentityIntoIdempotentNoOp() {
        roomService.stub("removeParticipant", error<Void>(404, NOT_FOUND_JSON))

        assertThatCode { adapter.removeParticipant("c:1", "u:1:a") }.doesNotThrowAnyException()

        assertThat(roomService.times("removeParticipant")).isEqualTo(1)
    }

    @Test
    fun updateParticipantSendsFullPermissionSetAndMetadata() {
        roomService.stub("updateParticipant", success(ParticipantInfo.getDefaultInstance()))

        // camera_restricted (Q22=A): sources minus camera, speech intact
        val grants =
            PublishGrants(
                canPublish = true,
                canPublishSources =
                    setOf(
                        PublicationSource.MICROPHONE,
                        PublicationSource.SCREEN_SHARE,
                        PublicationSource.SCREEN_SHARE_AUDIO,
                    ),
            )
        adapter.updateParticipant("c:1", "u:1:a", grants, """{"role":"participant","guest":false}""")

        val request = roomService.requestOf<UpdateParticipantRequest>("updateParticipant")
        assertThat(request.room).isEqualTo("c:1")
        assertThat(request.identity).isEqualTo("u:1:a")
        assertThat(request.metadata).isEqualTo("""{"role":"participant","guest":false}""")
        // the contract constants ride along (§3), the publish side is the domain grants verbatim
        assertThat(request.permission.canSubscribe).isTrue()
        assertThat(request.permission.canPublishData).isFalse()
        assertThat(request.permission.canPublish).isTrue()
        assertThat(request.permission.canPublishSourcesList).containsExactly(
            TrackSource.MICROPHONE,
            TrackSource.SCREEN_SHARE,
            TrackSource.SCREEN_SHARE_AUDIO,
        )
    }

    @Test
    fun updateParticipantFoldsAbsentIdentityIntoIdempotentNoOp() {
        roomService.stub("updateParticipant", error<Void>(404, NOT_FOUND_JSON))

        assertThatCode { adapter.updateParticipant("c:1", "u:1:a", PublishGrants(true, emptySet()), "{}") }
            .doesNotThrowAnyException()

        assertThat(roomService.times("updateParticipant")).isEqualTo(1)
    }

    @Test
    fun mutePublishedTrackMutesOnlyUnmutedTracksOfTheSource() {
        roomService.stub(
            "listParticipants",
            success(
                ListParticipantsResponse
                    .newBuilder()
                    .addParticipants(
                        ParticipantInfo
                            .newBuilder()
                            .setIdentity("u:1:a")
                            .addTracks(cameraTrack("TR_CAM", muted = false))
                            .addTracks(cameraTrack("TR_CAM_MUTED", muted = true))
                            .addTracks(
                                TrackInfo
                                    .newBuilder()
                                    .setSid("TR_MIC")
                                    .setSource(TrackSource.MICROPHONE)
                                    .setMuted(false),
                            ),
                    ).build(),
            ),
        )
        roomService.stub("mutePublishedTrack", success(MuteRoomTrackResponse.getDefaultInstance()))

        adapter.mutePublishedTrack("c:1", "u:1:a", PublicationSource.CAMERA)

        // only the LIVE, not-yet-muted camera track — the already muted
        // one and the microphone are SET-semantics no-ops
        assertThat(roomService.times("mutePublishedTrack")).isEqualTo(1)
        val request = roomService.requestOf<MuteRoomTrackRequest>("mutePublishedTrack")
        assertThat(request.room).isEqualTo("c:1")
        assertThat(request.identity).isEqualTo("u:1:a")
        assertThat(request.trackSid).isEqualTo("TR_CAM")
        assertThat(request.muted).isTrue()
    }

    @Test
    fun mutePublishedTrackSkipsEveryTrackOfAnAbsentIdentity() {
        roomService.stub(
            "listParticipants",
            success(
                ListParticipantsResponse
                    .newBuilder()
                    .addParticipants(
                        ParticipantInfo
                            .newBuilder()
                            .setIdentity("u:2:z")
                            .addTracks(cameraTrack("TR_CAM", muted = false)),
                    ).build(),
            ),
        )

        adapter.mutePublishedTrack("c:1", "u:1:a", PublicationSource.CAMERA)

        assertThat(roomService.times("mutePublishedTrack")).isEqualTo(0)
    }

    @Test
    fun sendDataBroadcastsReliableTopicPacketToEveryParticipant() {
        roomService.stub("sendData", voidSuccess())

        val payload = """{"type":"conference.warning","endsAt":"2026-10-16T12:00:00Z"}"""
        adapter.sendData("c:1", "conference.warning", payload)

        val request = roomService.requestOf<SendDataRequest>("sendData")
        assertThat(request.room).isEqualTo("c:1")
        assertThat(request.topic).isEqualTo("conference.warning")
        // RELIABLE + empty destinations: everyone, guests without SSE included (Q16=B)
        assertThat(request.kind).isEqualTo(DataPacket.Kind.RELIABLE)
        assertThat(request.destinationIdentitiesList).isEmpty()
        assertThat(request.destinationSidsList).isEmpty()
        assertThat(request.data.toStringUtf8()).isEqualTo(payload)
    }

    @Test
    fun retriesTransientNetworkFailureThenSucceeds() {
        roomService.enqueue("listRooms", failingCall<Void>("connection refused"))
        roomService.stub("listRooms", success(ListRoomsResponse.getDefaultInstance()))

        assertThat(adapter.listRoomNames()).isEmpty()

        assertThat(roomService.times("listRooms")).isEqualTo(2)
    }

    @Test
    fun retriesTransientHttpFailureThenSucceeds() {
        roomService.enqueue("listRooms", error<Void>(503, """{"code":"unavailable","msg":"node draining"}"""))
        roomService.stub("listRooms", success(ListRoomsResponse.getDefaultInstance()))

        assertThat(adapter.listRoomNames()).isEmpty()

        assertThat(roomService.times("listRooms")).isEqualTo(2)
    }

    @Test
    fun surfacesExhaustedRetriesAsMediaUnavailable() {
        roomService.stub("listRooms", failingCall<Void>("connection refused"))

        assertThatThrownBy { adapter.listRoomNames() }
            .isInstanceOf(ConferenceMediaUnavailableException::class.java)
            .hasMessageContaining("media plane unavailable")

        assertThat(roomService.times("listRooms")).isEqualTo(3)
    }

    @Test
    fun failsFastOnNonTransientClientErrorWithoutRetries() {
        // a rejected API pair is a misconfiguration, never an «unavailable» 503
        roomService.stub("listRooms", error<Void>(401, """{"code":"unauthenticated"}"""))

        assertThatThrownBy { adapter.listRoomNames() }
            .isNotInstanceOf(ConferenceMediaUnavailableException::class.java)
            .hasMessageContaining("HTTP 401")

        assertThat(roomService.times("listRooms")).isEqualTo(1)
    }

    private fun <T> success(body: T): StubCall<T> = StubCall { Response.success(body) }

    private fun cameraTrack(
        sid: String,
        muted: Boolean,
    ): TrackInfo =
        TrackInfo
            .newBuilder()
            .setSid(sid)
            .setSource(TrackSource.CAMERA)
            .setMuted(muted)
            .build()

    private fun voidSuccess(): StubCall<Void?> = StubCall { Response.success(null) }

    private fun <T> failingCall(reason: String): StubCall<T> = StubCall { throw IOException(reason) }

    /** okhttp 3.x-compatible construction (the test compile classpath resolves the 3.14.9 API surface). */
    private fun <T> error(
        code: Int,
        json: String,
    ): StubCall<T> = StubCall { Response.error(code, ResponseBody.create(MediaType.parse("application/json"), json)) }

    /**
     * The fake Twirp surface: records every invocation (method, request,
     * admin auth header) and answers from a one-shot queue falling back
     * to a per-method default — the plain-Mockito matcher route returns
     * null arguments that the Kotlin-compiled SDK interface rejects.
     */
    private class RecordingRoomService : RoomService {
        data class Invocation(
            val method: String,
            val request: Any?,
            val authHeader: String,
        )

        val invocations = mutableListOf<Invocation>()
        private val oneShots = mutableMapOf<String, ArrayDeque<Call<*>>>()
        private val defaults = mutableMapOf<String, Call<*>>()

        fun stub(
            method: String,
            call: Call<*>,
        ) {
            defaults[method] = call
        }

        fun enqueue(
            method: String,
            call: Call<*>,
        ) {
            oneShots.getOrPut(method) { ArrayDeque() }.addLast(call)
        }

        fun times(method: String): Int = invocations.count { it.method == method }

        fun authHeaderOf(method: String): String = invocations.single { it.method == method }.authHeader

        inline fun <reified T> requestOf(method: String): T =
            invocations
                .single { it.method == method }
                .request as T

        private fun <T> answer(
            method: String,
            request: Any?,
            authHeader: String,
        ): Call<T> {
            invocations += Invocation(method, request, authHeader)
            val call = oneShots[method]?.removeFirstOrNull() ?: defaults[method]
            checkNotNull(call) { "unscripted LiveKit call: $method" }
            @Suppress("UNCHECKED_CAST") // the scripted wire answer erases at the seam
            return call as Call<T>
        }

        override fun createRoom(
            request: CreateRoomRequest,
            authHeader: String,
        ): Call<Room> = answer("createRoom", request, authHeader)

        override fun listRooms(
            request: ListRoomsRequest,
            authHeader: String,
        ): Call<ListRoomsResponse> = answer("listRooms", request, authHeader)

        override fun deleteRoom(
            request: DeleteRoomRequest,
            authHeader: String,
        ): Call<Void?> = answer("deleteRoom", request, authHeader)

        override fun updateRoomMetadata(
            request: livekit.LivekitRoom.UpdateRoomMetadataRequest,
            authHeader: String,
        ): Call<Room> = answer("updateRoomMetadata", request, authHeader)

        override fun listParticipants(
            request: ListParticipantsRequest,
            authHeader: String,
        ): Call<ListParticipantsResponse> = answer("listParticipants", request, authHeader)

        override fun getParticipant(
            request: livekit.LivekitRoom.RoomParticipantIdentity,
            authHeader: String,
        ): Call<ParticipantInfo> = answer("getParticipant", request, authHeader)

        override fun removeParticipant(
            request: livekit.LivekitRoom.RoomParticipantIdentity,
            authHeader: String,
        ): Call<Void?> = answer("removeParticipant", request, authHeader)

        override fun forwardParticipant(
            request: livekit.LivekitRoom.ForwardParticipantRequest,
            authHeader: String,
        ): Call<livekit.LivekitRoom.ForwardParticipantResponse> = answer("forwardParticipant", request, authHeader)

        override fun moveParticipant(
            request: livekit.LivekitRoom.MoveParticipantRequest,
            authHeader: String,
        ): Call<livekit.LivekitRoom.MoveParticipantResponse> = answer("moveParticipant", request, authHeader)

        override fun mutePublishedTrack(
            request: MuteRoomTrackRequest,
            authHeader: String,
        ): Call<MuteRoomTrackResponse> = answer("mutePublishedTrack", request, authHeader)

        override fun updateParticipant(
            request: UpdateParticipantRequest,
            authHeader: String,
        ): Call<ParticipantInfo> = answer("updateParticipant", request, authHeader)

        override fun updateSubscriptions(
            request: livekit.LivekitRoom.UpdateSubscriptionsRequest,
            authHeader: String,
        ): Call<Void?> = answer("updateSubscriptions", request, authHeader)

        override fun sendData(
            request: SendDataRequest,
            authHeader: String,
        ): Call<Void?> = answer("sendData", request, authHeader)
    }

    /**
     * A synchronous [Call] replaying its scripted response on every
     * execute — the adapter re-invokes the SDK factory per retry.
     */
    private class StubCall<T>(
        private val respond: () -> Response<T>,
    ) : Call<T> {
        override fun clone(): Call<T> = StubCall(respond)

        override fun execute(): Response<T> = respond()

        override fun enqueue(callback: retrofit2.Callback<T>) = error("the adapter is synchronous")

        override fun isExecuted(): Boolean = false

        override fun cancel() = Unit

        override fun isCanceled(): Boolean = false

        override fun request(): okhttp3.Request = error("the adapter never reads the raw request")

        override fun timeout(): Timeout = error("the adapter never touches the call timeout")
    }

    private companion object {
        const val NOT_FOUND_JSON = """{"code":"not_found","msg":"room does not exist"}"""
    }
}
