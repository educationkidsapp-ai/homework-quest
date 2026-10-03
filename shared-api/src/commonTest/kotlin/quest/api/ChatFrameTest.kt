package quest.api

import quest.api.dto.ChatAttachment
import quest.api.dto.ChatCommand
import quest.api.dto.ChatFrame
import quest.api.dto.ChatMessage
import quest.api.dto.ChatSender
import quest.api.dto.ChatThreadStatus
import quest.api.validation.SchemaValidator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** C1: the socket frames the server writes are what `ChatFrame.schema.json` accepts, and nothing else is. */
class ChatFrameTest {
    private val json = SchemaValidator.json
    private val message = ChatMessage("m1", "t1", ChatSender.PARENT, "p1", "Hello <b>there</b>", 1_700_000_000_000)

    @Test fun everyFrameKindIsValidAgainstTheSchemaAndRoundTrips() {
        val frames = listOf(
            ChatFrame.Message(message, clientId = "c-1"),
            ChatFrame.Message(message),
            // B5: files alone — an empty body is valid exactly because the message carries attachments
            ChatFrame.Message(message.copy(body = "", attachments = listOf(
                ChatAttachment("a1", "image/jpeg", "photo.jpg", 120_000, width = 1200, height = 900),
                ChatAttachment("a2", "application/pdf", "plan.pdf", 300_000)))),
            ChatFrame.Read("t1", ChatSender.TEACHER, 1_700_000_000_500),
            ChatFrame.Typing("t1", ChatSender.PARENT),
            ChatFrame.Status("t1", ChatThreadStatus.RESOLVED, 1_700_000_000_900),
            ChatFrame.Ping, ChatFrame.Pong,
            ChatFrame.Error("rate_limited", "Slow down.", clientId = "c-2"),
        )
        frames.forEach { frame ->
            val raw = json.encodeToString(ChatFrame.serializer(), frame)
            val r = SchemaValidator.validateChatFrameJson(raw)
            assertTrue(r.isValid, "$raw: ${r.errors}")
            assertEquals(frame, json.decodeFromString(ChatFrame.serializer(), raw))
        }
    }

    @Test fun theDiscriminatorIsTypeAndEnumsAreLowerCase() {
        assertEquals("""{"type":"ping"}""", json.encodeToString(ChatFrame.serializer(), ChatFrame.Ping))
        val raw = json.encodeToString(ChatFrame.serializer(), ChatFrame.Typing("t1", ChatSender.TEACHER))
        assertEquals("""{"type":"typing","threadId":"t1","from":"teacher"}""", raw)
    }

    @Test fun unknownAndMalformedFramesAreRefused() {
        assertFalse(SchemaValidator.validateChatFrameJson("""{"type":"shout","body":"x"}""").isValid)
        assertFalse(SchemaValidator.validateChatFrameJson("""{"type":"message"}""").isValid)
        assertFalse(SchemaValidator.validateChatFrameJson("""{"type":"read","threadId":"t1","readBy":"admin","readAt":1}""").isValid)
        assertFalse(SchemaValidator.validateChatFrameJson("""{"type":"status","threadId":"t1","status":"closed","at":1}""").isValid)
        assertFalse(SchemaValidator.validateChatFrameJson("not json").isValid)
        // B5: an attachment is one of the four sniffed types, with its id, name and size
        assertFalse(SchemaValidator.validateChatFrameJson("""{"type":"message","message":{"id":"m","threadId":"t","sender":"parent","senderId":"p","body":"","createdAt":1,"attachments":[{"id":"a","contentType":"text/html","name":"x","size":1}]}}""").isValid)
    }

    @Test fun commandsDecodeFromTheDocumentedShapes() {
        val send = json.decodeFromString(ChatCommand.serializer(), """{"type":"message","childId":"ch1","teacherId":"te1","body":"hi","clientId":"c9"}""")
        assertEquals(ChatCommand.Send("ch1", "te1", "hi", "c9"), send)
        // B5: files with no text — `body` may be left out altogether
        assertEquals(ChatCommand.Send(threadId = "th1", attachmentIds = listOf("a1")),
            json.decodeFromString(ChatCommand.serializer(), """{"type":"message","threadId":"th1","attachmentIds":["a1"]}"""))
        assertEquals(ChatCommand.Pong, json.decodeFromString(ChatCommand.serializer(), """{"type":"pong"}"""))
        assertEquals(ChatCommand.Read("ch1"), json.decodeFromString(ChatCommand.serializer(), """{"type":"read","childId":"ch1"}"""))
        // R4: a coordinator names the thread instead of the child, and the child may be absent altogether.
        assertEquals(ChatCommand.Read(threadId = "th1"), json.decodeFromString(ChatCommand.serializer(), """{"type":"read","threadId":"th1"}"""))
    }
}
